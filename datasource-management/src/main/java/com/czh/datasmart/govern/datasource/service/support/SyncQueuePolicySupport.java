/**
 * @Author : Cui
 * @Date: 2026/05/05 18:27
 * @Description DataSmart Govern Backend - SyncQueuePolicySupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.czh.datasmart.govern.datasource.config.SyncExecutorProperties;
import com.czh.datasmart.govern.datasource.entity.SyncTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 同步任务队列策略支持组件。
 *
 * <p>这个类承载的是“如何解释同步任务队列压力”的策略逻辑，而不是任务状态机本身。
 * 过去这些方法散落在 `SyncTaskServiceImpl` 中，会让主服务同时负责：
 * 1. 查询队列；
 * 2. 修改任务状态；
 * 3. 创建告警；
 * 4. 写审计；
 * 5. 解析配置阈值；
 * 6. 生成运营建议文案。
 *
 * <p>这会导致主服务文件持续膨胀，也会让每次调整队列阈值、SLA 文案或预警等级时都必须修改核心事务服务。
 * 因此这里先把“纯策略/纯计算/纯解释”的部分拆出来，让 `SyncTaskServiceImpl` 继续专注于控制面编排。
 *
 * <p>后续商业化演进方向：
 * 1. 这里的默认阈值可以升级为按租户套餐、任务优先级、执行器池、数据源类型动态配置；
 * 2. 压力等级可以从 HEALTHY/WATCH/SATURATED 三档扩展为结合 P95/P99 等历史指标的 SLA 状态；
 * 3. 推荐文案可以替换为结构化建议码，供前端国际化、告警系统和自动化恢复流程消费。
 */
@Component
@RequiredArgsConstructor
public class SyncQueuePolicySupport {

    /**
     * 默认排队老化阈值。
     *
     * <p>900 秒即 15 分钟。对于早期同步控制面来说，如果任务排队超过 15 分钟仍未被认领，
     * 通常已经值得运营人员关注执行器容量、租户配额、数据源并发限制或任务优先级策略。
     */
    private static final int DEFAULT_QUEUED_TASK_AGING_THRESHOLD_SECONDS = 900;

    /**
     * 最小排队老化阈值。
     *
     * <p>即使配置写得过小，也至少保留 60 秒，避免系统在短暂抖动时过度标记告警。
     */
    private static final int MIN_QUEUED_TASK_AGING_THRESHOLD_SECONDS = 60;

    /**
     * 默认老化巡检批量大小。
     *
     * <p>该值控制一次巡检最多处理多少条老化任务，避免高积压场景下单次事务更新过多记录。
     */
    private static final int DEFAULT_QUEUED_TASK_AGING_SCAN_LIMIT = 100;

    /**
     * 默认全局队列预警阈值。
     */
    private static final int DEFAULT_QUEUE_ALERT_THRESHOLD_GLOBAL = 120;

    /**
     * 默认单租户队列预警阈值。
     */
    private static final int DEFAULT_QUEUE_ALERT_THRESHOLD_PER_TENANT = 20;

    /**
     * 运营说明最大长度。
     *
     * <p>同步任务 incidentNote 会写入数据库并展示给运营人员。
     * 控制长度可以避免外部输入或自动拼接内容过长，影响列表展示和存储稳定性。
     */
    private static final int INCIDENT_NOTE_MAX_LENGTH = 1000;

    /**
     * 执行器与队列治理配置。
     *
     * <p>当前从 Spring Boot `datasmart.datasource.sync-executor` 配置读取。
     * 它暂时是本地配置，未来可以被租户策略中心或执行器池策略服务替代。
     */
    private final SyncExecutorProperties syncExecutorProperties;

    /**
     * 解析排队老化阈值。
     *
     * <p>这里集中做默认值和下限保护，避免多个入口各自写一份 `null ? 900 : max(60, value)`。
     */
    public int resolveQueuedTaskAgingThresholdSeconds() {
        return syncExecutorProperties.getQueuedTaskAgingThresholdSeconds() == null
                ? DEFAULT_QUEUED_TASK_AGING_THRESHOLD_SECONDS
                : Math.max(MIN_QUEUED_TASK_AGING_THRESHOLD_SECONDS,
                syncExecutorProperties.getQueuedTaskAgingThresholdSeconds());
    }

    /**
     * 解析队列老化巡检批量大小。
     */
    public int resolveQueuedTaskAgingScanLimit() {
        return syncExecutorProperties.getQueuedTaskAgingScanLimit() == null
                ? DEFAULT_QUEUED_TASK_AGING_SCAN_LIMIT
                : Math.max(1, syncExecutorProperties.getQueuedTaskAgingScanLimit());
    }

    /**
     * 解析全局队列预警阈值。
     */
    public int resolveQueueAlertThresholdGlobal() {
        return syncExecutorProperties.getQueueAlertThresholdGlobal() == null
                ? DEFAULT_QUEUE_ALERT_THRESHOLD_GLOBAL
                : Math.max(1, syncExecutorProperties.getQueueAlertThresholdGlobal());
    }

    /**
     * 解析单租户队列预警阈值。
     */
    public int resolveQueueAlertThresholdPerTenant() {
        return syncExecutorProperties.getQueueAlertThresholdPerTenant() == null
                ? DEFAULT_QUEUE_ALERT_THRESHOLD_PER_TENANT
                : Math.max(1, syncExecutorProperties.getQueueAlertThresholdPerTenant());
    }

    /**
     * 判断任务是否已经排队过久。
     *
     * <p>这里故意只依赖 queuedAt 和 now，不访问数据库、不修改任务状态。
     * 这样该判断可以被队列健康快照、老化巡检、未来告警规则和测试用例复用。
     */
    public boolean isQueuedTaskAged(SyncTask task, LocalDateTime now, int thresholdSeconds) {
        if (task == null || task.getQueuedAt() == null) {
            return false;
        }
        return task.getQueuedAt().isBefore(now.minusSeconds(thresholdSeconds));
    }

    /**
     * 计算任务已经排队的秒数。
     *
     * <p>返回 null 表示没有排队时间，返回 0 或正数表示可展示的等待时长。
     * 使用 `Math.max(0, ...)` 是为了避免系统时间轻微回拨或测试时间构造导致负数污染运营视图。
     */
    public Long computeQueuedDurationSeconds(SyncTask task, LocalDateTime now) {
        if (task == null || task.getQueuedAt() == null) {
            return null;
        }
        return Math.max(0L, Duration.between(task.getQueuedAt(), now).getSeconds());
    }

    /**
     * 解析当前队列压力等级。
     *
     * <p>当前先用三档：
     * 1. HEALTHY：未触发明显风险；
     * 2. WATCH：已出现预警或老化，需要运营关注；
     * 3. SATURATED：已经触发硬上限或明显接近失控。
     */
    public String resolveQueuePressureLevel(boolean globalAlertTriggered,
                                            boolean tenantAlertTriggered,
                                            boolean globalSaturated,
                                            boolean tenantSaturated,
                                            long agedQueuedTaskCount) {
        if (globalSaturated || tenantSaturated) {
            return "SATURATED";
        }
        if (globalAlertTriggered || tenantAlertTriggered || agedQueuedTaskCount > 0) {
            return "WATCH";
        }
        return "HEALTHY";
    }

    /**
     * 为运营人员生成一个可直接阅读的队列健康建议。
     *
     * <p>这些文案不是简单提示语，而是把系统状态翻译成可执行的运营动作：
     * 1. 容量上限：检查执行器、恢复卡死任务、限制上游提交；
     * 2. 租户积压：检查租户提交模式、套餐配额或执行器池隔离；
     * 3. 老化任务：优先处理最老任务并检查公平性；
     * 4. 预警区间：提前观察吞吐和租约恢复，避免发展成事故。
     */
    public String buildQueueHealthRecommendation(boolean globalAlertTriggered,
                                                 boolean tenantAlertTriggered,
                                                 boolean globalSaturated,
                                                 boolean tenantSaturated,
                                                 long agedQueuedTaskCount,
                                                 Long oldestQueuedDurationSeconds,
                                                 Long highestBacklogTenantId) {
        if (globalSaturated) {
            return "全局待执行队列已达到容量上限，建议立即检查执行器容量、恢复卡死任务，并临时收紧上游提交节奏。";
        }
        if (tenantSaturated) {
            return "单租户待执行队列已达到上限，建议优先检查该租户的任务洪峰、套餐配额或执行器池隔离策略。";
        }
        if (agedQueuedTaskCount > 0) {
            return "队列中已出现排队老化任务，建议优先处理最老任务，并检查认领公平性、优先级和执行器可用性。";
        }
        if (globalAlertTriggered) {
            return "全局队列已进入预警区间，建议提前观察执行器吞吐和租约恢复情况，避免进一步积压。";
        }
        if (tenantAlertTriggered) {
            return "存在租户积压明显偏高，建议关注 tenantId=" + highestBacklogTenantId + " 的提交模式与容量配置。";
        }
        if (oldestQueuedDurationSeconds != null && oldestQueuedDurationSeconds > 0) {
            return "当前队列整体可控，但建议继续观察最老任务等待时长，防止由局部慢任务演变成系统性积压。";
        }
        return "当前队列压力健康，暂未发现明显积压或老化风险。";
    }

    /**
     * 为老化任务生成统一的人工关注说明。
     *
     * <p>该方法用于写入 SyncTask.incidentNote。
     * 统一生成说明有两个好处：
     * 1. 所有老化任务的提示风格一致，运营人员更容易批量理解；
     * 2. 后续如果要把说明改成结构化 JSON 或国际化文案，只需要改这里。
     */
    public String buildQueueAgingIncidentNote(SyncTask task, LocalDateTime now, int thresholdSeconds, String note) {
        String baseNote = "任务排队超过 " + thresholdSeconds + " 秒，已被队列老化巡检标记为需要人工关注";
        Long queuedDurationSeconds = computeQueuedDurationSeconds(task, now);
        String durationPart = queuedDurationSeconds == null ? "" : "，当前已排队约 " + queuedDurationSeconds + " 秒";
        if (note == null || note.isBlank()) {
            return truncate(baseNote + durationPart + "。");
        }
        return truncate(baseNote + durationPart + "。补充说明：" + note);
    }

    /**
     * 截断运营说明。
     */
    private String truncate(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value.length() > INCIDENT_NOTE_MAX_LENGTH ? value.substring(0, INCIDENT_NOTE_MAX_LENGTH) : value;
    }
}

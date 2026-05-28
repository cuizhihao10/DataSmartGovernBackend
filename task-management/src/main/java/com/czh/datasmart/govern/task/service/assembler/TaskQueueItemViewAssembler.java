/**
 * @Author : Cui
 * @Date: 2026/05/05 18:14
 * @Description DataSmart Govern Backend - TaskQueueItemViewAssembler.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.assembler;

import com.czh.datasmart.govern.task.controller.dto.TaskQueueItemView;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.support.TaskStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 任务队列运营视图组装器。
 *
 * <p>该类专门负责把任务主表中的“事实快照”转换成运营工作台可以直接消费的“解释型视图”。
 * 它不是持久化服务，也不负责改变任务状态，因此不应该放在 TaskServiceImpl 里继续膨胀主服务。
 *
 * <p>为什么要拆出这个类：
 * 1. 解耦：TaskServiceImpl 应聚焦任务生命周期、事务边界、状态转换和数据库写入；
 * 2. 可维护：运营视图的风险解释、展示字段、推荐动作会随着 SLA、租户等级、任务类型持续演进；
 * 3. 可测试：未来可以单独对 DEAD_LETTER、DEFERRED、租约过期、队列积压等场景写单元测试；
 * 4. 可复用：后续告警规则、运营仪表盘、导出报表也可以复用同一套风险解释口径；
 * 5. 控制文件规模：这是落实“单个文件尽量控制在 500 行内”的第一步拆分示范。
 */
@Component
public class TaskQueueItemViewAssembler {

    /**
     * 队列积压预警秒数。
     *
     * <p>当前先用 10 分钟作为早期默认值。
     * 这个常量放在组装器里，是因为它只影响“运营视图风险解释”，不影响任务状态机本身。
     * 后续更商业化的做法是引入配置化阈值，例如：
     * 1. 高优先级任务排队 1 分钟就预警；
     * 2. 普通批处理任务允许更长等待；
     * 3. 不同租户套餐或 SLA 使用不同阈值；
     * 4. 特定任务类型根据历史 P95/P99 执行耗时动态计算积压风险。
     */
    private static final long QUEUE_AGING_WARNING_SECONDS = 600L;

    /**
     * 默认最大连续退避次数。
     *
     * <p>这里和 TaskServiceImpl 中的默认值保持一致。
     * 目前项目还没有抽出统一的任务策略配置中心，因此组装器先用本地兜底值保证风险解释稳定。
     * 后续建议把 retry/defer/SLA/queue-aging 等策略迁移到 `TaskPolicyProperties` 或数据库策略表，
     * 这样创建任务、执行器退避、运营视图和告警规则都能读取同一份策略。
     */
    private static final int DEFAULT_MAX_DEFER_COUNT = 20;

    /**
     * 把任务主表快照转换为队列运营视图。
     *
     * <p>输入是 Task 实体和当前时间：
     * 1. Task 提供 status、queuedTime、heartbeatTime、leaseExpireTime、deferCount 等原始事实；
     * 2. now 由调用方传入，保证同一页多个任务使用同一个时间基准，避免每条记录计算出的“当前时间”略有差异。
     *
     * <p>输出是 TaskQueueItemView：
     * 1. 保留运营列表需要展示的基础字段；
     * 2. 补充派生字段，如排队秒数、延迟剩余秒数、心跳年龄、租约剩余秒数；
     * 3. 补充风险解释和推荐动作，避免前端重复实现业务判断。
     */
    public TaskQueueItemView toView(Task task, LocalDateTime now) {
        TaskQueueItemView view = new TaskQueueItemView();
        view.setId(task.getId());
        view.setName(task.getName());
        view.setType(task.getType());
        view.setTenantId(task.getTenantId());
        view.setOwnerId(task.getOwnerId());
        view.setProjectId(task.getProjectId());
        view.setStatus(task.getStatus());
        view.setPriority(task.getPriority());
        view.setProgress(task.getProgress());
        view.setCurrentExecutorId(task.getCurrentExecutorId());
        view.setCurrentExecutionRunId(task.getCurrentExecutionRunId());
        view.setQueuedTime(task.getQueuedTime());
        view.setQueueAgeSeconds(resolveQueueAgeSeconds(task, now));
        view.setQueuedDelayRemainingSeconds(resolveQueuedDelayRemainingSeconds(task, now));
        view.setHeartbeatTime(task.getHeartbeatTime());
        view.setHeartbeatAgeSeconds(resolveHeartbeatAgeSeconds(task, now));
        view.setLeaseExpireTime(task.getLeaseExpireTime());
        view.setLeaseRemainingSeconds(resolveLeaseRemainingSeconds(task, now));
        view.setAttentionRequired(task.getAttentionRequired());
        view.setDeferCount(safeDeferCount(task));
        view.setMaxDeferCount(normalizeMaxDeferCount(task.getMaxDeferCount()));
        view.setResult(task.getResult());
        applyRiskExplanation(task, view);
        return view;
    }

    /**
     * 计算已排队秒数。
     *
     * <p>如果 queuedTime 在未来，说明任务还处于 DEFERRED 延迟期。
     * 此时不能把未来时间计算成负数展示给运营人员，因为负数不直观；
     * 本方法把“已排队”按 0 处理，未来剩余时间由 queuedDelayRemainingSeconds 表达。
     */
    private Long resolveQueueAgeSeconds(Task task, LocalDateTime now) {
        if (task.getQueuedTime() == null) {
            return null;
        }
        return Math.max(0L, Duration.between(task.getQueuedTime(), now).getSeconds());
    }

    /**
     * 计算延迟队列剩余秒数。
     *
     * <p>该字段主要解释 DEFERRED 状态：
     * 1. 大于 0：任务还在冷却期，执行器不会认领；
     * 2. 等于 0：任务已经到期或不是延迟任务，可以等待执行器重新认领；
     * 3. null 不用于当前逻辑，因为没有 queuedTime 时仍可以用 0 表示“没有延迟剩余”。
     */
    private Long resolveQueuedDelayRemainingSeconds(Task task, LocalDateTime now) {
        if (task.getQueuedTime() == null || !task.getQueuedTime().isAfter(now)) {
            return 0L;
        }
        return Duration.between(now, task.getQueuedTime()).getSeconds();
    }

    /**
     * 计算心跳年龄。
     *
     * <p>心跳年龄用于判断执行器是否仍然活跃。
     * 它不是最终的故障判定依据，真正的故障判定还要结合 leaseExpireTime；
     * 但在运营页面中展示“多久没有心跳”可以帮助快速定位执行器抖动或网络中断。
     */
    private Long resolveHeartbeatAgeSeconds(Task task, LocalDateTime now) {
        if (task.getHeartbeatTime() == null) {
            return null;
        }
        return Math.max(0L, Duration.between(task.getHeartbeatTime(), now).getSeconds());
    }

    /**
     * 计算租约剩余秒数。
     *
     * <p>返回负数表示租约已经过期。
     * 比起只返回 true/false，负数能告诉运营人员“已经过期多久”，
     * 这对判断是否需要立即恢复、是否存在执行器大面积失联很有帮助。
     */
    private Long resolveLeaseRemainingSeconds(Task task, LocalDateTime now) {
        if (task.getLeaseExpireTime() == null) {
            return null;
        }
        return Duration.between(now, task.getLeaseExpireTime()).getSeconds();
    }

    /**
     * 给队列项补充风险解释。
     *
     * <p>风险解释的优先级从“必须人工处理”到“可观察”依次降低：
     * 1. DEAD_LETTER、人工关注、租约过期属于最高风险；
     * 2. DEFERRED、FAILED、接近退避上限、排队过久属于预警；
     * 3. PAUSED 属于信息提示；
     * 4. 其他情况标记为 NORMAL。
     *
     * <p>这里先采用确定性规则，而不是引入复杂评分模型。
     * 原因是当前阶段更需要可解释、可学习、可验证的规则；
     * 等任务规模、SLA 和历史执行数据积累后，再引入更复杂的评分模型会更稳。
     */
    private void applyRiskExplanation(Task task, TaskQueueItemView view) {
        if (TaskStatus.DEAD_LETTER.equals(task.getStatus())) {
            view.setRiskLevel("CRITICAL");
            view.setRiskReason("任务已进入 DEAD_LETTER，系统已停止自动调度，通常意味着连续退避超过上限或需要人工介入。");
            view.setRecommendedAction("检查执行日志、容量配额和下游依赖；确认问题修复后由管理员 forceRetry，或取消/暂停任务。");
            return;
        }
        if (Boolean.TRUE.equals(task.getAttentionRequired())) {
            view.setRiskLevel("CRITICAL");
            view.setRiskReason(defaultText(task.getResult(), "任务已被标记为需要人工关注。"));
            view.setRecommendedAction("查看任务日志和执行记录，判断是重试、取消、暂停还是调整执行资源。");
            return;
        }
        if (TaskStatus.RUNNING.equals(task.getStatus())
                && view.getLeaseRemainingSeconds() != null
                && view.getLeaseRemainingSeconds() < 0) {
            view.setRiskLevel("CRITICAL");
            view.setRiskReason("任务仍显示 RUNNING，但执行租约已经过期，可能存在执行器失联或心跳中断。");
            view.setRecommendedAction("调用租约超时恢复接口或检查执行器实例状态，避免任务长期卡在 RUNNING。");
            return;
        }
        if (TaskStatus.DEFERRED.equals(task.getStatus())) {
            view.setRiskLevel("WARNING");
            if (view.getQueuedDelayRemainingSeconds() != null && view.getQueuedDelayRemainingSeconds() > 0) {
                view.setRiskReason("任务处于 DEFERRED 延迟期，暂时不会被执行器重新认领。");
                view.setRecommendedAction("观察延迟到期后是否恢复；如频繁发生，请检查执行器容量、租户配额或数据源配额。");
            } else {
                view.setRiskReason("任务处于 DEFERRED 且已经到期，正在等待执行器重新认领。");
                view.setRecommendedAction("如果持续未被认领，请检查执行器是否在线、taskType 是否匹配、队列是否存在更高优先级积压。");
            }
            return;
        }
        if (TaskStatus.FAILED.equals(task.getStatus())) {
            view.setRiskLevel("WARNING");
            view.setRiskReason(defaultText(task.getResult(), "任务执行失败，等待重试或人工复盘。"));
            view.setRecommendedAction("查看失败原因和执行日志；如果外部依赖已恢复，可按重试策略 retry 或由管理员 forceRetry。");
            return;
        }
        if (isNearDeferLimit(task)) {
            view.setRiskLevel("WARNING");
            view.setRiskReason("任务连续退避次数已接近最大允许值，继续退避可能进入 DEAD_LETTER。");
            view.setRecommendedAction("提前检查执行器容量、租户配额和数据源并发限制，避免任务进入死信。");
            return;
        }
        if (TaskStatus.PENDING.equals(task.getStatus())
                && view.getQueueAgeSeconds() != null
                && view.getQueueAgeSeconds() >= QUEUE_AGING_WARNING_SECONDS) {
            view.setRiskLevel("WARNING");
            view.setRiskReason("任务处于 PENDING 且排队时间较长，可能存在执行器不足或队列积压。");
            view.setRecommendedAction("检查执行器在线情况、任务类型匹配、优先级配置和队列汇总指标。");
            return;
        }
        if (TaskStatus.PAUSED.equals(task.getStatus())) {
            view.setRiskLevel("INFO");
            view.setRiskReason("任务已暂停，不会继续自动推进。");
            view.setRecommendedAction("确认暂停原因是否仍然成立；如可恢复，由管理员 resume 或 forceRetry。");
            return;
        }
        view.setRiskLevel("NORMAL");
        view.setRiskReason("未发现明确队列风险。");
        view.setRecommendedAction("继续观察任务状态和队列汇总指标。");
    }

    /**
     * 判断任务是否接近连续退避上限。
     *
     * <p>当前采用 80% 阈值作为第一版预警。
     * 如果 maxDeferCount 为 0，说明第一次 defer 就会死信，因此不在这里重复预警。
     */
    private boolean isNearDeferLimit(Task task) {
        int maxDeferCount = normalizeMaxDeferCount(task.getMaxDeferCount());
        if (maxDeferCount <= 0) {
            return false;
        }
        return safeDeferCount(task) >= Math.ceil(maxDeferCount * 0.8D);
    }

    /**
     * 归一化最大连续退避次数。
     *
     * <p>小于 0 的值没有明确业务意义，统一回退到默认策略。
     */
    private int normalizeMaxDeferCount(Integer maxDeferCount) {
        if (maxDeferCount == null || maxDeferCount < 0) {
            return DEFAULT_MAX_DEFER_COUNT;
        }
        return maxDeferCount;
    }

    /**
     * 安全读取连续退避次数。
     */
    private int safeDeferCount(Task task) {
        return task.getDeferCount() == null ? 0 : task.getDeferCount();
    }

    /**
     * 字符串为空时使用默认文案。
     *
     * <p>运营视图面向人阅读，不应因为数据库某个 result 字段为空就展示空白说明。
     */
    private String defaultText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}

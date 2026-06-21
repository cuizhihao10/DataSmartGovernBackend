/**
 * @Author : Cui
 * @Date: 2026/06/21 00:00
 * @Description DataSmart Govern Backend - DataSyncWorkerCommandOutboxDeadLetterManagementService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.czh.datasmart.govern.task.entity.DataSyncWorkerCommandOutbox;
import com.czh.datasmart.govern.task.mapper.DataSyncWorkerCommandOutboxMapper;
import com.czh.datasmart.govern.task.support.DataSyncWorkerCommandOutboxStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * DataSync worker outbox 死信人工处置服务。
 *
 * <p>该服务补齐 DataSync outbox 生命周期中最后一个关键运维闭环：
 * 自动 dispatcher 和 recovery 只能把命令推进到 {@code DEAD_LETTER}，但真实生产系统不能让死信永远停留在那里。
 * 运维、平台管理员或受控补偿工具需要能够在确认业务风险后，把死信重新放回调度队列，或者明确关闭不再执行。</p>
 *
 * <p>为什么单独拆一个服务：</p>
 * <p>1. {@link DataSyncWorkerCommandDeliveryService} 负责投递，不应该混入人工处置语义；</p>
 * <p>2. {@link DataSyncWorkerCommandOutboxRecoveryService} 负责 DISPATCHING 超时补偿，不应该处理已经停止自动重试的死信；</p>
 * <p>3. 死信处置通常需要更高权限、审计说明和人工确认，独立服务更容易后续接入 permission-admin、审批流和审计表。</p>
 *
 * <p>状态流转：</p>
 * <p>1. REPLAY：DEAD_LETTER -> DEFERRED，attemptCount 归零，nextRetryAt 延迟设置，等待统一 dispatcher 重新领取；</p>
 * <p>2. CLOSE：DEAD_LETTER -> CLOSED，nextRetryAt 清空，普通 dispatcher 不再领取；</p>
 * <p>3. 非 DEAD_LETTER 状态会被拒绝，避免误把 SUCCEEDED/FAILED/CLOSED 等终态重新打开。</p>
 *
 * <p>低敏原则：</p>
 * <p>本服务不读取或返回 payloadJson，不返回 lastError 正文，不暴露 operator reason 正文。
 * reason 仅用于写入经过脱敏、截断后的 lastError 摘要，且控制面视图仍会隐藏 lastError 正文。</p>
 */
@Service
@RequiredArgsConstructor
public class DataSyncWorkerCommandOutboxDeadLetterManagementService {

    private static final String SCHEMA_VERSION =
            "datasmart.task.data-sync-worker-outbox.dead-letter-management.v1";
    private static final String MANAGEMENT_POLICY =
            "仅允许对 DEAD_LETTER 执行 REPLAY/CLOSE；REPLAY 只回到 DEFERRED 等待统一 dispatcher，不在管理接口直接调用下游；响应保持低敏。";
    private static final String REASON_VISIBILITY_POLICY =
            "operator reason 已在服务端做换行清理、常见密钥/endpoint/SQL 片段脱敏和长度裁剪；API 响应不返回原因正文。";
    private static final int DEFAULT_REPLAY_DELAY_SECONDS = 30;
    private static final int MIN_REPLAY_DELAY_SECONDS = 1;
    private static final int MAX_REPLAY_DELAY_SECONDS = 3600;
    private static final int MAX_REASON_LENGTH = 260;

    /**
     * 常见密钥赋值模式。
     *
     * <p>operator reason 是人工输入，真实环境里很容易把 password、token、secret 等字段顺手贴进备注。
     * 这里做“尽力脱敏”，不是完整 DLP；后续如果接入合规模块，应替换为统一敏感内容检测服务。</p>
     */
    private static final Pattern SECRET_ASSIGNMENT_PATTERN = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|token|secret|access[_-]?key|api[_-]?key|authorization|credential)\\s*[:=]\\s*[^\\s,;]+"
    );

    /**
     * 内部 endpoint、JDBC URL、连接串和常见服务地址模式。
     */
    private static final Pattern INTERNAL_ENDPOINT_PATTERN = Pattern.compile(
            "(?i)\\b(?:jdbc:|https?://|mysql://|postgresql://|redis://|mongodb://)\\S+"
    );

    /**
     * Bearer token 常见复制形态。
     */
    private static final Pattern BEARER_TOKEN_PATTERN = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._\\-]+");

    /**
     * 常见 SQL 语句开头。
     *
     * <p>这里刻意只匹配一小段疑似 SQL，而不是试图解析 SQL。目标是避免人工备注里出现 select/update/delete
     * 等真实业务语句正文；完整 SQL 风险应由统一审计和合规模块继续增强。</p>
     */
    private static final Pattern SQL_FRAGMENT_PATTERN = Pattern.compile(
            "(?i)\\b(select|insert|update|delete|merge|drop|alter|create)\\s+[^;]{0,160}"
    );

    private final DataSyncWorkerCommandOutboxMapper outboxMapper;

    /**
     * 管理一条 DEAD_LETTER 状态的 DataSync worker outbox。
     *
     * <p>该方法有数据库写副作用，必须在事务中运行。为了避免多个运维工具同时处理同一条死信，
     * 实际更新使用 id + status=DEAD_LETTER 的条件更新。只要状态被其他线程提前改走，本次处置就会失败，
     * 而不是覆盖别人的处置结果。</p>
     *
     * @param request 死信处置请求，包含 executorId、commandId、action、reason 和可选重放延迟。
     * @return 处置后的低敏结果。
     */
    @Transactional
    public DataSyncWorkerOutboxDeadLetterManageResult manage(DataSyncWorkerOutboxDeadLetterManageRequest request) {
        validateRequest(request);
        LocalDateTime now = LocalDateTime.now();
        String executorId = safeExecutorId(request.getExecutorId());
        DataSyncWorkerCommandOutbox outbox = loadByCommandId(request.getCommandId());
        DataSyncWorkerCommandOutboxStatus previousStatus = parseStatus(outbox.getStatus());
        if (previousStatus != DataSyncWorkerCommandOutboxStatus.DEAD_LETTER) {
            throw new IllegalStateException("只有 DEAD_LETTER 状态的 DataSync worker outbox 才允许人工处置，当前状态: "
                    + previousStatus.name());
        }

        SanitizedReason reason = sanitizeOperatorReason(request.getReason());
        List<String> warnings = new ArrayList<>();
        if (reason.changed()) {
            warnings.add("operator reason 已被脱敏或截断，避免保存凭据、内部地址、SQL 或过长备注");
        }

        ManagementDecision decision = decide(request, executorId, reason.value(), now, warnings, outbox);
        int updated = updateDeadLetter(outbox, decision);
        if (updated <= 0) {
            throw new IllegalStateException("DataSync worker outbox 死信处置失败：状态可能已被其他运维动作更新");
        }
        applyDecisionToMemory(outbox, decision);

        return new DataSyncWorkerOutboxDeadLetterManageResult(
                SCHEMA_VERSION,
                executorId,
                outbox.getCommandId(),
                request.getAction().name(),
                now,
                previousStatus.name(),
                decision.targetStatus().name(),
                decision.replayScheduled(),
                request.getRetryAfterSeconds(),
                decision.effectiveRetryAfterSeconds(),
                REASON_VISIBILITY_POLICY,
                MANAGEMENT_POLICY,
                DataSyncWorkerCommandOutboxViewAssembler.toView(outbox),
                List.copyOf(warnings)
        );
    }

    private ManagementDecision decide(DataSyncWorkerOutboxDeadLetterManageRequest request,
                                      String executorId,
                                      String reason,
                                      LocalDateTime now,
                                      List<String> warnings,
                                      DataSyncWorkerCommandOutbox outbox) {
        if (request.getAction() == DataSyncWorkerOutboxDeadLetterAction.REPLAY) {
            int retryAfterSeconds = clampRetryDelay(request.getRetryAfterSeconds());
            LocalDateTime nextRetryAt = now.plusSeconds(retryAfterSeconds);
            warnings.add("DEAD_LETTER 已重新放回 DEFERRED；到达 nextRetryAt 后由统一 dispatcher 重新领取，本接口不直接调用下游");
            if (Boolean.TRUE.equals(outbox.getSideEffectStarted())) {
                warnings.add("该命令历史上已经越过下游副作用边界，重放依赖 commandId/idempotencyKey 防止重复副作用");
            }
            return new ManagementDecision(
                    DataSyncWorkerCommandOutboxStatus.DEFERRED,
                    0,
                    nextRetryAt,
                    null,
                    Boolean.TRUE.equals(outbox.getSideEffectStarted()),
                    false,
                    buildReplayMessage(executorId, reason, retryAfterSeconds),
                    now,
                    true,
                    retryAfterSeconds
            );
        }

        warnings.add("DEAD_LETTER 已人工关闭并进入 CLOSED 终态；普通 dispatcher 不会再次领取");
        return new ManagementDecision(
                DataSyncWorkerCommandOutboxStatus.CLOSED,
                outbox.getAttemptCount(),
                null,
                outbox.getDispatchedAt(),
                Boolean.TRUE.equals(outbox.getSideEffectStarted()),
                false,
                buildCloseMessage(executorId, reason),
                now,
                false,
                null
        );
    }

    private int updateDeadLetter(DataSyncWorkerCommandOutbox outbox, ManagementDecision decision) {
        /*
         * 这里使用字符串列名版 UpdateWrapper，而不是 LambdaUpdateWrapper：
         * 1. 死信处置需要显式把 next_retry_at、dispatched_at 设为 null，entity update 默认会忽略 null 字段；
         * 2. 纯 Mockito 单测没有启动完整 MyBatis 表元数据缓存，LambdaUpdateWrapper#set 可能无法解析字段缓存；
         * 3. 这些列都属于本表稳定字段，集中写在一个方法里，比在多个服务中散落手写 SQL 更可控。
         */
        UpdateWrapper<DataSyncWorkerCommandOutbox> wrapper = new UpdateWrapper<DataSyncWorkerCommandOutbox>()
                .eq("id", outbox.getId())
                .eq("status", DataSyncWorkerCommandOutboxStatus.DEAD_LETTER.name())
                .set("status", decision.targetStatus().name())
                .set("attempt_count", decision.attemptCount())
                .set("next_retry_at", decision.nextRetryAt())
                .set("dispatched_at", decision.dispatchedAt())
                .set("side_effect_started", decision.sideEffectStarted())
                .set("side_effect_executed", decision.sideEffectExecuted())
                .set("last_error", decision.lastError())
                .set("update_time", decision.updateTime());
        return outboxMapper.update(null, wrapper);
    }

    private void applyDecisionToMemory(DataSyncWorkerCommandOutbox outbox, ManagementDecision decision) {
        outbox.setStatus(decision.targetStatus().name());
        outbox.setAttemptCount(decision.attemptCount());
        outbox.setNextRetryAt(decision.nextRetryAt());
        outbox.setDispatchedAt(decision.dispatchedAt());
        outbox.setSideEffectStarted(decision.sideEffectStarted());
        outbox.setSideEffectExecuted(decision.sideEffectExecuted());
        outbox.setLastError(decision.lastError());
        outbox.setUpdateTime(decision.updateTime());
    }

    private DataSyncWorkerCommandOutbox loadByCommandId(String commandId) {
        DataSyncWorkerCommandOutbox outbox = outboxMapper.selectOne(
                new LambdaQueryWrapper<DataSyncWorkerCommandOutbox>()
                        .eq(DataSyncWorkerCommandOutbox::getCommandId, commandId.trim())
                        .last("LIMIT 1")
        );
        if (outbox == null) {
            throw new IllegalStateException("DataSync worker command outbox 不存在: " + commandId);
        }
        return outbox;
    }

    private void validateRequest(DataSyncWorkerOutboxDeadLetterManageRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("DataSync worker outbox 死信处置请求不能为空");
        }
        if (request.getExecutorId() == null || request.getExecutorId().isBlank()) {
            throw new IllegalArgumentException("executorId 不能为空");
        }
        if (request.getCommandId() == null || request.getCommandId().isBlank()) {
            throw new IllegalArgumentException("commandId 不能为空");
        }
        if (request.getAction() == null) {
            throw new IllegalArgumentException("deadLetter action 不能为空");
        }
    }

    private DataSyncWorkerCommandOutboxStatus parseStatus(String status) {
        try {
            return DataSyncWorkerCommandOutboxStatus.valueOf(status);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("未知 DataSync worker command outbox 状态: " + status);
        }
    }

    private int clampRetryDelay(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_REPLAY_DELAY_SECONDS;
        }
        return Math.min(MAX_REPLAY_DELAY_SECONDS, Math.max(MIN_REPLAY_DELAY_SECONDS, requested));
    }

    private String safeExecutorId(String executorId) {
        String normalized = executorId.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }

    private SanitizedReason sanitizeOperatorReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return new SanitizedReason("未提供人工处置原因", false);
        }
        String normalized = reason.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
        String sanitized = SECRET_ASSIGNMENT_PATTERN.matcher(normalized).replaceAll("$1=<已隐藏>");
        sanitized = BEARER_TOKEN_PATTERN.matcher(sanitized).replaceAll("Bearer <已隐藏>");
        sanitized = INTERNAL_ENDPOINT_PATTERN.matcher(sanitized).replaceAll("<内部地址已隐藏>");
        sanitized = SQL_FRAGMENT_PATTERN.matcher(sanitized).replaceAll("<疑似SQL已隐藏>");
        boolean changed = !sanitized.equals(normalized);
        if (sanitized.length() > MAX_REASON_LENGTH) {
            sanitized = sanitized.substring(0, MAX_REASON_LENGTH) + "...";
            changed = true;
        }
        return new SanitizedReason(sanitized, changed);
    }

    private String buildReplayMessage(String executorId, String reason, int retryAfterSeconds) {
        return "DataSync worker DEAD_LETTER 已由 " + executorId
                + " 申请受控重放；attemptCount 已归零；retryAfterSeconds=" + retryAfterSeconds
                + "；operatorReason=" + reason;
    }

    private String buildCloseMessage(String executorId, String reason) {
        return "DataSync worker DEAD_LETTER 已由 " + executorId
                + " 人工关闭为 CLOSED；后续普通 dispatcher 不再领取；operatorReason=" + reason;
    }

    /**
     * 操作者原因脱敏结果。
     *
     * @param value 可持久化到 lastError 摘要的短文本。
     * @param changed true 表示文本经过脱敏或截断，响应中需要给运维提示。
     */
    private record SanitizedReason(String value, boolean changed) {
    }

    /**
     * 单条死信处置决策。
     *
     * <p>把目标状态、attempt、nextRetryAt、lastError 等字段聚合到 record 中，
     * 可以避免更新数据库和更新内存视图时使用两套散落变量。</p>
     */
    private record ManagementDecision(
            DataSyncWorkerCommandOutboxStatus targetStatus,
            Integer attemptCount,
            LocalDateTime nextRetryAt,
            LocalDateTime dispatchedAt,
            boolean sideEffectStarted,
            boolean sideEffectExecuted,
            String lastError,
            LocalDateTime updateTime,
            boolean replayScheduled,
            Integer effectiveRetryAfterSeconds
    ) {
    }
}

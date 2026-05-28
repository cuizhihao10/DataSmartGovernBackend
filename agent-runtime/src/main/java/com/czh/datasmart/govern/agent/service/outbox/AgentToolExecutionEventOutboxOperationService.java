/**
 * @Author : Cui
 * @Date: 2026/05/28 21:05
 * @Description DataSmart Govern Backend - AgentToolExecutionEventOutboxOperationService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.outbox;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionEventOutboxOperationRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionEventOutboxOperationResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionEventOutboxRecordView;
import com.czh.datasmart.govern.agent.event.outbox.AgentToolExecutionEventOutboxRecord;
import com.czh.datasmart.govern.agent.event.outbox.AgentToolExecutionEventOutboxStatus;
import com.czh.datasmart.govern.agent.event.outbox.AgentToolExecutionEventOutboxStore;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Agent 工具执行事件 outbox 人工补偿服务。
 *
 * <p>dispatcher 解决的是“系统自动投递与自动重试”，但商业化产品还必须给运维人员留下可控的人工出口。
 * 典型场景包括：payload 契约已经修复后把 BLOCKED 事件重新入队；确认某条历史事件已无业务价值后忽略归档；
 * 或者在排障过程中追加处理备注，便于下一位值班人员理解当前判断。</p>
 *
 * <p>服务层负责状态前置校验，而不是让 Controller 直接调用 Store。
 * 这样后续接入 permission-admin 权限细分、独立 outbox_operation_audit 表、审批流或双人复核时，可以集中扩展这里，
 * 不需要改动 HTTP 路由契约。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolExecutionEventOutboxOperationService {

    private final AgentToolExecutionEventOutboxStore outboxStore;

    /**
     * 将 FAILED/BLOCKED 事件重新放回 PENDING。
     *
     * <p>重新入队代表“修复后允许 dispatcher 再次投递”。它不会清空 attemptCount，因为尝试次数是判断事件健康度的重要信号。
     * 如果某条事件反复被重新入队又再次 BLOCKED，平台管理员就应该从契约、权限上下文、下游配置或消费者幂等策略排查。</p>
     */
    public AgentToolExecutionEventOutboxOperationResponse requeue(String outboxId,
                                                                  AgentToolExecutionEventOutboxOperationRequest request,
                                                                  String headerActorId) {
        return operate(outboxId, request, headerActorId, "REQUEUE", outboxStore::markRequeued);
    }

    /**
     * 将 FAILED/BLOCKED 事件人工忽略。
     *
     * <p>忽略不是成功投递，它代表管理员确认该事件不再需要自动补偿。该操作会进入 IGNORED 状态，
     * 让告警和待处理列表可以把它排除，但审计查询仍然能看到它曾经失败或阻断过。</p>
     */
    public AgentToolExecutionEventOutboxOperationResponse ignore(String outboxId,
                                                                 AgentToolExecutionEventOutboxOperationRequest request,
                                                                 String headerActorId) {
        return operate(outboxId, request, headerActorId, "IGNORE", outboxStore::markIgnored);
    }

    /**
     * 追加人工处理备注。
     *
     * <p>当前阶段没有独立操作审计表，因此这里把最近一次备注写入 lastError。
     * 这是一个过渡方案：它能马上让查询页显示处理上下文；后续接入审计表时，应保留完整多条操作历史。</p>
     */
    public AgentToolExecutionEventOutboxOperationResponse appendNote(String outboxId,
                                                                     AgentToolExecutionEventOutboxOperationRequest request,
                                                                     String headerActorId) {
        AgentToolExecutionEventOutboxRecord before = findExisting(outboxId);
        if (before.status() == AgentToolExecutionEventOutboxStatus.PUBLISHED) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                    "已投递成功的 outbox 事件不允许追加人工补偿备注，outboxId=" + outboxId
            );
        }
        Instant now = Instant.now();
        String reason = buildOperationReason("NOTE", request, headerActorId, now);
        AgentToolExecutionEventOutboxRecord updated = outboxStore.appendOperationNote(outboxId, reason, now)
                .orElseThrow(() -> conflict(outboxId, before.status(), "追加备注"));
        return response("NOTE", before, updated, resolveOperator(request, headerActorId), reason, now);
    }

    private AgentToolExecutionEventOutboxOperationResponse operate(String outboxId,
                                                                  AgentToolExecutionEventOutboxOperationRequest request,
                                                                  String headerActorId,
                                                                  String action,
                                                                  OutboxMutation mutation) {
        AgentToolExecutionEventOutboxRecord before = findExisting(outboxId);
        if (!canManuallyCompensate(before.status())) {
            throw conflict(outboxId, before.status(), action);
        }
        Instant now = Instant.now();
        String operatorId = resolveOperator(request, headerActorId);
        String reason = buildOperationReason(action, request, headerActorId, now);
        AgentToolExecutionEventOutboxRecord updated = mutation.apply(outboxId, reason, now)
                .orElseThrow(() -> conflict(outboxId, before.status(), action));
        return response(action, before, updated, operatorId, reason, now);
    }

    private AgentToolExecutionEventOutboxRecord findExisting(String outboxId) {
        if (!hasText(outboxId)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, "outboxId 不能为空");
        }
        return outboxStore.findByOutboxId(outboxId.trim())
                .orElseThrow(() -> new PlatformBusinessException(
                        PlatformErrorCode.NOT_FOUND,
                        "outbox 事件不存在，outboxId=" + outboxId
                ));
    }

    private boolean canManuallyCompensate(AgentToolExecutionEventOutboxStatus status) {
        return status == AgentToolExecutionEventOutboxStatus.BLOCKED
                || status == AgentToolExecutionEventOutboxStatus.FAILED;
    }

    private PlatformBusinessException conflict(String outboxId,
                                               AgentToolExecutionEventOutboxStatus currentStatus,
                                               String action) {
        return new PlatformBusinessException(
                PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                "当前 outbox 状态不允许执行人工补偿动作，action=" + action
                        + ", outboxId=" + outboxId
                        + ", status=" + currentStatus
        );
    }

    private AgentToolExecutionEventOutboxOperationResponse response(String action,
                                                                   AgentToolExecutionEventOutboxRecord before,
                                                                   AgentToolExecutionEventOutboxRecord updated,
                                                                   String operatorId,
                                                                   String reason,
                                                                   Instant now) {
        return new AgentToolExecutionEventOutboxOperationResponse(
                action,
                updated.outboxId(),
                before.status().name(),
                updated.status().name(),
                operatorId,
                reason,
                now,
                AgentToolExecutionEventOutboxRecordView.from(updated)
        );
    }

    private String buildOperationReason(String action,
                                        AgentToolExecutionEventOutboxOperationRequest request,
                                        String headerActorId,
                                        Instant now) {
        String operatorId = resolveOperator(request, headerActorId);
        String reason = Optional.ofNullable(request)
                .map(AgentToolExecutionEventOutboxOperationRequest::reason)
                .filter(this::hasText)
                .map(String::trim)
                .orElseThrow(() -> new PlatformBusinessException(
                        PlatformErrorCode.BAD_REQUEST,
                        "人工补偿动作必须填写 reason，避免 outbox 事件被静默重放或忽略"
                ));
        return "人工补偿动作=" + action
                + ", operatorId=" + operatorId
                + ", operatedAt=" + now
                + ", reason=" + reason;
    }

    private String resolveOperator(AgentToolExecutionEventOutboxOperationRequest request, String headerActorId) {
        if (request != null && hasText(request.operatorId())) {
            return request.operatorId().trim();
        }
        if (hasText(headerActorId)) {
            return headerActorId.trim();
        }
        /*
         * 真实生产请求应由 gateway 注入 X-DataSmart-Actor-Id；这里保留 unknown-operator 是为了让本地联调、
         * 单元测试和服务间脚本仍能使用接口。后续接入权限中心后，可以把缺失 actorId 升级为 FORBIDDEN。
         */
        return "unknown-operator";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @FunctionalInterface
    private interface OutboxMutation {
        Optional<AgentToolExecutionEventOutboxRecord> apply(String outboxId, String reason, Instant now);
    }
}

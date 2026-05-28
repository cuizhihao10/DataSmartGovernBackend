/**
 * @Author : Cui
 * @Date: 2026/05/24 02:46
 * @Description DataSmart Govern Backend - AgentPlanIngestionIdempotencySupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.plan;

import com.czh.datasmart.govern.agent.controller.dto.IngestAgentPlanRequest;
import com.czh.datasmart.govern.agent.controller.dto.IngestedAgentPlanView;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * AgentPlan 接入幂等支持组件。
 *
 * <p>它把“是否重复请求、是否允许回放、是否同 key 不同请求”从 `AgentPlanIngestionService` 中拆出来，
 * 让主服务继续聚焦接入流程、会话边界和工具审计生成。
 *
 * <p>业务规则：
 * 1. 优先使用请求显式传入的 `idempotencyKey`；
 * 2. 如果 `idempotencyKey` 为空，则退回使用 `pythonRequestId`，兼容 Python Runtime 已经具备的请求追踪 ID；
 * 3. 如果两个字段都为空，本组件不做去重，保持历史行为；
 * 4. 同一个幂等键重复提交相同请求时，直接返回首次成功接入的响应快照；
 * 5. 同一个幂等键提交不同请求时拒绝，避免调用方误复用 key 导致审计事实混淆。
 */
@Component
@RequiredArgsConstructor
public class AgentPlanIngestionIdempotencySupport {

    private final AgentPlanIngestionIdempotencyStore store;

    /**
     * 查找可回放的幂等结果。
     *
     * @param request 本次 AgentPlan 接入请求。
     * @return 如果命中相同请求的幂等记录，则返回首次响应；否则返回空。
     */
    public Optional<IngestedAgentPlanView> findReplay(IngestAgentPlanRequest request) {
        String dedupeKey = dedupeKey(request);
        if (dedupeKey == null) {
            return Optional.empty();
        }
        String fingerprint = fingerprint(request);
        return store.findByKey(dedupeKey)
                .map(record -> replayOrReject(record, fingerprint));
    }

    /**
     * 记录首次成功接入结果。
     *
     * <p>只有接入成功后才写幂等记录。
     * 如果校验失败、未知工具、审批状态构造失败，不应占用幂等键，否则调用方修正请求后仍会被旧失败记录挡住。
     */
    public void remember(IngestAgentPlanRequest request, IngestedAgentPlanView view) {
        String dedupeKey = dedupeKey(request);
        if (dedupeKey == null) {
            return;
        }
        store.save(new AgentPlanIngestionIdempotencyRecord(
                dedupeKey,
                fingerprint(request),
                view,
                LocalDateTime.now()
        ));
    }

    private IngestedAgentPlanView replayOrReject(AgentPlanIngestionIdempotencyRecord record, String fingerprint) {
        if (!record.requestFingerprint().equals(fingerprint)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "AgentPlan 幂等键已被不同请求使用，拒绝复用以避免重复 Run 或审计事实混淆。");
        }
        return record.view();
    }

    private String dedupeKey(IngestAgentPlanRequest request) {
        String rawKey = firstNonBlank(request.idempotencyKey(), request.pythonRequestId());
        if (rawKey == null) {
            return null;
        }
        return "tenant:" + request.tenantId()
                + ":project:" + request.projectId()
                + ":actor:" + request.actorId()
                + ":plan:" + rawKey.trim();
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private String fingerprint(IngestAgentPlanRequest request) {
        String raw = request.tenantId()
                + "|" + request.projectId()
                + "|" + request.workspaceId()
                + "|" + request.actorId()
                + "|" + request.sessionId()
                + "|" + request.objective()
                + "|" + request.userInput()
                + "|" + request.workloadType()
                + "|" + request.pythonRequestId()
                + "|" + request.stateTrace()
                + "|" + request.responseSummary()
                + "|" + request.requiresHumanApproval()
                + "|" + request.isolationLevel()
                + "|" + request.toolPlans()
                + "|" + request.modelGatewayGovernance()
                + "|" + request.memoryPlan()
                + "|" + request.memoryRetrievalReport();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256，无法计算 AgentPlan 幂等指纹", exception);
        }
    }
}

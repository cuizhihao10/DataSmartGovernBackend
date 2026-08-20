/**
 * @Author : Cui
 * @Date: 2026/08/21 10:00
 * @Description DataSmart Govern Backend - GraphFactApprovalServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.impl;

import com.czh.datasmart.govern.permission.controller.dto.AgentToolActionApprovalFactEvaluateRequest;
import com.czh.datasmart.govern.permission.controller.dto.AgentToolActionApprovalFactRegisterRequest;
import com.czh.datasmart.govern.permission.controller.dto.GraphFactApprovalEvaluateRequest;
import com.czh.datasmart.govern.permission.controller.dto.GraphFactApprovalRegisterRequest;
import com.czh.datasmart.govern.permission.controller.dto.GraphFactApprovalRegisterResponse;
import com.czh.datasmart.govern.permission.controller.dto.AgentToolActionApprovalFactEvaluationView;
import com.czh.datasmart.govern.permission.event.GraphFactApprovalEventPublisher;
import com.czh.datasmart.govern.permission.service.AgentToolActionApprovalFactService;
import com.czh.datasmart.govern.permission.service.GraphFactApprovalService;
import com.czh.datasmart.govern.permission.controller.dto.AgentToolActionApprovalFactRegisterResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 图事实审批服务实现。
 *
 * <p>先调用通用 approval fact service 进行双主体、应用/项目、session/run、策略版本和状态校验，
 * 再在 APPROVED 时追加 Kafka outbox。两个写入使用同一个 permission-admin 数据源事务，
 * 避免审批已经成功但事件没有留下可补偿记录。</p>
 */
@Service
@RequiredArgsConstructor
public class GraphFactApprovalServiceImpl implements GraphFactApprovalService {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Pattern URI = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*://[^\\s]{1,512}");

    private final AgentToolActionApprovalFactService approvalFactService;
    private final GraphFactApprovalEventPublisher eventPublisher;

    @Override
    @Transactional
    public GraphFactApprovalRegisterResponse register(GraphFactApprovalRegisterRequest request) {
        validate(request);
        AgentToolActionApprovalFactRegisterRequest factRequest = new AgentToolActionApprovalFactRegisterRequest();
        factRequest.setApprovalFactId(request.getApprovalFactId());
        factRequest.setTenantId(request.getTenantId());
        factRequest.setApplicationId(request.getApplicationId());
        factRequest.setProjectId(request.getProjectId());
        factRequest.setUserId(request.getUserId());
        factRequest.setActorId(request.getActorId());
        factRequest.setAgentId(request.getAgentId());
        factRequest.setSessionId(request.getSessionId());
        factRequest.setRunId(request.getRunId());
        factRequest.setDelegationId(request.getDelegationId());
        factRequest.setCommandId(request.getCommandId() == null || request.getCommandId().isBlank()
                ? "graph-ingestion:" + request.getFactFingerprint() : request.getCommandId());
        factRequest.setToolCode("graph.ingestion");
        factRequest.setPolicyVersion(request.getPolicyVersion());
        factRequest.setStatus(request.getStatus());
        factRequest.setExpiresAt(request.getExpiresAt());
        factRequest.setApprovedByActorId(request.getApprovedByActorId());
        factRequest.setReasonCodes(request.getReasonCodes());
        factRequest.setEvidenceCodes(request.getEvidenceCodes());
        AgentToolActionApprovalFactRegisterResponse registration = approvalFactService.register(factRequest);
        String eventId = "APPROVED".equalsIgnoreCase(registration.status())
                ? eventPublisher.publish(request)
                : null;
        return new GraphFactApprovalRegisterResponse(
                registration.approvalFactId(),
                registration.status(),
                request.getFactFingerprint(),
                eventId,
                eventId == null ? "图事实候选已登记，等待审批结果" : "图事实已批准并进入 Kafka outbox"
        );
    }

    @Override
    public AgentToolActionApprovalFactEvaluationView evaluate(GraphFactApprovalEvaluateRequest request) {
        AgentToolActionApprovalFactEvaluateRequest factRequest = new AgentToolActionApprovalFactEvaluateRequest();
        factRequest.setApprovalFactId(request.getApprovalFactId());
        factRequest.setTenantId(request.getTenantId());
        factRequest.setApplicationId(request.getApplicationId());
        factRequest.setProjectId(request.getProjectId());
        factRequest.setUserId(request.getUserId());
        factRequest.setActorId(request.getActorId());
        factRequest.setAgentId(request.getAgentId());
        factRequest.setSessionId(request.getSessionId());
        factRequest.setRunId(request.getRunId());
        factRequest.setDelegationId(request.getDelegationId());
        factRequest.setCommandId(request.getCommandId());
        factRequest.setToolCode("graph.ingestion");
        factRequest.setRequestedPolicyVersion(request.getRequestedPolicyVersion());
        return approvalFactService.evaluate(factRequest);
    }

    private void validate(GraphFactApprovalRegisterRequest request) {
        if (request == null || request.getApprovalFactId() == null || request.getApprovalFactId().isBlank()) {
            throw new IllegalArgumentException("approvalFactId 不能为空");
        }
        if (request.getFactFingerprint() == null || !SHA256.matcher(request.getFactFingerprint()).matches()) {
            throw new IllegalArgumentException("factFingerprint 必须是 64 位 SHA-256 十六进制摘要");
        }
        if (request.getFactBundleUri() == null || !URI.matcher(request.getFactBundleUri()).matches()) {
            throw new IllegalArgumentException("factBundleUri 必须是受控 URI");
        }
        String lower = request.getFactBundleUri().toLowerCase(Locale.ROOT);
        if (lower.contains("password") || lower.contains("token") || lower.contains("select ")) {
            throw new IllegalArgumentException("factBundleUri 不能包含敏感凭据或 SQL 片段");
        }
    }
}

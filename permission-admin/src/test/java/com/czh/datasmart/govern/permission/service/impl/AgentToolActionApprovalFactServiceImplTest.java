/**
 * @Author : Cui
 * @Date: 2026/06/11 23:20
 * @Description DataSmart Govern Backend - AgentToolActionApprovalFactServiceImplTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.impl;

import com.czh.datasmart.govern.permission.controller.dto.AgentToolActionApprovalFactEvaluateRequest;
import com.czh.datasmart.govern.permission.controller.dto.AgentToolActionApprovalFactEvaluationView;
import com.czh.datasmart.govern.permission.controller.dto.AgentToolActionApprovalFactRegisterRequest;
import com.czh.datasmart.govern.permission.service.support.AgentToolActionApprovalFactRecord;
import com.czh.datasmart.govern.permission.service.support.AgentToolActionApprovalFactStore;
import com.czh.datasmart.govern.permission.service.support.InMemoryAgentToolActionApprovalFactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 工具动作审批事实服务测试。
 *
 * <p>这些测试保护“审批必须是 permission-admin 服务端事实”的产品边界。
 * 受控工具动作不能只因为 task.params 里写了 approval:xxx 就继续推进；必须能回查到已登记、未过期、
 * 作用域匹配且状态为 APPROVED 的审批事实。</p>
 */
class AgentToolActionApprovalFactServiceImplTest {

    private final AgentToolActionApprovalFactServiceImpl service =
            new AgentToolActionApprovalFactServiceImpl(new InMemoryAgentToolActionApprovalFactStore());

    @Test
    void approvedFactShouldAllowWhenScopeMatches() {
        service.register(register("APPROVED", LocalDateTime.now().plusMinutes(30)));

        AgentToolActionApprovalFactEvaluationView view = service.evaluate(evaluate());

        assertThat(view.approved()).isTrue();
        assertThat(view.retryable()).isFalse();
        assertThat(view.decision()).isEqualTo("APPROVED");
        assertThat(view.evidenceCodes()).contains(
                "APPROVAL_FACT_FOUND",
                "APPROVAL_FACT_SCOPE_VERIFIED",
                "APPROVAL_FACT_STATUS_APPROVED",
                "APPROVAL_FACT_POLICY_VERSION_VERIFIED"
        );
    }

    @Test
    void unknownFactShouldBeRetryableWaitingState() {
        AgentToolActionApprovalFactEvaluationView view = service.evaluate(evaluate());

        assertThat(view.approved()).isFalse();
        assertThat(view.retryable()).isTrue();
        assertThat(view.decision()).isEqualTo("UNKNOWN");
        assertThat(view.issueCodes()).contains("APPROVAL_FACT_NOT_FOUND");
    }

    @Test
    void rejectedFactShouldBlockWithoutRetry() {
        service.register(register("REJECTED", LocalDateTime.now().plusMinutes(30)));

        AgentToolActionApprovalFactEvaluationView view = service.evaluate(evaluate());

        assertThat(view.approved()).isFalse();
        assertThat(view.retryable()).isFalse();
        assertThat(view.decision()).isEqualTo("REJECTED");
        assertThat(view.issueCodes()).contains("APPROVAL_FACT_REJECTED");
    }

    @Test
    void expiredFactShouldBlockWithoutRetry() {
        service.register(register("APPROVED", LocalDateTime.now().minusSeconds(1)));

        AgentToolActionApprovalFactEvaluationView view = service.evaluate(evaluate());

        assertThat(view.approved()).isFalse();
        assertThat(view.retryable()).isFalse();
        assertThat(view.decision()).isEqualTo("EXPIRED");
        assertThat(view.issueCodes()).contains("APPROVAL_FACT_EXPIRED");
    }

    @Test
    void scopeMismatchShouldBlockWithoutRetry() {
        service.register(register("APPROVED", LocalDateTime.now().plusMinutes(30)));
        AgentToolActionApprovalFactEvaluateRequest request = evaluate();
        request.setProjectId(999L);

        AgentToolActionApprovalFactEvaluationView view = service.evaluate(request);

        assertThat(view.approved()).isFalse();
        assertThat(view.retryable()).isFalse();
        assertThat(view.decision()).isEqualTo("SCOPE_MISMATCH");
        assertThat(view.issueCodes()).contains("APPROVAL_FACT_SCOPE_MISMATCH");
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "REJECTED"})
    void delayedPendingMustNotOverwriteTerminalStatus(String terminalStatus) {
        service.register(register(terminalStatus, LocalDateTime.now().plusMinutes(30)));

        service.register(register("PENDING", LocalDateTime.now().plusMinutes(30)));

        AgentToolActionApprovalFactEvaluationView view = service.evaluate(evaluate());

        assertThat(view.decision()).isEqualTo(terminalStatus);
        assertThat(view.status()).isEqualTo(terminalStatus);
    }

    @Test
    void registrationMustDelegateTheScopeAndVersionGuardToAtomicStoreWrite() {
        AgentToolActionApprovalFactStore store = mock(AgentToolActionApprovalFactStore.class);
        when(store.save(any(AgentToolActionApprovalFactRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        AgentToolActionApprovalFactServiceImpl atomicService = new AgentToolActionApprovalFactServiceImpl(store);

        atomicService.register(register("PENDING", LocalDateTime.now().plusMinutes(30)));

        verify(store).save(any(AgentToolActionApprovalFactRecord.class));
        verify(store, never()).findById(anyString());
    }

    private AgentToolActionApprovalFactRegisterRequest register(String status, LocalDateTime expiresAt) {
        AgentToolActionApprovalFactRegisterRequest request = new AgentToolActionApprovalFactRegisterRequest();
        request.setApprovalFactId("approval:human-001");
        request.setTenantId(10L);
        request.setApplicationId(10010L);
        request.setProjectId(20L);
        request.setUserId("30");
        request.setActorId("30");
        request.setAgentId("datasmart-govern-agent");
        request.setSessionId("session-proposal");
        request.setRunId("run-proposal");
        request.setDelegationId("delegation-proposal");
        request.setCommandId("taoc-consume-001");
        request.setToolCode("datasource.metadata.read");
        request.setPolicyVersion("tool-readiness-policy.v1");
        request.setStatus(status);
        request.setExpiresAt(expiresAt);
        request.setApprovedByActorId("31");
        request.setReasonCodes(List.of("HUMAN_APPROVED"));
        request.setEvidenceCodes(List.of("FRONTEND_CONFIRMATION_RECORDED"));
        return request;
    }

    private AgentToolActionApprovalFactEvaluateRequest evaluate() {
        AgentToolActionApprovalFactEvaluateRequest request = new AgentToolActionApprovalFactEvaluateRequest();
        request.setApprovalFactId("approval:human-001");
        request.setTenantId(10L);
        request.setApplicationId(10010L);
        request.setProjectId(20L);
        request.setUserId("30");
        request.setActorId("30");
        request.setAgentId("datasmart-govern-agent");
        request.setSessionId("session-proposal");
        request.setRunId("run-proposal");
        request.setDelegationId("delegation-proposal");
        request.setCommandId("taoc-consume-001");
        request.setToolCode("datasource.metadata.read");
        request.setRequestedPolicyVersion("tool-readiness-policy.v1");
        return request;
    }
}

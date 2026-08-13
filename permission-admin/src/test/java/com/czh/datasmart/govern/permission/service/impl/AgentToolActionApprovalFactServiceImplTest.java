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

    /**
     * Verifies that request-provided fingerprints cannot become an authorization
     * input: both values may originate from a model or caller and therefore
     * cannot prove what the approved action actually was.
     *
     * <p>The service must instead persist a SHA-256 binding calculated from
     * the trusted approval fact and independently calculate the same binding
     * from the action fields after their scope has matched the fact.</p>
     */
    @Test
    void callerSuppliedActionFingerprintMustNotInfluenceApprovalAuthorization() {
        InMemoryAgentToolActionApprovalFactStore factStore = new InMemoryAgentToolActionApprovalFactStore();
        AgentToolActionApprovalFactServiceImpl authoritativeService =
                new AgentToolActionApprovalFactServiceImpl(factStore);
        String registrationFingerprint = "sha256:model-claimed-action";

        authoritativeService.register(register("APPROVED", LocalDateTime.now().plusMinutes(30),
                registrationFingerprint));

        AgentToolActionApprovalFactEvaluationView view = authoritativeService.evaluate(
                evaluate("sha256:caller-tampered-value"));
        AgentToolActionApprovalFactRecord stored = factStore.findById("approval:human-001").orElseThrow();

        assertThat(view.approved()).isTrue();
        assertThat(view.evidenceCodes()).contains("APPROVAL_FACT_ACTION_FINGERPRINT_SERVER_VERIFIED");
        assertThat(stored.actionFingerprint())
                .matches("sha256:[0-9a-f]{64}")
                .isNotEqualTo(registrationFingerprint);
    }

    /**
     * Verifies that a legacy fact without a persisted server fingerprint cannot
     * authorize a matching current action.
     *
     * <p>The input deliberately uses the legacy record constructor, whose
     * actionFingerprint value is {@code null}, plus an evaluation request whose
     * normalized scope fields all match the record. The expected output is a
     * fail-closed decision rather than an approval. This protects the security
     * boundary between a row that merely has matching identifiers and a row for
     * which permission-admin has durably recorded its own authority binding.</p>
     */
    @Test
    void legacyFactWithoutPersistedServerFingerprintMustNotAuthorizeAction() {
        InMemoryAgentToolActionApprovalFactStore factStore = new InMemoryAgentToolActionApprovalFactStore();
        factStore.save(new AgentToolActionApprovalFactRecord(
                "approval:human-001",
                10L,
                10010L,
                20L,
                "30",
                "30",
                "datasmart-govern-agent",
                "session-proposal",
                "run-proposal",
                "delegation-proposal",
                "taoc-consume-001",
                "datasource.metadata.read",
                "tool-readiness-policy.v1",
                "APPROVED",
                LocalDateTime.now().plusMinutes(30),
                "31",
                List.of("HUMAN_APPROVED"),
                List.of("FRONTEND_CONFIRMATION_RECORDED"),
                LocalDateTime.now()
        ));
        AgentToolActionApprovalFactServiceImpl authoritativeService =
                new AgentToolActionApprovalFactServiceImpl(factStore);

        AgentToolActionApprovalFactEvaluationView view = authoritativeService.evaluate(evaluate());

        assertThat(view.approved()).isFalse();
        assertThat(view.retryable()).isFalse();
        assertThat(view.decision()).isEqualTo("ACTION_FINGERPRINT_MISSING");
        assertThat(view.issueCodes()).contains("APPROVAL_FACT_ACTION_FINGERPRINT_MISSING");
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

    /**
     * Builds the standard registration fixture without a caller fingerprint.
     *
     * <p>The inputs select the fact lifecycle state and expiry; the output is a
     * complete low-sensitive request whose immutable scope matches
     * {@link #evaluate()}. Passing {@code null} for the fingerprint is
     * intentional, because the security boundary under test requires the service
     * to create its own fingerprint instead of accepting test-client input.</p>
     *
     * @param status requested approval state for the fixture
     * @param expiresAt time at which the fixture should stop being usable
     * @return registration request ready for the service boundary
     */
    private AgentToolActionApprovalFactRegisterRequest register(String status, LocalDateTime expiresAt) {
        return register(status, expiresAt, null);
    }

    /**
     * Builds a complete registration fixture with a deliberately caller-owned fingerprint value.
     *
     * <p>The inputs control the lifecycle state, expiry, and arbitrary supplied
     * fingerprint. The output contains only stable test scope identifiers. The
     * method does not normalize or validate the fingerprint because its purpose
     * is to prove that permission-admin, rather than this simulated caller,
     * owns the authorization digest.</p>
     *
     * @param status requested approval state for the fixture
     * @param expiresAt time at which the fixture should stop being usable
     * @param actionFingerprint untrusted compatibility value supplied by the test caller
     * @return complete registration request for the service test
     */
    private AgentToolActionApprovalFactRegisterRequest register(String status,
                                                                LocalDateTime expiresAt,
                                                                String actionFingerprint) {
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
        request.setActionFingerprint(actionFingerprint);
        request.setPolicyVersion("tool-readiness-policy.v1");
        request.setStatus(status);
        request.setExpiresAt(expiresAt);
        request.setApprovedByActorId("31");
        request.setReasonCodes(List.of("HUMAN_APPROVED"));
        request.setEvidenceCodes(List.of("FRONTEND_CONFIRMATION_RECORDED"));
        return request;
    }

    /**
     * Builds the standard matching evaluation fixture without a caller fingerprint.
     *
     * <p>The output has the same normalized scope and action locator fields as
     * {@link #register(String, LocalDateTime)}, so any rejection in a test is
     * caused by the security condition being exercised rather than an accidental
     * scope mismatch. A {@code null} compatibility fingerprint also demonstrates
     * that evaluation must use the persisted server digest.</p>
     *
     * @return matching current-action context for evaluation tests
     */
    private AgentToolActionApprovalFactEvaluateRequest evaluate() {
        return evaluate(null);
    }

    /**
     * Builds a matching evaluation fixture with an arbitrary caller fingerprint.
     *
     * <p>The input is deliberately not trusted or normalized by this helper. The
     * output preserves it only to model the HTTP contract, while the service must
     * derive and compare its own digest after validating every scope field. This
     * keeps the test focused on the authorization boundary rather than DTO setup.</p>
     *
     * @param actionFingerprint untrusted compatibility value supplied at evaluation time
     * @return current-action request whose stable scope matches the registered fact
     */
    private AgentToolActionApprovalFactEvaluateRequest evaluate(String actionFingerprint) {
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
        request.setActionFingerprint(actionFingerprint);
        request.setRequestedPolicyVersion("tool-readiness-policy.v1");
        return request;
    }
}

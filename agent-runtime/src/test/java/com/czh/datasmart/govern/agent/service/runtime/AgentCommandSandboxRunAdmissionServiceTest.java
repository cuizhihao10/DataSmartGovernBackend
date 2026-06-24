/**
 * @Author : Cui
 * @Date: 2026/06/24 23:59
 * @Description DataSmart Govern Backend - AgentCommandSandboxRunAdmissionServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentCommandSandboxRunAdmissionRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentCommandSandboxRunAdmissionResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentCommandWorkerLeaseClaimRequest;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * command sandbox run 准入服务测试。
 *
 * <p>这组测试不是为了证明 Java 已经能执行真实 shell，而是保护真实执行前的 Host 控制面边界：
 * 只有当前 lease 持有者可以申请进入 sandbox；非 allow 安全决策必须 fail-closed；响应只能携带低敏合同，
 * 不能把 fencingToken、命令正文、stdout/stderr 或 URL 泄露给 timeline/管理台。</p>
 */
class AgentCommandSandboxRunAdmissionServiceTest {

    private static final String SESSION_ID = "session-command-sandbox";
    private static final String RUN_ID = "run-command-sandbox";
    private static final String COMMAND_ID = "cmd-sandbox-001";
    private static final String EXECUTOR_ID = "python-command-worker-001";

    @Test
    void shouldAdmitSandboxRunWhenLeaseAndSafetyEvidenceAreValid() throws JsonProcessingException {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-24T16:00:00Z"));
        AgentCommandWorkerLeaseService leaseService =
                new AgentCommandWorkerLeaseService(new InMemoryAgentCommandWorkerLeaseStore(), clock);
        AgentCommandWorkerLeaseRecord lease = claimLease(leaseService);
        AgentCommandSandboxRunAdmissionService service = new AgentCommandSandboxRunAdmissionService(leaseService, clock);

        AgentCommandSandboxRunAdmissionResponse response = service.admit(SESSION_ID, RUN_ID, request(
                lease,
                "ALLOW_CONTROLLED_EXECUTION",
                List.of(),
                "agent-workspace:tenant-10/project-20/run-command-sandbox",
                900,
                1024 * 1024
        ));
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);

        assertTrue(response.accepted());
        assertEquals("ADMITTED_FOR_SANDBOX_EXECUTION", response.decision());
        assertTrue(response.sandboxRunId().startsWith("sandbox-run:sha256:"));
        assertEquals("NO_NETWORK_PROCESS_SANDBOX", response.isolationMode());
        assertEquals(300, response.normalizedTimeoutSeconds());
        assertEquals(256 * 1024, response.normalizedOutputByteLimitBytes());
        assertEquals(false, response.processStarted());
        assertEquals(false, response.rawCommandAccepted());
        assertTrue(response.evidenceCodes().contains("CURRENT_WORKER_LEASE_VERIFIED"));
        assertTrue(response.evidenceCodes().contains("OUTPUT_BUDGET_CAPPED"));
        assertFalse(json.contains(lease.fencingToken()));
        assertFalse(json.contains("commandLine"));
        assertFalse(json.contains("stdout"));
        assertFalse(json.contains("https://"));
    }

    @Test
    void shouldDenyWithoutStartingProcessWhenSafetyIssueCodesRemain() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-24T16:05:00Z"));
        AgentCommandWorkerLeaseService leaseService =
                new AgentCommandWorkerLeaseService(new InMemoryAgentCommandWorkerLeaseStore(), clock);
        AgentCommandWorkerLeaseRecord lease = claimLease(leaseService);
        AgentCommandSandboxRunAdmissionService service = new AgentCommandSandboxRunAdmissionService(leaseService, clock);

        AgentCommandSandboxRunAdmissionResponse response = service.admit(SESSION_ID, RUN_ID, request(
                lease,
                "ALLOW_CONTROLLED_EXECUTION",
                List.of("NETWORK_REQUIRES_APPROVAL"),
                "agent-workspace:tenant-10/project-20/run-command-sandbox",
                30,
                4096
        ));

        assertFalse(response.accepted());
        assertEquals("DENIED_BY_COMMAND_SAFETY", response.decision());
        assertNull(response.sandboxRunId());
        assertTrue(response.issueCodes().contains("NETWORK_REQUIRES_APPROVAL"));
        assertFalse(response.processStarted());
        assertFalse(response.rawCommandAccepted());
    }

    @Test
    void shouldDenyWhenWorkspaceReferenceIsMissing() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-24T16:10:00Z"));
        AgentCommandWorkerLeaseService leaseService =
                new AgentCommandWorkerLeaseService(new InMemoryAgentCommandWorkerLeaseStore(), clock);
        AgentCommandWorkerLeaseRecord lease = claimLease(leaseService);
        AgentCommandSandboxRunAdmissionService service = new AgentCommandSandboxRunAdmissionService(leaseService, clock);

        AgentCommandSandboxRunAdmissionResponse response = service.admit(SESSION_ID, RUN_ID, request(
                lease,
                "ALLOW_CONTROLLED_EXECUTION",
                List.of(),
                null,
                30,
                4096
        ));

        assertFalse(response.accepted());
        assertEquals("DENIED_BY_WORKSPACE_POLICY", response.decision());
        assertTrue(response.issueCodes().contains("MISSING_WORKSPACE_REFERENCE"));
        assertFalse(response.processStarted());
    }

    @Test
    void shouldRejectStaleLeaseEvidenceBeforeAdmission() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-24T16:15:00Z"));
        AgentCommandWorkerLeaseService leaseService =
                new AgentCommandWorkerLeaseService(new InMemoryAgentCommandWorkerLeaseStore(), clock);
        AgentCommandWorkerLeaseRecord lease = claimLease(leaseService);
        AgentCommandSandboxRunAdmissionService service = new AgentCommandSandboxRunAdmissionService(leaseService, clock);

        assertThrows(PlatformBusinessException.class, () -> service.admit(SESSION_ID, RUN_ID,
                new AgentCommandSandboxRunAdmissionRequest(
                        COMMAND_ID,
                        EXECUTOR_ID,
                        lease.fencingToken(),
                        lease.leaseVersion(),
                        lease.leaseExpiresAt().minusSeconds(1).toEpochMilli(),
                        10L,
                        20L,
                        30L,
                        "ALLOW_CONTROLLED_EXECUTION",
                        "command-safety-policy.v1",
                        List.of(),
                        "NO_NETWORK_PROCESS_SANDBOX",
                        30,
                        4096,
                        500,
                        512,
                        "agent-workspace:tenant-10/project-20/run-command-sandbox",
                        "command.run-program",
                        "python-ai-runtime-controlled-worker",
                        "idempotency-sandbox-001"
                )));
    }

    @Test
    void shouldRejectSensitiveWorkspaceReference() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-24T16:20:00Z"));
        AgentCommandWorkerLeaseService leaseService =
                new AgentCommandWorkerLeaseService(new InMemoryAgentCommandWorkerLeaseStore(), clock);
        AgentCommandWorkerLeaseRecord lease = claimLease(leaseService);
        AgentCommandSandboxRunAdmissionService service = new AgentCommandSandboxRunAdmissionService(leaseService, clock);

        assertThrows(PlatformBusinessException.class, () -> service.admit(SESSION_ID, RUN_ID, request(
                lease,
                "ALLOW_CONTROLLED_EXECUTION",
                List.of(),
                "https://internal.example.local/workspace?token=secret",
                30,
                4096
        )));
    }

    private AgentCommandWorkerLeaseRecord claimLease(AgentCommandWorkerLeaseService leaseService) {
        return leaseService.claim(SESSION_ID, RUN_ID,
                new AgentCommandWorkerLeaseClaimRequest(COMMAND_ID, EXECUTOR_ID, 10L, 20L, 30L, 120)).record();
    }

    private AgentCommandSandboxRunAdmissionRequest request(AgentCommandWorkerLeaseRecord lease,
                                                           String safetyDecision,
                                                           List<String> issueCodes,
                                                           String workspaceReference,
                                                           Integer timeoutSeconds,
                                                           Integer outputLimitBytes) {
        return new AgentCommandSandboxRunAdmissionRequest(
                COMMAND_ID,
                EXECUTOR_ID,
                lease.fencingToken(),
                lease.leaseVersion(),
                lease.leaseExpiresAt().toEpochMilli(),
                10L,
                20L,
                30L,
                safetyDecision,
                "command-safety-policy.v1",
                issueCodes,
                "NO_NETWORK_PROCESS_SANDBOX",
                timeoutSeconds,
                outputLimitBytes,
                8_000,
                4_096,
                workspaceReference,
                "command.run-program",
                "python-ai-runtime-controlled-worker",
                "idempotency-sandbox-001"
        );
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

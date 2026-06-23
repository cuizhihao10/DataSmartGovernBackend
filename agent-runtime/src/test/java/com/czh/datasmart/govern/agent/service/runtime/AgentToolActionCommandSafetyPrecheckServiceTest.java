/**
 * @Author : Cui
 * @Date: 2026-06-23 01:37
 * @Description DataSmart Govern Backend - AgentToolActionCommandSafetyPrecheckServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentCommandSafetyPrecheckProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandSafetyPrecheckRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandSafetyPrecheckResponse;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 命令安全预检服务测试。
 *
 * <p>这组测试保护的是 command / permission / tools 三个能力域的交界：
 * - safe-cmd：常见只读命令可以进入受控执行链路；
 * - dangerous-path：工作区外路径、凭据路径、系统路径必须 fail-closed；
 * - HITL：联网、写入和未知命令必须进入人工审批或审批事实回查；
 * - low-sensitive：响应不能泄露原始命令、真实路径、URL 或潜在敏感参数。</p>
 */
class AgentToolActionCommandSafetyPrecheckServiceTest {

    @Test
    void precheckShouldAllowSafeReadOnlyCommandInsideWorkspace() {
        AgentToolActionCommandSafetyPrecheckService service = service();
        String workspaceRoot = workspaceRoot();
        String workingDirectory = workspaceChild("project-a");
        String sourcePath = workspaceChild("project-a/src");

        AgentToolActionCommandSafetyPrecheckResponse response = service.precheck(
                request("rg customer " + sourcePath, workspaceRoot, workingDirectory, List.of(sourcePath),
                        false, false, false, null, 30, 8192),
                projectOwnerContext()
        );

        assertEquals("ALLOW_CONTROLLED_EXECUTION", response.decision());
        assertEquals(true, response.executable());
        assertEquals(false, response.requiresHumanApproval());
        assertEquals(false, response.blocked());
        assertEquals("LOW", response.riskLevel());
        assertTrue(response.reasonCodes().contains("COMMAND_MATCHED_SAFE_ALLOWLIST"));
        assertTrue(response.pathCategories().contains("WORKING_DIRECTORY_INSIDE_WORKSPACE"));
        assertTrue(response.pathCategories().contains("REFERENCED_PATH_INSIDE_WORKSPACE"));
        assertEquals(false, response.commandLineReturned());
        assertEquals(false, response.pathValuesReturned());
        assertEquals(false, response.sideEffectExecuted());

        String serialized = response.toString();
        assertFalse(serialized.contains("rg customer"));
        assertFalse(serialized.contains(sourcePath));
        assertFalse(serialized.contains(workspaceRoot));
    }

    @Test
    void precheckShouldBlockDestructiveCommandEvenWhenWorkspaceLooksValid() {
        AgentToolActionCommandSafetyPrecheckService service = service();
        String workspaceRoot = workspaceRoot();
        String workingDirectory = workspaceChild("project-a");

        AgentToolActionCommandSafetyPrecheckResponse response = service.precheck(
                request("rm -rf " + workspaceChild("project-a/build"), workspaceRoot, workingDirectory,
                        List.of(workspaceChild("project-a/build")), true, false, true,
                        "approval-fact:dangerous-command", 60, 4096),
                projectOwnerContext()
        );

        assertEquals("BLOCKED_BY_COMMAND_SAFETY", response.decision());
        assertEquals(true, response.blocked());
        assertEquals(false, response.executable());
        assertEquals("CRITICAL", response.riskLevel());
        assertTrue(response.issueCodes().contains("DANGEROUS_COMMAND_FRAGMENT"));
        assertTrue(response.recommendedActions().stream().anyMatch(action -> action.contains("移除危险片段")));
        assertFalse(response.toString().contains("rm -rf"));
    }

    @Test
    void precheckShouldBlockCredentialPathAndWorkspaceEscapeWithoutReturningPathValue() {
        AgentToolActionCommandSafetyPrecheckService service = service();
        String workspaceRoot = workspaceRoot();
        String workingDirectory = workspaceChild("project-a");
        String secretPath = Path.of("C:\\Users\\Cui\\.ssh\\id_rsa").toString();

        AgentToolActionCommandSafetyPrecheckResponse response = service.precheck(
                request("cat " + secretPath, workspaceRoot, workingDirectory,
                        List.of(secretPath), false, false, false, null, 30, 4096),
                projectOwnerContext()
        );

        assertEquals("BLOCKED_BY_COMMAND_SAFETY", response.decision());
        assertEquals(true, response.blocked());
        assertTrue(response.issueCodes().contains("BLOCKED_PATH_FRAGMENT"));
        assertTrue(response.issueCodes().contains("REFERENCED_PATH_OUTSIDE_WORKSPACE"));
        assertTrue(response.pathCategories().contains("BLOCKED_PATH_FRAGMENT"));
        assertTrue(response.pathCategories().contains("REFERENCED_PATH_OUTSIDE_WORKSPACE"));

        String serialized = response.toString();
        assertFalse(serialized.contains("id_rsa"));
        assertFalse(serialized.contains(".ssh"));
        assertFalse(serialized.contains("C:\\Users\\Cui"));
    }

    @Test
    void precheckShouldRequireHumanApprovalForNetworkCommandAndKeepUrlHidden() {
        AgentToolActionCommandSafetyPrecheckService service = service();
        String workspaceRoot = workspaceRoot();
        String workingDirectory = workspaceChild("project-a");

        AgentToolActionCommandSafetyPrecheckResponse response = service.precheck(
                request("curl https://example.com/private.csv", workspaceRoot, workingDirectory,
                        List.of(workingDirectory), false, true, false, null, 30, 4096),
                projectOwnerContext()
        );

        assertEquals("REQUIRE_HUMAN_APPROVAL", response.decision());
        assertEquals(false, response.executable());
        assertEquals(true, response.requiresHumanApproval());
        assertTrue(response.issueCodes().contains("NETWORK_COMMAND_REQUIRES_APPROVAL"));
        assertTrue(response.issueCodes().contains("UNKNOWN_COMMAND_REQUIRES_APPROVAL"));
        assertFalse(response.toString().contains("https://example.com"));
        assertFalse(response.toString().contains("private.csv"));
    }

    @Test
    void precheckShouldKeepApprovalFactAsServerSideVerificationNotAsExecutedState() {
        AgentToolActionCommandSafetyPrecheckService service = service();
        String workspaceRoot = workspaceRoot();
        String workingDirectory = workspaceChild("project-a");

        AgentToolActionCommandSafetyPrecheckResponse response = service.precheck(
                request("curl https://example.com/private.csv", workspaceRoot, workingDirectory,
                        List.of(workingDirectory), false, true, true,
                        "approval-fact:network-download-001", 30, 4096),
                projectOwnerContext()
        );

        assertEquals("REQUIRE_HUMAN_APPROVAL", response.decision());
        assertEquals(false, response.executable());
        assertTrue(response.issueCodes().contains("APPROVAL_FACT_REQUIRES_SERVER_VERIFICATION"));
        assertFalse(response.issueCodes().contains("NETWORK_COMMAND_REQUIRES_APPROVAL"));
        assertFalse(response.issueCodes().contains("UNKNOWN_COMMAND_REQUIRES_APPROVAL"));
        assertFalse(response.toString().contains("network-download-001"));
        assertEquals(false, response.sideEffectExecuted());
    }

    @Test
    void precheckShouldCapTimeoutAndOutputBudgetByServerPolicy() {
        AgentToolActionCommandSafetyPrecheckService service = service();
        String workspaceRoot = workspaceRoot();
        String workingDirectory = workspaceChild("project-a");

        AgentToolActionCommandSafetyPrecheckResponse response = service.precheck(
                request("git status", workspaceRoot, workingDirectory,
                        List.of(workingDirectory), false, false, false, null, 999, 999999),
                projectOwnerContext()
        );

        assertEquals("ALLOW_CONTROLLED_EXECUTION", response.decision());
        assertEquals(120, response.normalizedTimeoutSeconds());
        assertEquals(65536, response.normalizedOutputByteLimitBytes());
        assertTrue(response.issueCodes().contains("TIMEOUT_CAPPED_TO_POLICY_LIMIT"));
        assertTrue(response.issueCodes().contains("OUTPUT_LIMIT_CAPPED_TO_POLICY_LIMIT"));
        assertTrue(response.recommendedActions().stream().anyMatch(action -> action.contains("预算已被服务端裁剪")));
    }

    @Test
    void precheckShouldBlockProjectScopeMismatchFromTrustedHeaders() {
        AgentToolActionCommandSafetyPrecheckService service = service();
        String workspaceRoot = workspaceRoot();
        String workingDirectory = workspaceChild("project-a");

        AgentToolActionCommandSafetyPrecheckResponse response = service.precheck(
                new AgentToolActionCommandSafetyPrecheckRequest(
                        "10",
                        "99",
                        "1001",
                        "request-scope",
                        "run-scope",
                        "session-scope",
                        "client-scope",
                        "MODEL_TOOL_CALL",
                        "git status",
                        "READ_ONLY",
                        workspaceRoot,
                        workingDirectory,
                        List.of(workingDirectory),
                        false,
                        false,
                        false,
                        null,
                        30,
                        4096
                ),
                projectOwnerContext()
        );

        assertEquals("BLOCKED_BY_COMMAND_SAFETY", response.decision());
        assertTrue(response.issueCodes().contains("PROJECT_SCOPE_NOT_AUTHORIZED"));
    }

    private AgentToolActionCommandSafetyPrecheckService service() {
        return new AgentToolActionCommandSafetyPrecheckService(new AgentCommandSafetyPrecheckProperties());
    }

    private AgentToolActionCommandSafetyPrecheckRequest request(String commandLine,
                                                               String workspaceRoot,
                                                               String workingDirectory,
                                                               List<String> referencedPaths,
                                                               boolean writeRequested,
                                                               boolean networkRequested,
                                                               boolean approvalConfirmed,
                                                               String approvalFactId,
                                                               Integer timeoutSeconds,
                                                               Integer outputByteLimitBytes) {
        return new AgentToolActionCommandSafetyPrecheckRequest(
                "10",
                "20",
                "1001",
                "request-command-safety",
                "run-command-safety",
                "session-command-safety",
                "client-command-safety",
                "MODEL_TOOL_CALL",
                commandLine,
                "READ_ONLY",
                workspaceRoot,
                workingDirectory,
                referencedPaths,
                writeRequested,
                networkRequested,
                approvalConfirmed,
                approvalFactId,
                timeoutSeconds,
                outputByteLimitBytes
        );
    }

    private AgentRuntimeEventQueryAccessContext projectOwnerContext() {
        return new AgentRuntimeEventQueryAccessContext(
                10L,
                1001L,
                "PROJECT_OWNER",
                "trace-command-safety-test",
                "PROJECT",
                List.of(20L)
        );
    }

    private String workspaceRoot() {
        return Path.of("D:\\DataSmartAgentWorkspace").toString();
    }

    private String workspaceChild(String child) {
        return Path.of(workspaceRoot(), child).toString();
    }
}

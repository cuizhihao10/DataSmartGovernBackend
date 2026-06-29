/**
 * @Author : Cui
 * @Date: 2026/06/29 00:00
 * @Description DataSmart Govern Backend - AgentWorkspaceFilePayloadMaterializationServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workspace 文件工具 payload 物化测试。
 *
 * <p>本测试不是在验证真实文件读写，因为真实读写属于后续 worker 的职责。这里验证的是 Agent 商业化闭环中更靠前、
 * 也更容易被忽略的安全契约：Java 控制面可以把文件工具参数登记成服务端 `agent-payload:` 事实，但任何对外响应、
 * 事件摘要或投影视图都不能暴露路径明文、写入正文、contentReference 原值、workspace root、SQL、prompt 或密钥。</p>
 */
class AgentWorkspaceFilePayloadMaterializationServiceTest {

    @Test
    void materializeReadShouldStoreRelativePathInternallyAndExposeOnlyDigest() {
        InMemoryAgentToolActionPayloadStore store = new InMemoryAgentToolActionPayloadStore();
        AgentWorkspaceFilePayloadMaterializationService service = service(store);

        AgentWorkspaceFilePayloadMaterializationService.AgentWorkspaceFilePayloadMaterializationResponse response =
                service.materialize(new AgentWorkspaceFilePayloadMaterializationService.AgentWorkspaceFilePayloadMaterializationRequest(
                        null,
                        "run-workspace-001",
                        "read-readme",
                        "10",
                        "20",
                        "1001",
                        "workspace.file.read",
                        "READ",
                        "graph-workspace-001",
                        "workspace-file-read.v1",
                        "docs/readme.md",
                        null,
                        null,
                        false,
                        null,
                        null,
                        Duration.ofMinutes(15)
                ));

        assertTrue(response.materialized());
        assertEquals("workspace.file.read", response.toolName());
        assertEquals("READ", response.operation());
        assertTrue(response.payloadReference().startsWith("agent-payload:run-workspace-001/"));
        assertNotNull(response.pathDigest());
        assertFalse(response.toString().contains("docs/readme.md"));
        assertFalse(response.toString().contains("workspaceRoot"));
        assertFalse(response.toString().contains("content="));

        AgentToolActionPayloadRecord record = store.findByReference(response.payloadReference()).orElseThrow();
        assertTrue(record.payloadBodyAvailable());
        assertEquals("docs/readme.md", record.payloadBody().get("relativePath"));
        assertEquals(response.pathDigest(), record.payloadBody().get("pathDigest"));
        assertTrue(record.sensitiveArgumentNames().contains("relativePath"));
    }

    @Test
    void materializeWriteShouldStoreContentInternallyAndHideContentFromResponse() {
        InMemoryAgentToolActionPayloadStore store = new InMemoryAgentToolActionPayloadStore();
        AgentWorkspaceFilePayloadMaterializationService service = service(store);
        String content = "## Release Note\n\ncontrolled workspace update\n";

        AgentWorkspaceFilePayloadMaterializationService.AgentWorkspaceFilePayloadMaterializationResponse response =
                service.materialize(new AgentWorkspaceFilePayloadMaterializationService.AgentWorkspaceFilePayloadMaterializationRequest(
                        null,
                        "run-workspace-002",
                        "write-release-note",
                        "10",
                        "20",
                        "1001",
                        "workspace.file.write",
                        "WRITE",
                        "graph-workspace-002",
                        "workspace-file-write.v1",
                        "docs/release-note.md",
                        content,
                        null,
                        false,
                        "f0".repeat(32),
                        1024,
                        Duration.ofMinutes(15)
                ));

        assertTrue(response.materialized());
        assertEquals("WRITE", response.operation());
        assertTrue(response.payloadBodyAvailable());
        assertTrue(response.contentSizeBytes() > 0);
        assertNotNull(response.contentSha256());
        assertTrue(response.expectedSha256Provided());
        assertFalse(response.toString().contains(content));
        assertFalse(response.toString().contains("docs/release-note.md"));

        Map<String, Object> body = store.findByReference(response.payloadReference()).orElseThrow().payloadBody();
        assertEquals(content, body.get("content"));
        assertEquals("docs/release-note.md", body.get("relativePath"));
        assertEquals(response.contentSha256(), body.get("contentSha256"));
    }

    @Test
    void materializeWriteShouldAcceptContentReferenceWithoutEchoingReferenceValue() {
        InMemoryAgentToolActionPayloadStore store = new InMemoryAgentToolActionPayloadStore();
        AgentWorkspaceFilePayloadMaterializationService service = service(store);
        String contentReference = "artifact-ref:tenant-10/project-20/private-write-body";

        AgentWorkspaceFilePayloadMaterializationService.AgentWorkspaceFilePayloadMaterializationResponse response =
                service.materialize(new AgentWorkspaceFilePayloadMaterializationService.AgentWorkspaceFilePayloadMaterializationRequest(
                        null,
                        "run-workspace-003",
                        "write-by-ref",
                        "10",
                        "20",
                        "1001",
                        "workspace.file.write",
                        "WRITE",
                        "graph-workspace-003",
                        "workspace-file-write.v1",
                        "docs/generated-plan.md",
                        null,
                        contentReference,
                        true,
                        null,
                        null,
                        Duration.ofMinutes(15)
                ));

        assertTrue(response.materialized());
        assertTrue(response.contentReferenceProvided());
        assertFalse(response.toString().contains(contentReference));
        assertEquals(0, response.contentSizeBytes());

        Map<String, Object> body = store.findByReference(response.payloadReference()).orElseThrow().payloadBody();
        assertEquals(contentReference, body.get("contentReference"));
        assertNotNull(body.get("contentReferenceDigest"));
    }

    @Test
    void materializeShouldRejectUnsafePathsBeforeStoreAppend() {
        InMemoryAgentToolActionPayloadStore store = new InMemoryAgentToolActionPayloadStore();
        AgentWorkspaceFilePayloadMaterializationService service = service(store);

        for (String path : new String[]{"../secret.txt", "C:\\temp\\a.txt", ".env", ".git/config", "config/key.pem"}) {
            AgentWorkspaceFilePayloadMaterializationService.AgentWorkspaceFilePayloadMaterializationResponse response =
                    service.materialize(new AgentWorkspaceFilePayloadMaterializationService.AgentWorkspaceFilePayloadMaterializationRequest(
                            null,
                            "run-workspace-denied",
                            "unsafe-" + path.replace("\\", "-").replace("/", "-"),
                            "10",
                            "20",
                            "1001",
                            "workspace.file.read",
                            "READ",
                            "graph-workspace-denied",
                            "workspace-file-read.v1",
                            path,
                            null,
                            null,
                            false,
                            null,
                            null,
                            Duration.ofMinutes(15)
                    ));

            assertFalse(response.materialized());
            assertTrue(response.issueCodes().stream().anyMatch(code -> code.startsWith("WORKSPACE_FILE_")));
        }
        assertFalse(store.findByReference("agent-payload:run-workspace-denied/unsafe-..-secret.txt").isPresent());
    }

    @Test
    void materializeShouldRejectWriteWithoutBodyOrWithRiskMarker() {
        InMemoryAgentToolActionPayloadStore store = new InMemoryAgentToolActionPayloadStore();
        AgentWorkspaceFilePayloadMaterializationService service = service(store);

        AgentWorkspaceFilePayloadMaterializationService.AgentWorkspaceFilePayloadMaterializationResponse missingContent =
                service.materialize(writeRequest("run-workspace-missing", "docs/a.md", null, null));
        assertFalse(missingContent.materialized());
        assertTrue(missingContent.issueCodes().contains("WORKSPACE_FILE_WRITE_CONTENT_REQUIRED"));

        AgentWorkspaceFilePayloadMaterializationService.AgentWorkspaceFilePayloadMaterializationResponse riskyContent =
                service.materialize(writeRequest("run-workspace-risky", "docs/b.md", "password=plain-text", null));
        assertFalse(riskyContent.materialized());
        assertTrue(riskyContent.issueCodes().contains("WORKSPACE_FILE_WRITE_CONTENT_RISK_MARKER_DETECTED"));
        assertFalse(riskyContent.toString().contains("password=plain-text"));
    }

    private AgentWorkspaceFilePayloadMaterializationService service(InMemoryAgentToolActionPayloadStore store) {
        return new AgentWorkspaceFilePayloadMaterializationService(
                new AgentToolActionPayloadMaterializationService(store)
        );
    }

    private AgentWorkspaceFilePayloadMaterializationService.AgentWorkspaceFilePayloadMaterializationRequest writeRequest(
            String runId,
            String relativePath,
            String content,
            String contentReference) {
        return new AgentWorkspaceFilePayloadMaterializationService.AgentWorkspaceFilePayloadMaterializationRequest(
                null,
                runId,
                "write-test",
                "10",
                "20",
                "1001",
                "workspace.file.write",
                "WRITE",
                "graph-workspace-test",
                "workspace-file-write.v1",
                relativePath,
                content,
                contentReference,
                false,
                null,
                1024,
                Duration.ofMinutes(15)
        );
    }
}

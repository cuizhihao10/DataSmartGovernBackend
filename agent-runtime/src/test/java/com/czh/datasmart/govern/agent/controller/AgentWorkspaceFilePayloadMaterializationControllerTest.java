/**
 * @Author : Cui
 * @Date: 2026/06/29 00:00
 * @Description DataSmart Govern Backend - AgentWorkspaceFilePayloadMaterializationControllerTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentWorkspaceFilePayloadMaterializationRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentWorkspaceFilePayloadMaterializationResponse;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContextResolver;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionPayloadMaterializationService;
import com.czh.datasmart.govern.agent.service.runtime.AgentToolActionPayloadRecord;
import com.czh.datasmart.govern.agent.service.runtime.AgentWorkspaceFilePayloadMaterializationService;
import com.czh.datasmart.govern.agent.service.runtime.InMemoryAgentToolActionPayloadStore;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workspace 文件工具 payload 物化 Controller 测试。
 *
 * <p>这里不启动完整 Spring MVC，而是直接调用 Controller 方法，聚焦本类真正负责的边界：
 * Header 身份范围收口、DTO 到领域请求的转换、统一响应包装，以及“响应白名单”是否仍然低敏。
 * 真实路径和写入正文允许进入服务端内部 store，但绝不能出现在 API response 中。</p>
 */
class AgentWorkspaceFilePayloadMaterializationControllerTest {

    @Test
    void materializeShouldUseTrustedHeadersAndHidePathAndContentInResponse() {
        InMemoryAgentToolActionPayloadStore store = new InMemoryAgentToolActionPayloadStore();
        AgentWorkspaceFilePayloadMaterializationController controller = controller(store);
        String content = "workspace controlled write\n";

        PlatformApiResponse<AgentWorkspaceFilePayloadMaterializationResponse> apiResponse =
                controller.materialize(
                        request(null, "20", null, "docs/controller-note.md", content),
                        "trace-workspace-controller",
                        "10",
                        "1001",
                        "PROJECT_OWNER",
                        "PROJECT",
                        "20,21"
                );

        AgentWorkspaceFilePayloadMaterializationResponse response = apiResponse.getData();
        assertEquals(0, apiResponse.getCode());
        assertTrue(response.materialized());
        assertEquals("10", store.findByReference(response.payloadReference()).orElseThrow().tenantId());
        assertEquals("20", store.findByReference(response.payloadReference()).orElseThrow().projectId());
        assertEquals("1001", store.findByReference(response.payloadReference()).orElseThrow().actorId());
        assertFalse(response.toString().contains("docs/controller-note.md"));
        assertFalse(response.toString().contains(content));

        AgentToolActionPayloadRecord record = store.findByReference(response.payloadReference()).orElseThrow();
        assertEquals("docs/controller-note.md", record.payloadBody().get("relativePath"));
        assertEquals(content, record.payloadBody().get("content"));
    }

    @Test
    void materializeShouldRejectTenantMismatchBetweenBodyAndTrustedHeader() {
        AgentWorkspaceFilePayloadMaterializationController controller =
                controller(new InMemoryAgentToolActionPayloadStore());

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class, () ->
                controller.materialize(
                        request("11", "20", "1001", "docs/a.md", "safe content"),
                        "trace-tenant-denied",
                        "10",
                        "1001",
                        "PROJECT_OWNER",
                        "PROJECT",
                        "20"
                )
        );

        assertEquals(PlatformErrorCode.TENANT_SCOPE_DENIED, exception.getErrorCode());
    }

    @Test
    void materializeShouldRejectProjectOutsideAuthorizedProjectScope() {
        AgentWorkspaceFilePayloadMaterializationController controller =
                controller(new InMemoryAgentToolActionPayloadStore());

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class, () ->
                controller.materialize(
                        request(null, "99", null, "docs/a.md", "safe content"),
                        "trace-project-denied",
                        "10",
                        "1001",
                        "PROJECT_OWNER",
                        "PROJECT",
                        "20,21"
                )
        );

        assertEquals(PlatformErrorCode.FORBIDDEN, exception.getErrorCode());
    }

    @Test
    void materializeShouldRejectActorMismatchBetweenBodyAndTrustedHeader() {
        AgentWorkspaceFilePayloadMaterializationController controller =
                controller(new InMemoryAgentToolActionPayloadStore());

        PlatformBusinessException exception = assertThrows(PlatformBusinessException.class, () ->
                controller.materialize(
                        request(null, "20", "2002", "docs/a.md", "safe content"),
                        "trace-actor-denied",
                        "10",
                        "1001",
                        "PROJECT_OWNER",
                        "PROJECT",
                        "20"
                )
        );

        assertEquals(PlatformErrorCode.FORBIDDEN, exception.getErrorCode());
    }

    private AgentWorkspaceFilePayloadMaterializationController controller(InMemoryAgentToolActionPayloadStore store) {
        return new AgentWorkspaceFilePayloadMaterializationController(
                new AgentWorkspaceFilePayloadMaterializationService(
                        new AgentToolActionPayloadMaterializationService(store)
                ),
                new AgentRuntimeEventQueryAccessContextResolver()
        );
    }

    private AgentWorkspaceFilePayloadMaterializationRequest request(String tenantId,
                                                                    String projectId,
                                                                    String actorId,
                                                                    String relativePath,
                                                                    String content) {
        return new AgentWorkspaceFilePayloadMaterializationRequest(
                null,
                "run-workspace-controller",
                "controller-write",
                tenantId,
                projectId,
                actorId,
                "workspace.file.write",
                "WRITE",
                "graph-workspace-controller",
                "workspace-file-write.v1",
                relativePath,
                content,
                null,
                false,
                null,
                1024,
                900L
        );
    }
}

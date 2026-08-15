/**
 * @Author : Cui
 * @Date: 2026/08/05 00:00
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandOutboxControllerTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxDispatcher;
import com.czh.datasmart.govern.agent.event.command.AgentAsyncTaskCommandOutboxStore;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagSelectedNodeOutboxEnqueueRequest;
import com.czh.datasmart.govern.agent.service.AgentSessionService;
import com.czh.datasmart.govern.agent.service.execution.AgentAsyncTaskCommandOutboxOperationService;
import com.czh.datasmart.govern.agent.service.execution.AgentRunAsyncTaskCommandOutboxService;
import com.czh.datasmart.govern.agent.service.execution.AgentRunToolDagSelectedNodeOutboxService;
import com.czh.datasmart.govern.agent.service.session.AgentSessionAccessContext;
import com.czh.datasmart.govern.agent.service.session.AgentSessionEndpointAccessResolver;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证异步命令入箱控制器必须先完成会话所有权校验。
 *
 * <p>这些测试关注调用顺序和副作用边界：只要对象级授权失败，outbox 和 DAG 节点服务就绝不能收到写入调用。</p>
 */
class AgentAsyncTaskCommandOutboxControllerTest {

    private AgentRunAsyncTaskCommandOutboxService outboxService;
    private AgentRunToolDagSelectedNodeOutboxService selectedNodeOutboxService;
    private AgentSessionService sessionService;
    private AgentSessionEndpointAccessResolver endpointAccessResolver;
    private AgentAsyncTaskCommandOutboxController controller;

    /** 为每个用例创建隔离的 mock，避免前一用例的调用记录影响副作用断言。 */
    @BeforeEach
    void setUp() {
        outboxService = mock(AgentRunAsyncTaskCommandOutboxService.class);
        selectedNodeOutboxService = mock(AgentRunToolDagSelectedNodeOutboxService.class);
        sessionService = mock(AgentSessionService.class);
        endpointAccessResolver = mock(AgentSessionEndpointAccessResolver.class);
        controller = new AgentAsyncTaskCommandOutboxController(
                outboxService,
                selectedNodeOutboxService,
                sessionService,
                endpointAccessResolver,
                mock(AgentAsyncTaskCommandOutboxStore.class),
                mock(AgentAsyncTaskCommandOutboxOperationService.class),
                mock(AgentAsyncTaskCommandOutboxDispatcher.class)
        );
    }

    /** 验证合法请求先解析会话访问上下文，再把异步命令写入 outbox。 */
    @Test
    void enqueueShouldCheckSessionMutationAccessBeforeWritingOutbox() {
        HttpHeaders headers = userHeaders();
        AgentSessionAccessContext resolvedAccess = new AgentSessionAccessContext(10L, 101L, "user-1", "USER");
        when(endpointAccessResolver.resolveAutomatedExecutionAccess(
                eq("session-1"), any(), eq(null), eq(null))).thenReturn(resolvedAccess);

        controller.enqueueRunCommands("session-1", "run-1", headers, "trace-1");

        verify(sessionService).requireMutationAccess("session-1", resolvedAccess);
        verify(outboxService).enqueueRunAsyncTaskCommands("session-1", "run-1");
    }

    /** 验证普通 Run 入箱在会话归属校验失败后立即停止，不产生任何异步副作用。 */
    @Test
    void deniedSessionMutationShouldNotWriteOutbox() {
        HttpHeaders headers = userHeaders();
        AgentSessionAccessContext resolvedAccess = new AgentSessionAccessContext(10L, 101L, "user-2", "USER");
        when(endpointAccessResolver.resolveAutomatedExecutionAccess(
                eq("session-1"), any(), eq(null), eq(null))).thenReturn(resolvedAccess);
        doThrow(new PlatformBusinessException(PlatformErrorCode.FORBIDDEN, "无权推进其他用户的 Agent 会话"))
                .when(sessionService).requireMutationAccess("session-1", resolvedAccess);

        assertThrows(PlatformBusinessException.class,
                () -> controller.enqueueRunCommands("session-1", "run-1", headers, "trace-1"));

        verify(outboxService, never()).enqueueRunAsyncTaskCommands(any(), any());
    }

    /** 验证选择性 DAG 节点入箱同样不能绕过会话所有权校验。 */
    @Test
    void deniedSessionMutationShouldNotEnqueueSelectedDagNodes() {
        HttpHeaders headers = userHeaders();
        AgentSessionAccessContext resolvedAccess = new AgentSessionAccessContext(10L, 101L, "user-2", "USER");
        when(endpointAccessResolver.resolveAutomatedExecutionAccess(
                eq("session-1"), any(), eq(null), eq(null))).thenReturn(resolvedAccess);
        doThrow(new PlatformBusinessException(PlatformErrorCode.FORBIDDEN, "无权推进其他用户的 Agent 会话"))
                .when(sessionService).requireMutationAccess("session-1", resolvedAccess);

        assertThrows(PlatformBusinessException.class, () -> controller.enqueueSelectedDagNodes(
                "session-1", "run-1", mock(AgentRunToolDagSelectedNodeOutboxEnqueueRequest.class), headers, "trace-1"));

        verify(selectedNodeOutboxService, never()).enqueueSelectedAsyncNodes(any(), any(), any(), any());
    }

    /** 单条命令观察必须先经过会话对象级读取校验，再触碰 outbox 查询服务。 */
    @Test
    void observationShouldResolveReadAccessBeforeQueryingCommand() {
        HttpHeaders headers = userHeaders();
        AgentSessionAccessContext resolvedAccess = new AgentSessionAccessContext(10L, 101L, "user-1", "USER");
        when(endpointAccessResolver.resolveReadAccess(
                eq("session-1"), any(), eq(null), eq(null))).thenReturn(resolvedAccess);

        controller.observe("session-1", "run-1", "command-1", headers, "trace-1");

        verify(sessionService).getSession("session-1", resolvedAccess);
        verify(outboxService).observe("session-1", "run-1", "command-1");
    }

    /** 通用 outbox 列表也必须绑定 session 对象级读取范围。 */
    @Test
    void queryShouldRequireSessionReadAccess() {
        HttpHeaders headers = userHeaders();
        AgentSessionAccessContext resolvedAccess = new AgentSessionAccessContext(10L, 101L, "user-1", "USER");
        when(endpointAccessResolver.resolveReadAccess(
                eq("session-1"), any(), eq(null), eq(null))).thenReturn(resolvedAccess);

        controller.query("session-1", "run-1", null, 20, headers, "trace-1");

        verify(sessionService).getSession("session-1", resolvedAccess);
        verify(outboxService).queryForSession("session-1", "run-1", null, 20);
    }

    /** 构造模拟 Gateway 已认证并注入的普通用户范围 Header。 */
    private HttpHeaders userHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(PlatformContextHeaders.TENANT_ID, "10");
        headers.set(PlatformContextHeaders.PROJECT_ID, "101");
        headers.set(PlatformContextHeaders.ACTOR_ID, "user-1");
        headers.set(PlatformContextHeaders.ACTOR_ROLE, "USER");
        return headers;
    }
}

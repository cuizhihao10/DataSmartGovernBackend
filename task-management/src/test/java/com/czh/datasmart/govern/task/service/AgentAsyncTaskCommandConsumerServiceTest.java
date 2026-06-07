/**
 * @Author : Cui
 * @Date: 2026/05/31 16:50
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandConsumerServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service;

import com.czh.datasmart.govern.task.controller.dto.AgentAsyncTaskCommandConsumeResponse;
import com.czh.datasmart.govern.task.controller.dto.AgentAsyncTaskCommandRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import com.czh.datasmart.govern.task.entity.AgentAsyncTaskCommandInbox;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.mapper.AgentAsyncTaskCommandInboxMapper;
import com.czh.datasmart.govern.task.support.AgentAsyncTaskCommandState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Agent 异步工具命令消费服务测试。
 *
 * <p>这里不启动 Spring 容器和真实数据库，而是用 Mock 固定消费侧业务语义：
 * - 首次 command 会写 Inbox 并创建一个通用任务；
 * - 重复 command 不会重复创建任务；
 * - 不支持的协议版本、非受控 payloadReference、敏感字段越界都会在 Service 层阻断；
 * - task.params 只保存引用和参数名，不保存敏感参数值。</p>
 */
@ExtendWith(MockitoExtension.class)
class AgentAsyncTaskCommandConsumerServiceTest {

    @Mock
    private AgentAsyncTaskCommandInboxMapper inboxMapper;

    @Mock
    private TaskService taskService;

    private AgentAsyncTaskCommandConsumerService service;

    @BeforeEach
    void setUp() {
        service = new AgentAsyncTaskCommandConsumerService(inboxMapper, taskService, new ObjectMapper());
    }

    @Test
    void firstCommandShouldCreateInboxAndTaskWithSafeParams() {
        AgentAsyncTaskCommandRequest request = validRequest();
        Task createdTask = new Task();
        createdTask.setId(9001L);
        AtomicReference<String> insertedState = new AtomicReference<>();
        when(inboxMapper.insert(any(AgentAsyncTaskCommandInbox.class))).thenAnswer(invocation -> {
            AgentAsyncTaskCommandInbox inbox = invocation.getArgument(0);
            insertedState.set(inbox.getConsumeState());
            inbox.setId(1L);
            return 1;
        });
        when(taskService.createTask(anyString(), anyString(), eq("AGENT_ASYNC_TOOL"),
                anyString(), any(), any(), any(), eq(10L), isNull(), eq(20L), any(TaskActorContext.class)))
                .thenReturn(createdTask);

        AgentAsyncTaskCommandConsumeResponse response = service.consume(request);

        assertEquals("aatc-test-001", response.commandId());
        assertFalse(response.duplicate());
        assertTrue(response.taskCreated());
        assertEquals(9001L, response.taskId());

        ArgumentCaptor<AgentAsyncTaskCommandInbox> inboxCaptor = ArgumentCaptor.forClass(AgentAsyncTaskCommandInbox.class);
        verify(inboxMapper).insert(inboxCaptor.capture());
        assertEquals(AgentAsyncTaskCommandState.PROCESSING, insertedState.get());
        assertEquals("agent-tool-audit://session-001/run-001/atea-001/plan-arguments",
                inboxCaptor.getValue().getPayloadReference());

        ArgumentCaptor<String> paramsCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskService).createTask(anyString(), anyString(), eq("AGENT_ASYNC_TOOL"),
                paramsCaptor.capture(), any(), any(), any(), eq(10L), isNull(), eq(20L),
                any(TaskActorContext.class));
        assertTrue(paramsCaptor.getValue().contains("\"payloadReference\""));
        assertTrue(paramsCaptor.getValue().contains("\"commandType\":\"AGENT_TOOL_ASYNC_TASK_REQUESTED\""));
        assertTrue(paramsCaptor.getValue().contains("\"payloadReferenceType\":\"AGENT_TOOL_AUDIT\""));
        assertTrue(paramsCaptor.getValue().contains("\"workerDispatchEnabled\":true"));
        assertTrue(paramsCaptor.getValue().contains("\"credentialRef\""));
        assertTrue(paramsCaptor.getValue().contains("\"confirmationId\":\"dag-confirmation:test-001\""));
        assertTrue(paramsCaptor.getValue().contains("\"policyVersions\":[\"route-policy:860\"]"));
        assertFalse(paramsCaptor.getValue().contains("secret://mysql-prod"));

        ArgumentCaptor<AgentAsyncTaskCommandInbox> updateCaptor = ArgumentCaptor.forClass(AgentAsyncTaskCommandInbox.class);
        verify(inboxMapper).updateById(updateCaptor.capture());
        assertEquals(AgentAsyncTaskCommandState.TASK_CREATED, updateCaptor.getValue().getConsumeState());
        assertEquals(9001L, updateCaptor.getValue().getTaskId());
    }

    @Test
    void controlledToolActionCommandShouldCreateIsolatedControlTask() {
        AgentAsyncTaskCommandRequest request = validControlledToolActionRequest();
        Task createdTask = new Task();
        createdTask.setId(9101L);
        when(inboxMapper.insert(any(AgentAsyncTaskCommandInbox.class))).thenAnswer(invocation -> {
            AgentAsyncTaskCommandInbox inbox = invocation.getArgument(0);
            inbox.setId(2L);
            return 1;
        });
        when(taskService.createTask(anyString(), anyString(), eq("AGENT_TOOL_ACTION_CONTROLLED"),
                anyString(), any(), any(), any(), eq(10L), isNull(), eq(20L), any(TaskActorContext.class)))
                .thenReturn(createdTask);

        AgentAsyncTaskCommandConsumeResponse response = service.consume(request);

        assertEquals("taoc-consume-001", response.commandId());
        assertFalse(response.duplicate());
        assertTrue(response.taskCreated());
        assertEquals(9101L, response.taskId());

        ArgumentCaptor<AgentAsyncTaskCommandInbox> inboxCaptor = ArgumentCaptor.forClass(AgentAsyncTaskCommandInbox.class);
        verify(inboxMapper).insert(inboxCaptor.capture());
        assertEquals("AGENT_TOOL_ACTION_CONTROLLED_COMMAND", inboxCaptor.getValue().getCommandType());
        assertEquals("tool-action:graph-contract-hash", inboxCaptor.getValue().getAuditId());
        assertEquals("agent-runtime", inboxCaptor.getValue().getTargetService());
        assertEquals(null, inboxCaptor.getValue().getTargetEndpoint());
        assertEquals(null, inboxCaptor.getValue().getWorkspaceId());
        assertEquals("agent-payload:run-proposal/datasource-metadata-read",
                inboxCaptor.getValue().getPayloadReference());

        ArgumentCaptor<String> paramsCaptor = ArgumentCaptor.forClass(String.class);
        verify(taskService).createTask(anyString(), anyString(), eq("AGENT_TOOL_ACTION_CONTROLLED"),
                paramsCaptor.capture(), any(), any(), any(), eq(10L), isNull(), eq(20L),
                any(TaskActorContext.class));
        assertTrue(paramsCaptor.getValue().contains("\"commandKind\":\"TOOL_ACTION_CONTROLLED\""));
        assertTrue(paramsCaptor.getValue().contains("\"payloadReferenceType\":\"AGENT_PAYLOAD\""));
        assertTrue(paramsCaptor.getValue().contains("\"workerDispatchEnabled\":false"));
        assertTrue(paramsCaptor.getValue().contains("\"confirmationId\":\"approval:human-001\""));
        assertFalse(paramsCaptor.getValue().contains("targetEndpoint\":\"/internal"));
    }

    @Test
    void duplicateCommandShouldReuseExistingTaskWithoutCreatingAnotherTask() {
        AgentAsyncTaskCommandRequest request = validRequest();
        AgentAsyncTaskCommandInbox existing = existingInbox();
        when(inboxMapper.insert(any(AgentAsyncTaskCommandInbox.class))).thenThrow(new DuplicateKeyException("duplicate"));
        when(inboxMapper.selectOne(any())).thenReturn(existing);

        AgentAsyncTaskCommandConsumeResponse response = service.consume(request);

        assertTrue(response.duplicate());
        assertTrue(response.taskCreated());
        assertEquals(9001L, response.taskId());
        verify(taskService, never()).createTask(anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any(), any());
        verify(inboxMapper).updateById(existing);
    }

    @Test
    void unsupportedSchemaVersionShouldBeRejectedBeforeInboxInsert() {
        AgentAsyncTaskCommandRequest request = validRequest();
        request.setSchemaVersion("datasmart.agent.async-task-command.v2");

        assertThrows(IllegalArgumentException.class, () -> service.consume(request));

        verifyNoInteractions(inboxMapper);
        verifyNoInteractions(taskService);
    }

    @Test
    void unsafePayloadReferenceShouldBeRejected() {
        AgentAsyncTaskCommandRequest request = validRequest();
        request.setPayloadReference("{\"datasourcePassword\":\"secret://mysql-prod\"}");

        assertThrows(IllegalArgumentException.class, () -> service.consume(request));

        verifyNoInteractions(inboxMapper);
        verifyNoInteractions(taskService);
    }

    @Test
    void sensitiveArgumentsMustBeSubsetOfArgumentNames() {
        AgentAsyncTaskCommandRequest request = validRequest();
        request.setSensitiveArgumentNames(List.of("credentialRef", "unknownSecret"));

        assertThrows(IllegalArgumentException.class, () -> service.consume(request));

        verifyNoInteractions(inboxMapper);
        verifyNoInteractions(taskService);
    }

    @Test
    void invalidConfirmationIdShouldBeRejectedBeforeInboxInsert() {
        AgentAsyncTaskCommandRequest request = validRequest();
        request.setConfirmationId("plain-confirmation-id");

        assertThrows(IllegalArgumentException.class, () -> service.consume(request));

        verifyNoInteractions(inboxMapper);
        verifyNoInteractions(taskService);
    }

    @Test
    void delegationEvidenceMustNotContainSensitivePayload() {
        AgentAsyncTaskCommandRequest request = validRequest();
        request.setDelegationEvidence(List.of("prompt: system secret"));

        assertThrows(IllegalArgumentException.class, () -> service.consume(request));

        verifyNoInteractions(inboxMapper);
        verifyNoInteractions(taskService);
    }

    @Test
    void controlledToolActionCommandMustNotCarryTargetEndpoint() {
        AgentAsyncTaskCommandRequest request = validControlledToolActionRequest();
        request.setTargetEndpoint("/internal/unsafe");

        assertThrows(IllegalArgumentException.class, () -> service.consume(request));

        verifyNoInteractions(inboxMapper);
        verifyNoInteractions(taskService);
    }

    @Test
    void controlledToolActionPayloadReferenceMustBelongToCurrentRun() {
        AgentAsyncTaskCommandRequest request = validControlledToolActionRequest();
        request.setPayloadReference("agent-payload:another-run/datasource-metadata-read");

        assertThrows(IllegalArgumentException.class, () -> service.consume(request));

        verifyNoInteractions(inboxMapper);
        verifyNoInteractions(taskService);
    }

    private AgentAsyncTaskCommandRequest validRequest() {
        AgentAsyncTaskCommandRequest request = new AgentAsyncTaskCommandRequest();
        request.setSchemaVersion(AgentAsyncTaskCommandConsumerService.SUPPORTED_SCHEMA_VERSION);
        request.setCommandId("aatc-test-001");
        request.setIdempotencyKey("agent-tool-async:session-001:run-001:atea-001");
        request.setCommandType(AgentAsyncTaskCommandConsumerService.SUPPORTED_COMMAND_TYPE);
        request.setAuditId("atea-001");
        request.setSessionId("session-001");
        request.setRunId("run-001");
        request.setToolCode("data-sync.execute");
        request.setTargetService("data-sync");
        request.setTargetEndpoint("/sync-tasks");
        request.setTenantId(10L);
        request.setProjectId(20L);
        request.setWorkspaceId(30L);
        request.setActorId("actor-agent");
        request.setTraceId("trace-agent-001");
        request.setPayloadReference("agent-tool-audit://session-001/run-001/atea-001/plan-arguments");
        request.setArgumentNames(List.of("datasourceId", "credentialRef"));
        request.setSensitiveArgumentNames(List.of("credentialRef"));
        request.setConfirmationId("dag-confirmation:test-001");
        request.setPolicyVersions(List.of("route-policy:860"));
        request.setDelegationEvidence(List.of("serviceAccount=datasmart-agent-runtime;representedActor=actor-agent"));
        request.setPriority("HIGH");
        request.setMaxRetryCount(3);
        request.setMaxDeferCount(20);
        return request;
    }

    private AgentAsyncTaskCommandRequest validControlledToolActionRequest() {
        AgentAsyncTaskCommandRequest request = new AgentAsyncTaskCommandRequest();
        request.setSchemaVersion(AgentAsyncTaskCommandConsumerService.SUPPORTED_SCHEMA_VERSION);
        request.setCommandId("taoc-consume-001");
        request.setIdempotencyKey("tool-action:proposal:run-proposal:datasource-metadata-read");
        request.setCommandType(AgentAsyncTaskCommandConsumerService.SUPPORTED_TOOL_ACTION_COMMAND_TYPE);
        request.setAuditId("tool-action:graph-contract-hash");
        request.setSessionId("session-proposal");
        request.setRunId("run-proposal");
        request.setToolCode("datasource.metadata.read");
        request.setTargetService("agent-runtime");
        request.setTargetEndpoint(null);
        request.setTenantId(10L);
        request.setProjectId(20L);
        request.setWorkspaceId(null);
        request.setActorId("1001");
        request.setTraceId("trace-tool-action-consume");
        request.setPayloadReference("agent-payload:run-proposal/datasource-metadata-read");
        request.setArgumentNames(List.of());
        request.setSensitiveArgumentNames(List.of());
        request.setConfirmationId("approval:human-001");
        request.setPolicyVersions(List.of("tool-readiness-policy.v1"));
        request.setDelegationEvidence(List.of("REFERENCE_PREFIX:agent-payload", "RUN_ID_BOUND:run-proposal"));
        request.setPriority("MEDIUM");
        request.setMaxRetryCount(3);
        request.setMaxDeferCount(20);
        return request;
    }

    private AgentAsyncTaskCommandInbox existingInbox() {
        AgentAsyncTaskCommandInbox inbox = new AgentAsyncTaskCommandInbox();
        inbox.setId(1L);
        inbox.setCommandId("aatc-test-001");
        inbox.setIdempotencyKey("agent-tool-async:session-001:run-001:atea-001");
        inbox.setAuditId("atea-001");
        inbox.setConsumeState(AgentAsyncTaskCommandState.TASK_CREATED);
        inbox.setTaskId(9001L);
        return inbox;
    }
}

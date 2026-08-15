/**
 * @Author : Cui
 * @Date: 2026/08/15
 * @Description DataSmart Govern Backend - SyncAgentExecutionCorrelationSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.AgentSyncTaskExecuteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirectAgentInvocationContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncAgentExecutionCorrelation;
import com.czh.datasmart.govern.datasync.mapper.SyncAgentExecutionCorrelationMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 验证跨域关联只记录低敏身份，并对普通/不完整调用保持空白。 */
class SyncAgentExecutionCorrelationSupportTest {

    /** 完整 Agent 调用必须把 execution、session、run 和 audit 绑定到同一条事实。 */
    @Test
    void shouldRecordLowSensitiveAgentExecutionIdentity() {
        SyncAgentExecutionCorrelationMapper mapper = mock(SyncAgentExecutionCorrelationMapper.class);
        SyncAgentExecutionCorrelationSupport support = new SyncAgentExecutionCorrelationSupport(mapper);
        AgentSyncTaskExecuteRequest request = request();

        support.record(request, 41L);

        ArgumentCaptor<SyncAgentExecutionCorrelation> captor =
                ArgumentCaptor.forClass(SyncAgentExecutionCorrelation.class);
        verify(mapper).insertIfAbsent(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo(11L);
        assertThat(captor.getValue().getSyncTaskId()).isEqualTo(31L);
        assertThat(captor.getValue().getSyncExecutionId()).isEqualTo(41L);
        assertThat(captor.getValue().getSessionId()).isEqualTo("session-1");
        assertThat(captor.getValue().getRunId()).isEqualTo("run-1");
        assertThat(captor.getValue().getAuditId()).isEqualTo("audit-1");
        assertThat(captor.getValue().getEntryMode()).isEqualTo("ASYNC_AGENT_COMMAND");
    }

    /** 直接 Agent 工具入口没有初始 commandId，但必须保留同一条 session/run/audit 权威关联。 */
    @Test
    void shouldRecordDirectAgentToolExecutionWithoutFabricatingCommand() {
        SyncAgentExecutionCorrelationMapper mapper = mock(SyncAgentExecutionCorrelationMapper.class);
        SyncAgentExecutionCorrelationSupport support = new SyncAgentExecutionCorrelationSupport(mapper);
        SyncTask task = new SyncTask();
        task.setId(31L);
        task.setTenantId(11L);
        task.setProjectId(13L);
        SyncDirectAgentInvocationContext invocation = new SyncDirectAgentInvocationContext(
                "session-1", "run-1", "audit-1", "trace-1", "agent-runtime");

        support.recordDirect(task, 41L, new SyncActorContext(11L, 7L, "PROJECT_OWNER", "trace-1"), invocation);

        ArgumentCaptor<SyncAgentExecutionCorrelation> captor =
                ArgumentCaptor.forClass(SyncAgentExecutionCorrelation.class);
        verify(mapper).insertIfAbsent(captor.capture());
        assertThat(captor.getValue().getCommandId()).isNull();
        assertThat(captor.getValue().getEntryMode()).isEqualTo("DIRECT_AGENT_TOOL");
        assertThat(captor.getValue().getSessionId()).isEqualTo("session-1");
        assertThat(captor.getValue().getAuditId()).isEqualTo("audit-1");
    }

    /** 没有 executionId 时不能生成虚假的 Agent 关联。 */
    @Test
    void shouldIgnoreIncompleteExecutionIdentity() {
        SyncAgentExecutionCorrelationMapper mapper = mock(SyncAgentExecutionCorrelationMapper.class);
        SyncAgentExecutionCorrelationSupport support = new SyncAgentExecutionCorrelationSupport(mapper);

        support.record(request(), null);

        verify(mapper, never()).insertIfAbsent(org.mockito.ArgumentMatchers.any());
    }

    private AgentSyncTaskExecuteRequest request() {
        AgentSyncTaskExecuteRequest request = new AgentSyncTaskExecuteRequest();
        request.setTenantId(11L);
        request.setProjectId(13L);
        request.setSyncTaskId(31L);
        request.setCommandId("command-1");
        request.setSessionId("session-1");
        request.setRunId("run-1");
        request.setAuditId("audit-1");
        request.setTraceId("trace-1");
        return request;
    }
}

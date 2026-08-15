/**
 * @Author : Cui
 * @Date: 2026/08/15
 * @Description DataSmart Govern Backend - SyncAgentInvocationAuthoritySupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirectAgentInvocationContext;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.integration.agent.AgentRuntimeAuditObservation;
import com.czh.datasmart.govern.datasync.integration.agent.HttpAgentRuntimeAuditObservationClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证共享令牌之后仍会用 Java 审计复核租户、项目和工具身份。 */
class SyncAgentInvocationAuthoritySupportTest {

    @Test
    void shouldAcceptMatchingDirectToolAudit() {
        HttpAgentRuntimeAuditObservationClient client = mock(HttpAgentRuntimeAuditObservationClient.class);
        SyncAgentInvocationAuthoritySupport support = new SyncAgentInvocationAuthoritySupport(client);
        SyncActorContext actor = new SyncActorContext(11L, 7L, "PROJECT_OWNER", "trace-1");
        when(client.observe("session-1", "run-1", "audit-1", actor))
                .thenReturn(audit(11L, 13L));

        assertThatCode(() -> support.verifyDirect(task(), invocation(), actor)).doesNotThrowAnyException();
    }

    @Test
    void shouldRejectCrossProjectAuditEvenWithValidServiceIdentity() {
        HttpAgentRuntimeAuditObservationClient client = mock(HttpAgentRuntimeAuditObservationClient.class);
        SyncAgentInvocationAuthoritySupport support = new SyncAgentInvocationAuthoritySupport(client);
        SyncActorContext actor = new SyncActorContext(11L, 7L, "PROJECT_OWNER", "trace-1");
        when(client.observe("session-1", "run-1", "audit-1", actor))
                .thenReturn(audit(11L, 99L));

        assertThatThrownBy(() -> support.verifyDirect(task(), invocation(), actor))
                .isInstanceOf(PlatformBusinessException.class);
    }

    private AgentRuntimeAuditObservation audit(Long tenantId, Long projectId) {
        return new AgentRuntimeAuditObservation(true, true, "EXECUTING", "sync.task.run",
                "LOW", false, null, null, null, null, null, "AGENT_RUNTIME_AUDIT",
                "audit-1", "session-1", "run-1", tenantId, projectId);
    }

    private SyncTask task() {
        SyncTask task = new SyncTask();
        task.setId(31L);
        task.setTenantId(11L);
        task.setProjectId(13L);
        return task;
    }

    private SyncDirectAgentInvocationContext invocation() {
        return new SyncDirectAgentInvocationContext(
                "session-1", "run-1", "audit-1", "trace-1", "agent-runtime");
    }
}

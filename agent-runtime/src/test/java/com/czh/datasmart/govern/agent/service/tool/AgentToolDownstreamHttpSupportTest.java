/**
 * @Author : Cui
 * @Date: 2026/07/10 00:00
 * @Description DataSmart Govern Backend - AgentToolDownstreamHttpSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.model.AgentRunState;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.model.WorkspaceIsolationLevel;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentToolDownstreamHttpSupportTest {

    @Test
    void shouldDelegateRealUserAndProjectRoleWithoutServiceAccountElevation() {
        AgentToolDownstreamHttpSupport support = new AgentToolDownstreamHttpSupport(new AgentRuntimeProperties());
        HttpHeaders headers = new HttpHeaders();

        support.applyUserDelegationHeaders(headers, context("ORDINARY_USER", "USER", "101:MANAGER"));

        assertEquals("1001", headers.getFirst(PlatformContextHeaders.ACTOR_ID));
        assertEquals("ORDINARY_USER", headers.getFirst(PlatformContextHeaders.ACTOR_ROLE));
        assertEquals("USER", headers.getFirst(PlatformContextHeaders.ACTOR_TYPE));
        assertEquals("101:MANAGER", headers.getFirst(PlatformContextHeaders.AUTHORIZED_PROJECT_ROLES));
        assertEquals("agent-runtime", headers.getFirst(PlatformContextHeaders.SOURCE_SERVICE));
        assertEquals("PROJECT", headers.getFirst(PlatformContextHeaders.DATA_SCOPE_LEVEL));
    }

    @Test
    void shouldFailClosedWhenRealActorRoleIsMissing() {
        AgentToolDownstreamHttpSupport support = new AgentToolDownstreamHttpSupport(new AgentRuntimeProperties());

        assertThrows(PlatformBusinessException.class,
                () -> support.applyUserDelegationHeaders(new HttpHeaders(), context(null, "USER", "101:MANAGER")));
    }

    private AgentToolExecutionContext context(String actorRole, String actorType, String projectRoles) {
        AgentSessionRecord session = new AgentSessionRecord(
                "session-delegation",
                10L,
                101L,
                null,
                "1001",
                actorRole,
                actorType,
                projectRoles,
                "WEB",
                "创建全量同步任务",
                WorkspaceIsolationLevel.PROJECT,
                "tenant:10:project:101",
                LocalDateTime.now()
        );
        AgentRunRecord run = new AgentRunRecord(
                "run-delegation",
                session.getSessionId(),
                AgentRunState.PLANNING,
                "AGENT_REASONING",
                "创建全量同步任务",
                false,
                true,
                List.of(),
                Map.of(),
                LocalDateTime.now(),
                "等待确认"
        );
        AgentToolExecutionAuditRecord audit = new AgentToolExecutionAuditRecord(
                "audit-delegation",
                session.getSessionId(),
                run.getRunId(),
                "binding-delegation",
                "sync.task.draft.save",
                "DATA_SYNC",
                "data-sync",
                "/sync-tasks/create-wizard/drafts",
                null,
                10L,
                101L,
                null,
                "1001",
                "HIGH",
                "APPROVAL_REQUIRED",
                true,
                false,
                false,
                List.of("CREATE_DRAFT"),
                AgentToolExecutionState.PLANNED,
                "trace-delegation",
                "等待执行",
                LocalDateTime.now()
        );
        return new AgentToolExecutionContext(session, run, audit, Map.of(), "trace-delegation");
    }
}

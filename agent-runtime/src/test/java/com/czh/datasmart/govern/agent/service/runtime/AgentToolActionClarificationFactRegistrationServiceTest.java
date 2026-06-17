/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - AgentToolActionClarificationFactRegistrationServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentToolActionResumeFactBundleProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionClarificationFactUpsertRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionClarificationFactView;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 澄清事实登记服务测试。
 *
 * <p>登记服务是外部调用进入澄清事实仓储的第一道业务边界。
 * 这里重点验证：可信 Header 会覆盖/约束请求体，PROJECT 数据范围不能越权，默认 TTL 会生效，
 * 以及登记后的记录确实进入仓储供恢复预检 evaluator 使用。</p>
 */
class AgentToolActionClarificationFactRegistrationServiceTest {

    @Test
    void upsertShouldUseTrustedHeaderScopeAndDefaultTtl() {
        AgentToolActionResumeFactBundleProperties properties = new AgentToolActionResumeFactBundleProperties();
        properties.setClarificationFactDefaultTtlSeconds(1800L);
        AgentToolActionClarificationFactStore store = new InMemoryAgentToolActionClarificationFactStore(properties);
        InMemoryAgentRuntimeEventProjectionStore projectionStore =
                new InMemoryAgentRuntimeEventProjectionStore(20, 100);
        AgentToolActionClarificationFactRegistrationService service =
                new AgentToolActionClarificationFactRegistrationService(
                        store,
                        properties,
                        new AgentToolActionClarificationFactEventPublisher(projectionStore)
                );

        AgentToolActionClarificationFactView view = service.upsert(
                request("clarification-register-001", 20L, "1001", null),
                projectOwnerContext()
        );

        assertEquals("clarification-register-001", view.clarificationFactId());
        assertEquals("10", view.tenantId());
        assertEquals("20", view.projectId());
        assertEquals("1001", view.actorId());
        assertEquals("AVAILABLE", view.status());
        assertTrue(view.evidenceCodes().contains("CLARIFICATION_FACT_CONTENT_NOT_STORED"));
        assertTrue(view.expiresAt().isAfter(view.createdAt()));
        assertEquals(1, store.size());
        assertEquals(1, projectionStore.size());
        AgentRuntimeEventProjectionRecord event = projectionStore.query(new AgentRuntimeEventProjectionQuery(
                "10",
                "20",
                "1001",
                null,
                "run-resume",
                "session-resume",
                AgentToolActionClarificationFactEventPublisher.EVENT_TYPE,
                null,
                null
        )).getFirst();
        assertEquals("CLARIFICATION_FACT_AVAILABLE",
                new AgentRuntimeEventDisplaySupport().buildDisplay(event).status());
        assertTrue(Boolean.TRUE.equals(event.attributes().get("clarificationFactIdPresent")));
        assertEquals("LOW_SENSITIVE_CLARIFICATION_FACT_METADATA_ONLY_NO_FACT_ID_NO_USER_CONTENT",
                event.attributes().get("payloadPolicy"));
        assertTrue(Objects.toString(event.attributes(), "").contains("FORM_CONFIRMED"));
        assertTrue(!Objects.toString(event.attributes(), "").contains("clarification-register-001"));
    }

    @Test
    void projectScopeShouldRejectUnauthorizedProject() {
        AgentToolActionResumeFactBundleProperties properties = new AgentToolActionResumeFactBundleProperties();
        AgentToolActionClarificationFactRegistrationService service =
                new AgentToolActionClarificationFactRegistrationService(
                        new InMemoryAgentToolActionClarificationFactStore(properties),
                        properties,
                        new AgentToolActionClarificationFactEventPublisher(
                                new InMemoryAgentRuntimeEventProjectionStore(20, 100)
                        )
                );

        assertThrows(PlatformBusinessException.class,
                () -> service.upsert(request("clarification-project-denied", 99L, "1001", null),
                        projectOwnerContext()));
    }

    @Test
    void actorMismatchShouldBeRejected() {
        AgentToolActionResumeFactBundleProperties properties = new AgentToolActionResumeFactBundleProperties();
        AgentToolActionClarificationFactRegistrationService service =
                new AgentToolActionClarificationFactRegistrationService(
                        new InMemoryAgentToolActionClarificationFactStore(properties),
                        properties,
                        new AgentToolActionClarificationFactEventPublisher(
                                new InMemoryAgentRuntimeEventProjectionStore(20, 100)
                        )
                );

        assertThrows(PlatformBusinessException.class,
                () -> service.upsert(request("clarification-actor-denied", 20L, "2002", null),
                        projectOwnerContext()));
    }

    private AgentToolActionClarificationFactUpsertRequest request(String factId,
                                                                  Long projectId,
                                                                  String actorId,
                                                                  Instant expiresAt) {
        return new AgentToolActionClarificationFactUpsertRequest(
                factId,
                "session-resume",
                "run-resume",
                "taoc-resume-001",
                "datasource.metadata.read",
                "tool-readiness-policy.v1",
                10L,
                projectId,
                actorId,
                null,
                List.of("FORM_CONFIRMED"),
                List.of(),
                expiresAt
        );
    }

    private AgentRuntimeEventQueryAccessContext projectOwnerContext() {
        return new AgentRuntimeEventQueryAccessContext(
                10L,
                1001L,
                "PROJECT_OWNER",
                "trace-resume",
                "PROJECT",
                List.of(20L)
        );
    }
}

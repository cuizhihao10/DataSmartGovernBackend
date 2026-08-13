/**
 * @Author : Cui
 * @Date: 2026/08/12 00:00
 * @Description DataSmart Govern Backend - AgentA2aDiscoveryControllerTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller;

import com.czh.datasmart.govern.agent.controller.dto.AgentA2aAgentCapabilitiesView;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aPublicAgentCardView;
import com.czh.datasmart.govern.agent.service.runtime.AgentExternalProtocolDiscoveryEventPublisher;
import com.czh.datasmart.govern.agent.service.runtime.AgentExternalProtocolDiscoveryService;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventProjectionQuery;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventProjectionRecord;
import com.czh.datasmart.govern.agent.service.runtime.InMemoryAgentRuntimeEventProjectionStore;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression tests for the anonymous A2A Agent Card discovery route.
 *
 * <p>The public route is reachable without authentication. These tests keep the distinction clear:
 * request headers may be useful to a trusted management route, but they cannot be treated as caller
 * identity when they arrive directly at a public discovery endpoint.</p>
 */
class AgentA2aDiscoveryControllerTest {

    private AgentExternalProtocolDiscoveryService discoveryService;
    private InMemoryAgentRuntimeEventProjectionStore projectionStore;
    private MockMvc mockMvc;

    /**
     * Builds the controller with an in-memory runtime event store.
     *
     * <p>This lets the test exercise Spring's real header binding and then inspect the exact audit
     * projection that would be written. No full application context or external infrastructure is needed.</p>
     */
    @BeforeEach
    void setUp() {
        discoveryService = mock(AgentExternalProtocolDiscoveryService.class);
        projectionStore = new InMemoryAgentRuntimeEventProjectionStore(50, 200);
        when(discoveryService.buildA2aPublicAgentCard(null, null)).thenReturn(publicAgentCard());

        mockMvc = MockMvcBuilders.standaloneSetup(new AgentA2aDiscoveryController(
                discoveryService,
                new AgentExternalProtocolDiscoveryEventPublisher(projectionStore)
        )).build();
    }

    /**
     * Verifies that a caller cannot inject a tenant, workspace, actor, role, source, or trace into a public audit event.
     *
     * <p>The request deliberately supplies values that look like a privileged internal identity. The event must remain
     * anonymous and use only the server-defined public endpoint label. This prevents a public scan from polluting the
     * runtime audit timeline with an identity that the caller chose for itself.</p>
     */
    @Test
    void publicAgentCardShouldIgnoreClientIdentityHeadersInRuntimeAuditProjection() throws Exception {
        mockMvc.perform(get("/.well-known/agent-card.json")
                        .header(PlatformContextHeaders.TRACE_ID, "attacker-trace")
                        .header(PlatformContextHeaders.TENANT_ID, "attacker-tenant")
                        .header(PlatformContextHeaders.WORKSPACE_ID, "attacker-workspace")
                        .header(PlatformContextHeaders.ACTOR_ID, "attacker-actor")
                        .header(PlatformContextHeaders.ACTOR_ROLE, "PLATFORM_ADMIN")
                        .header(PlatformContextHeaders.REQUEST_SOURCE, "ATTACKER_SOURCE")
                        .header(PlatformContextHeaders.SOURCE_SERVICE, "attacker-service"))
                .andExpect(status().isOk());

        AgentRuntimeEventProjectionRecord record = onlyDiscoveryEvent();
        assertNull(record.tenantId());
        assertNull(record.projectId());
        assertNull(record.actorId());
        assertNull(record.requestId());
        assertFalse(Boolean.TRUE.equals(record.attributes().get("actorRolePresent")));
        assertEquals("PUBLIC_WELL_KNOWN", record.attributes().get("requestSource"));
        assertEquals("UNKNOWN", record.attributes().get("sourceService"));
        verify(discoveryService).buildA2aPublicAgentCard(null, null);
    }

    /**
     * Reads the single event written by the public discovery request.
     *
     * <p>The test sends exactly one request, so one event makes the security assertion easy to read and prevents a
     * later implementation from hiding the polluted event among unrelated records.</p>
     */
    private AgentRuntimeEventProjectionRecord onlyDiscoveryEvent() {
        List<AgentRuntimeEventProjectionRecord> records = projectionStore.query(new AgentRuntimeEventProjectionQuery(
                null,
                null,
                null,
                null,
                null,
                null,
                AgentExternalProtocolDiscoveryEventPublisher.EVENT_TYPE,
                null,
                10
        ));
        assertEquals(1, records.size());
        return records.getFirst();
    }

    /**
     * Creates the smallest valid public card required by the event publisher and JSON response.
     *
     * <p>The card contents are intentionally unimportant here. Keeping them minimal makes the test about the audit
     * boundary rather than the separate rules that decide which skills appear in an Agent Card.</p>
     */
    private AgentA2aPublicAgentCardView publicAgentCard() {
        return new AgentA2aPublicAgentCardView(
                "DataSmart Govern Master Agent",
                "Public A2A discovery card",
                List.of(),
                null,
                "test",
                null,
                new AgentA2aAgentCapabilitiesView(false, false, false, List.of()),
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null
        );
    }
}

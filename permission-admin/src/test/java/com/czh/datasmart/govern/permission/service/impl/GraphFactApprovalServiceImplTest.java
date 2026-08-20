/**
 * @Author : Cui
 * @Date: 2026/08/21 12:00
 * @Description DataSmart Govern Backend - GraphFactApprovalServiceImplTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.impl;

import com.czh.datasmart.govern.permission.controller.dto.AgentToolActionApprovalFactRegisterResponse;
import com.czh.datasmart.govern.permission.controller.dto.GraphFactApprovalRegisterRequest;
import com.czh.datasmart.govern.permission.event.GraphFactApprovalEventPublisher;
import com.czh.datasmart.govern.permission.service.AgentToolActionApprovalFactService;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 图事实审批服务测试。
 *
 * <p>这里固定最关键的闭环门禁：Python 生成的事实候选即使携带 APPROVED 文本，
 * 也必须先由 permission-admin 的权威 approval fact service 返回 APPROVED，服务才
 * 可以创建 Kafka outbox 事件；PENDING/REJECTED 不能绕过审批直接进入 Neo4j。</p>
 */
class GraphFactApprovalServiceImplTest {

    @Test
    void approvedFactShouldCreateKafkaOutboxEvent() {
        AgentToolActionApprovalFactService approvalFactService = mock(AgentToolActionApprovalFactService.class);
        GraphFactApprovalEventPublisher eventPublisher = mock(GraphFactApprovalEventPublisher.class);
        when(approvalFactService.register(any())).thenReturn(
                new AgentToolActionApprovalFactRegisterResponse("approval-1", "APPROVED", "policy-v1", "ok"));
        when(eventPublisher.publish(any())).thenReturn("graph-facts-approved:approval-1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        GraphFactApprovalServiceImpl service = new GraphFactApprovalServiceImpl(approvalFactService, eventPublisher);

        var response = service.register(request("APPROVED"));

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.eventId()).startsWith("graph-facts-approved:");
        verify(eventPublisher).publish(any(GraphFactApprovalRegisterRequest.class));
    }

    @Test
    void pendingFactMustNotCreateKafkaOutboxEvent() {
        AgentToolActionApprovalFactService approvalFactService = mock(AgentToolActionApprovalFactService.class);
        GraphFactApprovalEventPublisher eventPublisher = mock(GraphFactApprovalEventPublisher.class);
        when(approvalFactService.register(any())).thenReturn(
                new AgentToolActionApprovalFactRegisterResponse("approval-1", "PENDING", "policy-v1", "waiting"));
        GraphFactApprovalServiceImpl service = new GraphFactApprovalServiceImpl(approvalFactService, eventPublisher);

        var response = service.register(request("PENDING"));

        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.eventId()).isNull();
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void factBundleUriMustRejectCredentialLikeFragments() {
        AgentToolActionApprovalFactService approvalFactService = mock(AgentToolActionApprovalFactService.class);
        GraphFactApprovalEventPublisher eventPublisher = mock(GraphFactApprovalEventPublisher.class);
        GraphFactApprovalServiceImpl service = new GraphFactApprovalServiceImpl(approvalFactService, eventPublisher);
        GraphFactApprovalRegisterRequest request = request("APPROVED");
        request.setFactBundleUri("s3://bucket/facts?token=secret");

        assertThatThrownBy(() -> service.register(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("敏感凭据");
        verify(approvalFactService, never()).register(any());
    }

    private static GraphFactApprovalRegisterRequest request(String status) {
        GraphFactApprovalRegisterRequest request = new GraphFactApprovalRegisterRequest();
        request.setApprovalFactId("approval-1");
        request.setTenantId(10L);
        request.setApplicationId(20L);
        request.setProjectId(30L);
        request.setUserId("user-1");
        request.setActorId("actor-1");
        request.setAgentId("agent-1");
        request.setSessionId("session-1");
        request.setRunId("run-1");
        request.setPolicyVersion("policy-v1");
        request.setStatus(status.toUpperCase(Locale.ROOT));
        request.setFactBundleUri("s3://bucket/facts/business-sync-001.json");
        request.setFactFingerprint("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        request.setEntityCount(14);
        request.setEdgeCount(16);
        return request;
    }
}

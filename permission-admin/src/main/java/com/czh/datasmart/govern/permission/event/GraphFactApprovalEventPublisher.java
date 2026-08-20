/**
 * @Author : Cui
 * @Date: 2026/08/21 10:00
 * @Description DataSmart Govern Backend - GraphFactApprovalEventPublisher.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.event;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.czh.datasmart.govern.permission.config.GraphFactApprovalEventProperties;
import com.czh.datasmart.govern.permission.controller.dto.GraphFactApprovalRegisterRequest;
import com.czh.datasmart.govern.permission.entity.PermissionEventOutbox;
import com.czh.datasmart.govern.permission.mapper.PermissionEventOutboxMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把已批准的图事实候选写入 permission-admin 同库 outbox。
 *
 * <p>eventId 使用审批事实 ID 和事实指纹计算，重复调用只返回已有事件，不会创建重复消息。
 * 事件正文只包含 URI、计数和范围，不包含实体名称、字段样本、SQL 或任何凭据。</p>
 */
@Component
@RequiredArgsConstructor
public class GraphFactApprovalEventPublisher {

    private static final String EVENT_TYPE = "GRAPH_FACTS_APPROVED";
    private static final String SCHEMA_VERSION = "datasmart.graph-facts-approved.v1";

    private final GraphFactApprovalEventProperties properties;
    private final PermissionEventOutboxMapper eventOutboxMapper;
    private final ObjectMapper objectMapper;

    /** 写入或幂等复用一条图事实审批事件。 */
    public String publish(GraphFactApprovalRegisterRequest request) {
        String eventId = "graph-facts-approved:" + request.getApprovalFactId() + ":" + request.getFactFingerprint();
        PermissionEventOutbox existing = eventOutboxMapper.selectOne(new QueryWrapper<PermissionEventOutbox>()
                .eq("event_id", eventId));
        if (existing != null) {
            return existing.getEventId();
        }
        PermissionEventOutbox outbox = new PermissionEventOutbox();
        outbox.setEventId(eventId);
        outbox.setEventType(EVENT_TYPE);
        outbox.setTopic(properties.getTopic());
        outbox.setEventKey(request.getApprovalFactId());
        outbox.setPayloadJson(payload(request, eventId));
        outbox.setStatus("PENDING");
        outbox.setAttemptCount(0);
        outbox.setMaxAttempts(properties.getMaxAttempts());
        outbox.setTenantId(request.getTenantId());
        outbox.setResourceType("GRAPH_FACTS");
        outbox.setResourceId(request.getApprovalFactId());
        outbox.setTraceId(request.getRunId());
        outbox.setCreateTime(LocalDateTime.now());
        outbox.setUpdateTime(LocalDateTime.now());
        eventOutboxMapper.insert(outbox);
        return eventId;
    }

    private String payload(GraphFactApprovalRegisterRequest request, String eventId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", SCHEMA_VERSION);
        payload.put("eventId", eventId);
        payload.put("approvalFactId", request.getApprovalFactId());
        payload.put("factBundleUri", request.getFactBundleUri());
        payload.put("factFingerprint", request.getFactFingerprint());
        payload.put("tenantId", request.getTenantId());
        payload.put("applicationId", request.getApplicationId());
        payload.put("projectId", request.getProjectId());
        payload.put("entityCount", request.getEntityCount());
        payload.put("edgeCount", request.getEdgeCount());
        payload.put("policyVersion", request.getPolicyVersion());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("图事实审批事件序列化失败", exception);
        }
    }
}

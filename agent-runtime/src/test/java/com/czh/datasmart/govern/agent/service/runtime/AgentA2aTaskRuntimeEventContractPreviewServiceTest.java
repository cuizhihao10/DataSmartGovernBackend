/**
 * @Author : Cui
 * @Date: 2026/06/06 12:55
 * @Description DataSmart Govern Backend - AgentA2aTaskRuntimeEventContractPreviewServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentA2aTaskEventDeliveryChannelView;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aTaskEventPayloadFieldView;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aTaskRuntimeEventContractPreviewResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aTaskRuntimeEventContractView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2A Task runtime event 契约预览测试。
 *
 * <p>这组测试保护的是“未来真实 task 事件的低敏事实合同”。状态机告诉我们任务如何流转，事件契约告诉我们这些流转
 * 如何被记录、投递、回放和审计。如果事件字段没有白名单，后续某个实现很容易把原始消息、工具输入、artifact 正文或
 * 内部执行细节塞进 runtime event，最终让可观测性变成敏感数据扩散面。</p>
 */
class AgentA2aTaskRuntimeEventContractPreviewServiceTest {

    private AgentA2aTaskRuntimeEventContractPreviewService service;

    @BeforeEach
    void setUp() {
        service = new AgentA2aTaskRuntimeEventContractPreviewService();
    }

    /**
     * 验证核心 task 生命周期事件齐备。
     *
     * <p>这里只检查契约，不检查真实发布，因为当前阶段还没有 task endpoint。我们需要先确认未来 submitted、
     * working、input-required、auth-required、cancel、artifact、completed、failed 等事件都有稳定名称，
     * 后续真实执行链路才能用同一套事件进入 timeline、stream、push 和审计。</p>
     */
    @Test
    void buildPreviewShouldExposeCoreA2aTaskEventContracts() {
        AgentA2aTaskRuntimeEventContractPreviewResponse response = service.buildPreview();
        Set<String> eventTypes = response.contracts()
                .stream()
                .map(AgentA2aTaskRuntimeEventContractView::eventType)
                .collect(Collectors.toSet());

        assertTrue(response.previewOnly());
        assertFalse(response.eventPublishingEnabled());
        assertFalse(response.taskEndpointEnabled());
        assertTrue(eventTypes.containsAll(Set.of(
                "agent.a2a_task.submitted",
                "agent.a2a_task.working",
                "agent.a2a_task.input_required",
                "agent.a2a_task.auth_required",
                "agent.a2a_task.cancel_requested",
                "agent.a2a_task.canceled",
                "agent.a2a_task.artifact_announced",
                "agent.a2a_task.completed",
                "agent.a2a_task.failed",
                "agent.a2a_task.rejected"
        )));
        assertTrue(response.contracts().stream()
                .filter(contract -> contract.eventType().equals("agent.a2a_task.completed"))
                .findFirst()
                .orElseThrow()
                .terminalEvent());
    }

    /**
     * 验证每个事件都携带字段白名单、stream 映射和禁止载荷说明。
     *
     * <p>这条测试防止“裸 Map 事件”重新出现。每个事件都必须说明可以写哪些字段、对应哪个 A2A stream/push
     * 语义，以及禁止保存哪些正文或内部细节。</p>
     */
    @Test
    void contractsShouldCarryPayloadAllowListAndStreamMapping() {
        AgentA2aTaskRuntimeEventContractPreviewResponse response = service.buildPreview();

        for (AgentA2aTaskRuntimeEventContractView contract : response.contracts()) {
            assertFalse(contract.payloadFieldNames().isEmpty());
            assertFalse(contract.forbiddenPayloadSummary().isBlank());
            assertFalse(contract.streamEventKind().isBlank());
            assertFalse(contract.consumerNotes().isEmpty());
            assertTrue(contract.payloadFieldNames().contains("eventId"));
            assertTrue(contract.payloadFieldNames().contains("taskPublicId"));
        }
    }

    /**
     * 验证字段白名单包含排序、状态、幂等和 artifact 引用所需字段。
     *
     * <p>事件回放最怕两个问题：无法排序、无法去重。`sequence` 和 `eventId` 是排序/去重基础；`idempotencyKeyHash`
     * 保护重复提交；`artifactRef` 让结果可以引用而不复制正文。</p>
     */
    @Test
    void payloadFieldsShouldIncludeReplayAndGovernanceFields() {
        AgentA2aTaskRuntimeEventContractPreviewResponse response = service.buildPreview();
        Set<String> fieldNames = response.payloadFields()
                .stream()
                .map(AgentA2aTaskEventPayloadFieldView::fieldName)
                .collect(Collectors.toSet());

        assertTrue(fieldNames.containsAll(Set.of(
                "eventId",
                "taskPublicId",
                "contextPublicId",
                "a2aState",
                "internalPhase",
                "sequence",
                "idempotencyKeyHash",
                "artifactRef",
                "terminal"
        )));
        assertTrue(response.payloadFields().stream()
                .filter(field -> field.fieldName().equals("idempotencyKeyHash"))
                .findFirst()
                .orElseThrow()
                .sensitivity()
                .equals("HASH_ONLY"));
    }

    /**
     * 验证 stream、push、metrics、audit 通道都有独立契约。
     *
     * <p>A2A 规范里 streaming 和 push notification 都可以承载 task status/artifact 更新，但 DataSmart 不能把
     * webhook、stream、内部 timeline 和指标混成一个通道。每个通道都需要自己的投递语义、安全要求和运维要求。</p>
     */
    @Test
    void deliveryChannelsShouldCoverStreamPushMetricsAndAudit() {
        AgentA2aTaskRuntimeEventContractPreviewResponse response = service.buildPreview();
        Set<String> channelTypes = response.deliveryChannels()
                .stream()
                .map(AgentA2aTaskEventDeliveryChannelView::channelType)
                .collect(Collectors.toSet());

        assertTrue(channelTypes.containsAll(Set.of(
                "INTERNAL_PROJECTION",
                "A2A_STREAM",
                "A2A_PUSH",
                "METRICS",
                "AUDIT"
        )));
        assertTrue(response.deliveryChannels().stream()
                .filter(channel -> channel.channelType().equals("A2A_PUSH"))
                .findFirst()
                .orElseThrow()
                .deliverySemantics()
                .contains("at-least-once"));
    }

    /**
     * 验证预览响应不包含敏感 payload 英文键或内部细节。
     *
     * <p>这里故意检查容易被误当成 JSON 字段契约的英文 token。中文注释可以解释边界，但 API 响应本身不应该出现
     * 这些 token，否则外部 Agent 或前端可能误认为这些字段可以被读取或传递。</p>
     */
    @Test
    void previewShouldNotExposeSensitivePayloadTokens() throws Exception {
        String serialized = objectMapper()
                .writeValueAsString(service.buildPreview())
                .toLowerCase(Locale.ROOT);
        List<String> forbiddenTokens = List.of(
                "toolarguments",
                "targetendpoint",
                "api-key",
                "secret",
                "\"prompt\"",
                "modeloutput",
                "sample data",
                "drop table",
                "internal.example.com"
        );

        for (String token : forbiddenTokens) {
            assertFalse(serialized.contains(token), "契约预览不应包含敏感 token：" + token);
        }
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}

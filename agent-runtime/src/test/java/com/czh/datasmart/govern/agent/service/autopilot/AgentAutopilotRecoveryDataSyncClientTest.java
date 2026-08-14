/**
 * @Author : Cui
 * @Date: 2026/08/11 22:20
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryDataSyncClientTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.service.tool.AgentToolDownstreamHttpSupport;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 验证 Autopilot Kafka 触发事件被确认前使用的专用 data-sync 回调。
 *
 * <p>测试使用 Spring 内存 HTTP 服务器，不启动 data-sync 应用，从而只聚焦客户端合同：固定路由、固定
 * 低敏请求体、不携带用户委派 Header，以及严格的平台 envelope 校验。证明这些边界不需要 Kafka broker、
 * 数据库或未校验 session。</p>
 */
class AgentAutopilotRecoveryDataSyncClientTest {

    /**
     * 已解析的触发结果必须使用固定回调路由，并且只能走服务间认证路径。
     *
     * <p>断言有意拒绝操作者与委派 Header。校验器可能在建立可信 session 或委派前就拒绝事件，结果上报
     * 不能仅为了关闭 Kafka 回执而伪造或转发这些身份。</p>
     */
    @Test
    void shouldRecordTriggerResultWithFixedInternalContract() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentAutopilotRecoveryDataSyncClient client = client(builder);
        AgentAutopilotRecoveryExecutionResult result = new AgentAutopilotRecoveryExecutionResult(
                "event-1", "ATTENTION_REQUIRED", "RECOVERY_REQUIRES_REVIEW", 81L, 41L,
                "SEARCH", "RAG", 2, "sha256:" + "c".repeat(64));

        server.expect(once(), requestTo(
                        "http://data-sync.test/internal/data-sync/autopilot/recovery/triggers/event-1/results"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(headerDoesNotExist(PlatformContextHeaders.ACTOR_ID))
                .andExpect(headerDoesNotExist(PlatformContextHeaders.AGENT_DELEGATION_ID))
                .andExpect(jsonPath("$.status").value("ATTENTION_REQUIRED"))
                .andExpect(jsonPath("$.reasonCode").value("RECOVERY_REQUIRES_REVIEW"))
                .andExpect(jsonPath("$.caseId").value(81))
                .andExpect(jsonPath("$.currentExecutionId").value(41))
                .andExpect(jsonPath("$.retrievalDecision").value("SEARCH"))
                .andExpect(jsonPath("$.retrievalStrategy").value("RAG"))
                .andExpect(jsonPath("$.retrievalEvidenceCount").value(2))
                .andExpect(jsonPath("$.retrievalEvidenceDigest").value("sha256:" + "c".repeat(64)))
                .andRespond(withSuccess(
                        "{\"code\":0,\"reason\":\"SUCCESS\",\"message\":\"success\",\"data\":{\"recorded\":true}}",
                        MediaType.APPLICATION_JSON));

        client.recordTriggerResult(result);

        server.verify();
    }

    /**
     * HTTP 200 但缺少平台成功码表示集成合同损坏，必须保持可重试。
     *
     * <p>若此处正常返回，Kafka 可能提交一份 data-sync 尚未持久化的结果。因此客户端抛出
     * {@link IllegalStateException}，监听器专用重试 topic 会重新投递，最终耗尽后才转入 DLT。</p>
     */
    @Test
    void shouldRejectInvalidTriggerResultEnvelopeAsTechnicalFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentAutopilotRecoveryDataSyncClient client = client(builder);
        AgentAutopilotRecoveryExecutionResult result = new AgentAutopilotRecoveryExecutionResult(
                "event-2", "REJECTED", "AUTOPILOT_DELEGATION_INACTIVE", null, 41L);

        server.expect(once(), requestTo(
                        "http://data-sync.test/internal/data-sync/autopilot/recovery/triggers/event-2/results"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"data\":{}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.recordTriggerResult(result))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AUTOPILOT_DATA_SYNC_TRIGGER_RESULT_ENVELOPE_INVALID");

        server.verify();
    }

    /**
     * DLT 收敛必须使用固定内部路由，且不能转发被代理用户的委派 Header。
     *
     * <p>DLT 事实表示传输重试耗尽，不是新的用户授权工具动作。因此 data-sync 只接收已配置的服务凭据和
     * 原执行身份，再从持久存储重新加载全部 case 权限。</p>
     */
    @Test
    void shouldRecordDeadLetterWithFixedInternalContract() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentAutopilotRecoveryDataSyncClient client = client(builder);

        server.expect(once(), requestTo(
                        "http://data-sync.test/internal/data-sync/autopilot/recovery/triggers/event-3/dead-letter"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(headerDoesNotExist(PlatformContextHeaders.ACTOR_ID))
                .andExpect(headerDoesNotExist(PlatformContextHeaders.AGENT_DELEGATION_ID))
                .andExpect(jsonPath("$.currentExecutionId").value(43))
                .andRespond(withSuccess(
                        "{\"code\":0,\"reason\":\"SUCCESS\",\"message\":\"success\",\"data\":{}}",
                        MediaType.APPLICATION_JSON));

        client.recordTriggerDeadLettered("event-3", 43L);

        server.verify();
    }

    /**
     * 自治隔离调用必须把每项副作用事实绑定到固定内部路由和回执。
     *
     * <p>测试有意使用真实 HTTP 请求匹配器，而不是 mock 客户端方法。它证明被代理用户与 Agent 委派
     * Header 均被保留，模型输出不能选择 URL 或原因，并且只有任务、执行、摘要和指纹事实全部与请求
     * 匹配时才接受返回回执。</p>
     */
    @Test
    void shouldApplyAutonomousQuarantineWithFixedDigestBoundContract() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentAutopilotRecoveryDataSyncClient client = client(builder);
        AgentAutopilotVerifiedRecoveryTrigger trigger = trigger();
        String previewDigest = "e".repeat(64);
        String actionFingerprint = "f".repeat(64);
        AgentAutopilotRecoveryCaseView recoveryCase = new AgentAutopilotRecoveryCaseView(
                81L, 31L, 40L, 41L, "AUTO_APPROVED", 0L, 1, 5,
                "APPLY_QUARANTINE", null, "c".repeat(64), "d".repeat(64));
        AgentAutopilotRecoveryPlanResponse response = new AgentAutopilotRecoveryPlanResponse(
                "datasmart.autopilot.recovery-candidate.v1", "event-1", "CANDIDATE_READY",
                "RECOVERY_CANDIDATE_READY", "APPLY_QUARANTINE", "LOW", true,
                actionFingerprint, "a".repeat(64), 0.91d, true,
                Map.of(), Map.of(), "SKIP", "STRUCTURED_DIAGNOSTIC", Map.of(), true,
                "autopilot-recovery:event-1", "LOW_SENSITIVE_AUTOPILOT_RECOVERY_CANDIDATE_ONLY",
                Map.of());
        AgentAutopilotRecoveryQuarantinePreview preview =
                new AgentAutopilotRecoveryQuarantinePreview(
                        previewDigest, List.of(501L, 502L), "agent-runtime://run-1/preview");

        server.expect(once(), requestTo(
                        "http://data-sync.test/internal/data-sync/autopilot/recovery/cases/81/quarantine/apply"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(PlatformContextHeaders.ACTOR_ID, "14"))
                .andExpect(header(PlatformContextHeaders.ACTOR_ROLE, "PROJECT_ADMIN"))
                .andExpect(header(PlatformContextHeaders.AGENT_ID, "main-agent"))
                .andExpect(header(PlatformContextHeaders.AGENT_DELEGATION_ID, "delegation-1"))
                .andExpect(jsonPath("$.expectedVersion").value(0))
                .andExpect(jsonPath("$.tenantId").value(11))
                .andExpect(jsonPath("$.projectId").value(13))
                .andExpect(jsonPath("$.syncTaskId").value(31))
                .andExpect(jsonPath("$.executionId").value(41))
                .andExpect(jsonPath("$.cycle").value(1))
                .andExpect(jsonPath("$.authorizationDigest").value("c".repeat(64)))
                .andExpect(jsonPath("$.policyDigest").value("d".repeat(64)))
                .andExpect(jsonPath("$.previewDigest").value(previewDigest))
                .andExpect(jsonPath("$.selectedSampleIds[0]").value(501))
                .andExpect(jsonPath("$.selectedSampleIds[1]").value(502))
                .andExpect(jsonPath("$.actionFingerprint").value(actionFingerprint))
                .andExpect(jsonPath("$.receiptId").value("event-1:quarantine-apply"))
                .andRespond(withSuccess("""
                        {"code":0,"reason":"SUCCESS","message":"success","data":{
                          "receiptId":"event-1:quarantine-apply","caseId":81,"syncTaskId":31,
                          "executionId":41,"selectedCount":2,"affectedCount":2,
                          "operationState":"APPLIED","receiptState":"COMPLETED",
                          "previewDigest":"%s","actionFingerprint":"%s"
                        }}
                        """.formatted(previewDigest, actionFingerprint), MediaType.APPLICATION_JSON));

        AgentAutopilotRecoveryQuarantineApplyReceipt receipt =
                client.applyAutonomousQuarantine(trigger, recoveryCase, response, preview);

        assertThat(receipt.isDurablyApplied()).isTrue();
        server.verify();
    }

    /**
     * 受治理修复调用必须使用固定内部路径，并把参数绑定到 case、授权摘要和双主体。
     */
    @Test
    void shouldApplyGovernedRepairWithFixedFingerprintBoundContract() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentAutopilotRecoveryDataSyncClient client = client(builder);
        AgentAutopilotVerifiedRecoveryTrigger trigger = trigger();
        String actionFingerprint = "f".repeat(64);
        AgentAutopilotRecoveryCaseView recoveryCase = new AgentAutopilotRecoveryCaseView(
                81L, 31L, 40L, 41L, "AUTO_APPROVED", 0L, 1, 5,
                "REPAIR_FIELD_MAPPING", null, "c".repeat(64), "d".repeat(64));
        AgentAutopilotRecoveryPlanResponse response = new AgentAutopilotRecoveryPlanResponse(
                "datasmart.autopilot.recovery-candidate.v1", "event-1", "CANDIDATE_READY",
                "RECOVERY_CANDIDATE_READY", "REPAIR_FIELD_MAPPING", "LOW", true,
                actionFingerprint, "a".repeat(64), 0.91d, true,
                Map.of(), Map.of(), "SKIP", "STRUCTURED_DIAGNOSTIC", Map.of(), true,
                "autopilot-recovery:event-1", "LOW_SENSITIVE_AUTOPILOT_RECOVERY_CANDIDATE_ONLY",
                Map.of(), Map.of(), Map.of("repairMode", "METADATA_PROVEN_SAFE"), Map.of());

        server.expect(once(), requestTo(
                        "http://data-sync.test/internal/data-sync/autopilot/recovery/cases/81/repairs/apply"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(PlatformContextHeaders.ACTOR_ID, "14"))
                .andExpect(header(PlatformContextHeaders.AGENT_ID, "main-agent"))
                .andExpect(jsonPath("$.expectedVersion").value(0))
                .andExpect(jsonPath("$.tenantId").value(11))
                .andExpect(jsonPath("$.projectId").value(13))
                .andExpect(jsonPath("$.syncTaskId").value(31))
                .andExpect(jsonPath("$.executionId").value(41))
                .andExpect(jsonPath("$.action").value("REPAIR_FIELD_MAPPING"))
                .andExpect(jsonPath("$.actionFingerprint").value(actionFingerprint))
                .andExpect(jsonPath("$.repairParameters.repairMode").value("METADATA_PROVEN_SAFE"))
                .andExpect(jsonPath("$.receiptId").value("event-1:repair-apply"))
                .andRespond(withSuccess("""
                        {"code":0,"data":{
                          "receiptId":"event-1:repair-apply","caseId":81,"syncTaskId":31,
                          "sourceExecutionId":41,"executionId":41,"action":"REPAIR_FIELD_MAPPING",
                          "applied":true,"affectedCount":1,"executionState":"RETRYING",
                          "taskState":"RETRYING","reasonCode":"AUTOPILOT_FIELD_MAPPING_REPAIRED",
                          "issueCodes":[],"actionFingerprint":"%s","caseState":"AUTO_APPROVED",
                          "replanQueued":false,"replanEventId":null,"nextCycle":null
                        }}
                        """.formatted(actionFingerprint), MediaType.APPLICATION_JSON));

        AgentAutopilotRecoveryRepairReceipt receipt = client.applyGovernedRepair(
                trigger, recoveryCase, response, response.repairParameters());

        assertThat(receipt.isDurablyApplied()).isTrue();
        assertThat(receipt.matchesScope(trigger.event(), recoveryCase, response)).isTrue();
        server.verify();
    }

    /**
     * 失败对象重试响应必须提供精确任务/执行标识和重排队生命周期状态。
     *
     * <p>这些字段是恢复后 Specialist 复核唯一允许使用的资源定位信息。标识缺失的 Map 不能再回退到旧
     * Kafka 事件并伪装成真实回执。</p>
     */
    @Test
    void shouldReturnScopeBoundRetryReceipt() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentAutopilotRecoveryDataSyncClient client = client(builder);

        server.expect(once(), requestTo(
                        "http://data-sync.test/sync-tasks/31/executions/41/objects/retry"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.idempotencyKey").value("event-1"))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"taskId":31,"executionId":41,"retryObjectCount":2,
                         "executionState":"QUEUED","taskState":"RETRYING"}}
                        """, MediaType.APPLICATION_JSON));

        AgentAutopilotRecoveryRetryReceipt receipt = client.retryFailedObjects(trigger());

        assertThat(receipt.matchesRequeuedScope(trigger().event())).isTrue();
        server.verify();
    }

    /** 格式错误或跨作用域的重试响应仍属于可重试集成故障。 */
    @Test
    void shouldRejectCrossScopeRetryReceipt() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AgentAutopilotRecoveryDataSyncClient client = client(builder);
        server.expect(once(), requestTo(
                        "http://data-sync.test/sync-tasks/31/executions/41/objects/retry"))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"taskId":999,"executionId":41,"retryObjectCount":2,
                         "executionState":"QUEUED","taskState":"RETRYING"}}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.retryFailedObjects(trigger()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("AUTOPILOT_RETRY_RECEIPT_SCOPE_OR_STATE_INVALID");
        server.verify();
    }

    /** 创建适合测试受治理 data-sync 写入的双主体触发事件。 */
    private AgentAutopilotVerifiedRecoveryTrigger trigger() {
        AgentAutopilotRecoveryTriggerEvent event = new AgentAutopilotRecoveryTriggerEvent(
                "datasmart.autopilot.recovery-trigger.v1", "event-1", "session-1", "run-1",
                11L, 12L, 13L, "14", "14", "main-agent", "delegation-1",
                31L, 40L, 41L, 1, 5, "2099-01-01T00:00:00Z", "a".repeat(64),
                0, null, List.of("OBJECT_TRANSFER_FAILED"), Map.of(),
                "sha256:" + "b".repeat(64), "2026-08-12T00:00:00Z");
        com.czh.datasmart.govern.agent.service.session.AgentSessionRecord session = mock(
                com.czh.datasmart.govern.agent.service.session.AgentSessionRecord.class);
        com.czh.datasmart.govern.agent.service.session.AgentRunRecord run = mock(
                com.czh.datasmart.govern.agent.service.session.AgentRunRecord.class);
        com.czh.datasmart.govern.agent.service.session.AgentDelegationRecord delegation = mock(
                com.czh.datasmart.govern.agent.service.session.AgentDelegationRecord.class);
        when(session.getTenantId()).thenReturn(11L);
        when(session.getApplicationId()).thenReturn(12L);
        when(session.getProjectId()).thenReturn(13L);
        when(session.getActorId()).thenReturn("14");
        when(session.getActorRole()).thenReturn("PROJECT_ADMIN");
        when(session.getActorType()).thenReturn("USER");
        when(session.getAgentId()).thenReturn("main-agent");
        when(session.getSessionId()).thenReturn("session-1");
        when(session.getDelegation()).thenReturn(delegation);
        when(delegation.getDelegationId()).thenReturn("delegation-1");
        when(run.getRunId()).thenReturn("run-1");
        return new AgentAutopilotVerifiedRecoveryTrigger(
                event, session, run, mock(AgentAutopilotAuthorizationSnapshot.class),
                OffsetDateTime.parse("2099-01-01T00:00:00Z"),
                OffsetDateTime.parse("2026-08-12T00:00:00Z"));
    }

    /**
     * 创建只包含传输测试所需受控 data-sync 基础 URL 的客户端。
     *
     * <p>支持对象使用真实实现，使测试覆盖生产使用的同一 URL 查询和服务令牌方法。它没有配置用户
     * session，从而证明结果回调的构造不依赖委派状态。</p>
     *
     * @param builder 被当前 MockRestServiceServer 拦截的 RestClient builder
     * @return 使用固定本地 data-sync 基础 URL 的被测客户端
     */
    private AgentAutopilotRecoveryDataSyncClient client(RestClient.Builder builder) {
        AgentRuntimeProperties properties = new AgentRuntimeProperties();
        properties.getToolServiceBaseUrls().put("data-sync", "http://data-sync.test");
        return new AgentAutopilotRecoveryDataSyncClient(
                builder, new AgentToolDownstreamHttpSupport(properties));
    }
}

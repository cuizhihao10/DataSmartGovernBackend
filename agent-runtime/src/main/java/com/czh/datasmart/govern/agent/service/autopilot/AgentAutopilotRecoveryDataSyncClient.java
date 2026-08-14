/**
 * @Author : Cui
 * @Date: 2026/08/11 20:25
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryDataSyncClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import com.czh.datasmart.govern.agent.service.tool.AgentToolDownstreamHttpSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * agent-runtime 调用 data-sync Autopilot case API 和失败对象重试 API 的受控客户端。
 *
 * <p>case decision/transition 使用内部服务令牌；真正的 retry 同时透传原用户与 Agent delegation，
 * 让 data-sync 按普通业务接口再次执行 RBAC、租户和资源归属校验。客户端不提供任意 URL、任意方法
 * 或任意 body 的通用出口，避免 Autopilot 变成内网 HTTP 代理。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentAutopilotRecoveryDataSyncClient {

    private static final String DATA_SYNC = "data-sync";
    private static final String DECISION_PATH = "/internal/data-sync/autopilot/recovery/decisions";
    private static final String TRANSITION_PATH =
            "/internal/data-sync/autopilot/recovery/cases/{caseId}/transitions";
    private static final String TRIGGER_RESULT_PATH =
            "/internal/data-sync/autopilot/recovery/triggers/{eventId}/results";
    private static final String TRIGGER_DEAD_LETTER_PATH =
            "/internal/data-sync/autopilot/recovery/triggers/{eventId}/dead-letter";
    private static final String QUARANTINE_APPLY_PATH =
            "/internal/data-sync/autopilot/recovery/cases/{caseId}/quarantine/apply";
    private static final String REPAIR_APPLY_PATH =
            "/internal/data-sync/autopilot/recovery/cases/{caseId}/repairs/apply";
    private static final String RETRY_PATH =
            "/sync-tasks/{taskId}/executions/{executionId}/objects/retry";

    private final RestClient.Builder restClientBuilder;
    private final AgentToolDownstreamHttpSupport httpSupport;

    /**
     * 把一条可解析 Kafka 触发器的最终处理结果写回 data-sync。
     *
     * <p>输入是消费者准备确认的低敏结果，data-sync 是触发结果回执的持久所有者，因此没有业务返回值。
     * 本方法刻意只发送内部服务令牌：验证器可能在事件尚无可信 session、run 或 delegation 时就拒绝，
     * 此时附加用户委派 Header 既没有事实基础，也可能让未验证事件影响授权边界。固定 URL 和固定请求字段
     * 同时防止该回调退化为通用内部 HTTP 代理。</p>
     *
     * <p>正常返回表示 data-sync 接受了成功平台信封，消费者可以提交 Kafka offset。HTTP 失败、信封缺失、
     * 非成功码或本地结果格式错误都属于基础设施/合同失败，必须抛出运行时异常，让监听器的有界重试和 DLT
     * 保留尚未持久化的终态结果。</p>
     *
     * @param result 已解析 Autopilot 恢复触发器的低敏终态结果
     * @throws IllegalStateException 当本地结果或 data-sync 响应违反回调合同时抛出
     */
    public void recordTriggerResult(AgentAutopilotRecoveryExecutionResult result) {
        if (result == null) {
            throw invalidDataSyncContract("AUTOPILOT_TRIGGER_RESULT_INVALID");
        }
        String eventId = requiredResultText(result.eventId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", requiredResultText(result.status()));
        body.put("reasonCode", requiredResultText(result.reasonCode()));
        body.put("caseId", result.caseId());
        body.put("currentExecutionId", result.currentExecutionId());
        body.put("retrievalDecision", result.retrievalDecision());
        body.put("retrievalStrategy", result.retrievalStrategy());
        body.put("retrievalEvidenceCount", result.retrievalEvidenceCount());
        body.put("retrievalEvidenceDigest", result.retrievalEvidenceDigest());

        RestClient client = httpSupport.serviceClient(restClientBuilder, DATA_SYNC);
        Map<String, Object> response = client.post()
                .uri(TRIGGER_RESULT_PATH, eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(httpSupport::applyInternalServiceToken)
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        if (!successfulPlatformEnvelope(response)) {
            throw invalidDataSyncContract("AUTOPILOT_DATA_SYNC_TRIGGER_RESULT_ENVELOPE_INVALID");
        }
    }

    /**
     * 请求 data-sync 持久收敛一条经过 Kafka 有界投递后仍进入死信主题的触发事件。
     *
     * <p>固定 URL 只携带 event ID，请求体只携带原始 current execution ID。这里刻意不发送被代理用户或
     * Agent 委托头，因为 DLT 处理器报告的是传输耗尽事实；data-sync 必须自行重新加载权威 outbox、决策回执、
     * case 范围和乐观锁版本。内部服务令牌只认证调用方，不能把 broker 载荷字段提升为状态迁移权限。</p>
     *
     * <p>平台成功信封只证明 DLT 事实已可在 data-sync 查询。任何本地身份格式错误、HTTP 失败或非成功信封
     * 仍然抛出异常，确保 Spring Kafka 的 {@code DltStrategy.FAIL_ON_ERROR} 不会提交尚未收敛的死信记录。</p>
     *
     * @param eventId 从原始触发载荷解析并验证的事件身份
     * @param currentExecutionId 触发器携带的正数执行身份
     */
    public void recordTriggerDeadLettered(String eventId, Long currentExecutionId) {
        String requiredEventId = requiredResultText(eventId);
        if (currentExecutionId == null || currentExecutionId <= 0) {
            throw invalidDataSyncContract("AUTOPILOT_TRIGGER_DEAD_LETTER_IDENTITY_INVALID");
        }
        Map<String, Object> response = httpSupport.serviceClient(restClientBuilder, DATA_SYNC)
                .post()
                .uri(TRIGGER_DEAD_LETTER_PATH, requiredEventId)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(httpSupport::applyInternalServiceToken)
                .body(Map.of("currentExecutionId", currentExecutionId))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        if (!successfulPlatformEnvelope(response)) {
            throw invalidDataSyncContract("AUTOPILOT_DATA_SYNC_DEAD_LETTER_ENVELOPE_INVALID");
        }
    }

    /**
     * 将 Java 证据校验后的候选交给 data-sync 做第二次本地策略评估，并持久化 recovery case 决策。
     *
     * <p>输入是可信触发器、Python 的低敏候选和 Java 证据校验结果；输出是 data-sync 返回的带版本 case 视图。
     * 方法会向固定内部路径发起一次 POST，并写入远端 case，因此具有网络和持久化副作用。它同时传递用户
     * delegation 和内部服务令牌：前者让 data-sync 重新执行租户、用户和资源 RBAC，后者只证明这是受控的
     * 服务间调用，二者缺一不可。</p>
     *
     * <p>{@code receiptId} 固定为 {@code eventId:decision}，使 data-sync 可以把同一 Kafka 事件的重复决策
     * 识别为同一业务事实。返回的 {@code state} 是第二道策略的权威结论，调用方不能只凭 Java 的
     * {@code AUTO_APPROVED} 绕过它。下游 HTTP 错误直接传播；响应没有可用 {@code data} 时抛出稳定的
     * 技术合同异常，而不是猜测成功。</p>
     *
     * @param trigger 已验证身份、授权范围和恢复时限的触发器
     * @param response 已由 Python 返回但尚未在 data-sync 落案的低敏候选
     * @param evidenceVerified Java 是否已复算并验证候选证据
     * @return data-sync 持久化后的 case 视图及其乐观锁版本
     * @throws IllegalStateException data-sync 响应合同缺少有效 case 数据时抛出
     */
    public AgentAutopilotRecoveryCaseView recordDecision(
            AgentAutopilotVerifiedRecoveryTrigger trigger,
            AgentAutopilotRecoveryPlanResponse response,
            boolean evidenceVerified) {
        AgentAutopilotRecoveryTriggerEvent event = trigger.event();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", event.tenantId());
        body.put("projectId", event.projectId());
        body.put("syncTaskId", event.syncTaskId());
        body.put("rootExecutionId", event.rootExecutionId());
        body.put("currentExecutionId", event.currentExecutionId());
        body.put("cycle", event.cycle());
        body.put("deadlineAt", trigger.deadlineAt().toString());
        body.put("errorFingerprint", event.errorFingerprint());
        body.put("repeatedErrorCount", event.repeatedErrorCount());
        body.put("action", response.action());
        body.put("riskLevel", response.riskLevel());
        body.put("repairFingerprint", response.repairFingerprint());
        body.put("receiptId", event.eventId() + ":decision");
        body.put("confidenceScore", (int) Math.round(response.confidence() * 100.0d));
        body.put("evidenceAvailable", evidenceVerified && response.evidenceAvailable());
        // 这些事实只是范围受限的传输投影。data-sync 在认定 RETRY_EXECUTION 已获自动授权前，
        // 仍必须重新读取自身的执行和错误账本。
        body.put("autopilotRecoveryFacts", response.autopilotRecoveryFacts());
        Map<String, Object> data = postEnvelope(
                trigger, DECISION_PATH, body, event.eventId(), new Object[0]);
        return caseView(data);
    }

    /**
     * 使用 data-sync 返回的乐观锁版本，推进 recovery case 的一条生命周期边。
     *
     * <p>输入包含可信触发器、上一步 case 视图、目标 receipt 类型和固定后缀；输出是远端接受迁移后的新视图。
     * 该调用会写入远端状态，并以用户 delegation 加内部令牌再次接受 data-sync 的权限和资源归属检查。
     * {@code expectedVersion} 防止过期调用覆盖新状态，{@code eventId:receiptSuffix} 则把同一事件、同一边的
     * 重复提交交给 data-sync 的持久 receipt 去重。</p>
     *
     * <p>这里不吞掉冲突：缺少 case 标识或版本、服务端拒绝过期状态、HTTP 失败和无效响应都会让调用方获得异常。
     * 执行编排层据此决定是否降级为人工关注，而不是把未记录的副作用误报为成功。</p>
     *
     * @param trigger 已验证的恢复触发器
     * @param recoveryCase 上一步 data-sync case，必须含 {@code caseId} 和 {@code version}
     * @param receiptType 要记录的状态迁移类型，例如 {@code RECOVERY_STARTED}
     * @param receiptSuffix 参与幂等 receiptId 的固定低敏后缀，例如 {@code started} 或 {@code failed}
     * @param attentionReason 需要人工关注时的稳定原因码；不需要时可为空
     * @return 推进后包含新版本的 case 视图
     * @throws IllegalStateException case 缺少乐观锁数据或响应合同无效时抛出
     */
    public AgentAutopilotRecoveryCaseView recordTransition(
            AgentAutopilotVerifiedRecoveryTrigger trigger,
            AgentAutopilotRecoveryCaseView recoveryCase,
            String receiptType,
            String receiptSuffix,
            String attentionReason) {
        return recordTransition(trigger, recoveryCase, receiptType, receiptSuffix, attentionReason,
                trigger == null ? null : trigger.event().currentExecutionId());
    }

    /**
     * 推进 case 并显式记录修复后 execution；checkpoint 恢复创建新 execution 时使用该重载。
     *
     * <p>其余迁移仍通过五参数方法自动沿用原事件 execution。显式值只来自已经通过范围校验的 data-sync
     * 修复回执，不能由模型或任意工具参数提供。</p>
     */
    public AgentAutopilotRecoveryCaseView recordTransition(
            AgentAutopilotVerifiedRecoveryTrigger trigger,
            AgentAutopilotRecoveryCaseView recoveryCase,
            String receiptType,
            String receiptSuffix,
            String attentionReason,
            Long currentExecutionId) {
        if (recoveryCase == null || recoveryCase.caseId() == null || recoveryCase.version() == null) {
            throw invalidDataSyncContract("AUTOPILOT_RECOVERY_CASE_VERSION_MISSING");
        }
        if (currentExecutionId == null || currentExecutionId <= 0) {
            throw invalidDataSyncContract("AUTOPILOT_RECOVERY_EXECUTION_ID_INVALID");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedVersion", recoveryCase.version());
        body.put("receiptId", trigger.event().eventId() + ":" + receiptSuffix);
        body.put("receiptType", receiptType);
        body.put("currentExecutionId", currentExecutionId);
        body.put("cycle", trigger.event().cycle());
        body.put("errorFingerprint", trigger.event().errorFingerprint());
        body.put("repeatedErrorCount", trigger.event().repeatedErrorCount());
        body.put("attentionReason", attentionReason);
        Map<String, Object> data = postEnvelope(
                trigger,
                TRANSITION_PATH,
                body,
                trigger.event().eventId(),
                recoveryCase.caseId());
        return caseView(data);
    }

    /**
     * 应用已经通过 Java 验证和 data-sync 策略批准的精确隔离预览。
     *
     * <p>本方法不开放任意工具、URL、选择器或原因。固定内部请求同时绑定持久 case 版本、原始租户/项目/
     * 任务/执行/循环、当前授权和策略摘要、Java 已验证的预览摘要与样本 ID，以及规范动作指纹。稳定的
     * {@code eventId:quarantine-apply} 回执可在 HTTP 响应丢失时重放已提交结果，但会拒绝同一回执被不同事实复用。</p>
     *
     * <p>{@link #postEnvelope} 同时发送用户/Agent 委托头和内部服务令牌，因此下游在修改错误样本状态前会重新
     * 校验被代理用户的资源范围和服务来源。HTTP 成功本身不作为完成证据；响应必须绑定预期 case 和回执，
     * 调用方还会在启动重试前要求持久状态达到 {@code COMPLETED/APPLIED}。</p>
     *
     * @param trigger 已验证的首次授权和恢复事件范围
     * @param recoveryCase 当前获准执行应用动作的 data-sync case
     * @param response 携带规范修复指纹的规划候选
     * @param preview Java 已验证的预览摘要和精确样本 ID
     * @return 与当前事件和恢复 case 绑定的持久应用回执
     * @throws IllegalStateException 本地输入或下游回执违反固定合同时抛出
     */
    public AgentAutopilotRecoveryQuarantineApplyReceipt applyAutonomousQuarantine(
            AgentAutopilotVerifiedRecoveryTrigger trigger,
            AgentAutopilotRecoveryCaseView recoveryCase,
            AgentAutopilotRecoveryPlanResponse response,
            AgentAutopilotRecoveryQuarantinePreview preview) {
        if (trigger == null || recoveryCase == null || response == null || preview == null
                || recoveryCase.caseId() == null || recoveryCase.version() == null) {
            throw invalidDataSyncContract("AUTOPILOT_QUARANTINE_APPLY_INPUT_INVALID");
        }
        AgentAutopilotRecoveryTriggerEvent event = trigger.event();
        String receiptId = requiredResultText(event.eventId()) + ":quarantine-apply";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedVersion", recoveryCase.version());
        body.put("tenantId", event.tenantId());
        body.put("projectId", event.projectId());
        body.put("syncTaskId", event.syncTaskId());
        body.put("executionId", event.currentExecutionId());
        body.put("cycle", event.cycle());
        body.put("authorizationDigest", recoveryCase.authorizationDigest());
        body.put("policyDigest", recoveryCase.policyDigest());
        body.put("previewDigest", preview.confirmationDigest());
        body.put("selectedSampleIds", preview.selectedSampleIds());
        body.put("actionFingerprint", response.repairFingerprint());
        body.put("receiptId", receiptId);

        Map<String, Object> data = postEnvelope(
                trigger, QUARANTINE_APPLY_PATH, body, event.eventId(), recoveryCase.caseId());
        AgentAutopilotRecoveryQuarantineApplyReceipt receipt =
                new AgentAutopilotRecoveryQuarantineApplyReceipt(
                        requiredText(data, "receiptId"),
                        requiredLong(data, "caseId"),
                        requiredLong(data, "syncTaskId"),
                        requiredLong(data, "executionId"),
                        requiredInt(data, "selectedCount"),
                        requiredInt(data, "affectedCount"),
                        requiredText(data, "operationState"),
                        requiredText(data, "receiptState"),
                        requiredText(data, "previewDigest"),
                        requiredText(data, "actionFingerprint"));
        if (!receiptId.equals(receipt.receiptId())
                || !recoveryCase.caseId().equals(receipt.caseId())
                || !event.syncTaskId().equals(receipt.syncTaskId())
                || !event.currentExecutionId().equals(receipt.executionId())
                || receipt.selectedCount() != preview.selectedSampleIds().size()
                || !preview.confirmationDigest().equals(receipt.previewDigest())
                || !response.repairFingerprint().equals(receipt.actionFingerprint())) {
            throw invalidDataSyncContract("AUTOPILOT_QUARANTINE_APPLY_RECEIPT_SCOPE_MISMATCH");
        }
        return receipt;
    }

    /**
     * 把已经由 Java 复核的修复参数提交到 data-sync 固定受治理执行入口。
     *
     * <p>请求体只包含 case 乐观锁、资源范围、持久授权摘要、动作指纹和白名单参数。checkpoint 内容、
     * 策略快照、失败分片和字段元数据不会跨服务传入，而是由 data-sync 从权威数据库重新读取。稳定的
     * {@code eventId:repair-apply} receipt 使“服务端已提交但响应丢失”的调用可以安全重放。</p>
     *
     * <p>响应必须与事件、case、动作和指纹完全一致。{@code applied=false} 是合法的确定性业务结果，
     * 表示服务端发现缺少 checkpoint、没有安全映射或调参越界；调用方应结束本轮并转人工关注，而不是
     * 把它当作瞬时网络错误无限重试。</p>
     *
     * @param trigger 已验证的事件与双主体上下文
     * @param recoveryCase data-sync 已自动批准的持久 case
     * @param response Python 候选，用于绑定动作和指纹
     * @param verifiedParameters Java 验证器返回的规范参数
     * @return 与当前范围绑定的修复回执
     */
    public AgentAutopilotRecoveryRepairReceipt applyGovernedRepair(
            AgentAutopilotVerifiedRecoveryTrigger trigger,
            AgentAutopilotRecoveryCaseView recoveryCase,
            AgentAutopilotRecoveryPlanResponse response,
            Map<String, Object> verifiedParameters) {
        if (trigger == null || recoveryCase == null || response == null || verifiedParameters == null
                || recoveryCase.caseId() == null || recoveryCase.version() == null) {
            throw invalidDataSyncContract("AUTOPILOT_REPAIR_APPLY_INPUT_INVALID");
        }
        AgentAutopilotRecoveryTriggerEvent event = trigger.event();
        String receiptId = requiredResultText(event.eventId()) + ":repair-apply";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedVersion", recoveryCase.version());
        body.put("tenantId", event.tenantId());
        body.put("projectId", event.projectId());
        body.put("syncTaskId", event.syncTaskId());
        body.put("executionId", event.currentExecutionId());
        body.put("cycle", event.cycle());
        body.put("authorizationDigest", recoveryCase.authorizationDigest());
        body.put("policyDigest", recoveryCase.policyDigest());
        body.put("action", response.action());
        body.put("actionFingerprint", response.repairFingerprint());
        body.put("receiptId", receiptId);
        body.put("repairParameters", verifiedParameters);

        Map<String, Object> data = postEnvelope(
                trigger, REPAIR_APPLY_PATH, body, event.eventId(), recoveryCase.caseId());
        AgentAutopilotRecoveryRepairReceipt receipt = new AgentAutopilotRecoveryRepairReceipt(
                requiredText(data, "receiptId"),
                requiredLong(data, "caseId"),
                requiredLong(data, "syncTaskId"),
                requiredLong(data, "sourceExecutionId"),
                requiredLong(data, "executionId"),
                requiredText(data, "action"),
                requiredBoolean(data, "applied"),
                requiredInt(data, "affectedCount"),
                optionalText(data.get("executionState")),
                optionalText(data.get("taskState")),
                requiredText(data, "reasonCode"),
                optionalCodeList(data.get("issueCodes")),
                requiredText(data, "actionFingerprint"),
                requiredText(data, "caseState"),
                requiredBoolean(data, "replanQueued"),
                optionalText(data.get("replanEventId")),
                optionalInt(data.get("nextCycle")));
        if (!receipt.matchesScope(event, recoveryCase, response)) {
            throw invalidDataSyncContract("AUTOPILOT_REPAIR_APPLY_RECEIPT_SCOPE_MISMATCH");
        }
        return receipt;
    }

    /**
     * 请求 data-sync 在当前 execution 范围内重新排队全部允许重试的失败对象。
     *
     * <p>输入只有可信触发器，输出为 data-sync 的低层响应字段。方法会产生一次远端重试请求，但不会接受任意
     * 对象 ID 或任意 URL；固定路径和 {@code retryAttemptBudget=1} 把本轮副作用限制在事件所属 execution
     * 的 FAILED 对象中。用户 delegation 让 data-sync 再次检查任务与执行权限，内部令牌限制调用来源。</p>
     *
     * <p>请求把稳定 {@code eventId} 同时作为 data-sync 的 {@code idempotencyKey}。如果 data-sync 已经提交
     * 对象重置但 HTTP 响应丢失，Agent Runtime 使用同一事件重试时会回放首次结果，而不会第二次执行控制面
     * 副作用。下游拒绝、网络失败或响应合同错误仍会向上层传播，执行服务只进行有界重试。</p>
     *
     * @param trigger 已验证且绑定当前同步任务与 execution 的触发器
     * @return 已校验并绑定原任务/执行范围的强类型重排队回执
     * @throws IllegalStateException 下游响应缺少可解析 case 数据时抛出
     */
    public AgentAutopilotRecoveryRetryReceipt retryFailedObjects(
            AgentAutopilotVerifiedRecoveryTrigger trigger) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("idempotencyKey", trigger.event().eventId());
        body.put("retryAttemptBudget", 1);
        body.put("resetAttemptCount", true);
        body.put("reason", "AUTOPILOT_PREAUTHORIZED_FAILED_OBJECT_RETRY");
        Map<String, Object> data = postEnvelope(
                trigger,
                RETRY_PATH,
                body,
                trigger.event().eventId(),
                trigger.event().syncTaskId(),
                trigger.event().currentExecutionId());
        AgentAutopilotRecoveryRetryReceipt receipt = new AgentAutopilotRecoveryRetryReceipt(
                requiredLong(data, "taskId"),
                requiredLong(data, "executionId"),
                requiredInt(data, "retryObjectCount"),
                requiredText(data, "executionState"),
                requiredText(data, "taskState"));
        if (!receipt.matchesRequeuedScope(trigger.event())) {
            throw invalidDataSyncContract("AUTOPILOT_RETRY_RECEIPT_SCOPE_OR_STATE_INVALID");
        }
        return receipt;
    }

    /**
     * 向固定 data-sync 路径发送 POST，附加双主体认证头，并解包平台响应的 {@code data} 字段。
     *
     * <p>输入路径仅来自本类常量，URI 变量和请求体也由受控调用方构造，因而该方法不是通用内网 HTTP 代理。
     * 输出为复制后的 {@code data} Map；发起 HTTP 调用是唯一副作用。它同时应用用户 delegation 和内部服务
     * 令牌，分别用于 data-sync 的业务 RBAC/作用域验证和服务间来源验证。</p>
     *
     * <p>{@code traceId} 用于跨服务证据和日志关联，但不单独构成幂等键；需要去重的调用必须在 {@code body}
     * 中传入稳定 receipt。HTTP 非成功状态按 {@link RestClient} 语义传播；空响应或没有 Map 形式
     * {@code data} 的响应转换为技术合同异常，避免下游合同漂移被当作空成功。</p>
     *
     * @param trigger 已验证的身份和授权上下文
     * @param path 受控的 data-sync 相对路径
     * @param body 要发送的低敏请求体
     * @param traceId 用于跨服务关联的事件标识
     * @param uriVariables 固定路径模板所需的 URI 变量
     * @return 下游平台 envelope 中复制出的 {@code data} Map
     * @throws IllegalStateException 响应 envelope 无法安全解包时抛出
     */
    private Map<String, Object> postEnvelope(AgentAutopilotVerifiedRecoveryTrigger trigger,
                                             String path,
                                             Map<String, Object> body,
                                             String traceId,
                                             Object... uriVariables) {
        RestClient client = httpSupport.serviceClient(restClientBuilder, DATA_SYNC);
        Map<String, Object> response = client.post()
                .uri(path, uriVariables)
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    httpSupport.applyUserDelegationHeaders(
                            headers, trigger.session(), trigger.rootRun(), traceId);
                    httpSupport.applyInternalServiceToken(headers);
                })
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
        if (!successfulPlatformEnvelope(response)
                || !(response.get("data") instanceof Map<?, ?> rawData)) {
            throw invalidDataSyncContract("AUTOPILOT_DATA_SYNC_RESPONSE_INVALID");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        rawData.forEach((key, value) -> data.put(String.valueOf(key), value));
        return data;
    }

    /**
     * 将已解包的平台 case 数据转换为强类型的乐观锁视图。
     *
     * <p>输入是 data-sync 的 {@code data} 字段，输出保留执行范围、策略结论、循环信息和版本号。该纯转换
     * 没有权限或网络副作用，却不能放松任何合同字段：缺少或格式错误的字段会终止当前恢复，而不是构造带空值
     * 的 case。版本和 {@code policyDigest} 是后续迁移与审计证据，不能被调用方自行补造。</p>
     *
     * @param data 已由平台 envelope 解包的 case 字段
     * @return 可用于下一次乐观锁迁移的强类型 case 视图
     * @throws IllegalStateException 必填响应字段缺失或格式错误时抛出
     */
    private AgentAutopilotRecoveryCaseView caseView(Map<String, Object> data) {
        return new AgentAutopilotRecoveryCaseView(
                requiredLong(data, "caseId"),
                requiredLong(data, "syncTaskId"),
                requiredLong(data, "rootExecutionId"),
                requiredLong(data, "currentExecutionId"),
                requiredText(data, "state"),
                requiredLong(data, "version"),
                requiredInt(data, "cycle"),
                requiredInt(data, "maxCycles"),
                requiredText(data, "recoveryAction"),
                optionalText(data.get("attentionReason")),
                requiredText(data, "authorizationDigest"),
                requiredText(data, "policyDigest")
        );
    }

    /**
     * 从下游响应读取一个必填的长整数字段。
     *
     * <p>输入 Map 不会被修改，输出为解析后的 {@link Long}。该方法不检查权限也不产生副作用；它只把响应
     * 合同错误统一为稳定原因码，以免不可信或损坏的 case 数据参与幂等版本比较。</p>
     *
     * @param data 下游响应字段
     * @param field 必须存在的字段名
     * @return 解析成功的长整数字段
     * @throws IllegalStateException 响应字段缺失或不是有效整数时抛出
     */
    private Long requiredLong(Map<String, Object> data, String field) {
        try {
            return Long.parseLong(requiredText(data, field));
        } catch (NumberFormatException exception) {
            throw invalidDataSyncContract("AUTOPILOT_DATA_SYNC_NUMERIC_FIELD_INVALID");
        }
    }

    /**
     * 从下游响应读取一个必须落在 Java {@code int} 范围内的整数字段。
     *
     * <p>该纯函数复用 {@link #requiredLong(Map, String)} 的缺失和格式验证，再拒绝溢出的值。循环数等字段
     * 是恢复预算和证据的一部分，宁可将异常合同视为冲突，也不截断后继续执行。</p>
     *
     * @param data 下游响应字段
     * @param field 必须存在的字段名
     * @return 已验证且未溢出的整数
     * @throws IllegalStateException 响应整数缺失、格式错误或超出 Java int 范围时抛出
     */
    private Integer requiredInt(Map<String, Object> data, String field) {
        long value = requiredLong(data, field);
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            throw invalidDataSyncContract("AUTOPILOT_DATA_SYNC_NUMERIC_FIELD_INVALID");
        }
        return (int) value;
    }

    /** 读取可空整数；字段存在但格式错误或溢出时仍按下游合同损坏失败关闭。 */
    private Integer optionalInt(Object value) {
        if (value == null) {
            return null;
        }
        try {
            long parsed = Long.parseLong(String.valueOf(value));
            if (parsed > Integer.MAX_VALUE || parsed < Integer.MIN_VALUE) {
                throw invalidDataSyncContract("AUTOPILOT_DATA_SYNC_NUMERIC_FIELD_INVALID");
            }
            return (int) parsed;
        } catch (NumberFormatException exception) {
            throw invalidDataSyncContract("AUTOPILOT_DATA_SYNC_NUMERIC_FIELD_INVALID");
        }
    }

    /** 读取严格布尔值，避免字符串、数字或缺失字段被误解释为修复成功。 */
    private boolean requiredBoolean(Map<String, Object> data, String field) {
        Object value = data.get(field);
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw invalidDataSyncContract("AUTOPILOT_DATA_SYNC_BOOLEAN_FIELD_INVALID");
    }

    /**
     * 读取可选的低敏原因码列表；任意自由文本或非字符串元素都会使合同失败关闭。
     */
    private List<String> optionalCodeList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> rawList)) {
            throw invalidDataSyncContract("AUTOPILOT_DATA_SYNC_CODE_LIST_INVALID");
        }
        List<String> result = new ArrayList<>();
        for (Object item : rawList) {
            String code = optionalText(item);
            if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,95}")) {
                throw invalidDataSyncContract("AUTOPILOT_DATA_SYNC_CODE_LIST_INVALID");
            }
            result.add(code);
        }
        return List.copyOf(result);
    }

    /**
     * 从下游响应读取一个不能为空白的文本字段。
     *
     * <p>输入只读，输出会去除首尾空白；没有副作用或权限判断。状态、动作和摘要等文本会影响策略和审计，
     * 所以空值不被默认为未知或成功，而是以稳定冲突异常终止流程。</p>
     *
     * @param data 下游响应字段
     * @param field 必须存在的字段名
     * @return 非空的已清理文本
     * @throws IllegalStateException 必填响应文本字段缺失或为空白时抛出
     */
    private String requiredText(Map<String, Object> data, String field) {
        String value = optionalText(data.get(field));
        if (value == null) {
            throw invalidDataSyncContract("AUTOPILOT_DATA_SYNC_FIELD_MISSING");
        }
        return value;
    }

    /**
     * 将可选响应值转换为去空白后的文本，或明确表示缺失。
     *
     * <p>这是无副作用的格式化辅助方法，不授予权限、不验证证据，也不把值写回 Map。相同输入始终得到相同
     * 输出，便于上层用 {@code null} 区分可选字段缺失与必填字段的合同冲突。</p>
     *
     * @param value 原始响应值，可为空
     * @return 清理后的文本；值为空或空白时返回 {@code null}
     */
    private String optionalText(Object value) {
        return value == null || String.valueOf(value).isBlank()
                ? null
                : String.valueOf(value).trim();
    }

    /**
     * 在本地触发结果字段进入固定回调合同前，校验该必填字段。
     *
     * <p>该辅助方法只做单值去空白和非空校验，不产生副作用。event ID、状态或原因码缺失并不是
     * data-sync 给出的业务拒绝，而是本地消费者无法构造约定的内部请求。因此这里抛出的
     * {@link IllegalStateException} 必须继续交给 Kafka 的有界重试流程处理，不能伪装成已完成回调。</p>
     *
     * @param value 待规范化的本地结果字段
     * @return 去除首尾空白后的非空字段值
     * @throws IllegalStateException 回调必填字段缺失时抛出
     */
    private String requiredResultText(String value) {
        String normalized = optionalText(value);
        if (normalized == null) {
            throw invalidDataSyncContract("AUTOPILOT_TRIGGER_RESULT_INVALID");
        }
        return normalized;
    }

    /**
     * 识别平台统一成功响应信封，同时避免把回调成功与可选 {@code data} 载荷绑定。
     *
     * <p>触发结果接口可能用空值或实现特定的 {@code data} 字段确认写入，因此本方法只依据平台统一的
     * {@code code == 0} 规则。通用 Map 反序列化可能得到 JSON 数字或字符串，两种形式都允许；其他形状
     * 代表传输合同错误，而不是 Autopilot 已经作出的终态业务裁决。</p>
     *
     * @param response Spring JSON 转换器解析出的响应信封
     * @return 仅当平台信封明确报告成功时返回 {@code true}
     */
    private boolean successfulPlatformEnvelope(Map<String, Object> response) {
        if (response == null || !response.containsKey("code")) {
            return false;
        }
        Object code = response.get("code");
        if (code instanceof Number number) {
            return number.longValue() == 0L;
        }
        try {
            return Long.parseLong(String.valueOf(code).trim()) == 0L;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * 为损坏的 data-sync 集成合同创建低敏技术异常。
     *
     * <p>异常只包含稳定原因码，不嵌入响应正文、URL、令牌或远端消息。格式错误的信封或不可能成立的
     * 本地请求属于技术失败，不能被确认成永久业务拒绝，因此统一使用 {@link IllegalStateException}。</p>
     *
     * @param reasonCode 用于日志、指标和重试诊断的稳定原因码
     * @return 当前回调或响应合同对应的技术异常
     */
    private IllegalStateException invalidDataSyncContract(String reasonCode) {
        return new IllegalStateException(reasonCode);
    }
}

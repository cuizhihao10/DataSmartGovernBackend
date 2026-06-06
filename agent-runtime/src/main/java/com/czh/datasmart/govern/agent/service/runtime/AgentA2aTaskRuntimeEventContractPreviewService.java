/**
 * @Author : Cui
 * @Date: 2026/06/06 12:55
 * @Description DataSmart Govern Backend - AgentA2aTaskRuntimeEventContractPreviewService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentA2aTaskEventDeliveryChannelView;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aTaskEventPayloadFieldView;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aTaskRuntimeEventContractPreviewResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aTaskRuntimeEventContractView;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * A2A Task runtime event 契约只读预览服务。
 *
 * <p>5.28 的状态机回答“task 可以处于哪些状态、如何流转”；本服务回答“这些状态变化应该如何成为可回放事件”。
 * 对商业化 Agent 平台来说，这两个问题必须分开：状态机是业务生命周期，事件契约是可观测、可审计、可恢复的事实源。
 * 如果没有事件契约，后续 A2A streaming、push notification、任务查询、指标告警和审计台会各自拼接字段，最终形成
 * 很难维护的多套 truth source。</p>
 *
 * <p>当前服务仍然是 preview-only。它不会发布 `agent.a2a_task.*` 真实事件，也不会写 runtime projection。
 * 这样做有一点“慢”，但很稳：先把低敏字段、终态、幂等、排序、回放、stream/push 和审计边界写清楚，再接真实
 * `message:send`、`tasks/get`、`tasks/cancel`，可以显著降低重构成本。</p>
 */
@Service
public class AgentA2aTaskRuntimeEventContractPreviewService {

    private static final String RESPONSE_SCHEMA_VERSION =
            "datasmart.agent-runtime.a2a-task-runtime-event-contract-preview.v1";
    private static final String EVENT_SCHEMA_VERSION =
            "datasmart.agent-runtime.a2a-task-runtime-event.v1";
    private static final String PROTOCOL_VERSION = "1.0";
    private static final String PAYLOAD_POLICY =
            "SUMMARY_ONLY_NO_RAW_MESSAGE_NO_TOOL_INPUT_BODY_NO_ARTIFACT_BODY_NO_MODEL_RESULT_NO_CREDENTIAL";
    private static final String FORBIDDEN_PAYLOAD_SUMMARY =
            "禁止保存原始消息正文、工具输入正文、资源正文、模型输出正文、查询语句、样例数据、凭证和内部端点。";

    /**
     * 构建 A2A Task runtime event 契约预览。
     *
     * <p>此方法没有读取 5.28 状态机服务，是一个刻意的轻量解耦：状态机和事件契约都属于协议控制面草案，
     * 但它们服务的消费方不同。未来如果状态机进入持久化配置或枚举，本服务可以再通过 mapper 读取同一份定义；
     * 当前先用静态契约保证学习可读性和测试可控性。</p>
     *
     * @return A2A Task 事件契约、字段白名单、投递通道和回放策略
     */
    public AgentA2aTaskRuntimeEventContractPreviewResponse buildPreview() {
        return new AgentA2aTaskRuntimeEventContractPreviewResponse(
                RESPONSE_SCHEMA_VERSION,
                Instant.now(),
                "A2A",
                PROTOCOL_VERSION,
                true,
                false,
                false,
                PAYLOAD_POLICY,
                buildContracts(),
                buildPayloadFields(),
                buildDeliveryChannels(),
                buildOrderingAndReplayPolicy(),
                buildPersistencePolicy(),
                buildNextSteps()
        );
    }

    private List<AgentA2aTaskRuntimeEventContractView> buildContracts() {
        return List.of(
                contract("agent.a2a_task.submitted", "a2a_task_submitted", "TASK_STATE_SUBMITTED",
                        "POLICY_PRECHECK", "message.send.accepted", false, "TASK_STATUS_UPDATE", true,
                        "AUDIT_FACT",
                        List.of("eventId", "tenantIdHash", "workspaceIdHash", "taskPublicId", "contextPublicId",
                                "a2aState", "previousA2aState", "internalPhase", "sequence", "idempotencyKeyHash",
                                "actorRefHash", "requestSource", "policyVersion", "createdAt"),
                        List.of("网关和管理台可用该事件确认外部委派已被接收。",
                                "该事件不代表任务已经进入 worker，也不代表工具可以执行。")),
                contract("agent.a2a_task.rejected", "a2a_task_rejected", "TASK_STATE_REJECTED",
                        "POLICY_PRECHECK", "policy.rejected", true, "TASK_STATUS_UPDATE", true,
                        "AUDIT_FACT",
                        List.of("eventId", "taskPublicId", "contextPublicId", "a2aState", "previousA2aState",
                                "internalPhase", "sequence", "reasonCode", "policyVersion", "terminal"),
                        List.of("用于解释权限、租户边界、Skill readiness、风险策略或能力不支持导致的拒绝。",
                                "拒绝是终态，外部 Agent 修正后必须提交新任务。")),
                contract("agent.a2a_task.working", "a2a_task_working", "TASK_STATE_WORKING",
                        "WORKER_PRECHECK", "worker.precheck.accepted", false, "TASK_STATUS_UPDATE", true,
                        "HOT_TIMELINE",
                        List.of("eventId", "taskPublicId", "contextPublicId", "a2aState", "previousA2aState",
                                "internalPhase", "sequence", "workerRefHash", "backlogBucket", "startedAt"),
                        List.of("用于 stream/push 向客户端说明任务已经进入受控执行链路。",
                                "该事件只描述阶段，不暴露 worker 真实路径、工具参数或模型上下文。")),
                contract("agent.a2a_task.input_required", "a2a_task_input_required", "TASK_STATE_INPUT_REQUIRED",
                        "INPUT_WAITING", "agent.requires.input", false, "TASK_STATUS_UPDATE", true,
                        "AUDIT_FACT",
                        List.of("eventId", "taskPublicId", "contextPublicId", "a2aState", "previousA2aState",
                                "internalPhase", "sequence", "requiredInputSummary", "deadlineAt", "reasonCode"),
                        List.of("前端可展示需要补充的信息类别，但不能回显原始用户消息或敏感字段值。",
                                "客户端补充输入后必须重新经过 schema、权限和租户边界校验。")),
                contract("agent.a2a_task.auth_required", "a2a_task_auth_required", "TASK_STATE_AUTH_REQUIRED",
                        "APPROVAL_WAITING", "agent.requires.authorization", false, "TASK_STATUS_UPDATE", true,
                        "AUDIT_FACT",
                        List.of("eventId", "taskPublicId", "contextPublicId", "a2aState", "previousA2aState",
                                "internalPhase", "sequence", "authorizationType", "approvalRefHash", "deadlineAt",
                                "policyVersion"),
                        List.of("该事件表示授权或审批缺口，不表示允许通过 A2A 事件交换凭证。",
                                "审批通过或拒绝应继续写后续状态事件，形成完整审计链。")),
                contract("agent.a2a_task.cancel_requested", "a2a_task_cancel_requested", "TASK_STATE_WORKING",
                        "RUNNING", "client.cancel.requested", false, "TASK_STATUS_UPDATE", true,
                        "AUDIT_FACT",
                        List.of("eventId", "taskPublicId", "contextPublicId", "a2aState", "internalPhase",
                                "sequence", "cancelRequestIdHash", "cancelSafety", "requestedByHash"),
                        List.of("取消请求不是 canceled 终态，必须等待 worker receipt 或补偿确认。",
                                "重复取消请求应按 cancelRequestIdHash 幂等处理。")),
                contract("agent.a2a_task.canceled", "a2a_task_canceled", "TASK_STATE_CANCELED",
                        "RESULT_READY", "worker.cancel.confirmed", true, "TASK_STATUS_UPDATE", true,
                        "AUDIT_FACT",
                        List.of("eventId", "taskPublicId", "contextPublicId", "a2aState", "previousA2aState",
                                "internalPhase", "sequence", "reasonCode", "terminal", "completedAt"),
                        List.of("只有确认未产生不可逆副作用，或补偿完成后，才能写入该终态事件。",
                                "终态事件之后不得再写 working/input/auth 类事件。")),
                contract("agent.a2a_task.artifact_announced", "a2a_task_artifact_announced", "TASK_STATE_WORKING",
                        "RESULT_READY", "artifact.metadata.ready", false, "TASK_ARTIFACT_UPDATE", true,
                        "HOT_TIMELINE",
                        List.of("eventId", "taskPublicId", "contextPublicId", "a2aState", "internalPhase",
                                "sequence", "artifactRef", "artifactType", "append", "lastChunk"),
                        List.of("该事件只发布 artifact 引用、类型和分块标记，不发布 artifact 正文。",
                                "streaming 和 push 都可以把该事件映射为 artifact update。")),
                contract("agent.a2a_task.completed", "a2a_task_completed", "TASK_STATE_COMPLETED",
                        "RESULT_READY", "worker.result.completed", true, "TASK_STATUS_UPDATE", true,
                        "AUDIT_FACT",
                        List.of("eventId", "taskPublicId", "contextPublicId", "a2aState", "previousA2aState",
                                "internalPhase", "sequence", "artifactCount", "terminal", "completedAt"),
                        List.of("完成事件只说明任务终态和 artifact 数量，不复制模型输出或工具响应。",
                                "完成后如需再次执行，必须创建新任务或管理员补偿记录。")),
                contract("agent.a2a_task.failed", "a2a_task_failed", "TASK_STATE_FAILED",
                        "DEAD_LETTER", "worker.terminal.failure", true, "TASK_STATUS_UPDATE", true,
                        "AUDIT_FACT",
                        List.of("eventId", "taskPublicId", "contextPublicId", "a2aState", "previousA2aState",
                                "internalPhase", "sequence", "reasonCode", "attemptCount", "terminal"),
                        List.of("失败事件记录错误类别、阶段和尝试次数，不记录完整堆栈或下游响应正文。",
                                "失败后如需重跑，应产生新的 task 或进入运营补偿流程。")),
                contract("agent.a2a_task.push_delivery_attempted", "a2a_task_push_delivery_attempted",
                        "TASK_STATE_WORKING", "RUNNING", "push.delivery.attempted", false,
                        "PUSH_DELIVERY_AUDIT", false, "METRIC_ONLY",
                        List.of("eventId", "taskPublicId", "contextPublicId", "a2aState", "sequence",
                                "pushConfigRefHash", "deliveryOutcome", "attemptCount"),
                        List.of("该事件服务运维和告警，不向外部 webhook 原样投递。",
                                "真实 push payload 应复用低敏 stream response 摘要。"))
        );
    }

    private List<AgentA2aTaskEventPayloadFieldView> buildPayloadFields() {
        return List.of(
                field("eventId", "string", "LOW_SENSITIVE", true,
                        "事件唯一标识，用于幂等写入和重复投递去重。",
                        List.of("runtime-projection", "audit-store", "operator-console"),
                        List.of("a2a-task-event:{task}:{sequence}")),
                field("taskPublicId", "string", "PUBLIC_PROTOCOL", true,
                        "A2A 对外 task id。生产环境应使用不可枚举的随机 ID。",
                        List.of("a2a-stream", "a2a-push", "task-query", "operator-console"),
                        List.of("task_opaque_001")),
                field("contextPublicId", "string", "PUBLIC_PROTOCOL", true,
                        "A2A context id，用于把多轮任务和消息关联在同一上下文。",
                        List.of("a2a-stream", "a2a-push", "task-query"),
                        List.of("ctx_opaque_001")),
                field("a2aState", "string", "PUBLIC_PROTOCOL", true,
                        "A2A 标准 TaskState。",
                        List.of("a2a-stream", "a2a-push", "task-query", "runtime-projection"),
                        List.of("TASK_STATE_WORKING", "TASK_STATE_COMPLETED")),
                field("previousA2aState", "string", "LOW_SENSITIVE", false,
                        "上一 A2A 状态，用于前端 timeline 和审计回放解释状态变化。",
                        List.of("runtime-projection", "operator-console"),
                        List.of("TASK_STATE_SUBMITTED")),
                field("internalPhase", "string", "INTERNAL_SUMMARY", true,
                        "DataSmart 内部治理阶段，例如 POLICY_PRECHECK、WORKER_PRECHECK、RUNNING。",
                        List.of("runtime-projection", "operator-console", "metrics"),
                        List.of("WORKER_PRECHECK")),
                field("sequence", "integer", "LOW_SENSITIVE", true,
                        "同一 task 内单调递增序号，用于排序、断线恢复和重复事件判断。",
                        List.of("runtime-projection", "a2a-stream", "task-query", "audit-store"),
                        List.of("1", "2")),
                field("idempotencyKeyHash", "string", "HASH_ONLY", false,
                        "提交或继续操作的幂等键摘要，禁止保存原始键值。",
                        List.of("audit-store", "operator-console"),
                        List.of("sha256:...")),
                field("actorRefHash", "string", "HASH_ONLY", false,
                        "发起方摘要，用于审计但不暴露真实账号标识。",
                        List.of("audit-store", "operator-console"),
                        List.of("actor-hash")),
                field("reasonCode", "string", "LOW_SENSITIVE", false,
                        "拒绝、失败、取消、等待输入或授权的原因类别。",
                        List.of("runtime-projection", "a2a-stream", "a2a-push", "metrics"),
                        List.of("POLICY_REJECTED", "APPROVAL_TIMEOUT")),
                field("artifactRef", "string", "LOW_SENSITIVE", false,
                        "artifact 引用或受控下载句柄，不包含正文。",
                        List.of("a2a-stream", "a2a-push", "task-query"),
                        List.of("artifact-ref-001")),
                field("terminal", "boolean", "PUBLIC_PROTOCOL", false,
                        "是否终态事件。终态后禁止继续写 working 类事件。",
                        List.of("a2a-stream", "a2a-push", "runtime-projection"),
                        List.of("true", "false"))
        );
    }

    private List<AgentA2aTaskEventDeliveryChannelView> buildDeliveryChannels() {
        return List.of(
                channel("RUNTIME_EVENT_PROJECTION", "INTERNAL_PROJECTION", true, "at-least-once", true,
                        "DataSmart 低敏事件对象",
                        List.of("只保存字段白名单。", "按租户、workspace、taskPublicId 做查询隔离。"),
                        List.of("需要投影幂等。", "需要按 eventType、state、phase 暴露低基数指标。")),
                channel("A2A_STREAM_STATUS", "A2A_STREAM", false, "best-effort-realtime", true,
                        "TaskStatusUpdateEvent 摘要",
                        List.of("断线后不能依赖 stream 消息完整性，必须允许 tasks/get 恢复。"),
                        List.of("需要心跳、断线重连、sequence 游标和超时关闭。")),
                channel("A2A_STREAM_ARTIFACT", "A2A_STREAM", false, "best-effort-realtime", true,
                        "TaskArtifactUpdateEvent 引用",
                        List.of("只发送 artifact metadata/ref，不发送 artifact 正文。"),
                        List.of("需要 append/lastChunk 语义和 artifact 引用有效期治理。")),
                channel("A2A_PUSH_WEBHOOK", "A2A_PUSH", false, "at-least-once", false,
                        "低敏 StreamResponse 摘要",
                        List.of("webhook 必须启用 HTTPS、签名或授权头、重放保护。"),
                        List.of("需要指数退避、最大失败次数、发送超时和失败告警。")),
                channel("TASK_HISTORY_QUERY", "AUDIT", false, "query-only", true,
                        "task status history 与 artifact metadata",
                        List.of("任务历史可查询但不能回显未脱敏正文。"),
                        List.of("需要分页、保留期、归档和租户级访问控制。")),
                channel("TASK_EVENT_METRICS", "METRICS", false, "aggregate-only", false,
                        "低基数计数和延迟桶",
                        List.of("指标标签禁止使用 taskId、actorId、traceId 或租户真实 ID。"),
                        List.of("建议标签只保留 state、phase、eventType、outcome、channel。"))
        );
    }

    private List<String> buildOrderingAndReplayPolicy() {
        return List.of(
                "同一 task 内事件必须携带单调递增 sequence，前端和审计台按 sequence 排序，不能只依赖写入时间。",
                "事件 eventId 必须可幂等生成或持久化保存，Kafka 重放、push 重试、stream 重连都不能产生重复副作用。",
                "终态事件写入后，除管理员补偿审计外，不允许再写 working、input-required 或 auth-required 状态事件。",
                "streaming 是实时体验，不是可靠历史；断线恢复必须通过 task history 或 runtime projection 查询。",
                "push notification 至少尝试一次，客户端必须按 taskPublicId、sequence 和 eventId 幂等处理重复通知。"
        );
    }

    private List<String> buildPersistencePolicy() {
        return List.of(
                "runtime projection 只保存低敏热窗口，服务前端 timeline 和运维诊断。",
                "长期 task fact 表保存 task header、状态历史、终态、sequence 和策略版本，支撑跨重启恢复。",
                "command outbox 保存受控执行命令和 worker receipt，不与 A2A stream/push payload 混用。",
                "artifact metadata 与 artifact 正文分层：事件只保存引用和类型，正文进入受权限控制的文件或业务存储。",
                "指标只聚合 eventType、状态、阶段、通道和结果，不使用高基数业务 ID。"
        );
    }

    private List<String> buildNextSteps() {
        return List.of(
                "下一步可以实现只读 A2A task preview/query 草案，用 mock task fact 展示状态历史如何消费这些事件。",
                "真实 `message:send` 前必须先接 permission-admin、task-management、confirmation/outbox、worker pre-check、限流和幂等。",
                "A2A push/stream 建议先做内部管理端灰度，再开放给可信外部 Agent。",
                "MCP tools/call 与 A2A task 应复用同一 durable action event contract，避免两套副作用事件。"
        );
    }

    private AgentA2aTaskRuntimeEventContractView contract(String eventType,
                                                          String stage,
                                                          String a2aState,
                                                          String internalPhase,
                                                          String trigger,
                                                          boolean terminalEvent,
                                                          String streamEventKind,
                                                          boolean pushDeliveryEligible,
                                                          String retentionClass,
                                                          List<String> payloadFieldNames,
                                                          List<String> consumerNotes) {
        return new AgentA2aTaskRuntimeEventContractView(
                eventType,
                EVENT_SCHEMA_VERSION,
                stage,
                a2aState,
                internalPhase,
                trigger,
                terminalEvent,
                streamEventKind,
                pushDeliveryEligible,
                retentionClass,
                payloadFieldNames,
                FORBIDDEN_PAYLOAD_SUMMARY,
                consumerNotes
        );
    }

    private AgentA2aTaskEventPayloadFieldView field(String fieldName,
                                                    String valueType,
                                                    String sensitivity,
                                                    boolean required,
                                                    String description,
                                                    List<String> allowedConsumers,
                                                    List<String> examples) {
        return new AgentA2aTaskEventPayloadFieldView(
                fieldName,
                valueType,
                sensitivity,
                required,
                description,
                allowedConsumers,
                examples
        );
    }

    private AgentA2aTaskEventDeliveryChannelView channel(String channelCode,
                                                         String channelType,
                                                         boolean enabled,
                                                         String deliverySemantics,
                                                         boolean replaySupported,
                                                         String payloadShape,
                                                         List<String> securityNotes,
                                                         List<String> operationalNotes) {
        return new AgentA2aTaskEventDeliveryChannelView(
                channelCode,
                channelType,
                enabled,
                deliverySemantics,
                replaySupported,
                payloadShape,
                securityNotes,
                operationalNotes
        );
    }
}

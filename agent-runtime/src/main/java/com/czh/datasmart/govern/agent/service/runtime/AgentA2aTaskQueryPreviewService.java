/**
 * @Author : Cui
 * @Date: 2026/06/06 13:03
 * @Description DataSmart Govern Backend - AgentA2aTaskQueryPreviewService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentA2aTaskArtifactReferenceView;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aTaskHistoryEventView;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aTaskPreviewView;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aTaskPushPreviewView;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aTaskQueryContractView;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aTaskQueryPreviewResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aTaskStreamReplayView;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * A2A Task 查询预览服务。
 *
 * <p>本服务承接 5.29 的事件契约，用 mock task fact 演示未来只读任务查询该如何从事件历史恢复当前状态。
 * 它不是 `tasks/get` 的真实实现，不读取数据库、不接受真实 task id、不执行权限查询、不触发 worker。之所以先做预览，
 * 是为了把查询响应形态、historyLength 行为、artifact 引用、stream replay cursor、push 边界和错误映射提前固定下来。</p>
 *
 * <p>与真实实现的关系：
 * 真实 A2A `GetTask` 应从 task fact 表、runtime event history、artifact metadata、push config 和权限系统聚合；
 * 当前 preview 只用静态场景模拟这些事实。这样我们可以先让前端、网关、Python Runtime 和审计台理解未来契约，
 * 再决定 task fact 落 MySQL、热状态进 Redis、历史进入 ClickHouse/OpenSearch 还是沿用现有 runtime projection。</p>
 */
@Service
public class AgentA2aTaskQueryPreviewService {

    private static final String SCHEMA_VERSION = "datasmart.agent-runtime.a2a-task-query-preview.v1";
    private static final String PROTOCOL_VERSION = "1.0";
    private static final String PAYLOAD_POLICY =
            "SUMMARY_ONLY_NO_RAW_MESSAGE_NO_TOOL_INPUT_BODY_NO_ARTIFACT_BODY_NO_MODEL_RESULT_NO_CREDENTIAL";
    private static final Instant BASE_TIME = Instant.parse("2026-06-06T05:00:00Z");
    private static final int DEFAULT_HISTORY_LENGTH = 20;
    private static final int MAX_HISTORY_LENGTH = 50;

    /**
     * 构建 A2A task 查询预览。
     *
     * <p>`scenario` 用来选择静态场景，而不是查真实 task。当前支持：
     * completed、working、input-required、auth-required、failed、canceled。未识别场景会回退到 completed。
     * `historyLength` 模拟 A2A 查询中的 history 长度限制，服务端会限制最大值，防止真实实现被误用成无界历史导出。</p>
     *
     * @param scenario 预览场景
     * @param historyLength 希望返回的最近历史条数；为空时使用默认值
     * @return A2A task 查询预览响应
     */
    public AgentA2aTaskQueryPreviewResponse buildPreview(String scenario, Integer historyLength) {
        String normalizedScenario = normalizeScenario(scenario);
        int effectiveHistoryLength = normalizeHistoryLength(historyLength);
        List<AgentA2aTaskHistoryEventView> fullHistory = buildHistory(normalizedScenario);
        List<AgentA2aTaskHistoryEventView> visibleHistory = tail(fullHistory, effectiveHistoryLength);
        AgentA2aTaskHistoryEventView latest = fullHistory.getLast();
        List<AgentA2aTaskArtifactReferenceView> artifacts = buildArtifacts(normalizedScenario);

        return new AgentA2aTaskQueryPreviewResponse(
                SCHEMA_VERSION,
                Instant.now(),
                "A2A",
                PROTOCOL_VERSION,
                true,
                false,
                normalizedScenario,
                PAYLOAD_POLICY,
                buildQueryContract(),
                buildTask(normalizedScenario, latest, artifacts),
                visibleHistory,
                artifacts,
                buildStreamReplay(latest.sequence(), visibleHistory.size(), normalizedScenario),
                buildPushPreview(normalizedScenario, latest.terminal()),
                buildMissingProductionRequirements(),
                buildNextSteps()
        );
    }

    private AgentA2aTaskQueryContractView buildQueryContract() {
        return new AgentA2aTaskQueryContractView(
                "/agent-runtime/protocol-adapters/a2a/task-query-preview",
                "GetTask/ListTasks/SubscribeToTask preview",
                "GET",
                List.of(
                        "scenario：选择 mock task 生命周期场景，用于验证前端和网关展示。",
                        "historyLength：模拟 A2A historyLength，控制返回最近多少条低敏历史事件。"
                ),
                "返回 task 低敏快照、状态历史、artifact 引用、stream replay 游标和 push 边界说明。",
                List.of(
                        "真实查询必须校验 gateway 身份、tenant、project、workspace、task 可见性和角色权限。",
                        "服务账号只能读取自己提交或被授权的 task；管理员读取跨项目 task 需要审计。",
                        "artifact 引用读取需要二次鉴权，不能因为 task 可见就直接读取正文。"
                ),
                List.of(
                        "historyLength 只能限制低敏历史事件数量，不能导出消息正文或 artifact 正文。",
                        "sequence 是断线恢复和 stream replay 的排序依据，不应只依赖写入时间。",
                        "终态 task 不支持 SubscribeToTask 实时订阅，但可以通过查询读取历史。"
                ),
                List.of(
                        "TASK_NOT_FOUND -> 404 或 A2A not found error。",
                        "TASK_FORBIDDEN -> 403 或 A2A permission error。",
                        "HISTORY_TOO_LARGE -> 400 并提示分页或降低 historyLength。",
                        "TASK_ARCHIVED -> 410 或归档查询入口。"
                ),
                List.of(
                        "当前接口只读预览，不接受真实 task id。",
                        "当前不读取 task-management、MySQL、Redis、Kafka、artifact store 或 push config。",
                        "当前不会创建、取消、恢复、订阅或推送任何真实任务。"
                )
        );
    }

    private AgentA2aTaskPreviewView buildTask(String scenario,
                                              AgentA2aTaskHistoryEventView latest,
                                              List<AgentA2aTaskArtifactReferenceView> artifacts) {
        boolean terminal = latest.terminal();
        boolean interrupted = "TASK_STATE_INPUT_REQUIRED".equals(latest.a2aState())
                || "TASK_STATE_AUTH_REQUIRED".equals(latest.a2aState());
        /*
         * cancelRequested 表示“取消请求已经进入系统，但还没被 worker receipt 或补偿结果确认”。
         * 一旦状态已经是 TASK_STATE_CANCELED，任务就进入了终态，不能再继续把它标成“仍在等待取消确认”。
         */
        boolean cancelRequested = !terminal
                && "canceled".equals(scenario)
                && "TASK_STATE_WORKING".equals(latest.a2aState());
        return new AgentA2aTaskPreviewView(
                "task_preview_" + scenario.replace("-", "_"),
                "ctx_preview_governance",
                "OPAQUE_TENANT_ROUTING_PREVIEW",
                latest.a2aState(),
                latest.internalPhase(),
                terminal,
                interrupted,
                cancelRequested,
                latest.sequence(),
                "a2a-task-lifecycle-preview-v1",
                BASE_TIME,
                latest.occurredAt(),
                terminal ? latest.occurredAt() : null,
                statusSummary(scenario, latest.a2aState(), artifacts.size()),
                latest.reasonCode(),
                allowedOperations(latest.a2aState(), terminal),
                List.of(
                        "查询结果来自低敏 task fact 与 runtime event history 聚合。",
                        "真实实现必须重新校验租户、项目、workspace、角色和 artifact 访问权限。",
                        "当前 preview 不代表 task endpoint、streaming 或 push notification 已启用。"
                )
        );
    }

    private List<AgentA2aTaskHistoryEventView> buildHistory(String scenario) {
        List<AgentA2aTaskHistoryEventView> base = List.of(
                event(1, "agent.a2a_task.submitted", "TASK_STATE_SUBMITTED", "POLICY_PRECHECK",
                        "TASK_STATUS_UPDATE", false, "ACCEPTED_FOR_PRECHECK", null,
                        "任务委派已被接收，等待权限、幂等和容量预检。"),
                event(2, "agent.a2a_task.working", "TASK_STATE_WORKING", "WORKER_PRECHECK",
                        "TASK_STATUS_UPDATE", false, "WORKER_PRECHECK_ACCEPTED", null,
                        "任务进入 worker pre-check，尚未承诺外部副作用。")
        );
        return switch (scenario) {
            case "working" -> base;
            case "input-required" -> append(base,
                    event(3, "agent.a2a_task.input_required", "TASK_STATE_INPUT_REQUIRED", "INPUT_WAITING",
                            "TASK_STATUS_UPDATE", false, "REQUIRED_FIELD_MISSING", null,
                            "任务等待用户补充非敏感字段类别。"));
            case "auth-required" -> append(base,
                    event(3, "agent.a2a_task.auth_required", "TASK_STATE_AUTH_REQUIRED", "APPROVAL_WAITING",
                            "TASK_STATUS_UPDATE", false, "APPROVAL_REQUIRED", null,
                            "任务等待审批或额外授权。"));
            case "failed" -> append(base,
                    event(3, "agent.a2a_task.failed", "TASK_STATE_FAILED", "DEAD_LETTER",
                            "TASK_STATUS_UPDATE", true, "WORKER_RETRY_EXHAUSTED", null,
                            "任务重试耗尽，进入失败终态。"));
            case "canceled" -> append(base,
                    event(3, "agent.a2a_task.cancel_requested", "TASK_STATE_WORKING", "RUNNING",
                            "TASK_STATUS_UPDATE", false, "CLIENT_CANCEL_REQUESTED", null,
                            "客户端请求取消，等待 worker 确认安全边界。"),
                    event(4, "agent.a2a_task.canceled", "TASK_STATE_CANCELED", "RESULT_READY",
                            "TASK_STATUS_UPDATE", true, "CANCEL_CONFIRMED", null,
                            "worker 确认可取消，任务进入取消终态。"));
            default -> append(base,
                    event(3, "agent.a2a_task.artifact_announced", "TASK_STATE_WORKING", "RESULT_READY",
                            "TASK_ARTIFACT_UPDATE", false, "ARTIFACT_METADATA_READY", "artifact_preview_summary",
                            "任务生成 artifact 引用，但不返回正文。"),
                    event(4, "agent.a2a_task.completed", "TASK_STATE_COMPLETED", "RESULT_READY",
                            "TASK_STATUS_UPDATE", true, "COMPLETED", null,
                            "任务完成，结果通过受控 artifact 引用读取。"));
        };
    }

    private List<AgentA2aTaskArtifactReferenceView> buildArtifacts(String scenario) {
        if (!"completed".equals(scenario)) {
            return List.of();
        }
        return List.of(new AgentA2aTaskArtifactReferenceView(
                "artifact_preview_summary",
                "governance-result-summary",
                "metadata-reference-only",
                true,
                true,
                BASE_TIME.plusSeconds(90),
                List.of(
                        "读取 artifact 正文必须经过 task 可见性和 artifact 二次鉴权。",
                        "外部 Agent 只能拿到受控引用，不能看到内部存储路径。"
                ),
                List.of(
                        "热数据保留 30 天，归档数据进入低频存储。",
                        "删除或导出需要审计，并遵守租户保留策略。"
                ),
                "低敏治理结果摘要引用，正文不在 task 查询响应中返回。",
                List.of(3L)
        ));
    }

    private AgentA2aTaskStreamReplayView buildStreamReplay(long latestSequence,
                                                           int historyLengthApplied,
                                                           String scenario) {
        boolean terminal = List.of("completed", "failed", "canceled").contains(scenario);
        return new AgentA2aTaskStreamReplayView(
                true,
                "after-sequence:" + latestSequence,
                historyLengthApplied,
                List.of(
                        "TASK_STATUS_UPDATE 用于状态变化。",
                        "TASK_ARTIFACT_UPDATE 只承载 artifact metadata/ref。",
                        "streaming 是实时体验，不替代 task history。"
                ),
                List.of(
                        "客户端断线后使用 sequence cursor 查询缺失事件。",
                        "终态 task 不再支持实时 SubscribeToTask，但可以查询历史。",
                        "重复事件按 eventId 和 sequence 幂等处理。"
                ),
                terminal
                        ? List.of("终态 task 不支持继续订阅实时更新。")
                        : List.of("真实 SubscribeToTask 尚未启用。")
        );
    }

    private AgentA2aTaskPushPreviewView buildPushPreview(String scenario, boolean terminal) {
        return new AgentA2aTaskPushPreviewView(
                false,
                false,
                terminal || "input-required".equals(scenario) || "auth-required".equals(scenario),
                "只允许发送 task id、context id、状态、sequence、原因类别和 artifact 引用。",
                "PREVIEW_ONLY_NOT_DELIVERED",
                List.of(
                        "webhook 必须使用 HTTPS。",
                        "通知必须签名并带重放保护。",
                        "push 配置需要可查询、可删除、可审计。"
                ),
                List.of(
                        "至少一次投递，客户端按 eventId 和 sequence 幂等。",
                        "失败采用指数退避并限制最大尝试次数。",
                        "连续失败后进入运维告警，不阻塞 task 终态。"
                ),
                List.of(
                        "当前仅展示 push 查询和投递边界，不保存真实 webhook 配置。",
                        "push payload 与 stream response 共享低敏事件契约。"
                )
        );
    }

    private List<String> buildMissingProductionRequirements() {
        return List.of(
                "需要 task-management 或 agent-runtime task fact 表承载 task header、当前状态和状态版本。",
                "需要 runtime event history 或 dedicated task history index 支撑 sequence 查询和断线恢复。",
                "需要 artifact metadata store 与 artifact 正文存储分层，并做二次鉴权。",
                "需要 Redis 或本地 projection 支撑高频状态查询，同时确保长期事实可恢复。",
                "需要 gateway、permission-admin、租户配额、限流和审计策略接入真实查询链路。"
        );
    }

    private List<String> buildNextSteps() {
        return List.of(
                "下一步不建议继续堆 Java 协议预览字段，可以转向 Python Runtime 编排或长期记忆/模型网关真实遥测。",
                "若继续 A2A 主线，应先设计 task fact 表与 task-management 对接方式，再实现真实 GetTask。",
                "真实 message send 前必须接 permission-admin、confirmation/outbox、worker pre-check、限流和幂等。",
                "MCP tools/call 与 A2A task 应共用 durable action fact 和 event contract。"
        );
    }

    private AgentA2aTaskHistoryEventView event(long sequence,
                                               String eventType,
                                               String a2aState,
                                               String internalPhase,
                                               String streamEventKind,
                                               boolean terminal,
                                               String reasonCode,
                                               String artifactRef,
                                               String summary) {
        return new AgentA2aTaskHistoryEventView(
                sequence,
                eventType,
                a2aState,
                internalPhase,
                streamEventKind,
                BASE_TIME.plusSeconds(sequence * 30),
                terminal,
                reasonCode,
                artifactRef,
                summary,
                "按 taskPublicId + sequence 排序回放；重复事件按 eventId 幂等处理。"
        );
    }

    private List<AgentA2aTaskHistoryEventView> append(List<AgentA2aTaskHistoryEventView> base,
                                                      AgentA2aTaskHistoryEventView... tail) {
        java.util.ArrayList<AgentA2aTaskHistoryEventView> events = new java.util.ArrayList<>(base);
        events.addAll(List.of(tail));
        return List.copyOf(events);
    }

    private List<AgentA2aTaskHistoryEventView> tail(List<AgentA2aTaskHistoryEventView> events, int historyLength) {
        int fromIndex = Math.max(0, events.size() - historyLength);
        return events.subList(fromIndex, events.size());
    }

    private List<String> allowedOperations(String state, boolean terminal) {
        if (terminal) {
            return List.of("get", "list-history", "download-authorized-artifact");
        }
        if ("TASK_STATE_INPUT_REQUIRED".equals(state)) {
            return List.of("get", "continue-with-input", "cancel");
        }
        if ("TASK_STATE_AUTH_REQUIRED".equals(state)) {
            return List.of("get", "continue-after-authorization", "cancel");
        }
        return List.of("get", "subscribe-preview", "request-cancel-preview");
    }

    private String statusSummary(String scenario, String state, int artifactCount) {
        return switch (scenario) {
            case "working" -> "任务正在受控执行链路中，当前只展示阶段摘要。";
            case "input-required" -> "任务等待用户补充输入类别，未暴露原始消息。";
            case "auth-required" -> "任务等待审批或授权，未通过事件交换凭证。";
            case "failed" -> "任务失败，响应只展示错误类别和治理建议。";
            case "canceled" -> "任务已取消，取消前后的安全边界由历史事件说明。";
            default -> "任务已完成，包含 " + artifactCount + " 个受控 artifact 引用。当前状态：" + state + "。";
        };
    }

    private String normalizeScenario(String scenario) {
        if (scenario == null || scenario.isBlank()) {
            return "completed";
        }
        String normalized = scenario.trim().toLowerCase(Locale.ROOT).replace("_", "-");
        if (List.of("completed", "working", "input-required", "auth-required", "failed", "canceled")
                .contains(normalized)) {
            return normalized;
        }
        return "completed";
    }

    private int normalizeHistoryLength(Integer historyLength) {
        if (historyLength == null) {
            return DEFAULT_HISTORY_LENGTH;
        }
        if (historyLength < 0) {
            return 0;
        }
        return Math.min(historyLength, MAX_HISTORY_LENGTH);
    }
}

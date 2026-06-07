/**
 * @Author : Cui
 * @Date: 2026/06/07 14:31
 * @Description DataSmart Govern Backend - AgentToolActionExecutionGraphPreviewBuilder.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionExecutionGraphEdgeView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionExecutionGraphNodeView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionExecutionGraphView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionIntakeDurableActionContractView;

import java.util.ArrayList;
import java.util.List;

/**
 * 将单条 durable action contract 转换为工具动作执行图。
 *
 * <p>拆出 builder 的原因是控制 service 文件体积和职责：service 负责查询与窗口聚合，builder 负责“怎样把契约画成图”。
 * 这也方便后续把执行图接入真正的 LangGraph/OpenClaw-style runner 时，单独替换图构建策略，而不用改查询、权限收口和
 * runtime event 投影逻辑。</p>
 */
final class AgentToolActionExecutionGraphPreviewBuilder {

    static final String STATE_WAITING_APPROVAL = "WAITING_APPROVAL";
    static final String STATE_NEEDS_CLARIFICATION = "NEEDS_CLARIFICATION";
    static final String STATE_WAITING_BUDGET = "WAITING_TOOL_BUDGET";
    static final String STATE_BLOCKED = "BLOCKED_BEFORE_EXECUTION";
    static final String STATE_REJECTED = "REJECTED_BEFORE_READINESS";
    static final String GRAPH_READY_OUTBOX = "READY_FOR_OUTBOX_WRITE";
    static final String GRAPH_WAITING_EVIDENCE = "WAITING_DURABLE_ACTION_EVIDENCE";

    private static final String STATE_READY_CONTROLLED = "READY_FOR_DURABLE_ACTION_CONTRACT";
    private static final String STATE_READY_ASYNC = "READY_FOR_ASYNC_OUTBOX_CONTRACT";
    private static final String GRAPH_INTAKE_ONLY = "INTAKE_ONLY";

    /**
     * 构建单张执行图。
     *
     * <p>图中的节点顺序固定为：入口 -> readiness -> 可选等待节点 -> contract -> outbox -> worker receipt。
     * 这种顺序刻意把“判断能否执行”和“真正执行副作用”分开，避免调用方看到 READY 就绕过审批、幂等和 worker 回执。</p>
     */
    AgentToolActionExecutionGraphView buildGraph(AgentToolActionIntakeDurableActionContractView contract) {
        String graphState = graphState(contract);
        List<AgentToolActionExecutionGraphNodeView> nodes = new ArrayList<>();
        List<AgentToolActionExecutionGraphEdgeView> edges = new ArrayList<>();

        nodes.add(intakeNode(contract, graphState));
        nodes.add(readinessNode(contract, graphState));
        edges.add(edge("intake", "readiness", "INTAKE_TO_READINESS", "ALWAYS",
                "工具动作入口事实必须先经过 readiness 判断，不能从协议入口直接进入真实副作用。"));

        List<String> waitingGateIds = addWaitingGateNodes(graphState, nodes, edges);
        nodes.add(contractNode(contract, graphState, waitingGateIds));
        connectReadinessToContract(waitingGateIds, edges);

        nodes.add(outboxNode(contract, graphState));
        nodes.add(workerReceiptNode(contract, graphState));
        edges.add(edge("contract", "outbox", "CONTRACT_TO_OUTBOX", "WHEN_CONTRACT_READY",
                "只有契约证据齐备后，专用 command builder 才能写入 outbox。"));
        edges.add(edge("outbox", "worker-receipt", "OUTBOX_TO_WORKER_RECEIPT", "WHEN_COMMAND_DISPATCHED",
                "worker receipt 必须证明副作用由 worker 接单或完成，不能由 projection 自行假定。"));

        return new AgentToolActionExecutionGraphView(
                graphId(contract),
                contract.contractId(),
                contract.sourceEventIdentityKey(),
                contract.sourceReplaySequence(),
                contract.tenantId(),
                contract.projectId(),
                contract.actorId(),
                contract.requestId(),
                contract.runId(),
                contract.sessionId(),
                contract.eventCreatedAt(),
                contract.protocolFamily(),
                contract.intakeSource(),
                contract.toolName(),
                graphState,
                terminalState(graphState),
                contract.outboxWritableNow(),
                nodes.size(),
                edges.size(),
                safeList(contract.requiredEvidence()),
                safeList(contract.missingRequirements()),
                contract.payloadPolicy(),
                graphSummary(contract, graphState),
                graphRecommendedActions(contract, graphState),
                List.copyOf(nodes),
                List.copyOf(edges)
        );
    }

    boolean isBlockedGraphState(String graphState) {
        return STATE_BLOCKED.equals(graphState) || STATE_REJECTED.equals(graphState);
    }

    private AgentToolActionExecutionGraphNodeView intakeNode(
            AgentToolActionIntakeDurableActionContractView contract,
            String graphState) {
        boolean executable = !STATE_REJECTED.equals(graphState);
        return node(
                "intake",
                "INTAKE",
                "PROTOCOL_INTAKE",
                executable ? "ACCEPTED_BY_CONTROL_PLANE" : "REJECTED_BEFORE_READINESS",
                true,
                executable,
                List.of(),
                List.of(
                        "event:" + contract.sourceEventIdentityKey(),
                        "replaySequence:" + contract.sourceReplaySequence(),
                        "protocol:" + defaultText(contract.protocolFamily(), "UNKNOWN_PROTOCOL"),
                        "source:" + defaultText(contract.intakeSource(), "UNKNOWN_SOURCE")
                ),
                STATE_REJECTED.equals(graphState)
                        ? List.of("VISIBLE_TOOL_OR_PROTOCOL_ACCEPTANCE_REQUIRED")
                        : List.of(),
                List.of(
                        "入口节点表示外部协议或模型工具意图已经被 DataSmart 捕获并转成低敏事实。",
                        "该节点不携带原始 arguments，只保留协议族、入口来源和 replay 游标，便于审计回放。"
                ),
                executable
                        ? List.of("继续进入 readiness 节点，由平台判断是否需要审批、澄清、排队或阻断。")
                        : List.of("检查工具注册名、协议 method、可见工具目录和 JSON-RPC 形态后再重新进入 intake。")
        );
    }

    private AgentToolActionExecutionGraphNodeView readinessNode(
            AgentToolActionIntakeDurableActionContractView contract,
            String graphState) {
        boolean executable = GRAPH_READY_OUTBOX.equals(graphState) || GRAPH_WAITING_EVIDENCE.equals(graphState);
        return node(
                "readiness",
                "READINESS_GATE",
                "EXECUTION_READINESS",
                readinessStatus(graphState),
                true,
                executable,
                STATE_REJECTED.equals(graphState) ? List.of("intake") : List.of(),
                evidenceRefs("decision:" + defaultText(contract.readinessDecision(), "UNKNOWN_DECISION"),
                        prefixAll("issue:", contract.issueCodes()),
                        prefixAll("reason:", contract.reasonCodes())),
                readinessMissingRequirements(contract, graphState),
                List.of(
                        "readiness 节点把工具动作从“模型或协议想执行”转成“平台是否允许继续”的治理判断。",
                        "它会综合工具可见性、参数形态、审批要求、预算/队列和阻断策略，但只输出低敏 reason/issue code。"
                ),
                readinessActions(graphState)
        );
    }

    private List<String> addWaitingGateNodes(String graphState,
                                             List<AgentToolActionExecutionGraphNodeView> nodes,
                                             List<AgentToolActionExecutionGraphEdgeView> edges) {
        List<String> waitingGateIds = new ArrayList<>();
        if (STATE_WAITING_APPROVAL.equals(graphState)) {
            addWaitingGate(waitingGateIds, nodes, edges, "approval", "HUMAN_APPROVAL",
                    "APPROVAL_GATE", "WAITING_APPROVAL_FACT", "HUMAN_APPROVAL_FACT_REQUIRED",
                    "人工审批节点用于承载高风险工具、跨权限动作或需要用户确认的副作用。",
                    "接入 permission-admin 或前端确认页，生成 HUMAN_APPROVAL_CONFIRMATION_ID 后再回到 contract 节点。",
                    "READINESS_TO_APPROVAL", "WHEN_APPROVAL_REQUIRED",
                    "readiness 判断需要人工确认，因此必须先等待审批事实。");
        }
        if (STATE_NEEDS_CLARIFICATION.equals(graphState)) {
            addWaitingGate(waitingGateIds, nodes, edges, "clarification", "USER_CLARIFICATION",
                    "PARAMETER_CLARIFICATION", "WAITING_USER_OR_AGENT_INPUT", "USER_CLARIFICATION_FACT_REQUIRED",
                    "澄清节点用于参数不完整、语义不明确或工具 schema 无法安全填充的场景。",
                    "向用户或上游 Agent 请求补充信息，只保存澄清事实引用，不保存原始业务载荷。",
                    "READINESS_TO_CLARIFICATION", "WHEN_ARGUMENT_SHAPE_INVALID",
                    "参数形态不满足安全执行要求时，必须先进入澄清节点。");
        }
        if (STATE_WAITING_BUDGET.equals(graphState)) {
            addWaitingGate(waitingGateIds, nodes, edges, "budget", "BUDGET_AND_BACKLOG",
                    "CAPACITY_GATE", "WAITING_TOOL_BUDGET_OR_WORKER_CAPACITY",
                    "WORKER_CAPACITY_OR_TOOL_BUDGET_REQUIRED",
                    "预算与队列节点用于表达工具预算、租户额度、worker backlog 或容量保护导致的等待。",
                    "等待 permission-admin、task-management 或 worker backlog 快照恢复后再重新评估 contract。",
                    "READINESS_TO_BUDGET", "WHEN_BUDGET_OR_BACKLOG_LIMITED",
                    "工具预算或队列容量不足时，不能直接写 outbox。");
        }
        return List.copyOf(waitingGateIds);
    }

    private void addWaitingGate(List<String> waitingGateIds,
                                List<AgentToolActionExecutionGraphNodeView> nodes,
                                List<AgentToolActionExecutionGraphEdgeView> edges,
                                String nodeId,
                                String nodeType,
                                String stage,
                                String status,
                                String missingRequirement,
                                String reason,
                                String action,
                                String edgeType,
                                String condition,
                                String edgeReason) {
        waitingGateIds.add(nodeId);
        nodes.add(waitingNode(nodeId, nodeType, stage, status, missingRequirement, reason, action));
        edges.add(edge("readiness", nodeId, edgeType, condition, edgeReason));
    }

    private void connectReadinessToContract(List<String> waitingGateIds,
                                            List<AgentToolActionExecutionGraphEdgeView> edges) {
        if (waitingGateIds.isEmpty()) {
            edges.add(edge("readiness", "contract", "READINESS_TO_CONTRACT", "WHEN_READINESS_EVALUATED",
                    "readiness 已形成低敏结论后，才能推导 durable action contract 缺口。"));
            return;
        }
        for (String waitingGateId : waitingGateIds) {
            edges.add(edge(waitingGateId, "contract", "WAITING_GATE_TO_CONTRACT", "WHEN_GATE_FACT_READY",
                    "审批、澄清或预算等待节点补齐事实后，contract 才能重新评估是否进入 outbox。"));
        }
    }

    private AgentToolActionExecutionGraphNodeView contractNode(
            AgentToolActionIntakeDurableActionContractView contract,
            String graphState,
            List<String> waitingGateIds) {
        boolean executable = GRAPH_READY_OUTBOX.equals(graphState);
        List<String> blockedBy = new ArrayList<>(waitingGateIds);
        if (isBlockedGraphState(graphState)) {
            blockedBy.add("readiness");
        }
        return node(
                "contract",
                "DURABLE_CONTRACT",
                "DURABLE_ACTION_CONTRACT",
                executable ? "CONTRACT_READY" : "WAITING_EVIDENCE",
                true,
                executable,
                blockedBy,
                List.of(
                        "contract:" + contract.contractId(),
                        "idempotencyKey:" + contract.idempotencyKey(),
                        "payloadPolicy:" + defaultText(contract.payloadPolicy(), "UNKNOWN_PAYLOAD_POLICY")
                ),
                safeList(contract.missingRequirements()),
                List.of(
                        "contract 节点把 readiness 结论转换成真实执行前的证据清单。",
                        "它刻意不恢复原始参数，而是要求 payloadReference、幂等键、outbox command schema 和 worker receipt 等证据。"
                ),
                executable
                        ? List.of("可以交给专用确认 API 或 command builder 做最终权限、幂等和容量复核后写入 outbox。")
                        : List.of("先补齐 missingRequirements，再重新生成 contract；不要让 projection DTO 直接替代 outbox command。")
        );
    }

    private AgentToolActionExecutionGraphNodeView outboxNode(
            AgentToolActionIntakeDurableActionContractView contract,
            String graphState) {
        boolean executable = GRAPH_READY_OUTBOX.equals(graphState);
        return node(
                "outbox",
                "OUTBOX_COMMAND",
                "DURABLE_OUTBOX",
                executable ? "READY_TO_WRITE" : "NOT_WRITABLE_IN_PREVIEW",
                true,
                executable,
                executable ? List.of() : List.of("contract"),
                List.of(
                        "commandType:" + defaultText(contract.outboxCommandType(), "NONE"),
                        "idempotencyKey:" + contract.idempotencyKey()
                ),
                executable ? List.of() : List.of("OUTBOX_RECORD_NOT_WRITTEN"),
                List.of(
                        "outbox 节点代表未来可恢复执行链路的持久化命令入口。",
                        "当前接口是预览接口，因此不会创建 outbox record；真实写入必须由单独命令接口完成。"
                ),
                executable
                        ? List.of("进入正式 command builder 前再次校验权限、payloadReference、幂等键和 worker 容量。")
                        : List.of("不要从本图直接派发 worker；先补齐 contract 缺口并通过正式确认链路。")
        );
    }

    private AgentToolActionExecutionGraphNodeView workerReceiptNode(
            AgentToolActionIntakeDurableActionContractView contract,
            String graphState) {
        return node(
                "worker-receipt",
                "WORKER_RECEIPT",
                "WORKER_EXECUTION_RECEIPT",
                GRAPH_READY_OUTBOX.equals(graphState) ? "WAITING_OUTBOX_DISPATCH" : "WAITING_OUTBOX_RECORD",
                true,
                false,
                List.of("outbox"),
                List.of(
                        "workerReceiptRequired:" + Boolean.TRUE.equals(contract.workerReceiptRequired()),
                        "sourceReplaySequence:" + contract.sourceReplaySequence()
                ),
                List.of("WORKER_RECEIPT_REQUIRED"),
                List.of(
                        "worker receipt 节点用于证明副作用由受控 worker 接单或完成，而不是由模型响应自行宣称完成。",
                        "该节点只表达回执要求，不保存工具结果正文；结果正文应走受控 artifact 或脱敏结果引用。"
                ),
                List.of("后续接入 task-management worker 后，应把接单、成功、失败、重试和死信状态回写为低敏 receipt 事实。")
        );
    }

    private AgentToolActionExecutionGraphNodeView waitingNode(String nodeId,
                                                              String nodeType,
                                                              String stage,
                                                              String status,
                                                              String missingRequirement,
                                                              String reason,
                                                              String action) {
        return node(nodeId, nodeType, stage, status, true, false, List.of("readiness"),
                List.of("missing:" + missingRequirement), List.of(missingRequirement), List.of(reason), List.of(action));
    }

    private AgentToolActionExecutionGraphNodeView node(String nodeId,
                                                       String nodeType,
                                                       String stage,
                                                       String status,
                                                       boolean required,
                                                       boolean executable,
                                                       List<String> blockedByNodeIds,
                                                       List<String> evidenceRefs,
                                                       List<String> missingRequirements,
                                                       List<String> reasons,
                                                       List<String> recommendedActions) {
        return new AgentToolActionExecutionGraphNodeView(nodeId, nodeType, stage, status, required, executable,
                List.copyOf(blockedByNodeIds), List.copyOf(evidenceRefs), List.copyOf(missingRequirements),
                List.copyOf(reasons), List.copyOf(recommendedActions));
    }

    private AgentToolActionExecutionGraphEdgeView edge(String fromNodeId,
                                                       String toNodeId,
                                                       String edgeType,
                                                       String condition,
                                                       String reason) {
        return new AgentToolActionExecutionGraphEdgeView(fromNodeId, toNodeId, edgeType, condition, reason);
    }

    private String graphState(AgentToolActionIntakeDurableActionContractView contract) {
        String state = defaultText(contract.durableActionState(), GRAPH_INTAKE_ONLY);
        if (STATE_REJECTED.equals(state) || STATE_BLOCKED.equals(state)
                || STATE_WAITING_APPROVAL.equals(state) || STATE_NEEDS_CLARIFICATION.equals(state)
                || STATE_WAITING_BUDGET.equals(state)) {
            return state;
        }
        if ((STATE_READY_CONTROLLED.equals(state) || STATE_READY_ASYNC.equals(state))
                && Boolean.TRUE.equals(contract.outboxWritableNow())) {
            return GRAPH_READY_OUTBOX;
        }
        if (STATE_READY_CONTROLLED.equals(state) || STATE_READY_ASYNC.equals(state)) {
            return GRAPH_WAITING_EVIDENCE;
        }
        return GRAPH_INTAKE_ONLY;
    }

    private String terminalState(String graphState) {
        return switch (graphState) {
            case GRAPH_READY_OUTBOX -> "READY_FOR_COMMAND_BUILDER";
            case GRAPH_WAITING_EVIDENCE -> "WAIT_FOR_PAYLOAD_OR_OUTBOX_EVIDENCE";
            case STATE_WAITING_APPROVAL -> "WAIT_FOR_HUMAN_APPROVAL";
            case STATE_NEEDS_CLARIFICATION -> "WAIT_FOR_USER_OR_AGENT_CLARIFICATION";
            case STATE_WAITING_BUDGET -> "WAIT_FOR_BUDGET_OR_WORKER_CAPACITY";
            case STATE_BLOCKED -> "STOP_BEFORE_SIDE_EFFECT";
            case STATE_REJECTED -> "STOP_BEFORE_READINESS";
            default -> "WAIT_FOR_MORE_INTAKE_FACTS";
        };
    }

    private String readinessStatus(String graphState) {
        return switch (graphState) {
            case GRAPH_READY_OUTBOX, GRAPH_WAITING_EVIDENCE -> "PASSED";
            case STATE_WAITING_APPROVAL -> "WAITING_APPROVAL";
            case STATE_NEEDS_CLARIFICATION -> "NEEDS_CLARIFICATION";
            case STATE_WAITING_BUDGET -> "WAITING_BUDGET_OR_CAPACITY";
            case STATE_BLOCKED, STATE_REJECTED -> "BLOCKED";
            default -> "RECORDED_ONLY";
        };
    }

    private List<String> readinessMissingRequirements(AgentToolActionIntakeDurableActionContractView contract,
                                                      String graphState) {
        return switch (graphState) {
            case STATE_WAITING_APPROVAL -> List.of("HUMAN_APPROVAL_FACT_REQUIRED");
            case STATE_NEEDS_CLARIFICATION -> List.of("USER_CLARIFICATION_FACT_REQUIRED");
            case STATE_WAITING_BUDGET -> List.of("WORKER_CAPACITY_OR_TOOL_BUDGET_REQUIRED");
            case STATE_REJECTED -> List.of("VISIBLE_TOOL_OR_PROTOCOL_ACCEPTANCE_REQUIRED");
            case STATE_BLOCKED -> safeList(contract.issueCodes());
            default -> List.of();
        };
    }

    private List<String> readinessActions(String graphState) {
        return switch (graphState) {
            case GRAPH_READY_OUTBOX, GRAPH_WAITING_EVIDENCE ->
                    List.of("继续检查 durable contract 是否具备 payloadReference、幂等键、outbox schema 和 worker receipt 策略。");
            case STATE_WAITING_APPROVAL -> List.of("进入审批或前端确认页，生成可审计的人类确认事实。");
            case STATE_NEEDS_CLARIFICATION -> List.of("请求用户或上游 Agent 澄清参数形态，不要让模型猜测敏感参数值。");
            case STATE_WAITING_BUDGET -> List.of("等待预算、租户额度或 worker backlog 恢复，再重新评估 readiness。");
            case STATE_BLOCKED, STATE_REJECTED -> List.of("停止真实副作用，回到工具注册、协议适配、权限策略或参数 schema 层修复。");
            default -> List.of("继续收集入口事实，暂不进入执行链路。");
        };
    }

    private List<String> graphSummary(AgentToolActionIntakeDurableActionContractView contract, String graphState) {
        List<String> summary = new ArrayList<>();
        summary.add("工具 `" + defaultText(contract.toolName(), "UNKNOWN_TOOL") + "` 的动作已被转换为执行图预览，当前状态为 "
                + graphState + "。");
        summary.add("图中所有节点都来自低敏 contract 和 runtime event，不包含工具实参、SQL、prompt、样本数据或模型输出。");
        if (GRAPH_WAITING_EVIDENCE.equals(graphState)) {
            summary.add("readiness 已接近通过，但仍缺 payloadReference、outbox record 或 worker receipt 等可恢复执行证据。");
        }
        if (GRAPH_READY_OUTBOX.equals(graphState)) {
            summary.add("当前图具备最低 outbox 写入条件，但仍必须由专用确认 API 进行最终复核，不能由预览接口直接执行。");
        }
        return List.copyOf(summary);
    }

    private List<String> graphRecommendedActions(AgentToolActionIntakeDurableActionContractView contract,
                                                 String graphState) {
        List<String> actions = new ArrayList<>();
        actions.add("把该图作为前端确认页和 outbox command builder 的输入契约，而不是把 projection DTO 当作执行命令。");
        actions.add("真实执行前必须重新校验 tenant/project/actor 数据范围、payloadReference、幂等键和 worker 容量。");
        if (!safeList(contract.missingRequirements()).isEmpty()) {
            actions.add("优先补齐缺口：" + String.join(",", safeList(contract.missingRequirements())) + "。");
        }
        if (STATE_WAITING_APPROVAL.equals(graphState)) {
            actions.add("接入 permission-admin 或审批台，生成审批事实后再重放 contract。");
        }
        if (STATE_REJECTED.equals(graphState) || STATE_BLOCKED.equals(graphState)) {
            actions.add("不要进入 outbox；先修复工具可见性、协议适配、权限策略或参数 schema。");
        }
        return List.copyOf(actions);
    }

    private String graphId(AgentToolActionIntakeDurableActionContractView contract) {
        String contractId = defaultText(contract.contractId(), "unknown");
        return contractId.replace("tool-action-contract:", "tool-action-execution-graph:");
    }

    @SafeVarargs
    private final List<String> evidenceRefs(Object... values) {
        List<String> refs = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof List<?> list) {
                for (Object item : list) {
                    addText(refs, item);
                }
            } else {
                addText(refs, value);
            }
        }
        return List.copyOf(refs);
    }

    private List<String> prefixAll(String prefix, List<String> values) {
        List<String> refs = new ArrayList<>();
        for (String value : safeList(values)) {
            String text = defaultText(value, null);
            if (text != null) {
                refs.add(prefix + text);
            }
        }
        return List.copyOf(refs);
    }

    private void addText(List<String> refs, Object value) {
        String text = value == null ? null : defaultText(value.toString(), null);
        if (text != null) {
            refs.add(text);
        }
    }

    private String defaultText(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.trim();
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }
}

/**
 * @Author : Cui
 * @Date: 2026/06/07 14:48
 * @Description DataSmart Govern Backend - AgentToolActionCommandProposalService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalRequest;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionCommandProposalResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionExecutionGraphNodeView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionExecutionGraphQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolActionExecutionGraphView;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 工具动作 command proposal 服务。
 *
 * <p>本服务是 5.51 执行图预览之后的“正式 command builder 预备层”。它会读取执行图，接收调用方提供的
 * payloadReference、审批事实 ID、澄清事实 ID、策略版本等低敏证据，然后判断是否已经具备进入正式 outbox writer
 * 的最低条件。</p>
 *
 * <p>注意：本服务仍然不会写 outbox。原因是 command proposal 只解决“证据是否足够”这个问题，真正写入 outbox 时
 * 还必须由专用 writer 再次读取 payloadReference、校验权限、校验幂等、检查 worker 容量并生成持久化命令记录。
 * 这种两段式设计虽然比直接写入慢一步，但能避免外部协议或模型工具调用绕过 DataSmart 的生产级治理边界。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolActionCommandProposalService {

    /**
     * proposal 自身的状态枚举。
     *
     * <p>这些状态刻意不复用 execution graph 状态：graph 描述“工具动作现在卡在哪个治理节点”，proposal 描述
     * “当前低敏证据是否足以进入正式 outbox writer 复核”。两者分离可以避免把只读图预览误当成真实命令写入。</p>
     */
    private static final String STATE_READY_CONFIRMATION = "READY_FOR_OUTBOX_CONFIRMATION";
    private static final String STATE_WAITING_EVIDENCE = "WAITING_REQUIRED_EVIDENCE";
    private static final String STATE_WAITING_APPROVAL = "WAITING_HUMAN_APPROVAL_FACT";
    private static final String STATE_WAITING_CLARIFICATION = "WAITING_CLARIFICATION_FACT";
    private static final String STATE_WAITING_BUDGET = "WAITING_BUDGET_OR_WORKER_CAPACITY";
    private static final String STATE_BLOCKED = "BLOCKED_BY_GRAPH_STATE";

    /**
     * execution graph 来源状态。
     *
     * <p>这些值来自 5.51 的执行图预览服务。proposal 只能读取它们做前置判断，不能修改执行图，也不能因为调用方
     * 传了 approvalConfirmationId 或 payloadReference 就越过图上的等待/阻断分支。</p>
     */
    private static final String GRAPH_READY_OUTBOX = "READY_FOR_OUTBOX_WRITE";
    private static final String GRAPH_WAITING_EVIDENCE = "WAITING_DURABLE_ACTION_EVIDENCE";
    private static final String GRAPH_WAITING_APPROVAL = "WAITING_APPROVAL";
    private static final String GRAPH_WAITING_CLARIFICATION = "NEEDS_CLARIFICATION";
    private static final String GRAPH_WAITING_BUDGET = "WAITING_TOOL_BUDGET";
    private static final String GRAPH_BLOCKED = "BLOCKED_BEFORE_EXECUTION";
    private static final String GRAPH_REJECTED = "REJECTED_BEFORE_READINESS";

    private final AgentToolActionExecutionGraphPreviewService executionGraphPreviewService;

    /**
     * 生成工具动作 command proposal。
     *
     * @param request 调用方提交的低敏证据和图选择条件。
     * @param accessContext gateway/认证层解析出的数据范围上下文。
     * @return command proposal 结果。返回 ready 也不代表本方法已经写 outbox。
     */
    public AgentToolActionCommandProposalResponse propose(
            AgentToolActionCommandProposalRequest request,
            AgentRuntimeEventQueryAccessContext accessContext) {
        AgentToolActionCommandProposalRequest safeRequest = request == null
                ? new AgentToolActionCommandProposalRequest(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null)
                : request;
        AgentToolActionExecutionGraphView graph = selectGraph(safeRequest, accessContext);
        ProposalEvidence evidence = evaluateEvidence(safeRequest, graph);
        String proposalState = proposalState(graph, evidence);
        boolean allowed = STATE_READY_CONFIRMATION.equals(proposalState);
        String commandType = evidence.commandType() == null ? "NONE" : evidence.commandType();
        String idempotencyKey = evidence.idempotencyKey();
        String schemaVersion = defaultText(safeRequest.commandSchemaVersion(), "agent-tool-action-command.v1");
        String workerReceiptMode = defaultText(safeRequest.workerReceiptMode(), "REQUIRED");
        return new AgentToolActionCommandProposalResponse(
                proposalId(graph, safeRequest, schemaVersion),
                proposalState,
                allowed,
                graph.graphId(),
                graph.contractId(),
                graph.sourceEventIdentityKey(),
                graph.sourceReplaySequence(),
                graph.tenantId(),
                graph.projectId(),
                graph.actorId(),
                graph.requestId(),
                graph.runId(),
                graph.sessionId(),
                Instant.now(),
                graph.toolName(),
                commandType,
                schemaVersion,
                idempotencyKey,
                safeText(safeRequest.payloadReference()),
                graph.payloadPolicy(),
                true,
                workerReceiptMode,
                graph.graphState(),
                graph.terminalState(),
                evidence.accepted(),
                evidence.missing(),
                evidence.rejected(),
                guardrailNotes(graph, allowed),
                summaryReasons(graph, proposalState, allowed),
                recommendedActions(graph, proposalState, evidence)
        );
    }

    /**
     * 从执行图窗口中选出唯一目标图。
     *
     * <p>command proposal 必须绑定一张明确的 graph/contract，不能对“当前窗口第一条”做隐式操作。
     * 如果查询条件命中 0 张图，说明调用方引用了不存在或无权访问的图；如果命中多张图，说明缺少 graphId/contractId
     * 这样的精确选择条件。两种情况都必须失败，避免后续 writer 对错误工具动作生成命令。</p>
     */
    private AgentToolActionExecutionGraphView selectGraph(
            AgentToolActionCommandProposalRequest request,
            AgentRuntimeEventQueryAccessContext accessContext) {
        AgentRuntimeEventProjectionQuery query = new AgentRuntimeEventProjectionQuery(
                request.tenantId(),
                request.projectId(),
                request.actorId(),
                request.requestId(),
                request.runId(),
                request.sessionId(),
                null,
                null,
                request.limit() == null ? 100 : request.limit(),
                request.afterSequence()
        );
        AgentToolActionExecutionGraphQueryResponse response =
                executionGraphPreviewService.queryExecutionGraphs(query, accessContext);
        List<AgentToolActionExecutionGraphView> candidates = response.graphs().stream()
                .filter(graph -> matchesSelection(request, graph))
                .toList();
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        if (candidates.isEmpty()) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.NOT_FOUND,
                    "未找到匹配 graphId/contractId 的工具动作执行图，不能生成 command proposal"
            );
        }
        throw new PlatformBusinessException(
                PlatformErrorCode.BAD_REQUEST,
                "当前选择条件命中多张工具动作执行图，请提供 graphId 或 contractId 精确选择"
        );
    }

    /**
     * 判断候选执行图是否匹配调用方选择条件。
     *
     * <p>这里要求至少提供 graphId 或 contractId。原因是 proposal 属于“准备写命令”的高风险前置步骤，
     * 不能像普通列表查询一样允许宽松匹配，否则同一 run 里多个工具动作可能被误绑定到同一个 payloadReference。</p>
     */
    private boolean matchesSelection(AgentToolActionCommandProposalRequest request,
                                     AgentToolActionExecutionGraphView graph) {
        String graphId = safeText(request.graphId());
        String contractId = safeText(request.contractId());
        if (graphId == null && contractId == null) {
            return false;
        }
        return (graphId == null || graphId.equals(graph.graphId()))
                && (contractId == null || contractId.equals(graph.contractId()));
    }

    /**
     * 汇总并校验进入正式 writer 前需要的低敏证据。
     *
     * <p>本方法把执行图缺失项、调用方提交的 payloadReference/policyVersion、图节点 evidenceRefs 中的 commandType
     * 与 idempotencyKey 聚合成三类列表：accepted、missing、rejected。accepted 表示“低敏形式可以被 proposal 接受”，
     * missing 表示“还缺少必要事实”，rejected 表示“证据存在但不能信任或必须服务端复核”。正式 writer 后续只能在
     * accepted 且无 missing/rejected 的基础上继续复核，不能直接信任 proposal 响应。</p>
     */
    private ProposalEvidence evaluateEvidence(AgentToolActionCommandProposalRequest request,
                                               AgentToolActionExecutionGraphView graph) {
        Set<String> accepted = new LinkedHashSet<>();
        Set<String> missing = new LinkedHashSet<>();
        Set<String> rejected = new LinkedHashSet<>();
        addGraphMissingRequirements(graph, missing);

        String payloadReference = safeText(request.payloadReference());
        if (payloadReference == null) {
            missing.add("PAYLOAD_REFERENCE_REQUIRED");
        } else if (!isSafePayloadReference(payloadReference)) {
            rejected.add("PAYLOAD_REFERENCE_UNSAFE_OR_INLINE");
        } else {
            accepted.add("PAYLOAD_REFERENCE:" + payloadReference);
            missing.remove("PAYLOAD_REFERENCE_REQUIRED");
        }

        String commandType = extractEvidence(graph, "commandType:");
        if (commandType == null || "NONE".equals(commandType)) {
            missing.add("OUTBOX_COMMAND_TYPE_REQUIRED");
        } else {
            accepted.add("COMMAND_TYPE:" + commandType);
        }

        String idempotencyKey = extractEvidence(graph, "idempotencyKey:");
        if (idempotencyKey == null) {
            missing.add("IDEMPOTENCY_KEY_REQUIRED");
        } else {
            accepted.add("IDEMPOTENCY_KEY");
        }

        if (GRAPH_WAITING_APPROVAL.equals(graph.graphState())) {
            if (safeText(request.approvalConfirmationId()) == null) {
                missing.add("HUMAN_APPROVAL_FACT_REQUIRED");
            } else {
                accepted.add("HUMAN_APPROVAL_CONFIRMATION_ID:" + request.approvalConfirmationId().trim());
                missing.remove("HUMAN_APPROVAL_FACT_REQUIRED");
                rejected.add("HUMAN_APPROVAL_REQUIRES_SERVER_SIDE_VERIFICATION");
            }
        }
        if (GRAPH_WAITING_CLARIFICATION.equals(graph.graphState())) {
            if (safeText(request.clarificationFactId()) == null) {
                missing.add("USER_CLARIFICATION_FACT_REQUIRED");
            } else {
                accepted.add("CLARIFICATION_FACT_ID:" + request.clarificationFactId().trim());
                missing.remove("USER_CLARIFICATION_FACT_REQUIRED");
                rejected.add("CLARIFICATION_REQUIRES_READINESS_REPLAY");
            }
        }
        if (safeText(request.policyVersion()) == null) {
            missing.add("POLICY_VERSION_REQUIRED");
        } else {
            accepted.add("POLICY_VERSION:" + request.policyVersion().trim());
        }

        removeCommandBuilderResolvableRequirements(missing);
        return new ProposalEvidence(
                commandType,
                idempotencyKey,
                List.copyOf(accepted),
                List.copyOf(missing),
                List.copyOf(rejected)
        );
    }

    /**
     * 根据执行图状态和证据结果计算 proposal 状态。
     *
     * <p>这里采用 fail-closed 顺序：先处理图上已阻断、预算、审批、澄清等状态，再处理证据缺口。
     * 也就是说，即使调用方补齐 payloadReference，只要图仍在等待审批或澄清，本方法也不会返回 READY。
     * 这可以防止客户端伪造一个字段就绕过人类确认、用户澄清或 worker 容量保护。</p>
     */
    private String proposalState(AgentToolActionExecutionGraphView graph, ProposalEvidence evidence) {
        if (GRAPH_BLOCKED.equals(graph.graphState()) || GRAPH_REJECTED.equals(graph.graphState())) {
            return STATE_BLOCKED;
        }
        if (GRAPH_WAITING_BUDGET.equals(graph.graphState())) {
            return STATE_WAITING_BUDGET;
        }
        if (GRAPH_WAITING_APPROVAL.equals(graph.graphState())) {
            return STATE_WAITING_APPROVAL;
        }
        if (GRAPH_WAITING_CLARIFICATION.equals(graph.graphState())) {
            return STATE_WAITING_CLARIFICATION;
        }
        if (!evidence.rejected().isEmpty() || !evidence.missing().isEmpty()) {
            return STATE_WAITING_EVIDENCE;
        }
        if (GRAPH_READY_OUTBOX.equals(graph.graphState()) || GRAPH_WAITING_EVIDENCE.equals(graph.graphState())) {
            return STATE_READY_CONFIRMATION;
        }
        return STATE_WAITING_EVIDENCE;
    }

    /**
     * 从执行图整体和每个节点中收集缺失要求。
     *
     * <p>执行图可能在图级别给出总缺口，也可能在节点级别给出局部缺口。proposal 需要合并这两类信息，
     * 否则前端确认页只能看到“不能写入”，却不知道缺口来自 payload、审批、预算还是 worker receipt。</p>
     */
    private void addGraphMissingRequirements(AgentToolActionExecutionGraphView graph, Set<String> missing) {
        for (String requirement : safeList(graph.missingRequirements())) {
            addIfText(missing, requirement);
        }
        for (AgentToolActionExecutionGraphNodeView node : safeList(graph.nodes())) {
            for (String requirement : safeList(node.missingRequirements())) {
                addIfText(missing, requirement);
            }
        }
    }

    /**
     * 移除正式 command builder 可以在下一阶段解决的缺口。
     *
     * <p>执行图中的 outbox 未写入、worker receipt 未生成等缺口，本来就应该由后续 writer/worker 产生。
     * proposal 阶段不能因为“outbox 还没写”而永远阻塞，否则系统会形成循环依赖：没有 proposal 就不能写 outbox，
     * 没有 outbox 又不能生成 proposal。</p>
     */
    private void removeCommandBuilderResolvableRequirements(Set<String> missing) {
        missing.remove("DURABLE_ACTION_COMMAND_NOT_CREATED");
        missing.remove("OUTBOX_COMMAND_AND_WORKER_RECEIPT");
        missing.remove("OUTBOX_RECORD_NOT_WRITTEN");
        missing.remove("WORKER_RECEIPT_REQUIRED");
    }

    /**
     * 从图节点 evidenceRefs 中提取指定前缀的低敏证据。
     *
     * <p>当前 commandType 和 idempotencyKey 都由 contract/graph builder 以 evidenceRef 的形式写入节点。
     * proposal 只读取这些低敏引用字符串，不读取工具参数、SQL、prompt 或 payload 正文。</p>
     */
    private String extractEvidence(AgentToolActionExecutionGraphView graph, String prefix) {
        for (AgentToolActionExecutionGraphNodeView node : safeList(graph.nodes())) {
            for (String ref : safeList(node.evidenceRefs())) {
                if (ref != null && ref.startsWith(prefix)) {
                    return safeText(ref.substring(prefix.length()));
                }
            }
        }
        return null;
    }

    /**
     * 校验 payloadReference 是否像一个受控引用，而不是内联载荷。
     *
     * <p>这个方法不是最终安全鉴权，只是 proposal 层的第一道粗筛。它拒绝 HTTP/HTTPS 内部地址、换行、SQL 片段、
     * JSON/数组痕迹、疑似 password 参数和过长字符串，并要求引用带有明确前缀。正式 writer 后续仍必须按引用去
     * payload 存储中做租户、项目、操作者和用途级别的二次鉴权。</p>
     */
    private boolean isSafePayloadReference(String payloadReference) {
        String normalized = payloadReference.trim().toLowerCase(Locale.ROOT);
        if (payloadReference.length() > 256) {
            return false;
        }
        if (normalized.contains("http://") || normalized.contains("https://")
                || normalized.contains("\n") || normalized.contains("\r")) {
            return false;
        }
        if (normalized.contains("select *") || normalized.contains("password=")
                || normalized.contains("{") || normalized.contains("}") || normalized.contains("[")) {
            return false;
        }
        return normalized.startsWith("payload-ref:")
                || normalized.startsWith("artifact-ref:")
                || normalized.startsWith("agent-payload:");
    }

    /**
     * 生成面向前端确认页和运维排障的护栏说明。
     *
     * <p>这里返回的是低敏说明文本，而不是内部策略表达式。它帮助用户理解“为什么 ready 也还没有执行”，
     * 也提醒后续 writer 必须重新鉴权 payloadReference 和审批事实。</p>
     */
    private List<String> guardrailNotes(AgentToolActionExecutionGraphView graph, boolean allowed) {
        List<String> notes = new ArrayList<>();
        notes.add("本 proposal 只做正式 outbox writer 前的低敏预备校验，不会写 outbox 或调用 worker。");
        notes.add("payloadReference 只是受控引用，正式 writer 必须在服务端重新鉴权和读取，不能信任客户端传入的参数正文。");
        if (allowed) {
            notes.add("当前证据满足进入正式 writer 的最低条件，但 writer 仍需复核权限、幂等、容量和审批事实。");
        } else {
            notes.add("当前 proposal 不能进入 outbox writer，请先修复 missingEvidence 或 rejectedEvidence。");
        }
        if (GRAPH_WAITING_APPROVAL.equals(graph.graphState())) {
            notes.add("审批 ID 的存在不等于审批已通过，后续必须回查 permission-admin 或确认记录。");
        }
        return List.copyOf(notes);
    }

    /**
     * 生成人读摘要原因。
     *
     * <p>summaryReasons 主要给管理台列表、timeline 或调试面板使用，帮助快速判断 proposal 是 ready、
     * waiting 还是 blocked。它不拼接任何工具实参或 payload 内容。</p>
     */
    private List<String> summaryReasons(AgentToolActionExecutionGraphView graph,
                                         String proposalState,
                                        boolean allowed) {
        List<String> reasons = new ArrayList<>();
        reasons.add("工具 `" + graph.toolName() + "` 的执行图状态为 " + graph.graphState()
                + "，proposal 状态为 " + proposalState + "。");
        if (allowed) {
            reasons.add("payloadReference、policyVersion、commandType 和 idempotencyKey 已满足预备条件。");
        } else {
            reasons.add("当前仍缺少进入正式 outbox writer 所需的证据，或图状态仍处于等待/阻断分支。");
        }
        return List.copyOf(reasons);
    }

    /**
     * 根据 proposal 状态输出下一步建议。
     *
     * <p>推荐动作不是自动执行计划，只是控制面提示。真正执行仍要由正式 writer、审批中心和 worker receipt
     * 链路完成；这样可以把“提示用户下一步做什么”和“系统真的做了什么”清楚分开。</p>
     */
    private List<String> recommendedActions(AgentToolActionExecutionGraphView graph,
                                            String proposalState,
                                            ProposalEvidence evidence) {
        List<String> actions = new ArrayList<>();
        if (STATE_READY_CONFIRMATION.equals(proposalState)) {
            actions.add("下一步调用正式 outbox writer，由服务端回查 payloadReference、校验审批事实和写入幂等 command。");
        } else {
            actions.add("先补齐 missingEvidence/rejectedEvidence，再重新生成 proposal。");
        }
        if (GRAPH_WAITING_APPROVAL.equals(graph.graphState())) {
            actions.add("先接入前端确认页或 permission-admin，确认审批事实后重放 readiness/contract。");
        }
        if (!evidence.rejected().isEmpty()) {
            actions.add("不要把被拒绝证据传给 writer；需要重新生成安全 payloadReference 或服务端确认事实。");
        }
        return List.copyOf(actions);
    }

    /**
     * 生成稳定 proposalId。
     *
     * <p>proposalId 使用 graphId、payloadReference、审批事实、澄清事实和 schemaVersion 计算摘要。
     * 这样同一组证据重复提交会得到同一类可关联 ID，便于前端重试、审计排查和后续 writer 做幂等关联。
     * 注意这里仍不把原始 payload 放入 ID 输入，只使用受控引用字符串。</p>
     */
    private String proposalId(AgentToolActionExecutionGraphView graph,
                              AgentToolActionCommandProposalRequest request,
                              String schemaVersion) {
        return "tool-action-command-proposal:" + sha256(graph.graphId() + "\n"
                + defaultText(request.payloadReference(), "") + "\n"
                + defaultText(request.approvalConfirmationId(), "") + "\n"
                + defaultText(request.clarificationFactId(), "") + "\n"
                + schemaVersion).substring(0, 32);
    }

    /**
     * 计算 SHA-256 摘要。
     *
     * <p>JDK 21 标准环境必然包含 SHA-256；如果运行时缺失，说明基础 JDK 已异常，直接抛出 IllegalStateException
     * 比返回随机 ID 更安全，因为随机 ID 会破坏 proposal 与 writer 之间的幂等关联。</p>
     */
    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 缺少 SHA-256，无法生成工具动作 command proposal ID", exception);
        }
    }

    /**
     * 仅在字符串有实际内容时加入集合。
     *
     * <p>统一通过 safeText 处理，可以避免 missing/accepted 列表里出现空字符串，减少前端和审计台的歧义。</p>
     */
    private void addIfText(Set<String> target, String value) {
        String text = safeText(value);
        if (text != null) {
            target.add(text);
        }
    }

    /**
     * 获取非空文本，否则返回默认值。
     *
     * <p>主要用于 schemaVersion、workerReceiptMode 等可选字段。默认值代表控制面的保守约定，
     * 不是客户端可以用来降低安全要求的开关。</p>
     */
    private String defaultText(String value, String fallback) {
        String text = safeText(value);
        return text == null ? fallback : text;
    }

    /**
     * 规范化可选字符串。
     *
     * <p>所有来自请求或图节点的字符串先经过 trim/blank 处理，再进入证据计算，避免空格导致幂等键、
     * policyVersion 或引用匹配出现看似不同、实际相同的边界问题。</p>
     */
    private String safeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * 空列表保护。
     *
     * <p>执行图 DTO 来自 projection/preview 层，理论上列表不应为空指针；但 proposal 属于写入前置保护层，
     * 对上游 DTO 的容错要更保守，宁可按空列表处理，也不要因为 NPE 跳过后续安全判断。</p>
     */
    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    /**
     * proposal 证据聚合结果。
     *
     * <p>commandType/idempotencyKey 是后续 writer 的关键输入；accepted/missing/rejected 则分别服务于确认页展示、
     * 审计解释和 fail-closed 判断。record 只承载低敏代码和引用，不承载工具实参或 payload 正文。</p>
     */
    private record ProposalEvidence(
            String commandType,
            String idempotencyKey,
            List<String> accepted,
            List<String> missing,
            List<String> rejected
    ) {
    }
}

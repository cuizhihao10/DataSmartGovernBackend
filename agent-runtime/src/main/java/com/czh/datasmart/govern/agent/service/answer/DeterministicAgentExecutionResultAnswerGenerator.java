/**
 * @Author : Cui
 * @Date: 2026/07/16 00:00
 * @Description DataSmart Govern Backend - DeterministicAgentExecutionResultAnswerGenerator.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.answer;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionAuditView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionResultView;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 不依赖大模型的二轮回答兜底实现。
 *
 * <p>当前环境暂不配置真实模型密钥，所以答复必须严格来自 Java 控制面已经记录的执行事实。该实现
 * 保证即使模型不可用，用户仍能得到明确的成功、失败或未完成结论；将来模型实现超时或失败时也可以
 * 回退到这里，避免 Agent 因推理服务故障丢失最终答复。</p>
 */
@Component
public class DeterministicAgentExecutionResultAnswerGenerator implements AgentExecutionResultAnswerGenerator {

    public static final String ANSWER_MODE = "DETERMINISTIC_FALLBACK";
    public static final String MODEL_PROVIDER_STATUS = "RESERVED_NOT_INVOKED";
    private static final String SYNC_TASK_DRAFT_SAVE = "sync.task.draft.save";
    private static final String SYNC_TASK_PUBLISH = "sync.task.publish";
    private static final String SYNC_TASK_RUN = "sync.task.run";
    private static final String SYNC_EXECUTION_STATUS = "sync.execution.status";
    private static final Set<String> SUCCESSFUL_EXECUTION_TERMINALS = Set.of("SUCCEEDED", "PARTIALLY_SUCCEEDED");

    @Override
    public AgentExecutionAssistantAnswer generate(
            String runState,
            int plannedCount,
            int succeededCount,
            int failedCount,
            List<AgentToolExecutionAuditView> toolAudits,
            List<AgentToolExecutionResultView> toolResults,
            List<String> nextActions) {
        if (failedCount == 0 && plannedCount > 0 && succeededCount == plannedCount) {
            if (hasTrustedSuccessfulTerminal(toolResults)) {
                return answer("本次同步执行已到达成功终态。你可以前往同步任务详情查看传输数据量、运行日志、"
                        + "对象级分片账本和最终结果。");
            }
            if (hasSucceededTool(SYNC_TASK_RUN, toolAudits, toolResults)) {
                return answer("同步任务已经完成草稿、预检查和发布，并已提交真实 worker 执行链路。"
                        + "你可以前往同步任务详情继续查看排队、传输进度、运行日志和最终数据量。");
            }
            if (hasSucceededTool(SYNC_TASK_PUBLISH, toolAudits, toolResults)) {
                return answer("同步任务已经通过预检查并发布成功，但本轮没有提交即时运行。"
                        + "定期任务将等待调度时间，其他模式需要继续确认实际运行状态。");
            }
            if (hasSucceededTool(SYNC_TASK_DRAFT_SAVE, toolAudits, toolResults)) {
                return answer("同步任务草稿已经保存，但尚未完成发布或真实运行。请继续完成预检查和后续任务生命周期节点。");
            }
            return answer("本轮只完成了数据源目录、连接或元数据等信息收集，尚未创建或运行同步任务。"
                    + "系统需要基于这些真实结果继续规划下一批工具，不能把信息收集成功当成数据迁移成功。");
        }

        if (failedCount > 0) {
            String failedTools = failedToolCodes(toolAudits, toolResults);
            String toolDetail = failedTools.isBlank() ? "" : "失败节点：" + failedTools + "。";
            return answer("本次计划执行未全部完成：成功 " + succeededCount + " 个，失败 " + failedCount
                    + " 个。" + toolDetail + "请查看节点错误详情，修复配置或权限问题后重新发起执行。");
        }

        String actionHint = nextActions == null || nextActions.isEmpty()
                ? "请查看 Run 状态并继续完成所需确认。"
                : "下一步：" + String.join("、", nextActions) + "。";
        return answer("本次计划当前状态为 " + Objects.toString(runState, "UNKNOWN") + "，已完成 "
                + succeededCount + "/" + plannedCount + " 个工具节点。" + actionHint);
    }

    private AgentExecutionAssistantAnswer answer(String content) {
        return new AgentExecutionAssistantAnswer(content, ANSWER_MODE, MODEL_PROVIDER_STATUS);
    }

    /**
     * 失败摘要最多展示三个工具编码，既能帮助定位，也避免把完整工具输出带入普通会话响应。
     */
    private boolean hasSucceededTool(
            String toolCode,
            List<AgentToolExecutionAuditView> toolAudits,
            List<AgentToolExecutionResultView> toolResults) {
        return auditStream(toolAudits, toolResults)
                .anyMatch(audit -> toolCode.equals(audit.toolCode()) && "SUCCEEDED".equals(audit.state()));
    }

    /**
     * 状态查询工具本身执行成功并不代表同步成功；只有工具明确返回 terminal=true，且业务执行状态属于
     * SUCCEEDED/PARTIALLY_SUCCEEDED，才把它视为可信同步终态。
     */
    private boolean hasTrustedSuccessfulTerminal(List<AgentToolExecutionResultView> toolResults) {
        if (toolResults == null) {
            return false;
        }
        return toolResults.stream()
                .filter(Objects::nonNull)
                .filter(result -> result.audit() != null)
                .filter(result -> SYNC_EXECUTION_STATUS.equals(result.audit().toolCode()))
                .filter(result -> "SUCCEEDED".equals(result.audit().state()))
                .map(AgentToolExecutionResultView::output)
                .filter(Objects::nonNull)
                .anyMatch(this::isSuccessfulTerminalOutput);
    }

    private boolean isSuccessfulTerminalOutput(Map<String, Object> output) {
        boolean terminal = Boolean.TRUE.equals(output.get("terminal"))
                || "true".equalsIgnoreCase(Objects.toString(output.get("terminal"), ""));
        String state = Objects.toString(output.get("executionState"), "").toUpperCase(Locale.ROOT);
        return terminal && SUCCESSFUL_EXECUTION_TERMINALS.contains(state);
    }

    private String failedToolCodes(
            List<AgentToolExecutionAuditView> toolAudits,
            List<AgentToolExecutionResultView> toolResults) {
        return auditStream(toolAudits, toolResults)
                .filter(audit -> "FAILED".equals(audit.state()))
                .map(audit -> audit.toolCode())
                .filter(Objects::nonNull)
                .distinct()
                .limit(3)
                .collect(Collectors.joining("、"));
    }

    private Stream<AgentToolExecutionAuditView> auditStream(
            List<AgentToolExecutionAuditView> toolAudits,
            List<AgentToolExecutionResultView> toolResults) {
        Stream<AgentToolExecutionAuditView> finalAudits = toolAudits == null
                ? Stream.empty()
                : toolAudits.stream().filter(Objects::nonNull);
        Stream<AgentToolExecutionAuditView> resultAudits = toolResults == null
                ? Stream.empty()
                : toolResults.stream()
                .filter(Objects::nonNull)
                .map(AgentToolExecutionResultView::audit)
                .filter(Objects::nonNull);
        return Stream.concat(finalAudits, resultAudits).distinct();
    }
}

/**
 * @Author : Cui
 * @Date: 2026/07/16 00:00
 * @Description DataSmart Govern Backend - DeterministicAgentExecutionResultAnswerGenerator.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.answer;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionResultView;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

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

    @Override
    public AgentExecutionAssistantAnswer generate(
            String runState,
            int plannedCount,
            int succeededCount,
            int failedCount,
            List<AgentToolExecutionResultView> toolResults,
            List<String> nextActions) {
        if (failedCount == 0 && plannedCount > 0 && succeededCount == plannedCount) {
            return answer("本次计划的 " + plannedCount
                    + " 个工具节点已全部执行成功，数据同步任务已经进入真实业务执行链路。"
                    + "你可以前往同步任务详情查看传输进度、运行日志和最终数据量。");
        }

        if (failedCount > 0) {
            String failedTools = failedToolCodes(toolResults);
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
    private String failedToolCodes(List<AgentToolExecutionResultView> toolResults) {
        if (toolResults == null) {
            return "";
        }
        return toolResults.stream()
                .filter(Objects::nonNull)
                .map(AgentToolExecutionResultView::audit)
                .filter(Objects::nonNull)
                .filter(audit -> "FAILED".equals(audit.state()))
                .map(audit -> audit.toolCode())
                .filter(Objects::nonNull)
                .distinct()
                .limit(3)
                .collect(Collectors.joining("、"));
    }
}

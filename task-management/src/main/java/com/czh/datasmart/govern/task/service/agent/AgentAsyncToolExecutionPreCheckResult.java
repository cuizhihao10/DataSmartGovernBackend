/**
 * @Author : Cui
 * @Date: 2026/06/01 21:18
 * @Description DataSmart Govern Backend - AgentAsyncToolExecutionPreCheckResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 异步工具 worker 执行前二次复核结果。
 *
 * <p>这个对象专门表达“worker 是否允许继续调用真实工具适配器”。它和 `AgentAsyncToolExecutionResult`
 * 不是同一层语义：后者描述工具已经执行后的业务结果，当前对象描述工具尚未执行前的安全决策。
 * 这样可以避免把 pre-check、真实执行、状态回写三类逻辑混在一起。</p>
 *
 * @param allowed true 表示允许进入白名单工具适配器；false 表示必须在副作用前阻断
 * @param errorCode 阻断时写回 agent-runtime 的稳定错误码，便于前端和审计台分类展示
 * @param message 人类可读的复核说明，成功时解释通过原因，失败时解释阻断原因
 * @param validationMessages 复核过程中产生的学习型说明，适合调试、审计和后续运营台展示
 * @param diagnosticOutput 低敏诊断摘要，只能保存 ID、状态、字段名、计数等信息，不能保存工具参数原值
 */
public record AgentAsyncToolExecutionPreCheckResult(
        boolean allowed,
        String errorCode,
        String message,
        List<String> validationMessages,
        Map<String, Object> diagnosticOutput
) {

    public AgentAsyncToolExecutionPreCheckResult {
        validationMessages = validationMessages == null ? List.of() : List.copyOf(validationMessages);
        diagnosticOutput = diagnosticOutput == null ? Map.of() : new LinkedHashMap<>(diagnosticOutput);
    }

    /**
     * 构造允许执行的结果。
     *
     * <p>成功结果不需要 errorCode，但仍保留 validationMessages，方便调用方把“通过了哪些安全门”
     * 继续写入日志、诊断或未来的 worker 指标。</p>
     */
    public static AgentAsyncToolExecutionPreCheckResult allowed(List<String> validationMessages) {
        return new AgentAsyncToolExecutionPreCheckResult(
                true,
                null,
                "Agent 异步工具执行前复核通过，可以进入白名单工具适配器。",
                validationMessages,
                Map.of()
        );
    }

    /**
     * 构造阻断执行的结果。
     *
     * <p>阻断不等于系统异常。很多阻断是商业化 Agent 平台应该主动做出的 fail-closed 决策，
     * 例如审批证据缺失、工具状态已经终结、没有白名单适配器或任务状态不再处于 RUNNING。</p>
     */
    public static AgentAsyncToolExecutionPreCheckResult rejected(String errorCode,
                                                                 String message,
                                                                 List<String> validationMessages,
                                                                 Map<String, Object> diagnosticOutput) {
        return new AgentAsyncToolExecutionPreCheckResult(
                false,
                errorCode == null || errorCode.isBlank()
                        ? "AGENT_ASYNC_TOOL_PRECHECK_REJECTED"
                        : errorCode.trim(),
                message,
                validationMessages,
                diagnosticOutput
        );
    }
}

/**
 * @Author : Cui
 * @Date: 2026/08/13 00:20
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryRetryReceipt.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

/**
 * data-sync 接受失败对象重排队后的强类型低敏回执。
 *
 * <p>该回执证明的是“控制面已经把失败对象放回 worker 队列”，不表示数据搬运已经成功。Autopilot
 * 后续会把这里的真实 {@code taskId/executionId} 交给 Python 的 PRECHECK_AGENT 与 MONITOR_AGENT 做
 * 只读复核。将 Map 收紧为 record 可以避免字段缺失时回退到旧事件 ID，从而把损坏响应误报为成功。</p>
 *
 * @param taskId data-sync 权威返回的同步任务标识
 * @param executionId 被重新排队的父执行标识
 * @param retryObjectCount 本次重置为待执行状态的失败对象数量
 * @param executionState 重排队后的执行状态，通常为 QUEUED
 * @param taskState 重排队后的任务状态，通常为 RETRYING
 */
public record AgentAutopilotRecoveryRetryReceipt(
        Long taskId,
        Long executionId,
        int retryObjectCount,
        String executionState,
        String taskState) {

    /**
     * 判断回执是否完整且仍绑定原始恢复触发器范围。
     *
     * <p>输入是 Java 已验证的事件；输出只表达合同是否匹配，不执行网络请求或业务写入。任务、执行、状态
     * 任一不符都说明不能启动后置 Specialist 复核，调用方应把它当技术合同失败交给 Kafka 重试。</p>
     *
     * @param event 已通过 session、run、授权和 deadline 校验的恢复事件
     * @return 只有同一 task/execution 且状态明确表示已重排队时返回 true
     */
    public boolean matchesRequeuedScope(AgentAutopilotRecoveryTriggerEvent event) {
        return event != null
                && taskId != null && taskId > 0
                && executionId != null && executionId > 0
                && taskId.equals(event.syncTaskId())
                && executionId.equals(event.currentExecutionId())
                && retryObjectCount > 0
                && "QUEUED".equals(normalize(executionState))
                && "RETRYING".equals(normalize(taskState));
    }

    /** 将跨服务状态文本规范化为固定比较形式，不保留原始异常或响应正文。 */
    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase().replace('-', '_');
    }
}

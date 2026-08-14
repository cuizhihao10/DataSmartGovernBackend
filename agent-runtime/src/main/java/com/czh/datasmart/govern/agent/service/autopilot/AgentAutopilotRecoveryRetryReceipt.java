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
 * @param taskState 同一 execution 重排队后应为 RETRYING；checkpoint replay 创建新 execution 后应为 QUEUED
 */
public record AgentAutopilotRecoveryRetryReceipt(
        Long taskId,
        Long executionId,
        int retryObjectCount,
        String executionState,
        String taskState) {

    /**
     * 判断回执是否完整，且是否仍绑定原始恢复触发器中的当前 execution。
     *
     * <p>这是普通“同一 execution 重排队”场景使用的便捷入口。它把触发事件中的
     * {@code currentExecutionId} 同时作为期望 execution，因此任务状态必须是 {@code RETRYING}。
     * 输入是 Java 已验证的事件；输出只表达合同是否匹配，不执行网络请求或业务写入。任务、执行、状态
     * 任一不符都说明不能启动后置 Specialist 复核，调用方应把它当技术合同失败交给 Kafka 重试。</p>
     *
     * @param event 已通过 session、run、授权和 deadline 校验的恢复事件
     * @return 只有同一 task/execution 且状态明确表示已重排队时返回 true
     */
    public boolean matchesRequeuedScope(AgentAutopilotRecoveryTriggerEvent event) {
        return matchesRequeuedScope(event, event == null ? null : event.currentExecutionId());
    }

    /**
     * 将回执与触发范围、恢复后的权威 execution 及其状态机分支一起校验。
     *
     * <p>初学者需要区分这里的两种重排队含义：当 {@code expectedExecutionId} 与触发事件的
     * {@code currentExecutionId} 相同时，data-sync 只是把原 execution 放回队列，所以任务会进入
     * {@code RETRYING}；当两个标识不同时，表示 checkpoint replay 已经创建了新的 execution，旧任务
     * 不应再被当作原 execution 重试，新的任务状态必须是 {@code QUEUED}。两种情况的 execution 状态
     * 都必须为 {@code QUEUED}，因为 worker 尚未重新开始处理。</p>
     *
     * <p>该方法先验证触发 execution 和期望 execution 都是正数，再验证任务标识、回执 execution、
     * 重排数量与状态。任何字段缺失、越界或不一致都会返回 {@code false}，不会猜测状态或回退到事件中的
     * 旧 execution，从而保持跨服务回执的失败关闭语义。</p>
     *
     * @param event 原始恢复事件，继续提供任务范围
     * @param expectedExecutionId data-sync case 或修复回执确认的恢复后 execution
     * @return 任务范围、预期 execution 和对应状态分支全部匹配时返回 {@code true}
     */
    public boolean matchesRequeuedScope(
            AgentAutopilotRecoveryTriggerEvent event,
            Long expectedExecutionId) {
        if (event == null
                || event.currentExecutionId() == null
                || event.currentExecutionId() <= 0
                || expectedExecutionId == null
                || expectedExecutionId <= 0) {
            return false;
        }
        String requiredTaskState = expectedExecutionId.equals(event.currentExecutionId())
                ? "RETRYING"
                : "QUEUED";
        return event != null
                && taskId != null && taskId > 0
                && executionId != null && executionId > 0
                && taskId.equals(event.syncTaskId())
                && executionId.equals(expectedExecutionId)
                && retryObjectCount > 0
                && "QUEUED".equals(normalize(executionState))
                && requiredTaskState.equals(normalize(taskState));
    }

    /** 将跨服务状态文本规范化为固定比较形式，不保留原始异常或响应正文。 */
    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase().replace('-', '_');
    }
}

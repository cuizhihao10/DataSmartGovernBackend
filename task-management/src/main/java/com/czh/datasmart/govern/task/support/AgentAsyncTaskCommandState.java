/**
 * @Author : Cui
 * @Date: 2026/05/31 16:42
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandState.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.support;

/**
 * Agent 异步工具命令在 task-management Inbox 中的消费状态。
 *
 * <p>Inbox 表解决的是“消息有没有被任务中心可靠接收并转换”，不是“任务有没有执行完成”。
 * 两类状态必须分开建模：</p>
 *
 * <p>1. Inbox 状态描述 command 消费过程，例如正在创建任务、已经创建任务；</p>
 * <p>2. TaskStatus 描述真实任务生命周期，例如 PENDING、RUNNING、SUCCESS、FAILED；</p>
 * <p>3. 后续 Agent Runtime 需要展示进度时，应通过 taskId 继续查询任务状态，而不是把 Inbox 当成任务状态机。</p>
 */
public final class AgentAsyncTaskCommandState {

    /**
     * 已占用 commandId / idempotencyKey，正在把命令转换为任务。
     *
     * <p>当前 Inbox 插入和任务创建在同一个数据库事务中完成。如果后续步骤抛异常，
     * PROCESSING 记录也会随事务回滚，使 Kafka 可以安全重试。</p>
     */
    public static final String PROCESSING = "PROCESSING";

    /**
     * 命令已经成功转换为 task 主表记录。
     *
     * <p>重复消息再次到达时，消费者会直接返回已经创建的 taskId，不会重复创建任务。</p>
     */
    public static final String TASK_CREATED = "TASK_CREATED";

    private AgentAsyncTaskCommandState() {
    }
}

/**
 * @Author : Cui
 * @Date: 2026/06/21 00:00
 * @Description DataSmart Govern Backend - DataSyncWorkerOutboxDeadLetterAction.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

/**
 * DataSync worker outbox 死信人工处置动作。
 *
 * <p>该枚举只表达运维控制面的“处置意图”，不直接表达数据库状态。
 * 例如 {@link #REPLAY} 会把 {@code DEAD_LETTER} 重新放回 {@code DEFERRED}，
 * 而 {@link #CLOSE} 会把 {@code DEAD_LETTER} 推进到 {@code CLOSED}。
 * 把动作和目标状态拆开，可以让接口更贴近人的操作语言，也方便后续扩展
 * ESCALATE、EXPORT_INCIDENT、OPEN_TICKET 等更完整的商业化运维动作。</p>
 */
public enum DataSyncWorkerOutboxDeadLetterAction {

    /**
     * 受控重放。
     *
     * <p>适用于下游 datasource-management 已恢复、参数和模板经人工确认仍然有效的场景。
     * 重放不会在本接口里直接调用下游，而是把命令放回 DEFERRED 并设置 nextRetryAt，
     * 让统一 dispatcher 按原有幂等投递链路继续处理。</p>
     */
    REPLAY,

    /**
     * 人工关闭。
     *
     * <p>适用于命令已经不应再执行的场景，例如业务方撤销任务、模板已废弃、数据源已下线、
     * 或事故处置后决定不再自动补偿。关闭后的命令进入 CLOSED 终态，普通 dispatcher 不会再领取。</p>
     */
    CLOSE
}

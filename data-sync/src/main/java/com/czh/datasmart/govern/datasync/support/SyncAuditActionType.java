/**
 * @Author : Cui
 * @Date: 2026/05/07 21:38
 * @Description DataSmart Govern Backend - SyncAuditActionType.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.support;

/**
 * 数据同步审计动作类型。
 *
 * <p>审计动作使用枚举集中维护，可以避免每个 Service 方法手写字符串导致统计口径不一致。
 */
public enum SyncAuditActionType {
    CREATE_TEMPLATE,
    VALIDATE_TEMPLATE,
    CREATE_TASK,
    /**
     * 普通运行或执行器生命周期回调。
     *
     * <p>该动作覆盖手动入队、执行器认领、心跳、退避、开始执行、完成执行等“运行链路”事件。
     */
    RUN_TASK,
    /**
     * 普通用户或项目负责人主动暂停同步任务。
     *
     * <p>暂停不是终态，后续可通过 RESUME_TASK 恢复；运行中的任务采用协作式暂停语义。
     */
    PAUSE_TASK,
    /**
     * 从 PAUSED 状态恢复同步任务，并创建新的待执行 execution。
     */
    RESUME_TASK,
    /**
     * 从 FAILED 或 PARTIALLY_SUCCEEDED 状态发起普通重试。
     *
     * <p>AWAITING_OPERATOR_ACTION 的重跑不使用该动作，而使用 RERUN_ATTENTION_TASK，避免绕过运营介入闭环。
     */
    RETRY_TASK,
    /**
     * 普通生命周期取消动作。
     *
     * <p>该动作面向非人工介入任务；人工介入任务关闭到取消态时使用 CANCEL_ATTENTION_TASK。
     */
    CANCEL_TASK,
    /**
     * 从历史 execution 或 checkpoint 发起回放。
     *
     * <p>回放常用于失败恢复、下游重建或修复错误写入，必须和普通 retry 区分审计口径。
     */
    REPLAY_TASK,
    /**
     * 按窗口或分区发起历史补数。
     *
     * <p>补数通常是运维或项目负责人操作，可能影响大量历史数据，因此需要独立审计动作。
     */
    BACKFILL_TASK,
    /**
     * worker 已读取 replay/backfill 恢复计划。
     *
     * <p>该动作证明控制面创建的恢复计划已经被具体执行器接收，审计 payload 只记录 planId、recoveryType、
     * executorId 和状态，不记录补数窗口原文、SQL、连接配置、样本数据或 checkpoint 内容。
     */
    CLAIM_RECOVERY_PLAN,
    /**
     * worker 已把恢复计划作为执行输入消费。
     *
     * <p>该动作证明 worker 已经完成恢复计划加载，后续会走普通 checkpoint/complete/fail 回调链路。
     * 它与 CLAIM_RECOVERY_PLAN 分开，是为了事故复盘时能区分“worker 看过计划”和“worker 真正开始按计划执行”。
     */
    CONSUME_RECOVERY_PLAN,
    CREATE_EXECUTION,
    UPDATE_CHECKPOINT,
    RECORD_ERROR_SAMPLE,
    ACKNOWLEDGE_ATTENTION,
    RESOLVE_ATTENTION,
    RERUN_ATTENTION_TASK,
    CANCEL_ATTENTION_TASK,
    ARCHIVE_ATTENTION_TASK,
    CREATE_INCIDENT,
    ACKNOWLEDGE_INCIDENT,
    ASSIGN_INCIDENT,
    RESOLVE_INCIDENT,
    CLOSE_INCIDENT
}

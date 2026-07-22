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
     * 编辑同步任务定义。
     *
     * <p>该动作只覆盖名称、说明、负责人、分组、调度配置、运行模式等任务定义字段；
     * 不创建 execution、不读取源端数据、不写目标端数据。它和 UPDATE_TASK_GROUP 分开，是因为完整编辑可能导致任务退回 DRAFT，
     * 并关闭 scheduleEnabled，影响后续是否能被调度器扫描。</p>
     */
    UPDATE_TASK,
    /**
     * 发布同步任务定义。
     *
     * <p>发布会重新执行预检、审批判断和调度配置解析，并把任务推进到 CONFIGURED、SCHEDULED 或 PENDING_APPROVAL。
     * 该动作是“编辑态”和“可执行态”之间的安全闸门，需要独立审计。</p>
     */
    PUBLISH_TASK,
    /**
     * 导出同步任务定义。
     *
     * <p>导出动作只包含低敏任务定义字段和模板引用，不包含连接串、密码、完整 SQL、样本数据或 worker 内部计划。
     * 该动作独立审计，是为了后续回答“谁在什么时候批量导出了哪些任务定义”。</p>
     */
    EXPORT_TASKS,
    /**
     * 导入同步任务定义。
     *
     * <p>导入动作可能批量创建 DRAFT 任务，或在明确 runImmediately=true 时发布并创建执行记录。
     * 因此它必须与 CREATE_TASK 区分，方便审计批量来源和文件导入批次。</p>
     */
    IMPORT_TASKS,
    /**
     * 按选中任务 ID 批量导出同步任务定义。
     *
     * <p>该动作与普通 EXPORT_TASKS 的区别是：普通导出通常来自筛选条件，批量导出来自用户或 Agent 明确选中的 taskId 列表。
     * 生产排查时需要知道“导出了一个视图范围”还是“导出了这批指定任务”，因此单独审计。</p>
     */
    BATCH_EXPORT_TASKS,
    /**
     * 批量手工调度同步任务。
     *
     * <p>每个任务仍会单独记录 MANUAL_DISPATCH_TASK；该批量动作记录本次批处理汇总，
     * 便于运营台回答“这次批量操作一共成功/失败/跳过多少条”。</p>
     */
    BATCH_MANUAL_DISPATCH_TASKS,
    /**
     * 批量下线同步任务。
     *
     * <p>批量下线会关闭每个任务的自动调度，是批量删除到回收站之前的治理前置步骤。</p>
     */
    BATCH_OFFLINE_TASKS,
    /**
     * 批量删除同步任务到回收站。
     *
     * <p>每条任务仍必须满足 OFFLINE -> RECYCLED 状态流转，批量入口不会绕过删除前置条件。</p>
     */
    BATCH_RECYCLE_TASKS,
    /**
     * 批量彻底删除回收站同步任务。
     *
     * <p>当前彻底删除仍是逻辑 DELETED，保留 execution、checkpoint、错误样本和审计证据。</p>
     */
    BATCH_HARD_DELETE_TASKS,
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
     * 针对 OBJECT_LIST 父 execution 内部 FAILED 对象发起选择性重试。
     *
     * <p>它和 {@link #RETRY_TASK} 的区别是：RETRY_TASK 是任务级整单重跑，通常会创建新的 execution；
     * RETRY_OBJECT_EXECUTIONS 是对象级恢复，会复用同一个父 execution 的对象账本，只重置失败对象，
     * 让已成功对象在 fan-out 重入时被跳过。</p>
     */
    RETRY_OBJECT_EXECUTIONS,
    /**
     * 普通生命周期取消动作。
     *
     * <p>该动作面向非人工介入任务；人工介入任务关闭到取消态时使用 CANCEL_ATTENTION_TASK。
     */
    CANCEL_TASK,
    /**
     * 手工结束同步任务。
     *
     * <p>该动作和 CANCEL_TASK 分开统计：取消偏向撤销执行意图，手工结束偏向操作者主动终止当前运行。
     * 生产事故复盘时，这两个动作的责任边界和根因分析不同，因此需要独立审计口径。</p>
     */
    MANUAL_TERMINATE_TASK,
    /**
     * 下线同步任务。
     *
     * <p>下线会关闭后续自动调度，是删除进回收站前的强制前置步骤。</p>
     */
    OFFLINE_TASK,
    /**
     * 将同步任务移动到回收站。
     */
    RECYCLE_TASK,
    /**
     * 从回收站彻底删除同步任务。
     *
     * <p>当前实现是逻辑删除，审计动作仍按“彻底删除”记录用户意图。</p>
     */
    HARD_DELETE_TASK,
    /**
     * 克隆同步任务。
     *
     * <p>克隆通常用于复用成熟配置创建新任务，必须记录来源 taskId 和新 taskId，方便后续定位配置来源。</p>
     */
    CLONE_TASK,
    /**
     * 手工调度同步任务。
     *
     * <p>该动作和普通 RUN_TASK 分开，是为了让运营台区分“用户点击立即执行一次”和系统内部运行链路事件。</p>
     */
    MANUAL_DISPATCH_TASK,
    /**
     * 调整同步任务分组。
     *
     * <p>分组不会改变执行事实，但会改变运营视图、导入导出范围、Agent 批量编排范围和后续告警聚合范围，
     * 因此仍需要独立审计。事故复盘时可以据此回答“这个任务为什么被归到某个业务域或迁移批次”。</p>
     */
    UPDATE_TASK_GROUP,
    /**
     * 创建同步任务分组。
     *
     * <p>分组会影响任务菜单归属、批量运营范围、导入导出筛选和 Agent 工具选择范围，
     * 因此它虽然不直接搬运数据，也需要独立审计。</p>
     */
    CREATE_TASK_GROUP,
    /**
     * 删除同步任务分组。
     *
     * <p>删除分组不会删除任务，而是把任务迁回默认分组。独立审计该动作可以在事故复盘时回答：
     * 哪些任务为什么从某个业务分组回到了 DEFAULT。</p>
     */
    DELETE_TASK_GROUP,
    /**
     * 发现同步任务配置阶段的低敏元数据。
     *
     * <p>元数据发现只返回 schema/table/field 摘要，不返回样本数据或连接凭据；但表结构仍属于企业资产信息，
     * 因此需要保留调用审计入口。</p>
     */
    DISCOVER_TASK_METADATA,
    /**
     * 生成字段映射建议。
     *
     * <p>字段映射建议不会直接写入任务，但它会影响用户后续配置决策。独立动作便于统计 Agent/用户
     * 自动配置辅助能力的使用情况。</p>
     */
    SUGGEST_FIELD_MAPPINGS,
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
    /**
     * 基于结构化错误样本发起脏数据修复重放。
     *
     * <p>该动作区别于普通 REPLAY_TASK：
     * REPLAY_TASK 主要按 execution/checkpoint 回放整段执行上下文；
     * REPLAY_DIRTY_RECORDS 则明确表示“操作者已经处理某批 dirty record，并只希望按错误样本 selector 重放”。</p>
     */
    REPLAY_DIRTY_RECORDS,
    /** 经用户确认后隔离精确主键脏记录，重试时跳过但不删除源数据。 */
    QUARANTINE_DIRTY_RECORDS,
    PUBLISH_RECOVERY_CASE,
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

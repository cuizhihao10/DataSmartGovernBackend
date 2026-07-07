/**
 * @Author : Cui
 * @Date: 2026/05/07 21:26
 * @Description DataSmart Govern Backend - SyncTaskState.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.support;

/**
 * 同步任务主状态。
 *
 * <p>这里的状态参考 data-sync PRD 中的状态机，不直接复用 task-management 的 TaskStatus。
 * 原因是 data-sync 的任务不仅是通用调度任务，还包含审批、配置完整性、checkpoint、部分成功、回放补数等数据移动专属语义。
 *
 * <p>后续接入 task-management 时，data-sync 可以把自身任务转化为平台任务，但不能把领域状态完全丢给通用任务中心。
 */
public enum SyncTaskState {
    /**
     * 编辑中。
     *
     * <p>DRAFT 表示任务定义还没有被发布到可执行生命周期。它适合导入、克隆、Agent 草拟或用户尚未完成配置的场景：
     * 任务可以被查看、编辑、克隆和删除进回收站，但不能被后台调度器扫描，也不能被 worker 认领执行。
     * 这样可以避免“用户只是保存了半成品配置，系统却已经开始搬运数据”的生产事故。</p>
     */
    DRAFT,
    /**
     * 已配置，等待人工触发。
     *
     * <p>CONFIGURED 通常用于一次性全量、SQL 自定义传输、补数前模板化任务等非定时场景。
     * 它代表配置已通过基本校验，但不会被 task scheduler 自动触发；用户点击手工调度或 Agent 在权限允许后调用手工调度入口，
     * 才会生成一条新的 execution。</p>
     */
    CONFIGURED,
    /**
     * 待审批。
     *
     * <p>高风险任务，例如全库迁移、自定义 SQL、覆盖式写入或大规模导出，在审批通过前必须停留在该状态。
     * 即使任务已经有 scheduleConfig，也不能进入 SCHEDULED，否则后台调度器会绕过审批自动执行。</p>
     */
    PENDING_APPROVAL,
    /**
     * 等待调度。
     *
     * <p>这是定期全量、定期批量等 recurring task 的长期主状态。单次 execution 成功或失败后，
     * 执行历史记录会保存 SUCCEEDED/FAILED，而任务主状态应回到 SCHEDULED 等待下一次 nextFireTime。
     * 只有用户下线、暂停或手工结束任务，才应离开这个长期计划状态。</p>
     */
    SCHEDULED,
    QUEUED,
    RUNNING,
    PAUSED,
    RETRYING,
    PARTIALLY_SUCCEEDED,
    SUCCEEDED,
    FAILED,
    /**
     * 需要人工介入。
     *
     * <p>这个状态用于区别普通 FAILED：
     * FAILED 只说明一次执行失败，而 AWAITING_OPERATOR_ACTION 表示系统已经多次退避、恢复或重试仍无法安全推进，
     * 继续自动执行可能浪费资源或扩大故障影响，因此需要运营人员检查数据源连通性、目标端容量、字段映射、租户配额或连接器版本。
     */
    AWAITING_OPERATOR_ACTION,
    /**
     * 手工结束。
     *
     * <p>MANUALLY_TERMINATED 表示用户或运营人员明确终止任务继续运行。它和 CANCELLED 的区别在于：
     * CANCELLED 更像撤销一次执行意图，MANUALLY_TERMINATED 更像人工把任务从运行链路中结束并保留“人为终止”的历史证据。
     * 该状态默认不再参与自动调度，若需要重新使用，应通过克隆或后续“重新发布/恢复”能力进入新生命周期。</p>
     */
    MANUALLY_TERMINATED,
    /**
     * 已下线。
     *
     * <p>OFFLINE 是删除前置状态。生产系统不应允许直接删除仍可调度或仍可执行的任务，
     * 因为那会让 execution、checkpoint、错误样本和审计证据失去清晰归属。
     * 下线会关闭 scheduleEnabled 并清空 nextFireTime，使后台调度器无法再触发该任务。</p>
     */
    OFFLINE,
    /**
     * 回收站。
     *
     * <p>RECYCLED 表示任务已经从正常列表移入回收站。它仍可查看详情和克隆，
     * 便于误删恢复配置思路，但不能直接运行、调度、暂停或重试。</p>
     */
    RECYCLED,
    CANCELLED,
    ARCHIVED,
    /**
     * 已彻底删除。
     *
     * <p>当前实现采用逻辑彻底删除，而不是物理删除行。原因是 data-sync 的任务会关联 execution、checkpoint、
     * error sample、object ledger 和审计记录；直接物理删除会破坏事故复盘和合规留痕。
     * 对外语义上 DELETED 不再出现在普通列表和详情中，后续可由数据保留策略异步清理历史证据。</p>
     */
    DELETED
}

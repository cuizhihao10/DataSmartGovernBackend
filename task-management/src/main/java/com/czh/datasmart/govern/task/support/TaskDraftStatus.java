/**
 * @Author : Cui
 * @Date: 2026/05/24 23:58
 * @Description DataSmart Govern Backend - TaskDraftStatus.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.support;

/**
 * 任务草稿状态常量。
 *
 * <p>草稿状态和任务主状态必须分开建模。`TaskStatus.PENDING` 表示真实任务已经进入可调度队列，
 * 执行器可以认领；而这里的 `DRAFT/PENDING_APPROVAL/APPROVED` 都还不是可执行任务。
 * 这种隔离是 Agent 场景的安全边界：Agent 可以生成草稿，但不能直接制造生产队列任务。</p>
 */
public final class TaskDraftStatus {

    /**
     * 草稿编辑中。
     */
    public static final String DRAFT = "DRAFT";

    /**
     * 已提交审批，等待项目负责人、运营或平台管理员确认。
     */
    public static final String PENDING_APPROVAL = "PENDING_APPROVAL";

    /**
     * 审批通过，可被转换为真实任务。
     */
    public static final String APPROVED = "APPROVED";

    /**
     * 正在转换真实任务。
     *
     * <p>该状态不是给用户长期停留的业务状态，而是并发安全门闩。
     * 当草稿从 APPROVED 转真实任务时，服务端会先用数据库条件更新把状态抢占为 CONVERTING。
     * 只有抢占成功的事务才允许创建真实 task；其他并发请求看到 CONVERTING 时不能再创建任务。
     * 这样可以避免用户重复点击、Agent 重试、网关超时重放导致同一草稿生成多条真实任务。</p>
     */
    public static final String CONVERTING = "CONVERTING";

    /**
     * 审批拒绝，需要修改后重新提交，或直接归档。
     */
    public static final String REJECTED = "REJECTED";

    /**
     * 已转换为真实任务。
     */
    public static final String CONVERTED = "CONVERTED";

    private TaskDraftStatus() {
    }
}

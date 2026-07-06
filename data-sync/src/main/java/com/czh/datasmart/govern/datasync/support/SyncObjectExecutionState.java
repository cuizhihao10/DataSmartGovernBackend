/**
 * @Author : Cui
 * @Date: 2026/07/06 21:49
 * @Description DataSmart Govern Backend - SyncObjectExecutionState.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.support;

/**
 * 多对象同步中“单个对象/分片”的执行状态。
 *
 * <p>它和 {@link SyncExecutionState} 的关系需要分层理解：</p>
 * <p>1. {@code SyncExecutionState} 描述父级 execution，也就是用户点击“运行一次同步任务”后形成的整次运行；</p>
 * <p>2. {@code SyncObjectExecutionState} 描述父 execution 内部的一个可恢复工作单元，例如第 1 张表、第 2 张表，
 * 未来也可以扩展为第 1 张表的第 3 个 splitPk 分片；</p>
 * <p>3. 父 execution 的最终状态由所有对象/分片汇总得到：全部成功为 SUCCEEDED，部分成功部分失败为 PARTIALLY_SUCCEEDED，
 * 全部失败为 FAILED。</p>
 *
 * <p>为什么要单独建状态枚举：DataX 的 Job 会拆成多个 Task/Channel，失败 Task 可以单独重试，成功 Task
 * 不应该被无意义重跑。本项目当前还没有完整 DataX 引擎，但必须先在控制面建立相同的“子执行事实账本”，
 * 否则无法解释部分成功、失败重传和断点恢复。</p>
 */
public enum SyncObjectExecutionState {

    /**
     * 已创建对象级执行记录，但尚未开始真实调用 Reader/Writer。
     */
    PENDING,

    /**
     * 当前对象正在被某个 data-sync worker 触发执行。
     */
    RUNNING,

    /**
     * 当前对象上一轮尝试失败，但错误被判定仍可自动重试，且尚未超过最大尝试次数。
     */
    RETRYING,

    /**
     * 当前对象已经成功完成；后续同一个父 execution 重入时应跳过它，避免重复写入目标端。
     */
    SUCCEEDED,

    /**
     * 当前对象已经失败且不再自动重试；父 execution 结束时会根据成功对象数量决定 FAILED 或 PARTIALLY_SUCCEEDED。
     */
    FAILED,

    /**
     * 当前对象被策略显式跳过。当前版本暂未主动使用，预留给后续 include/exclude、灰度迁移或人工跳过场景。
     */
    SKIPPED
}

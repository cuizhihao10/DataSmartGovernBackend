/**
 * @Author : Cui
 * @Date: 2026/07/07 18:43
 * @Description DataSmart Govern Backend - SyncTaskGroupSummary.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同步任务分组汇总视图。
 *
 * <p>该 DTO 面向任务分组列表、运营台分组卡片和 Agent 查询结果。
 * 它不是持久化实体，而是由 data_sync_task 聚合得到的只读视图。这样当前版本不必引入独立分组表，
 * 也能让用户快速看到每个分组下有多少任务、多少等待调度、多少正在执行、多少失败或进入回收站。</p>
 */
@Data
public class SyncTaskGroupSummary {

    /**
     * 租户 ID。
     *
     * <p>分组汇总仍然必须落在租户边界内。即使 groupCode 相同，不同租户也代表完全不同的业务分组。</p>
     */
    private Long tenantId;

    /**
     * 项目 ID。
     *
     * <p>同一个租户下不同项目可以复用相同 groupCode。项目维度会参与 permission-admin 的 PROJECT 数据范围过滤。</p>
     */
    private Long projectId;

    /**
     * 工作空间 ID。
     */
    private Long workspaceId;

    /**
     * 稳定分组编码。
     */
    private String groupCode;

    /**
     * 分组展示名称。
     */
    private String groupName;

    /**
     * 分组内任务总数。
     */
    private Long taskCount;

    /**
     * 分组内仍处于日常运营范围的任务数量。
     *
     * <p>当前把 OFFLINE、RECYCLED、DELETED、ARCHIVED 排除在活跃任务外，
     * 用于帮助用户快速判断这个分组是否仍在实际参与调度或运行。</p>
     */
    private Long activeTaskCount;

    /**
     * 等待调度的周期任务数量。
     */
    private Long scheduledTaskCount;

    /**
     * 正在排队、运行或重试的任务数量。
     */
    private Long runningTaskCount;

    /**
     * 失败、部分成功或等待人工介入的任务数量。
     */
    private Long failedTaskCount;

    /**
     * 已进入回收站的任务数量。
     */
    private Long recycledTaskCount;

    /**
     * 分组内任务最近更新时间。
     *
     * <p>前端可据此把最近有变化的分组排在前面，Agent 也可以优先关注最近失败或刚被调整的分组。</p>
     */
    private LocalDateTime lastUpdateTime;
}

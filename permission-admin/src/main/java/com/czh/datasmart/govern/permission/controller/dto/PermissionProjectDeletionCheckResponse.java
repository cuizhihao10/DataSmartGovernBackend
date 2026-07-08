/**
 * @Author : Cui
 * @Date: 2026/07/08 23:58
 * @Description DataSmart Govern Backend - PermissionProjectDeletionCheckResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import java.util.List;

/**
 * 项目删除/归档可行性检查结果。
 *
 * <p>项目是数据源、同步任务、质量规则和 Agent 会话的归属边界。商业化系统不能像 demo 一样直接物理删除项目，
 * 否则历史执行、审计、账单和故障复盘都会失去上下文。因此删除前必须先做占用检查：
 * 有活动数据源、启用模板或非归档任务时，前端应引导用户先下线/归档对应业务资源。</p>
 */
public record PermissionProjectDeletionCheckResponse(
        /**
         * 被检查的项目 ID。
         */
        Long projectId,

        /**
         * 项目所属租户。
         */
        Long tenantId,

        /**
         * 是否允许归档式删除。
         */
        boolean deletable,

        /**
         * 活动数据源数量。
         */
        long activeDatasourceCount,

        /**
         * 启用中的数据同步模板数量。
         */
        long enabledSyncTemplateCount,

        /**
         * 非归档同步任务数量。
         */
        long activeSyncTaskCount,

        /**
         * 阻断项列表。为空表示可以执行归档式删除。
         */
        List<PermissionProjectDeletionBlocker> blockers,

        /**
         * 面向前端和运营人员的总体说明。
         */
        String message) {
}

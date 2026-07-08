/**
 * @Author : Cui
 * @Date: 2026/07/08 23:58
 * @Description DataSmart Govern Backend - PermissionProjectUpdateRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import jakarta.validation.constraints.Size;

/**
 * 项目基础信息更新请求。
 *
 * <p>该请求只承载用户真正需要维护的项目业务信息，不包含 tenantId、workspaceId、applicationId
 * 这类平台内部上下文字段。tenantId 由已存在的项目主数据确定，applicationId 在创建项目时已经绑定，
 * workspaceId 已经从用户可见层级退场，因此都不应该在普通编辑页面中再次暴露。</p>
 *
 * <p>允许更新 projectCode 是为了支持导入、交付和命名规范调整场景；但服务层仍会做同租户唯一性校验，
 * 防止两个项目使用同一个稳定编码导致审计、导入导出和 Agent 工具参数歧义。</p>
 */
public record PermissionProjectUpdateRequest(
        /**
         * 项目稳定编码。
         *
         * <p>为空表示不修改。传值时会被统一规范为大写，并校验只能包含大写字母、数字、下划线和短横线。</p>
         */
        @Size(max = 64, message = "projectCode 长度不能超过 64")
        String projectCode,

        /**
         * 项目展示名称。
         *
         * <p>为空表示不修改。项目名称会展示在项目切换器、数据源列表、同步任务列表和审计视图中。</p>
         */
        @Size(max = 128, message = "projectName 长度不能超过 128")
        String projectName,

        /**
         * 项目类型。
         *
         * <p>为空表示不修改。当前主要用于区分 DATA_GOVERNANCE、PLATFORM_ADMINISTRATION 等产品语义。</p>
         */
        @Size(max = 64, message = "projectType 长度不能超过 64")
        String projectType,

        /**
         * 默认负责人 actorId。
         *
         * <p>为空表示不修改。负责人变更只更新项目主数据字段，不自动变更成员授权；
         * 真正的成员增删改仍应走项目成员管理接口，避免“改负责人”隐式扩大权限。</p>
         */
        Long ownerActorId,

        /**
         * 项目低敏描述。
         *
         * <p>允许传空字符串清空描述。禁止保存密码、Token、连接串、完整 SQL 或样本数据。</p>
         */
        @Size(max = 1000, message = "description 长度不能超过 1000")
        String description,

        /**
         * 更新原因。
         *
         * <p>写入审计摘要，便于后续排查“为什么修改项目名称/编码/负责人”。</p>
         */
        @Size(max = 500, message = "reason 长度不能超过 500")
        String reason) {
}

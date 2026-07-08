/**
 * @Author : Cui
 * @Date: 2026/05/10 19:32
 * @Description DataSmart Govern Backend - ProjectMembershipCreateRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 新增或幂等更新项目成员授权请求。
 *
 * <p>本接口采用“按 tenantId + actorId + projectId 幂等 upsert”的语义：
 * 如果关系不存在则创建；如果关系已经存在则更新角色、来源和启用状态。
 * 这样适合管理后台重复提交、批量导入重跑、组织同步补偿等真实生产场景，
 * 不会因为一次网络超时后重试就产生重复成员。
 *
 * <p>重要收敛规则：用户可见产品层级已经确定为“租户 -> 项目 -> 资源”，
 * 因此创建项目成员授权时不再接收 workspaceId。数据库表中的 workspace_id 仅作为历史兼容字段保留，
 * 由服务层在新增/幂等更新时统一写入 null，避免前端继续出现工作空间输入框。</p>
 */
public record ProjectMembershipCreateRequest(
        /**
         * 目标租户 ID。
         *
         * <p>为空时服务层会按操作者租户推断；平台管理员也可以显式指定其他租户。
         */
        Long tenantId,

        /**
         * 被授权 actor ID。
         *
         * <p>不能为空，因为项目成员关系的核心就是“哪个 actor 能访问哪个项目”。
         */
        @NotNull(message = "actorId 不能为空")
        Long actorId,

        /**
         * 项目 ID。
         *
         * <p>不能为空；PROJECT 数据范围最终会把该字段集合透传给业务模块做 project_id 过滤。
         */
        @NotNull(message = "projectId 不能为空")
        Long projectId,

        /**
         * 项目内角色。
         *
         * <p>为空时默认 MEMBER。建议值包括 OWNER、MAINTAINER、VIEWER、MEMBER。
         */
        @Size(max = 64, message = "projectRole 长度不能超过 64")
        String projectRole,

        /**
         * 授权来源。
         *
         * <p>为空时默认 MANUAL。批量导入可传 IMPORT，组织同步可传 IDP_GROUP，审批流可传 APPROVAL。
         */
        @Size(max = 64, message = "grantSource 长度不能超过 64")
        String grantSource,

        /**
         * 是否启用。
         *
         * <p>为空时默认启用。禁用状态适合“先导入但暂不生效”或“保留历史但暂停授权”的场景。
         */
        Boolean enabled,

        /**
         * 操作原因。
         *
         * <p>会写入审计摘要，建议管理后台要求管理员填写，方便后续追溯为什么新增这条授权。
         */
        @Size(max = 500, message = "reason 长度不能超过 500")
        String reason) {
}

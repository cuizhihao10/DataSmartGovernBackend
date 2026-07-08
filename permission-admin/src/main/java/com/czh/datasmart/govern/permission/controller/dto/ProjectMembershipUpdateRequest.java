/**
 * @Author : Cui
 * @Date: 2026/05/10 19:32
 * @Description DataSmart Govern Backend - ProjectMembershipUpdateRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import jakarta.validation.constraints.Size;

/**
 * 更新项目成员授权请求。
 *
 * <p>更新接口只允许修改项目内角色、授权来源和启用状态，
 * 不允许直接修改 tenantId、actorId、projectId。
 * 这样做是为了避免一条历史授权记录被“搬到”另一个用户或项目，导致审计时间线失真。
 * 如果确实要转移成员，应禁用旧关系并创建新关系。
 *
 * <p>注意：workspaceId 已经从用户可见产品层级中退场，因此这里不提供 workspaceId 或 clearWorkspace。
 * 老数据中存在的 workspace_id 只用于历史兼容和审计，不再允许普通管理页面继续修改。</p>
 */
public record ProjectMembershipUpdateRequest(
        /**
         * 新项目内角色。为空表示不修改。
         */
        @Size(max = 64, message = "projectRole 长度不能超过 64")
        String projectRole,

        /**
         * 新授权来源。为空表示不修改。
         */
        @Size(max = 64, message = "grantSource 长度不能超过 64")
        String grantSource,

        /**
         * 新启用状态。为空表示不修改。
         */
        Boolean enabled,

        /**
         * 更新原因，用于审计。
         */
        @Size(max = 500, message = "reason 长度不能超过 500")
        String reason) {
}

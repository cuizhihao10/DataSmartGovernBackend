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
 * <p>更新接口只允许修改项目内角色、工作空间、授权来源和启用状态，
 * 不允许直接修改 tenantId、actorId、projectId。
 * 这样做是为了避免一条历史授权记录被“搬到”另一个用户或项目，导致审计时间线失真。
 * 如果确实要转移成员，应禁用旧关系并创建新关系。
 */
public record ProjectMembershipUpdateRequest(
        /**
         * 新项目内角色。为空表示不修改。
         */
        @Size(max = 64, message = "projectRole 长度不能超过 64")
        String projectRole,

        /**
         * 新工作空间 ID。
         *
         * <p>为空时默认表示“不修改工作空间”，避免前端局部更新时因为省略字段而误清空空间归属。
         * 如果确实需要清空 workspaceId，请同时传 `clearWorkspace=true`。
         */
        Long workspaceId,

        /**
         * 是否主动清空工作空间 ID。
         *
         * <p>为什么需要单独开关？
         * JSON 请求无法天然区分“字段缺失”和“字段传 null”，如果直接把 null 当作清空，
         * 管理后台做局部更新时很容易误删 workspaceId。
         */
        Boolean clearWorkspace,

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

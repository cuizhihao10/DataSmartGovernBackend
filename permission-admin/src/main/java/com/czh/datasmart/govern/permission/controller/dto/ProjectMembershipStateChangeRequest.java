/**
 * @Author : Cui
 * @Date: 2026/05/10 19:32
 * @Description DataSmart Govern Backend - ProjectMembershipStateChangeRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import jakarta.validation.constraints.Size;

/**
 * 项目成员授权启用/禁用请求。
 *
 * <p>启用和禁用是高频管理动作，单独建 DTO 而不是复用 UpdateRequest，
 * 是为了让 API 语义更清晰：调用者明确是在改变授权生效状态，而不是顺手修改角色或来源。
 */
public record ProjectMembershipStateChangeRequest(
        /**
         * 状态变更原因。
         *
         * <p>例如“成员离职”、“项目交接完成”、“审批单 PRJ-20260510 已通过”。
         */
        @Size(max = 500, message = "reason 长度不能超过 500")
        String reason) {
}

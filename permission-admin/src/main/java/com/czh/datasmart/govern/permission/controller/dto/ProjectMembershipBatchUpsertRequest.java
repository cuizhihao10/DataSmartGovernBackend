/**
 * @Author : Cui
 * @Date: 2026/05/10 19:32
 * @Description DataSmart Govern Backend - ProjectMembershipBatchUpsertRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量导入或批量幂等更新项目成员授权请求。
 *
 * <p>真实企业项目成员通常来自组织架构、项目台账、Excel 导入、审批流或 IdP 用户组同步，
 * 不可能只靠后台逐条点击新增。因此批量接口是商业化权限中心的基础能力之一。
 * 服务层会限制单批数量，避免一次请求写入过多记录造成长事务和审计表暴涨。
 */
public record ProjectMembershipBatchUpsertRequest(
        /**
         * 待导入成员列表。
         */
        @Valid
        @NotEmpty(message = "memberships 不能为空")
        @Size(max = 200, message = "单次最多导入 200 条项目成员授权")
        List<ProjectMembershipCreateRequest> memberships,

        /**
         * 批量操作原因。
         *
         * <p>如果单条成员没有填写 reason，服务层会使用该批量 reason 作为审计说明。
         */
        @Size(max = 500, message = "reason 长度不能超过 500")
        String reason) {
}

/**
 * @Author : Cui
 * @Date: 2026/07/08 23:58
 * @Description DataSmart Govern Backend - PermissionProjectStatusChangeRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import jakarta.validation.constraints.Size;

/**
 * 项目生命周期状态变更请求。
 *
 * <p>状态本身不由前端自由填写，而是通过 activate/disable/archive/delete 等明确路由表达。
 * 这样可以避免页面传入任意字符串绕过服务端状态机，也能让权限策略按不同动作单独收口。</p>
 */
public record PermissionProjectStatusChangeRequest(
        /**
         * 操作原因。
         *
         * <p>禁用、启用、归档和删除都会影响项目下的数据源、同步任务和 Agent 上下文，
         * 因此必须至少在审计中留下低敏说明。为空时服务端会写入默认原因。</p>
         */
        @Size(max = 500, message = "reason 长度不能超过 500")
        String reason) {
}

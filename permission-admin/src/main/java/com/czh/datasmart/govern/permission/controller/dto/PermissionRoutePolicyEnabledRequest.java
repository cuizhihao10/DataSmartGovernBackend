/**
 * @Author : Cui
 * @Date: 2026/04/26 20:33
 * @Description DataSmart Govern Backend - PermissionRoutePolicyEnabledRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 路由策略启停请求。
 *
 * <p>这里没有提供物理删除接口，而是先做启停。
 * 对商业权限系统而言，策略删除属于高风险动作：
 * 删除后很难追溯历史配置，也可能让排障时失去“当时为什么允许/拒绝”的证据。
 * 启停更适合当前阶段，既能让策略不再生效，又保留审计和回滚空间。
 */
@Data
public class PermissionRoutePolicyEnabledRequest {

    /**
     * 是否启用策略。
     */
    @NotNull(message = "不能为空")
    private Boolean enabled;

    /**
     * 启停原因。
     *
     * <p>例如“临时封禁普通用户写入权限”“上线新任务策略前禁用旧规则”。
     * 高风险权限变更必须要求管理员写清楚原因，后续审计和复盘才有依据。
     */
    @Size(max = 1000, message = "长度不能超过 1000")
    private String reason;
}

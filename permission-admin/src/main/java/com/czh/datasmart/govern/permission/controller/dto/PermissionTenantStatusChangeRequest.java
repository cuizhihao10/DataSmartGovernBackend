/**
 * @Author : Cui
 * @Date: 2026/07/10 14:05
 * @Description DataSmart Govern Backend - PermissionTenantStatusChangeRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 租户生命周期变更请求。
 *
 * <p>暂停、恢复和关闭属于平台级高风险动作，必须提供原因并写入审计。CLOSED 为终态，不允许通过普通恢复接口重新启用。</p>
 */
public record PermissionTenantStatusChangeRequest(
        @NotBlank @Size(max = 500) String reason) {
}

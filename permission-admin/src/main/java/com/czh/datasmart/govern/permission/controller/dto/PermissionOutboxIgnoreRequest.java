/**
 * @Author : Cui
 * @Date: 2026/04/27 00:40
 * @Description DataSmart Govern Backend - PermissionOutboxIgnoreRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 忽略 outbox 事件请求。
 *
 * <p>“忽略”是高风险运维动作：它代表管理员确认某条权限变更事件不再需要投递。
 * 例如事件对应的策略已经被后续事件覆盖、目标 gateway 缓存已通过其他方式清理、或该事件载荷确认异常不能再发送。
 *
 * <p>因此 reason 必填，并会写入权限审计表，方便后续复盘“为什么这条事件没有继续发送”。
 */
@Data
public class PermissionOutboxIgnoreRequest {

    /**
     * 忽略原因。
     *
     * <p>生产系统里，人工跳过失败事件必须留下原因，否则未来排查 gateway 缓存不一致时无法判断这是故障还是人工决策。
     */
    @NotBlank(message = "忽略原因不能为空")
    @Size(max = 500, message = "忽略原因不能超过 500 个字符")
    private String reason;
}

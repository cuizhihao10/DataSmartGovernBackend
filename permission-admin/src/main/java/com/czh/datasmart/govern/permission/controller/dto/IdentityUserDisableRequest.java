/**
 * @Author : Cui
 * @Date: 2026/07/05 03:26
 * @Description DataSmartGovernBackend - IdentityUserDisableRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

import jakarta.validation.constraints.Size;

/**
 * 禁用身份请求。
 *
 * <p>禁用账号是高风险管理动作：它会阻断用户继续通过 IdP 登录，并让本地影子身份进入 DISABLED 状态。
 * 因此必须记录禁用原因，便于审计员复盘“谁在什么时候因为什么原因禁用了哪个账号”。
 *
 * @param reason 禁用原因，不能包含密码、Token、身份证号等敏感原文。
 */
public record IdentityUserDisableRequest(@Size(max = 500) String reason) {
}

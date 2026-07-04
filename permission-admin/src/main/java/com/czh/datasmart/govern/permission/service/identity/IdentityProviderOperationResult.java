/**
 * @Author : Cui
 * @Date: 2026/07/05 03:26
 * @Description DataSmartGovernBackend - IdentityProviderOperationResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.identity;

import java.util.List;

/**
 * 外部 IdP 操作结果。
 *
 * <p>结果对象只表达低敏事实，例如 providerUserId、操作是否成功和证据码。不要把 IdP access token、
 * HTTP 响应体、client secret、密码或 endpoint 明文放进这里，否则很容易被上层响应或审计持久化。
 */
public record IdentityProviderOperationResult(
        String providerUserId,
        String operation,
        String message,
        List<String> evidenceCodes) {
}

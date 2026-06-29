/**
 * @Author : Cui
 * @Date: 2026/06/29 23:59
 * @Description DataSmart Govern Backend - GatewayServiceAccountDelegationSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.authorization;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * 网关服务账号委托上下文补齐组件。
 *
 * <p>为什么需要单独拆出这个类？</p>
 * <p>GatewayAuthorizationFilter 的核心职责是“在请求转发前调用 permission-admin 决定是否放行”。
 * 服务账号委托则是另一类关注点：它要解释机器身份是谁、是否代表某个上游主体、为什么执行当前动作。
 * 如果把这些解析规则继续塞进过滤器，过滤器会快速膨胀，并且后续 data-sync worker、agent-runtime、task-management
 * 都难以复用同一套责任链语义。</p>
 *
 * <p>本组件只处理低敏控制面字段：</p>
 * <p>1. 服务账号 actorId；</p>
 * <p>2. 服务账号可读编码；</p>
 * <p>3. 被代表主体；</p>
 * <p>4. 委托类型；</p>
 * <p>5. 委托原因摘要。</p>
 *
 * <p>它绝不读取或生成 prompt、SQL、工具参数、样本数据、模型输出、token、client secret、证书或内部 endpoint。
 * 这些边界非常重要，因为授权请求会进入审计和缓存链路，不能把高敏正文扩散到 permission-admin。</p>
 */
@Component
public class GatewayServiceAccountDelegationSupport {

    /**
     * 补齐服务账号委托字段。
     *
     * <p>该方法不会因为 delegation header 存在就直接放行请求。它只是把可审计上下文写入
     * GatewayPermissionDecisionRequest，真正是否允许仍由 permission-admin 的路由策略、数据范围、审批要求决定。</p>
     *
     * @param headers gateway 当前请求 Header，必须已经经过 GatewayContractFilter 清理。
     * @param decisionRequest 即将发送给 permission-admin 的判定请求。
     * @param actorId 当前已认证主体 ID；当主体本身就是服务账号时可作为 serviceAccountActorId 兜底。
     */
    public void populate(HttpHeaders headers,
                         GatewayPermissionDecisionRequest decisionRequest,
                         Long actorId) {
        Long explicitServiceAccountActorId = parseLongOrNull(headers.getFirst(PlatformContextHeaders.SERVICE_ACCOUNT_ACTOR_ID));
        String explicitServiceAccountCode = trimToNull(headers.getFirst(PlatformContextHeaders.SERVICE_ACCOUNT_CODE));
        boolean serviceAccountActor = isServiceAccount(decisionRequest.getActorRole(), decisionRequest.getActorType())
                || explicitServiceAccountActorId != null
                || explicitServiceAccountCode != null;
        if (!serviceAccountActor) {
            return;
        }

        decisionRequest.setServiceAccountActorId(explicitServiceAccountActorId == null ? actorId : explicitServiceAccountActorId);
        decisionRequest.setServiceAccountCode(firstText(
                explicitServiceAccountCode,
                trimToNull(headers.getFirst(PlatformContextHeaders.SOURCE_SERVICE)),
                decisionRequest.getWorkspaceId(),
                "datasmart-service-account"
        ));
        decisionRequest.setRepresentedActorId(trimToNull(headers.getFirst(PlatformContextHeaders.REPRESENTED_ACTOR_ID)));
        decisionRequest.setDelegationType(firstText(
                normalizeCode(headers.getFirst(PlatformContextHeaders.DELEGATION_TYPE)),
                decisionRequest.getRepresentedActorId() == null ? "SERVICE_ACCOUNT_DIRECT_CALL" : "SERVICE_ACCOUNT_ON_BEHALF_OF_ACTOR"
        ));
        decisionRequest.setDelegationReason(firstText(
                trimToNull(headers.getFirst(PlatformContextHeaders.DELEGATION_REASON)),
                "GATEWAY_PERMISSION_EVALUATE_SERVICE_ACCOUNT"
        ));
    }

    /**
     * 判断角色或主体类型是否表示服务账号。
     */
    private boolean isServiceAccount(String actorRole, String actorType) {
        return "SERVICE_ACCOUNT".equalsIgnoreCase(actorRole) || "SERVICE_ACCOUNT".equalsIgnoreCase(actorType);
    }

    /**
     * 解析可选 Long。格式错误时返回 null，避免伪造一个 0 号服务账号。
     */
    private Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 规范化枚举型低敏编码。
     */
    private String normalizeCode(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.trim().replace('-', '_').replace(' ', '_').toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * 去除空白并把空文本转为 null。
     */
    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 返回第一个非空文本。
     */
    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }
}

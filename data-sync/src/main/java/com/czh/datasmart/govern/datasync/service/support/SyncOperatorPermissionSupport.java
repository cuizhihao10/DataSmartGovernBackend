/**
 * @Author : Cui
 * @Date: 2026/05/08 22:36
 * @Description DataSmart Govern Backend - SyncOperatorPermissionSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * data-sync 运营权限兜底组件。
 *
 * <p>当前项目还没有把 permission-admin 的策略决策远程接入 data-sync，
 * 但人工介入和事故处理都属于高风险运营动作，不能完全依赖调用方自觉。
 * 因此这里提供模块内兜底：只有平台管理员、租户管理员、运营人员和服务账号可以执行运营处理动作。
 *
 * <p>后续接入 permission-admin 后，这个组件可以演进为：
 * 1. 调用权限中心判断菜单/按钮/数据范围；
 * 2. 支持按租户、项目、数据源、连接器维度授权；
 * 3. 对服务账号使用签名、密钥轮换或 mTLS 身份认证。
 */
@Component
public class SyncOperatorPermissionSupport {

    private static final Set<String> OPERATOR_ROLES = Set.of(
            "PLATFORM_ADMINISTRATOR",
            "TENANT_ADMINISTRATOR",
            "OPERATOR",
            "SERVICE_ACCOUNT"
    );

    /**
     * 校验当前操作者是否具备 data-sync 运营动作权限。
     *
     * @param actorContext 网关或上游服务透传的操作者上下文
     * @param operation 当前操作名称，用于生成更清晰的错误提示
     */
    public void assertOperator(SyncActorContext actorContext, String operation) {
        String role = normalizeRole(actorContext);
        if (!OPERATOR_ROLES.contains(role)) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "当前角色无权执行 data-sync 运营动作，operation=" + operation + ", role=" + role);
        }
    }

    private String normalizeRole(SyncActorContext actorContext) {
        String role = actorContext == null ? null : actorContext.actorRole();
        return role == null || role.isBlank() ? "USER" : role.trim().toUpperCase(Locale.ROOT);
    }
}

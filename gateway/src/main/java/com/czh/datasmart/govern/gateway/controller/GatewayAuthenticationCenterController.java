/**
 * @Author : Cui
 * @Date: 2026/06/29 23:34
 * @Description DataSmart Govern Backend - GatewayAuthenticationCenterController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.gateway.authentication.GatewayAuthenticationCenterService;
import com.czh.datasmart.govern.gateway.controller.dto.GatewayAuthenticationCapabilityView;
import com.czh.datasmart.govern.gateway.controller.dto.GatewayAuthenticationPrincipalView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 网关认证中心控制器。
 *
 * <p>这是 DataSmart Govern 当前阶段补齐“统一认证入口”的生产化落地点。
 * 需要强调的是：它不是自研用户中心，也不替代 permission-admin。它只负责展示 OIDC 认证能力和当前身份解析；
 * token 签发、密码策略、MFA、SSO、refresh token 轮换应交给 Keycloak 或企业 IdP；
 * 路由权限、数据范围、审批和审计仍然交给 gateway 授权过滤器与 permission-admin。</p>
 *
 * <p>为什么同时暴露 `/auth/**` 与 `/api/auth/**`：</p>
 * <p>1. `/auth/**` 贴近 OAuth2/OIDC 常见入口，方便后续接入真实 IdP 或本地排障；</p>
 * <p>2. `/api/auth/**` 贴合当前前端/网关统一 API 前缀，便于前端只记一套入口；</p>
 * <p>3. 两套路径调用同一个 service，不产生两套业务逻辑。</p>
 */
@RestController
@RequiredArgsConstructor
public class GatewayAuthenticationCenterController {

    private final GatewayAuthenticationCenterService authenticationCenterService;

    /**
     * 查询认证中心能力。
     *
     * <p>该接口是公开低敏能力说明：它只返回当前认证模式、OIDC 是否启用、支持的 actorType 和生产替代建议。
     * 不返回 token、密码、密钥、权限策略正文或内部服务地址。</p>
     */
    @GetMapping({"/auth/capabilities", "/api/auth/capabilities"})
    public ResponseEntity<PlatformApiResponse<GatewayAuthenticationCapabilityView>> capabilities(
            @RequestHeader HttpHeaders headers) {
        return ResponseEntity.ok(PlatformApiResponse.success(
                "网关认证中心能力查询成功",
                authenticationCenterService.capabilities(),
                traceId(headers)
        ));
    }

    /**
     * 查询当前请求身份。
     *
     * <p>该接口用于回答“gateway 当前把我识别成谁”。它不会返回授权策略，不会告诉调用方能访问哪些具体资源。
     * 如果返回 authenticated=false，说明当前请求没有被 OIDC/JWT 或可信上游 Header 解析成可用身份。</p>
     */
    @GetMapping({"/auth/session", "/api/auth/session"})
    public ResponseEntity<PlatformApiResponse<GatewayAuthenticationPrincipalView>> currentSession(
            Authentication authentication,
            @RequestHeader HttpHeaders headers) {
        return ResponseEntity.ok(PlatformApiResponse.success(
                "当前认证身份解析完成",
                authenticationCenterService.currentPrincipal(authentication, headers),
                traceId(headers)
        ));
    }

    private String traceId(HttpHeaders headers) {
        return headers.getFirst(PlatformContextHeaders.TRACE_ID);
    }
}

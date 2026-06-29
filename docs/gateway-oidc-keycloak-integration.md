# Gateway OIDC / Keycloak / 企业 IdP 接入说明

## 目标定位

DataSmart Govern 的 gateway 不再把“认证中心”做成临时 Header、开发 token 或自研简化登录接口，而是按生产主流方式作为 **OIDC Resource Server** 接入 Keycloak、企业统一 IdP 或云 IAM。

这份说明用于解释当前代码实现、配置项含义、Keycloak/企业 IdP 应提供哪些 claim，以及后续商用部署还需要继续补齐哪些安全能力。

## 职责边界

- `Keycloak / 企业 IdP` 负责用户登录、密码策略、MFA、SSO、账号生命周期、access token 签发、refresh token 轮换和会话撤销。
- `gateway` 负责校验 `Authorization: Bearer <access_token>` 的签名、issuer、过期时间和 audience，并把可信 claim 映射为 `X-DataSmart-*` 平台身份 Header。
- `permission-admin` 负责 RBAC、数据范围、菜单/路由权限、审批策略、服务账号策略和审计事实，不负责 token 签发。
- `业务微服务` 不直接解析 JWT，只消费 gateway 写入的低敏平台上下文，避免每个服务重复耦合 IdP claim 结构。

## 关键运行链路

1. 前端或外部系统跳转到 Keycloak/企业 IdP 完成登录。
2. IdP 签发 access token，token 中包含 issuer、audience、租户、操作者、角色、操作者类型和 workspace 等 claim。
3. 调用方访问 gateway 时携带 `Authorization: Bearer <access_token>`。
4. `GatewaySecurityConfig` 使用 Spring Security OAuth2 Resource Server 校验 token。
5. `GatewayJwtAudienceValidator` 校验 `aud` 是否指向 DataSmart gateway，避免其他系统 token 被误用。
6. `GatewayOidcAuthenticationContextFilter` 把已验证 JWT 映射为 `X-DataSmart-Tenant-Id`、`X-DataSmart-Actor-Id`、`X-DataSmart-Actor-Role`、`X-DataSmart-Actor-Type`、`X-DataSmart-Workspace-Id`。
7. `GatewayAuthorizationFilter` 再调用 permission-admin 判定当前身份是否允许访问目标路由和动作。

## 必需 Claim

| Claim | 默认名称 | 作用 |
|---|---|---|
| `tenantId` | `datasmart_tenant_id` | 多租户边界，所有业务请求必须能归属到租户。 |
| `actorId` | `datasmart_actor_id` | 平台内部操作者 ID，用于权限、审计和数据范围。 |
| `actorRole` | `datasmart_actor_role` | 平台角色，例如 `PROJECT_OWNER`、`OPERATOR`、`AUDITOR`。 |
| `actorType` | `datasmart_actor_type` | 操作者类型，例如 `USER`、`SERVICE_ACCOUNT`、`AGENT`、`SYSTEM_SCHEDULER`。 |
| `workspaceId` | `datasmart_workspace_id` | Agent 工作区、任务隔离和资产目录边界。 |
| `aud` | OIDC 标准 claim | 证明 token 是签发给 DataSmart gateway 的资源 token。 |

如果 JWT 签名合法但缺少 `tenantId`、`actorId` 或角色，gateway 默认失败关闭并返回 403。原因是“token 合法”不等于“可以进入多租户业务系统”。

## Keycloak 建议配置

- Realm：建议使用 `datasmart` 或企业统一命名。
- Client：建议为 gateway 创建独立 client，例如 `datasmart-gateway`。
- Audience Mapper：把 `datasmart-gateway` 写入 access token 的 `aud`。
- Client Scope Mapper：把 `datasmart_tenant_id`、`datasmart_actor_id`、`datasmart_actor_role`、`datasmart_actor_type`、`datasmart_workspace_id` 写入 access token。
- 角色来源：可以直接写 `datasmart_actor_role`，也可以复用 Keycloak `realm_access.roles`，gateway 会裁剪 `ROLE_` 和 `DATASMART_` 前缀。
- 服务账号：机器调用应使用独立 service account，并映射为 `actorType=SERVICE_ACCOUNT`、`actorRole=SERVICE_ACCOUNT` 或更细粒度平台角色。

## Gateway 关键配置

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${DATASMART_GATEWAY_OIDC_ISSUER_URI:http://localhost:18080/realms/datasmart}

datasmart:
  gateway:
    authentication-center:
      enabled: true
      provider-mode: OIDC_RESOURCE_SERVER
      issuer: ${DATASMART_GATEWAY_AUTH_CENTER_ISSUER:http://localhost:18080/realms/datasmart}
      oidc:
        enabled: true
        fail-closed-on-missing-required-claims: true
        audience-validation-enabled: true
        required-audiences:
          - ${DATASMART_GATEWAY_OIDC_REQUIRED_AUDIENCE:datasmart-gateway}
```

`issuer-uri` 与 `authentication-center.issuer` 应保持一致。前者给 Spring Security Resource Server 使用，后者用于 DataSmart 自身能力说明和显式 decoder 配置。

## 公开能力接口

- `GET /auth/capabilities`
- `GET /api/auth/capabilities`

用于查看当前认证模式、issuer、OIDC 是否启用、audience 校验是否启用，以及支持的身份类型。该接口只返回低敏配置事实，不返回 token、密钥、完整 claim 或策略正文。

- `GET /auth/session`
- `GET /api/auth/session`

用于查看当前请求被 gateway 解析成哪个平台身份。该接口需要已认证 JWT；返回内容只包含低敏身份上下文和 issueCode，不返回 access token、refresh token、完整 claim、邮箱、手机号或权限策略。

## 生产部署检查清单

- 保持 `datasmart.gateway.authentication-center.oidc.enabled=true`。
- 保持 `audience-validation-enabled=true`，并确保 IdP access token 的 `aud` 包含 gateway 资源标识。
- 保持 `fail-closed-on-missing-required-claims=true`，避免缺租户、缺操作者或缺角色时进入业务路由。
- 保持 `datasmart.gateway.context.trust-incoming-platform-context=false`，禁止外部客户端自报 `X-DataSmart-*`。
- 关闭或严格限制 `development-identity`，生产环境不应允许开发 token 覆盖 OIDC 身份。
- 将 `datasmart.gateway.authorization.enabled=true`、`shadow-mode=false`、`fail-open-on-error=false`，让 permission-admin 强制授权。
- 为服务账号、Agent、系统调度器分别设计独立 client 或独立角色，避免所有机器流量共用人类管理员身份。
- 把 IdP client secret、gateway HMAC 密钥、服务账号凭据放入 Secret Manager、Kubernetes Secret 或 Docker secret，不写入 Git。
- 后续补审计事件：登录态解析失败、audience 不匹配、claim 缺失、角色映射失败、permission-admin 拒绝、服务账号越权都应形成可查询审计记录。

## 当前边界

- 当前 gateway 已能作为 OIDC Resource Server 校验 JWT 并映射平台身份，但项目尚未内置 Keycloak realm 导入文件。
- 当前未实现 OIDC login redirect 或 BFF session，前端应直接与 IdP 交互获取 access token，或由企业网关/BFF 完成登录态托管。
- 当前 token revocation、session logout、组织/用户同步、SCIM、JIT provisioning 尚未实现。
- 当前授权缓存仍默认关闭，生产开启强授权后需要继续接 permission-admin 权限变更事件和缓存失效机制。

## 收敛建议

认证中心这一块不建议继续扩展自研登录能力。后续应沿三条收敛路线推进：

- 补 Keycloak realm 示例或企业 IdP 对接手册，让本地一键启动能拿到符合 DataSmart claim 规范的 access token。
- 让 permission-admin 管理角色、路由、菜单、数据范围和服务账号策略，避免 gateway 承担授权中心职责。
- 把服务到服务调用升级为 OIDC service account + HMAC/mTLS/service mesh 身份组合，逐步替换当前少量 internal Header 白名单。

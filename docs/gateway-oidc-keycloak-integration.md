# Gateway OIDC / Keycloak / 企业 IdP 接入说明

## 2026-06-30 补充：Python Runtime 低敏诊断路由收口

本阶段在既有 OIDC/Keycloak 认证中心和 `/api/internal/agent-runtime/**` 服务账号入口基础上，继续把 Python AI Runtime 的低敏诊断能力收口到统一 gateway。此前 gateway 只把 `/api/agent/plans` 和 `/api/agent/events/ws` 显式转给 Python Runtime，其它 `/api/agent/**` 会落入 Java `agent-runtime` 通配路由。这样会导致 `/api/agent/skills/publication/refresh`、能力闭口、模型诊断等 Python 接口在文档上存在，但从统一入口并不能稳定命中。

本次新增的 gateway 路由只覆盖低敏诊断与闭口检查：

- `/api/agent/capabilities/**`
- `/api/agent/skills/publication/diagnostics`
- `/api/agent/skills/publication/refresh`
- `/api/agent/models/provider-health/diagnostics`
- `/api/agent/models/capabilities/diagnostics`
- `/api/agent/models/inference-optimization/diagnostics`
- `/api/agent/security/gateway-signature/diagnostics`
- `/api/agent/tool-actions/checkpoints/diagnostics`
- `/api/agent/platform/convergence/diagnostics`

设计边界如下：

- 这些路径必须放在通用 `/api/agent/** -> agent-runtime` 路由之前，避免被 Java 控制面通配吞掉。
- 这些接口默认仍受 gateway OIDC 和 permission-admin 路由授权保护；gateway 只负责统一入口和身份上下文，不替代授权中心。
- POST `/api/agent/skills/publication/refresh` 只刷新 Python 进程内 Manifest 诊断缓存，属于运维诊断动作；它不创建业务对象、不触发工具执行、不写 worker outbox，也不绕过 permission-admin、HITL、tool readiness 或 runtime protection。
- 响应只允许包含低敏状态、原因码、计数、指纹存在性、策略版本和建议动作；不得返回 token、完整 JWT claim、prompt、SQL、工具参数、样本数据、模型输出、内部 endpoint、artifact 正文或长期记忆正文。

本地闭环验证可以使用 smoke 脚本的认证网关探针：

```powershell
.\scripts\local-e2e-smoke-check.ps1 -CheckServiceAccountToken -CheckAgentGatewayDiagnostics
```

该命令会先用本地 `sync-service` 样例服务账号验证 `/auth/session` 的低敏身份映射，再携带 Bearer token 访问 gateway 下的 `/api/agent/capabilities/closure-readiness`、`/api/agent/skills/publication/diagnostics` 和 `/api/agent/models/inference-optimization/diagnostics`。脚本只判断状态码，不打印 token、完整 claim、诊断响应正文或 Python Runtime 下游错误正文。

排障时可以按失败状态区分边界：

- `401/403` 通常说明 OIDC claim、audience、gateway 资源服务器配置或 permission-admin 路由策略不一致。
- `502/503/timeout` 通常说明 gateway 下游不可达、路由顺序漂移、Python Runtime 未启动或 `8090` 地址配置不一致。
- `/auth/session` 成功但 Agent 诊断失败，说明认证中心基本可用，应优先检查授权策略、route rewrite 和 Python Runtime 下游链路，而不是继续怀疑 token 签发。

## 2026-06-30 补充：内部服务账号端点保护

本阶段在既有 OIDC/Keycloak 认证中心基础上，继续收敛服务间调用边界：`/api/internal/agent-runtime/**` 已作为 gateway 统一入口转发到 `agent-runtime` 的 `/internal/agent-runtime/**`，用于 worker lease、worker receipt、payload materialization 等机器协议调用。该入口不属于普通用户会话 API，必须先通过 gateway 本地内部端点守卫，再进入 permission-admin 做策略判定与审计。

关键原则如下：

- 内部端点默认同时要求 `actorRole=SERVICE_ACCOUNT` 与 `actorType=SERVICE_ACCOUNT`。`actorRole` 表示权限集合，`actorType` 表示 token 主体类型；两者分开校验可以避免普通用户或管理员仅凭临时角色误入机器协议入口。
- `/api/internal/agent-runtime/**` 在授权语义上统一映射为 `AI_RUNTIME + EXECUTE_INTERNAL`，GET 映射为 `VIEW_INTERNAL`，便于 permission-admin 为服务账号配置独立策略、审计和告警。
- 默认保护的端点覆盖 AgentPlan ingestion、command worker receipt、command worker lease、controlled dry-run receipt、sandbox admission、output sanitization、workspace file payload materialization 和 workspace file worker receipt。
- 当前仍是 gateway 本地固定窗口限流和可选 internal token；生产环境建议继续升级为 OIDC client credentials + HMAC/mTLS/service mesh + Redis/网关级分布式限流。
- 守卫和授权审计只处理低敏控制面字段，不记录 access token、client secret、完整 JWT claim、prompt、SQL、工具参数正文、样本数据、模型输出、文件正文或内部 endpoint 正文。

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
8. 如果当前主体是 `SERVICE_ACCOUNT`，gateway 会把服务账号 actorId、服务账号编码、被代表主体、委托类型、委托原因和请求来源放入 permission-admin 判定请求，便于后续形成“机器账号代表谁执行了什么”的审计责任链。
9. `GatewayAuthenticationAuditSupport` 会把身份解析结果写成低敏认证审计事件，并同步记录 `datasmart.gateway.authentication.outcome` 指标；这一步只回答“gateway 是否接受该身份”，不替代 permission-admin 后续“是否允许访问资源”的授权审计。

## 认证审计与指标

认证中心进入生产链路后，单纯依赖 HTTP 401/403 已经不足以支撑商用排障、安全审计和客户现场验收。本项目在 gateway 内新增了认证审计端口，用于记录 OIDC/JWT 身份解析成功、失败关闭和 issueCode 摘要。

当前默认实现是 `LoggingGatewayAuthenticationAuditSink`，它会输出结构化日志字段：

- `eventId`：认证审计事件 ID，后续接 Kafka、SIEM 或审计表时可作为去重和追踪键。
- `traceId`：来自 `X-DataSmart-Trace-Id` 的链路追踪 ID，用于串联 gateway、permission-admin 与下游业务服务日志。
- `outcome`：认证解析结果，例如 `RESOLVED` 或 `REJECTED`。
- `authenticationType`：身份来源，例如 `OIDC_JWT`。
- `tenantId`、`actorId`、`actorRole`、`actorType`、`workspaceId`：低敏平台身份摘要，用于定位哪类身份通过或失败。
- `requestPath`：只记录路径，不记录 query string，避免把查询条件、搜索词或业务参数写入日志。
- `issueCodes`：认证解析问题码，例如缺少 DataSmart 必需 claim、principal 类型不支持等。
- `payloadPolicy`：事件载荷策略，显式声明该事件禁止包含 token、完整 claim、PII、secret、prompt、SQL、工具参数、样本数据、模型输出和内部 endpoint。

配套指标为：

```text
datasmart.gateway.authentication.outcome
```

该指标只使用低基数标签：

- `outcome`：认证结果。
- `auth_type`：认证类型。
- `actor_type`：主体类型。
- `primary_issue`：主要问题码。

这里刻意不把 `tenantId`、`actorId`、`workspaceId` 作为 Prometheus 标签，因为真实多租户、高并发环境下这些字段会造成指标基数爆炸。需要按租户、用户、workspace 做明细分析时，应走日志、审计表或安全分析系统，而不是直接进入指标标签。

## 生产 Profile

仓库新增 `gateway/src/main/resources/application-prod.yml` 作为生产 profile 收敛配置。启用方式示例：

```powershell
$env:SPRING_PROFILES_ACTIVE="prod"
$env:DATASMART_GATEWAY_OIDC_ISSUER_URI="https://idp.example.com/realms/datasmart"
$env:DATASMART_GATEWAY_OIDC_REQUIRED_AUDIENCE="datasmart-gateway"
$env:DATASMART_GATEWAY_PYTHON_RUNTIME_SIGNATURE_SECRET="<from-secret-manager>"
mvn -pl gateway spring-boot:run
```

该 profile 的核心含义是：

- OIDC issuer 必须由环境变量或 Secret Manager 注入，不在 Git 中写死生产 IdP 地址。
- `trust-incoming-platform-context=false`，禁止外部请求自报 `X-DataSmart-*` 身份 Header。
- `development-identity.enabled=false`，禁用本地开发 token 和开发 Header 注入。
- `authorization.enabled=true`、`shadow-mode=false`、`fail-open-on-error=false`，确保 permission-admin 不可用时不会默认放行。
- `authentication-center.oidc.audience-validation-enabled=true`，确保 access token 的 audience 指向 DataSmart gateway。
- `authentication-center.oidc.fail-closed-on-missing-required-claims=true`，确保缺租户、缺操作者或缺角色时直接拒绝。
- `python-runtime-signature.enabled=true`，要求 gateway 到 Python Runtime 的控制面 Header 使用 HMAC 签名，避免绕过 gateway 伪造可信上下文。

生产 profile 仍不是完整生产部署方案，它只是把默认开发友好的配置切到更安全的商用基线。真正上线还需要 TLS、正式 Keycloak/企业 IdP 高可用、Secret Manager、服务网格或内网 ACL、权限变更事件驱动缓存失效、集中审计表和安全告警。

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

## 服务账号委托链路

OIDC 接入后，服务账号不应被视为“万能管理员”。DataSmart 的推荐链路是：

- IdP 签发服务账号 token，至少包含 `actorType=SERVICE_ACCOUNT`、`actorRole=SERVICE_ACCOUNT`、服务账号 actorId 和 workspace。
- gateway 校验 token 后写入平台身份 Header，并在授权请求中补齐 `serviceAccountActorId`、`serviceAccountCode`、`representedActorId`、`delegationType`、`delegationReason`。
- permission-admin 仍按角色、路由策略、资源类型、动作、数据范围和审批要求判定是否允许。
- 审计记录保存低敏委托摘要，说明服务账号是谁、代表谁、因为什么场景访问了哪个资源。

这些字段只用于低敏责任链和策略解释，不允许承载 access token、client secret、prompt、SQL、工具参数、样本数据、模型输出或内部 endpoint。

## 仓库内置本地 Realm

仓库已经提供本地 Keycloak realm 样板，位置为 `docker/keycloak/import/datasmart-realm.json`，配套说明见 `docker/keycloak/README.md`。该样板的设计目的，是让开发环境可以使用真实 OIDC access token 验证 gateway 的 JWT 验签、audience 校验、claim 映射和下游身份 Header 传递，而不是继续依赖临时 Header 或伪造 token。

本地启动：

```powershell
docker compose up -d keycloak
```

本地 Keycloak 地址：

```text
http://localhost:18080
```

realm 样板内置内容：

- `datasmart` realm：作为 DataSmart 本地开发和集成测试使用的身份域。
- `datasmart-gateway` client：作为 gateway Resource Server 期望的 audience，access token 会携带 `aud=datasmart-gateway`。
- DataSmart 平台 claim mapper：把 Keycloak 用户属性映射为 `datasmart_tenant_id`、`datasmart_actor_id`、`datasmart_actor_role`、`datasmart_actor_type`、`datasmart_workspace_id`。
- 样例用户：覆盖项目负责人、运营人员、审计员、平台管理员和服务账号模拟用户，便于验证不同角色进入 gateway 后的身份上下文。

这些样例只用于本地联调。生产环境应改为正式 Keycloak 集群、企业 IdP 或云 IAM，并使用 Authorization Code + PKCE、Client Credentials、TLS、外部数据库、Secret Manager、组织同步和审计策略。

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
- 保持认证审计日志低敏输出，并把 `datasmart.gateway.authentication.outcome` 接入 Prometheus/Grafana 或集中监控。
- 后续补持久化审计：audience 不匹配、claim 缺失、角色映射失败、permission-admin 拒绝、服务账号越权都应形成可查询审计记录。

## 当前边界

- 当前 gateway 已能作为 OIDC Resource Server 校验 JWT 并映射平台身份，仓库已提供本地 Keycloak realm 导入文件用于真实 token 联调。
- 当前已具备低敏认证审计日志与认证结果指标，但还没有落 MySQL 审计表、Kafka 审计 topic 或 SIEM 集成。
- 当前已提供生产 profile 安全基线，但尚未补 Kubernetes/Helm、mTLS、Secret Manager 接入样例和正式高可用部署拓扑。
- 当前未实现 OIDC login redirect 或 BFF session，前端应直接与 IdP 交互获取 access token，或由企业网关/BFF 完成登录态托管。
- 当前 token revocation、session logout、组织/用户同步、SCIM、JIT provisioning 尚未实现。
- 当前授权缓存仍默认关闭，生产开启强授权后需要继续接 permission-admin 权限变更事件和缓存失效机制。

## 收敛建议

认证中心这一块不建议继续扩展自研登录能力。后续应沿三条收敛路线推进：

- 用仓库内置 Keycloak realm 完成 gateway、permission-admin、服务账号和 agent-runtime 的端到端 token 联调，避免身份链路停留在文档层。
- 让 permission-admin 管理角色、路由、菜单、数据范围和服务账号策略，避免 gateway 承担授权中心职责。
- 把服务到服务调用升级为 OIDC service account + HMAC/mTLS/service mesh 身份组合，逐步替换当前少量 internal Header 白名单。

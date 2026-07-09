# DataSmart Govern Keycloak 本地 Realm 样板

## 目的

该目录为 gateway 的 OIDC Resource Server 能力提供本地可运行的身份提供方样板。它不是生产 Keycloak 配置，也不是企业 IdP 的最终方案，而是让开发、测试和学习环境可以拿到符合 DataSmart claim 规范的真实 JWT。

## 文件说明

- `import/datasmart-realm.json`：Keycloak 启动时导入的 `datasmart` realm。
- `datasmart-gateway` client：gateway 对应的 OIDC client，access token 会包含 `aud=datasmart-gateway`。
- 样例用户：覆盖项目负责人、运营人员、审计员、平台管理员和服务账号模拟用户。

## 本地启动

```powershell
docker compose up -d keycloak
```

该命令会自动拉起 `postgresql` 和一次性的 `keycloak-db-bootstrap` 服务。`keycloak-db-bootstrap` 会在 PostgreSQL 中幂等创建 `keycloak` database 和 `keycloak` 登录角色，然后 Keycloak 通过 JDBC 连接该 database。也就是说，本地 Keycloak 虽然仍使用 `start-dev` 简化启动参数，但 realm、client、用户、角色、服务账号和密钥轮换历史不再保存到 Keycloak 容器文件目录，而是保存到 PostgreSQL。

Keycloak Admin Console：

```text
http://localhost:18080
```

默认本地管理员：

```text
username: admin
password: admin
```

这些值只用于本地容器。生产必须使用环境变量、Secret Manager、Kubernetes Secret 或企业密码库注入，不允许使用默认密码。

## 运行态 claim 同步

如果本地已经存在 PostgreSQL-backed Keycloak 数据库，仅修改
`docker/keycloak/import/datasmart-realm.json` 并重启容器通常不会覆盖已有
`datasmart` realm。Keycloak 的 realm import 更适合“首次创建 realm”场景；
已有 realm 需要通过 Admin API 做受控迁移或补丁。

本仓库提供幂等同步脚本，用于补齐运行态 Keycloak 中的 DataSmart claim mapper、
样例用户属性、realm role，以及 Keycloak 26 User Profile 中的受管理自定义属性：

```powershell
.\scripts\keycloak-datasmart-claim-sync.ps1
.\scripts\keycloak-datasmart-claim-sync.ps1 -Apply
```

脚本默认 dry-run，只有传入 `-Apply` 才会写入 Keycloak。它不会打印 admin token、
access token、refresh token、密码、client secret 或完整 JWT。执行后必须重新获取
access token，旧 token 不会自动追加新 claim。

特别注意：`datasmart_project_ids` 只是认证中心侧声明的“项目候选集合”，用于
`/api/auth/session`、前端项目切换提示和本地闭环诊断；业务请求是否允许访问
`projectId=101` 仍由 gateway 调用 permission-admin 后，根据数据库中的项目成员关系、
路由策略和数据范围策略重新判定。

## PostgreSQL 持久化边界

当前 Compose 已把 Keycloak 从开发态文件卷切换为 PostgreSQL-backed 存储：

- `docker/postgresql/init/01-keycloak-database.sh`：首次创建 `postgresql_data` 卷时自动创建 Keycloak database 和角色。
- `keycloak-db-bootstrap`：面向已有 `postgresql_data` 卷的补偿服务。因为 PostgreSQL init 脚本只在首次建库时执行，所以旧本地环境需要这个一次性服务在 Keycloak 启动前补齐 database。
- `KC_DB=postgres` 与 `KC_DB_URL=jdbc:postgresql://postgresql:5432/${DATASMART_KEYCLOAK_DB_NAME:-keycloak}`：明确 Keycloak 使用 PostgreSQL，而不是 dev 文件存储。
- `.env.application.example` 中的 `DATASMART_KEYCLOAK_DB_NAME`、`DATASMART_KEYCLOAK_DB_USERNAME`、`DATASMART_KEYCLOAK_DB_PASSWORD` 只是本地样例值。生产必须改为 Secret Manager、Kubernetes Secret、Docker secret 或企业密码库注入。

需要特别区分两类“账号数据”：

- Keycloak 数据库保存真正的登录账号、密码哈希、realm、client、角色、mapper、服务账号和会话/密钥相关状态。
- DataSmart 的 `permission_identity_user` 只保存低敏影子身份映射，例如 `providerUserId`、`tenantId`、`actorId`、`actorRole`、`actorType`、`workspaceId` 和 `status`。它用于平台授权、审计和租户上下文关联，不是密码登录库。

如果本机曾经使用旧版 `keycloak_data` 文件卷创建过用户，这些用户不会自动迁移到 PostgreSQL。推荐路径是从旧 Keycloak 导出 realm/users，再导入新的 PostgreSQL-backed Keycloak；如果只是本地学习数据，也可以在新 Keycloak 中重新执行账号供应或重新导入仓库内 realm 样板。不要直接删除旧卷来“修复”问题，除非确认其中没有需要保留的本地账号。

## 样例用户

| 用户名 | 本地密码 | 角色 | actorType | tenantId | actorId | workspace |
|---|---|---|---|---|---|---|
| `ordinary-user` | `DataSmart@123` | `ORDINARY_USER` | `USER` | `10` | `1004` | `workspace-a` |
| `project-owner` | `DataSmart@123` | `PROJECT_OWNER` | `USER` | `10` | `1001` | `workspace-a` |
| `operator` | `DataSmart@123` | `OPERATOR` | `USER` | `10` | `1002` | `workspace-a` |
| `auditor` | `DataSmart@123` | `AUDITOR` | `USER` | `10` | `1003` | `workspace-a` |
| `platform-admin` | `DataSmart@123` | `PLATFORM_ADMINISTRATOR` | `USER` | `1` | `9001` | `platform` |
| `sync-service` | `DataSmart@123` | `SERVICE_ACCOUNT` | `SERVICE_ACCOUNT` | `10` | `9101` | `system-sync` |

本地密码只为 CLI 联调准备。生产环境应禁用 password grant，使用 Authorization Code + PKCE、Client Credentials、设备码或企业 IdP 托管登录。

## 获取本地 Access Token

PowerShell 示例：

```powershell
$body = @{
  grant_type = "password"
  client_id = "datasmart-gateway"
  username = "project-owner"
  password = "DataSmart@123"
}
$token = Invoke-RestMethod -Method Post `
  -Uri "http://localhost:18080/realms/datasmart/protocol/openid-connect/token" `
  -ContentType "application/x-www-form-urlencoded" `
  -Body $body
$token.access_token
```

然后访问 gateway：

```powershell
Invoke-RestMethod -Headers @{ Authorization = "Bearer $($token.access_token)" } `
  -Uri "http://localhost:8080/auth/session"
```

gateway 应解析出 `tenantId=10`、`actorId=1001`、`actorRole=PROJECT_OWNER`、`actorType=USER` 和 `workspaceId=workspace-a`。

## 本地服务账号 Smoke

本地 realm 同时提供 `sync-service` 样例账号，用于验证服务账号 token 是否能被 gateway 映射为机器主体。推荐通过仓库根目录的 smoke 脚本执行：

```powershell
.\scripts\local-e2e-smoke-check.ps1 -CheckServiceAccountToken
```

该探针只会完成两步：

- 使用 `sync-service` 从本地 Keycloak 获取 access token。
- 携带该 token 调用 gateway `/auth/session`，确认低敏身份字段为 `actorRole=SERVICE_ACCOUNT`、`actorType=SERVICE_ACCOUNT`、`tenantId=10`、`actorId=9101`、`workspaceId=system-sync`。

脚本不会打印 access token、refresh token、密码、完整 JWT claim 或响应正文，也不会调用 agent-runtime、data-sync、task-management 的任何写入接口。

生产环境不要沿用该 password grant 示例。真实服务间调用应使用独立 confidential client、client credentials、企业 IdP 托管服务账号、mTLS 或 service mesh 身份；client secret 必须放入 Secret Manager、Kubernetes Secret、Docker secret 或企业密钥库，不能写入 realm JSON、脚本或 Git 历史。

## 账号供应与登录关系

当前系统的登录不是“在 DataSmart 数据库里查用户名和密码”，也不是“前端随便选择一个账号登录”。生产路线是 OIDC：

- 人类用户通过 Keycloak/企业 IdP 的登录页完成账号密码、MFA、账号锁定、密码策略和会话管理。
- gateway 作为 OAuth2 Resource Server 校验 access token，并把 token 中的 `tenantId`、`actorId`、`actorRole`、`actorType`、`workspaceId` 转成下游服务统一使用的 `X-DataSmart-*` 上下文。
- permission-admin 负责账号供应：租户管理员或平台管理员调用 `/api/identity/users/register`、`/api/identity/users/{providerUserId}/disable`、`/api/identity/users/{providerUserId}/password/reset`，由 permission-admin 通过 Keycloak Admin API 创建、禁用或重置外部身份。
- DataSmart 只在 `permission_identity_user` 保存影子身份映射，包括 `providerUserId`、`tenantId`、`actorId`、`actorRole`、`actorType`、`workspaceId`、`status` 等低敏控制面字段。
- DataSmart 不保存用户密码、refresh token、Keycloak admin token、client secret 或可直接登录的凭据；账号供应响应和审计 detail 也必须遵守 `NO_PASSWORD_NO_TOKEN_NO_SECRET` 策略。

如果客户已经有企业 IdP，例如公司自建 Keycloak、Okta、Azure AD、LDAP 网关或 CAS/OIDC 网关，推荐让 DataSmart 对接客户现有 IdP。此时可以关闭本地账号供应，或替换 `IdentityProviderAdminClient` 适配器为客户 IdP/SCIM 管理接口。若客户没有企业 IdP，本仓库随 Compose 提供的 Keycloak 可以作为项目自带的正式身份服务部署，但生产环境必须改用 TLS、外部数据库、专用 confidential client、最小权限管理员和安全的 Secret 注入方式。

## Claim 设计

realm 样板通过 client protocol mapper 把用户属性映射为 DataSmart 所需 claim：

- `datasmart_tenant_id`
- `datasmart_actor_id`
- `datasmart_actor_role`
- `datasmart_actor_type`
- `datasmart_workspace_id`
- `datasmart_application_id`
- `datasmart_application_code`
- `datasmart_project_ids`

同时通过 audience mapper 把 `datasmart-gateway` 写入 access token 的 `aud`，让 gateway 的 `GatewayJwtAudienceValidator` 能确认 token 是签发给 DataSmart gateway 的。

## 生产注意事项

- 不要使用 `start-dev`，生产应使用 `start`、TLS、外部数据库和固定 hostname。
- 不要使用仓库内样例用户和样例密码。
- 不要在 Git 中保存 client secret、管理员密码或服务账号密钥。
- 服务到服务调用应使用独立 confidential client、Client Credentials、mTLS 或 service mesh 身份。
- 若修改 realm JSON 后 PostgreSQL 中已经存在同名 realm，Keycloak import 通常不会覆盖现有 realm；本地可通过 Admin Console 调整、删除目标 realm 后重启导入，或在受控环境中执行正式 realm 迁移。

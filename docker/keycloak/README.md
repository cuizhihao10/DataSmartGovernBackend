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

## 样例用户

| 用户名 | 本地密码 | 角色 | actorType | tenantId | actorId | workspace |
|---|---|---|---|---|---|---|
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

## Claim 设计

realm 样板通过 client protocol mapper 把用户属性映射为 DataSmart 所需 claim：

- `datasmart_tenant_id`
- `datasmart_actor_id`
- `datasmart_actor_role`
- `datasmart_actor_type`
- `datasmart_workspace_id`

同时通过 audience mapper 把 `datasmart-gateway` 写入 access token 的 `aud`，让 gateway 的 `GatewayJwtAudienceValidator` 能确认 token 是签发给 DataSmart gateway 的。

## 生产注意事项

- 不要使用 `start-dev`，生产应使用 `start`、TLS、外部数据库和固定 hostname。
- 不要使用仓库内样例用户和样例密码。
- 不要在 Git 中保存 client secret、管理员密码或服务账号密钥。
- 服务到服务调用应使用独立 confidential client、Client Credentials、mTLS 或 service mesh 身份。
- 若修改 realm JSON 后已有 `keycloak_data` 卷，Keycloak 不一定重新覆盖现有 realm；本地可删除卷后重新导入。

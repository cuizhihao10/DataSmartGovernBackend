# FlashSync 开租初始化说明

## 1. 什么是“开租”

在 DataSmart Govern 这类企业级多租户系统中，`tenantId`、`applicationId`、`projectId`、`workspaceId` 不应该由普通用户在业务页面里随意填写。它们属于平台控制面的主数据，通常由平台管理员、交付工程师或安装脚本在客户环境启用时创建。

这个过程可以称为“开租”或“租户初始化”。它的含义不是单纯插入一条租户记录，而是一次性建立“谁是这个租户、开通了哪个应用、默认项目和工作空间是什么、哪些账号拥有初始权限、这些事实如何被审计”的完整基线。

## 2. FlashSync 本次初始化结果

本次已经把开租基线固化到 permission-admin 迁移中，应用名称为 `FlashSync`。

| 对象 | ID / 编码 | 说明 |
|---|---:|---|
| 租户 | `tenant_id=10`，`tenant_code=FLASHSYNC` | FlashSync 业务租户，对齐本地 Keycloak 样例用户的 `datasmart_tenant_id=10`。 |
| 应用 | `application_id=10010`，`application_code=FLASHSYNC` | FlashSync 数据同步应用，承载数据源、同步任务、质量校验和 Agent 辅助配置入口。 |
| 默认项目 | `project_id=101`，`project_code=FLASHSYNC_DEFAULT` | 用户首次创建数据源、同步模板、同步任务、质量规则或 Agent 会话时的默认业务项目。 |
| 默认工作空间 | `workspace_id=10001`，`external_workspace_key=workspace-a` | 对齐本地 Keycloak 样例用户的 `datasmart_workspace_id=workspace-a`。 |
| 系统同步空间 | `workspace_id=10002`，`external_workspace_key=system-sync` | 给同步 worker、调度器、服务账号等机器主体使用，避免和人工配置空间混杂。 |
| 平台管理租户 | `tenant_id=1`，`application_id=9000`，`workspace_id=90001` | 给 `platform-admin` 这类平台管理员使用，不承载客户业务数据。 |

## 3. 为什么同时有数字 ID 和字符串 key

当前项目里存在一个历史兼容点：Java 业务表大量使用数字型 `workspace_id`，而 Keycloak 本地样例 Token 里的 workspace claim 是字符串，例如 `workspace-a`。

因此新的 `permission_workspace` 表同时保存两类字段：

- `workspace_id`：数字 ID，适合进入业务表、索引、统计、数据范围过滤和外键关系。
- `external_workspace_key`：外部稳定 key，适合进入 OIDC claim、前端路由上下文、日志和人工排障。

后续如果决定把 Token 中的 `datasmart_workspace_id` 统一改成数字，也可以通过 `permission_workspace` 做平滑迁移，而不用修改所有业务表。

## 4. 账号到底存在哪里

真实登录账号仍然存放在 Keycloak 或企业 IdP 中。Keycloak 保存用户名、密码哈希、MFA、会话、realm、client、role、mapper 等身份系统事实。

DataSmart 自己的 `permission_identity_user` 只保存低敏影子身份映射，例如：

- `tenant_id`
- `actor_id`
- `provider_mode`
- `provider_user_id`
- `username`
- `actor_role`
- `actor_type`
- `workspace_id`
- `status`

它不保存密码、access token、refresh token、client secret 或 Keycloak admin token。这样做的目的是让业务系统可以做授权、审计、项目成员关系和租户归属查询，同时避免自己实现一套不成熟的密码登录系统。

## 5. FlashSync 初始化账号映射

本地 Keycloak realm 样例账号已经和 FlashSync 主数据对齐：

| 用户名 | tenantId | actorId | role | workspace | application |
|---|---:|---:|---|---|---|
| `project-owner` | `10` | `1001` | `PROJECT_OWNER` | `workspace-a` | `FLASHSYNC` |
| `operator` | `10` | `1002` | `OPERATOR` | `workspace-a` | `FLASHSYNC` |
| `auditor` | `10` | `1003` | `AUDITOR` | `workspace-a` | `FLASHSYNC` |
| `sync-service` | `10` | `9101` | `SERVICE_ACCOUNT` | `system-sync` | `FLASHSYNC` |
| `platform-admin` | `1` | `9001` | `PLATFORM_ADMINISTRATOR` | `platform` | `DATASMART_PLATFORM` |

其中 `project-owner/operator/auditor/sync-service` 都会被授予 FlashSync 默认项目 `project_id=101` 的项目成员关系。这样 permission-admin 在 PROJECT 数据范围判定时，可以把授权项目集合物化给 gateway，再由 gateway 透传给 data-sync、data-quality、agent-runtime 等服务。

## 6. 本次落地文件

PostgreSQL 主路径：

```text
permission-admin/src/main/resources/db/migration/postgresql/permission-admin/V12__tenant_application_workspace_bootstrap.sql
```

MySQL 兼容增量：

```text
docker/mysql/migrations/20260718_tenant_application_workspace_bootstrap.sql
```

MySQL fresh init 后置脚本：

```text
docker/mysql/init/zz-permission-admin-flashsync-tenant-bootstrap.sql
```

Keycloak 本地 realm 样例：

```text
docker/keycloak/import/datasmart-realm.json
```

## 7. 后续正式开租流程建议

当前是用迁移脚本完成 FlashSync 的本地基线开租。商业化产品后续建议把它升级为受控管理能力：

1. 平台管理员创建租户，填写租户名称、套餐、负责人、启用状态和审计原因。
2. 平台管理员或租户管理员开通应用，例如 FlashSync、质量治理、资产目录等。
3. 系统创建默认项目和默认工作空间，必要时再创建生产、测试、系统执行空间。
4. 管理员通过 Keycloak/企业 IdP/SCIM 创建或导入账号。
5. permission-admin 写入影子身份映射和项目成员授权。
6. gateway 从 OIDC Token 解析 tenant/actor/role/workspace/application claim。
7. permission-admin 做路由权限和数据范围判定，并把授权项目集合返回给 gateway。
8. 业务服务只消费可信的 `X-DataSmart-*` Header，不直接信任前端提交的租户或工作空间 ID。

这个流程可以避免“用户手填内部 ID 导致越权、串租户、审计解释不清”的问题，也为未来多租户、多应用、多工作空间的商业化部署打下基础。

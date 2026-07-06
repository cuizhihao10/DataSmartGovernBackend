# DataSmart Govern 本地端到端闭环联调 Runbook

## 1. 本文目标

本文用于把当前已经完成的核心能力收敛为一条可启动、可探测、可复查的本地闭环链路。它不是生产部署手册，也不是压测方案，而是给开发者、架构评审者和后续 AI 辅助开发线程提供一个统一的“项目是否已经串起来”的检查入口。

当前最小闭环覆盖：

- `Keycloak`：本地 OIDC 身份提供方，负责签发标准 access token。
- `gateway`：平台入口，负责 JWT 校验、平台身份上下文映射和 permission-admin 授权判定转发。
- `permission-admin`：授权中心，负责角色、路由、资源、服务账号委托和审计责任链判定。
- `task-management`：任务中心，负责 DataSync worker command outbox 和 execution receipt 投影。
- `data-sync`：数据同步控制面，负责模板、execution、worker loop、run-once dispatch 和 receipt 投递。
- `datasource-management`：数据源与 connector runtime，负责受控单批 run-once 读写执行。
- `python-ai-runtime`：Agent Host 运行时，负责能力闭口诊断、Skill Manifest 消费、模型网关诊断和受控规划入口。

增强闭环依赖暂不作为本 runbook 的硬性通过条件：

- `agent-runtime`：用于 Agent plans、tools、skills、memory、sessions、runtime events 等 Java 控制面闭环。
- `Chroma`：用于长期语义记忆、RAG、语义检索和后续模型上下文增强。
- `Neo4j`：用于血缘、资产关系、GraphRAG 和治理知识图谱。
- `MinIO`：用于报告、工件、大对象、导出文件和后续 artifact 读取授权。
- `Prometheus/Grafana/Alertmanager`：用于全链路运维观测和告警。

## 2. 为什么要先做本地闭环

当前项目已经具备很多生产级方向的能力碎片，例如 OIDC、服务账号委托、DataSync worker loop、task-management receipt、Agent runtime event projection、memory index 等。如果没有一个统一的本地联调入口，后续开发容易继续在单个模块里无限扩展，导致“某个模块越来越丰富，但平台整体仍然没有闭合”。

本 runbook 的设计原则：

- 先确认跨模块链路是否可启动、可访问、可诊断。
- 默认只做只读检查，不触发真实数据搬运。
- 把认证中心、授权中心、任务中心、同步控制面和数据源执行面放到同一条路径上审视。
- 把当前缺口显式写出来，避免把本地开发样板误认为商用部署完成态。

## 3. 前置条件

本地机器至少需要：

- JDK 21。本仓库已配置 Maven Toolchains，若 `mvn -v` 显示 Java 8，请先阅读 [development-jdk21.md](development-jdk21.md)。
- Maven。建议所有命令附带本地仓库参数，避免污染全局 Maven 缓存。
- Docker Desktop 或兼容 Docker daemon。
- PowerShell 7 或 Windows PowerShell。当前 smoke 脚本使用 PowerShell 编写。
- 可用端口：`8080`、`8081`、`8082`、`8085`、`8086`、`8090`、`8091`、`18080`、`3306`、`6379`、`8848`、`9092`、`9090`、`3000`。

建议固定使用的 Maven 参数：

```powershell
-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2
```

### 3.1 启动前环境就绪诊断

在启动容器、Java 微服务或 Python Runtime 之前，建议先运行环境就绪脚本：

```powershell
.\scripts\local-e2e-environment-readiness.ps1
```

该脚本的定位是“启动前体检”，不是 smoke check。它会检查 Docker CLI/daemon、`mysql.exe`、MySQL 凭据环境变量是否设置、关键端口是否已经打开、`fastapi/uvicorn` 是否可导入，以及 Python Runtime 低敏诊断接口是否可访问。

脚本默认不会猜测数据库密码，也不会连接 MySQL 执行任何 SQL。如果你已经明确设置了本地开发库账号密码，可以追加凭据探针：

```powershell
$env:DATASMART_MYSQL_USER = "root"
$env:DATASMART_MYSQL_PASSWORD = "<请填写本地开发库密码>"
.\scripts\local-e2e-environment-readiness.ps1 -ProbeMySqlCredential
```

安全边界：

- MySQL 凭据探针只执行 `SELECT 1`，不读取业务表、不创建库表、不应用 migration。
- 脚本只报告环境变量是否设置，不打印任何密码、token、SQL、HTTP 响应正文或业务数据。
- Python Runtime 探针只检查 3 个低敏 GET 诊断端点的状态码，不解析或保存响应正文。
- 如果输出中 Docker、Redis、Kafka、Nacos、Keycloak、gateway、Java 微服务端口为 `FAIL`，优先启动依赖或服务，不要先怀疑业务代码。

## 4. 启动顺序

### 4.1 启动基础设施

最小闭环建议先启动这些容器：

```powershell
docker compose up -d mysql redis zookeeper kafka nacos keycloak prometheus grafana
```

如果要同时验证 AI 增强链路，可以再启动：

```powershell
docker compose up -d neo4j minio chroma alertmanager
```

注意事项：

- `docker-compose.yml` 中的 Keycloak、Grafana、MySQL、Nacos 等默认账号只允许本地开发使用，生产环境必须接入正式 Secret Manager、TLS、外部数据库和企业身份体系。
- `docker/mysql/init` 只会在 MySQL 数据卷首次初始化时自动执行。
- `docker/mysql/migrations` 不会被当前 Compose 自动执行；如果本地 MySQL 数据卷已经存在，需要人工应用迁移或后续接入 Flyway/Liquibase。

### 4.2 应用数据库迁移

如果你是全新数据库卷，初始化脚本会创建基础库表，但历史增量迁移仍建议按文件名顺序检查。当前仓库尚未接入统一迁移框架，因此本地联调时要特别注意 schema 是否和代码一致。

迁移命令示例：

```powershell
$env:DATASMART_MYSQL_USER = "root"
$env:DATASMART_MYSQL_PASSWORD = "<请填写本地开发库密码>"

Get-ChildItem -LiteralPath .\docker\mysql\migrations -Filter *.sql |
    Sort-Object Name |
    ForEach-Object {
        Write-Host "Applying migration: $($_.Name)"
        Get-Content -Raw -Encoding UTF8 -LiteralPath $_.FullName |
            docker exec -i datasmart-mysql mysql -u$env:DATASMART_MYSQL_USER -p$env:DATASMART_MYSQL_PASSWORD datasmart_govern
    }
```

迁移边界说明：

- 执行迁移前建议备份本地数据卷或确认这是可丢弃的开发库。
- 不要把生产数据库密码写入脚本、文档或提交历史。
- 当前最小闭环至少需要 task-management outbox、task-management receipt、data-sync template execution contract 和 data-sync task-management receipt outbox 相关迁移。
- 当前仓库已经提供过渡型本地迁移治理脚本 [local-mysql-migration-governance.ps1](../scripts/local-mysql-migration-governance.ps1)，用于把 schema 版本从“人工记忆”推进到“可检查、可登记、可回放”的闭环状态；后续商业化收敛仍建议引入 Flyway 或 Liquibase。

建议先执行静态检查，确认迁移目录里没有命名漂移、空文件或重复 migrationId：

```powershell
.\scripts\local-mysql-migration-governance.ps1 -StaticOnly
```

当 MySQL 容器已经启动后，可以查看当前数据库迁移计划。默认模式只读取历史表和输出计划，不执行 SQL：

```powershell
.\scripts\local-mysql-migration-governance.ps1
```

迁移脚本默认使用 `-ConnectionMode Auto`。在 Auto 模式下，它会优先使用正在运行的 `datasmart-mysql` Docker 容器；如果当前机器没有 Docker CLI、Docker Desktop 未加入 PATH，或容器没有运行，但本机存在 `mysql.exe`，脚本会退到本机 MySQL CLI 连接 `127.0.0.1:3306/datasmart_govern`。如果你明确要使用某一种模式，可以手动指定：

```powershell
.\scripts\local-mysql-migration-governance.ps1 -ConnectionMode Docker
.\scripts\local-mysql-migration-governance.ps1 -ConnectionMode LocalCli
```

本机 MySQL CLI 模式可以通过参数或环境变量覆盖连接信息：

```powershell
$env:DATASMART_MYSQL_USER = "root"
$env:DATASMART_MYSQL_PASSWORD = "<请填写本地开发库密码>"
.\scripts\local-mysql-migration-governance.ps1 -ConnectionMode LocalCli -MySqlHost 127.0.0.1 -MySqlPort 3306 -DatabaseName datasmart_govern
```

如果本机有 `mysql.exe` 但 MySQL 服务没有启动，脚本会输出 `MySQL 连接` 失败；这表示当前只能完成 migration 文件静态治理，暂时不能读取真实数据库历史。

常见低敏错误码说明：

- `ACCESS_DENIED`：MySQL 服务可达，但用户名或密码不匹配。请检查 `-MySqlUser`、`DATASMART_MYSQL_USER`、`DATASMART_MYSQL_PASSWORD` 或本地 root 密码。
- `UNKNOWN_DATABASE`：MySQL 服务和凭据可用，但 `datasmart_govern` 数据库不存在。请先创建开发库，或通过 `-DatabaseName` 指定已有库。
- `CONNECTION_FAILED`：端口不可达、服务未启动、连接被防火墙阻断，或应该改用 Docker 模式。
- `HOST_UNRESOLVED`：`-MySqlHost` 主机名无法解析。
- `MYSQL_CLI_ARGUMENT_ERROR`：本机 `mysql.exe` 参数兼容性或版本存在问题。

这些错误码只用于排障分类，脚本不会打印 MySQL 密码、SQL 正文或原始错误正文。

如果确认这是可变更的本地开发库，并且希望执行尚未登记的 migration，再显式追加 `-Apply`：

```powershell
.\scripts\local-mysql-migration-governance.ps1 -Apply
```

如果某个旧本地库已经人工执行过迁移，但之前没有历史表，可以使用 `-BaselineExisting` 做补登记。该模式不会执行 SQL，只把当前仓库 migration 文件名和 SHA-256 校验和登记到 `datasmart_schema_migration_history`，用于后续发现文件漂移：

```powershell
.\scripts\local-mysql-migration-governance.ps1 -BaselineExisting
```

迁移治理脚本的设计边界：

- 默认不执行 SQL，避免把只读 smoke 或计划检查变成数据库变更动作。
- `-Apply` 按文件名顺序执行 `docker/mysql/migrations/*.sql`，执行完成后登记 migrationId、文件名、SHA-256、耗时和执行模式。
- `-BaselineExisting` 只补登记，不代表真实执行过 SQL；它适合开发库补账，不应作为生产跳过迁移的手段。
- 脚本不会打印 MySQL 密码、业务数据、SQL 正文或查询结果正文。
- 该脚本是 Flyway/Liquibase 前的本地闭环过渡层，不是最终生产迁移系统。

### 4.3 启动 Java 微服务与 Python AI Runtime

优先使用统一启动脚本：

```powershell
.\scripts\local-e2e-start-runtime.ps1 -MySqlPort 13306
```

脚本会做这些事情：

- 设置本地 E2E 所需环境变量，例如 Docker MySQL `13306`、Nacos `8848`、Kafka `9092`、Keycloak issuer、Python `PYTHONPATH`。
- 先把 `platform-common` 安装到项目级 `.m2`，再进入每个子模块目录启动 Spring Boot 应用。
- 后台启动 `permission-admin`、`task-management`、`datasource-management`、`data-quality`、`observability`、`data-sync`、`agent-runtime`、`gateway` 和 `python-ai-runtime`。
- 端口已打开时跳过重复启动，避免多进程抢端口。
- stdout/stderr 写入 `logs/local-e2e/*.log`，该目录已被 `.gitignore` 忽略。

为什么推荐脚本而不是直接复制多条 Maven 命令：

- `mvn -pl <module> -am spring-boot:run` 会把父 POM 也纳入执行，父 POM 没有 Spring Boot main class，真实启动时会报 `Unable to find a suitable main class`。
- 当前各模块普通 `package` 产物还不是可直接 `java -jar` 的 Spring Boot fat jar，直接运行 jar 会报缺少主清单。
- 子模块目录内执行 `mvn spring-boot:run` 是当前最稳定的本地联调方式；如果后续要切到可执行 jar 或容器镜像，应单独补 Spring Boot repackage、镜像构建和生产启动参数。

如需手动排障，可以先安装共享模块：

```powershell
mvn -pl platform-common -DskipTests install "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"
```

再进入单个子模块目录启动，例如：

```powershell
cd .\permission-admin
mvn spring-boot:run "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"
```

### 4.4 Python AI Runtime 直连说明

Python Runtime 默认不强绑定 FastAPI 依赖，便于离线单测和学习。如果只想单独验证 Python 诊断链路，可以手动启动 `8090`：

```powershell
$env:PYTHONPATH = "$PWD\python-ai-runtime\src"
python -m pip install -e ".\python-ai-runtime[api]"
python -m uvicorn "datasmart_ai_runtime.api:create_app" --factory --host 127.0.0.1 --port 8090
```

启动后可以直连这些低敏诊断接口：

```text
GET http://localhost:8090/agent/capabilities/closure-readiness
GET http://localhost:8090/agent/skills/publication/diagnostics
GET http://localhost:8090/agent/models/inference-optimization/diagnostics
GET http://localhost:8090/agent/metrics
```

这些接口只用于闭口检查、运行时诊断和 Prometheus 低基数指标导出，不会执行工具、不创建任务、不读取源端数据、不写 worker outbox，也不会返回 prompt、SQL、工具参数、样本数据、模型输出、token、内部 endpoint 或长期记忆正文。`/agent/metrics` 只允许输出固定枚举标签，例如 LangGraph workflow 状态、记忆检索状态、模型 Provider 健康状态、checkpoint 查询结果和 Agent 交付分层；它不能输出 tenantId、projectId、runId、sessionId、memoryId、memory namespace 或任何业务正文。

启动顺序说明：

- `permission-admin` 应先于 `gateway` 启动，否则 gateway 的强授权模式会无法访问授权中心。
- `task-management` 应先于需要投递 receipt 的 `data-sync` 运行，否则 receipt 投影只能走低敏失败日志。
- `datasource-management` 应先于触发 data-sync worker loop 运行，否则 run-once dispatch 会 fail-closed。
- `data-sync` 的 worker loop scheduler 默认不建议开启，避免服务启动后无意触发真实数据搬运。

### 4.5 数据同步真实数据库 E2E（显式写入验收）

当只读 smoke check 通过后，如果需要进一步确认“数据同步执行面真的能搬运数据”，可以运行专用脚本：

```powershell
.\scripts\local-data-sync-real-e2e.ps1
```

该脚本会执行以下动作：

- 启动或复用 `docker-compose.yml + docker-compose.local-e2e.yml` 中的 `postgresql` 与 `mysql` 容器，其中 MySQL 默认暴露在 `13306`，用于避开 Windows 本机 `MySQL80` 常见的 `3306` 占用。
- 等待 TCP 端口可达后，再继续等待容器内 `SELECT 1` 凭据探针成功，避免 MySQL 端口刚打开但 database/user/permission 还没初始化完成时抢跑。
- 只为当前 Maven 进程注入 `DATASMART_E2E_REAL_JDBC=true`、MySQL/PostgreSQL JDBC URL、账号和密码，不把凭据写入仓库文件。
- 运行 `SyncBatchConnectorRuntimeExternalJdbcE2ETest`，验证 MySQL 源表到 PostgreSQL 目标表的真实 JDBC 同步链路。

安全边界：

- 该脚本不是只读检查，会创建/覆盖专用 E2E 表：MySQL `datasmart_e2e_source_customers` 与 PostgreSQL `datasmart_e2e.customers_clean`。
- 脚本和测试不会打印密码、完整 JDBC URL、SQL 正文、源端样本行、目标端样本行、JWT 或 token。
- 该脚本只验证 datasource-management Java Reader/Writer 执行面；data-sync 控制面、对象账本、选择性重试已经由独立 E2E 测试覆盖。
- 如果只想检查脚本计划，不启动容器、不运行 Maven，可以使用：

```powershell
.\scripts\local-data-sync-real-e2e.ps1 -PlanOnly
```

当前通过标准：

- Docker daemon 可用；
- `datasmart-mysql` 与 `datasmart-postgresql` 可启动或已运行；
- MySQL `127.0.0.1:13306`、PostgreSQL `127.0.0.1:5432` 可达；
- 两个数据库的 E2E 用户均可执行 `SELECT 1`；
- `mvn -pl datasource-management -am -Dtest=SyncBatchConnectorRuntimeExternalJdbcE2ETest -Dsurefire.failIfNoSpecifiedTests=false test -DskipTests=false` 通过。

## 5. Smoke Check

仓库提供了只读 smoke 脚本：

```powershell
.\scripts\local-e2e-smoke-check.ps1
```

脚本默认检查：

- 关键文件是否存在，例如根 `pom.xml`、JDK 21 文档、Compose 文件、Keycloak realm、关键 MySQL 迁移。
- Gateway 是否保留 `spring-cloud-starter-loadbalancer`、Caffeine 实例缓存和 404/503 状态码保留处理，避免 `lb://` 服务发现路由退化为运行时 500。
- 关键容器是否运行，例如 MySQL、Redis、Kafka、Nacos、Keycloak、Prometheus、Grafana。
- 关键 HTTP 探针是否可访问，例如 `/actuator/health`、Keycloak realm metadata、gateway auth capabilities、data-sync connector capabilities、task-management receipt query。
- AI Runtime 闭环契约是否仍然存在，例如 `/agent/metrics` 指标路由、`agentMemoryRetrievalWorkflow` 低基数指标记录、多 Agent `runtimeAgentDeliveryTiers` 分层、gateway `/api/agent/metrics` 统一入口和能力矩阵证据。
- Java Agent Runtime 控制面事实是否可查询，例如 sessions、tools、Skill Manifest、model routes、runtime event diagnostics、Skill 可见性投影诊断、工具事件 outbox 诊断和异步命令 outbox 诊断。

如果只是验证脚本语法和仓库文件完整性：

```powershell
.\scripts\local-e2e-smoke-check.ps1 -SkipDocker -SkipHttp
```

如果需要在 CI 或严格验收中失败即退出：

```powershell
.\scripts\local-e2e-smoke-check.ps1 -Strict
```

如果需要验证本地 Keycloak 样例服务账号是否能被 gateway 解析为机器主体：

```powershell
.\scripts\local-e2e-smoke-check.ps1 -CheckServiceAccountToken
```

该探针会使用本地 realm 中的 `sync-service` 样例账号向 Keycloak 获取 access token，然后只调用 gateway 的 `/auth/session` 读取低敏身份视图。它的通过条件是 gateway 返回 `tenantId=10`、`actorId=9101`、`actorRole=SERVICE_ACCOUNT`、`actorType=SERVICE_ACCOUNT`、`workspaceId=system-sync`。脚本不会打印 access token、refresh token、密码、完整 JWT claim 或响应正文，也不会调用任何 POST 业务接口。

如果需要进一步验证“认证后的统一 gateway 入口是否能访问 Python AI Runtime 与 Java Agent Runtime 的低敏诊断/只读控制面接口”，可以在服务账号探针基础上追加 gateway Agent 诊断探针：

```powershell
.\scripts\local-e2e-smoke-check.ps1 -CheckServiceAccountToken -CheckAgentGatewayDiagnostics
```

该探针会继续使用本地 `sync-service` 样例账号获取 Bearer token，然后通过 gateway 调用以下只读入口：

```text
GET http://localhost:8080/api/agent/capabilities/closure-readiness
GET http://localhost:8080/api/agent/skills/publication/diagnostics
GET http://localhost:8080/api/agent/models/inference-optimization/diagnostics
GET http://localhost:8080/api/agent/metrics
GET http://localhost:8080/api/agent/sessions
GET http://localhost:8080/api/agent/tools/descriptors
GET http://localhost:8080/api/agent/skills/publication/manifest
GET http://localhost:8080/api/agent/models/routes
GET http://localhost:8080/api/agent/runtime-events/diagnostics
GET http://localhost:8080/api/agent/runtime-events/skill-visibility-snapshots/diagnostics
GET http://localhost:8080/api/agent/tool-execution-events/outbox/diagnostics
GET http://localhost:8080/api/agent/async-task-commands/outbox/diagnostics
```

设计意图是验证真实入口链路中的 `Keycloak -> gateway OIDC -> permission-admin route authorization -> gateway route rewrite -> Python Runtime / Java agent-runtime` 是否贯通，而不是验证 Python Runtime `8090` 或 Java agent-runtime `8091` 直连端口本身。脚本只检查 HTTP 状态码，不解析、不保存、不打印诊断、指标或控制面响应正文；即使后续诊断字段继续扩展，或 Prometheus 指标族继续增加，也不会把 prompt、SQL、工具参数、样本数据、模型输出、token、内部 endpoint、长期记忆正文、会话明细、工具目录详情、模型路由详情或 outbox 排障正文带到终端日志里。

故障判断建议：

- 如果返回 `401/403`，优先检查 Keycloak realm、`aud=datasmart-gateway`、DataSmart 必需 claim、gateway OIDC 配置和 permission-admin 路由策略。
- 如果返回 `500`，且 gateway 日志包含 `Unable to resolve the Configuration with the provided Issuer` 或 `Connection refused: localhost/127.0.0.1:18080`，说明容器内 Gateway 正在用外部 issuer 地址做 discovery。全容器 E2E 应保留 `DATASMART_GATEWAY_OIDC_ISSUER_URI=http://localhost:18080/realms/datasmart` 校验 token 的 `iss`，同时设置 `DATASMART_GATEWAY_OIDC_JWK_SET_URI=http://keycloak:18080/realms/datasmart/protocol/openid-connect/certs` 让 Gateway 通过容器 DNS 拉取公钥。
- 如果返回 `502/503` 或超时，优先检查 gateway 路由顺序、`python-ai-runtime-runtime-diagnostics` 路由是否仍位于通用 `/api/agent/** -> agent-runtime` 之前、Python Runtime 是否已在 `8090` 端口启动，以及 Java agent-runtime 是否已在 `8091` 端口启动。
- 如果 `/auth/session` 通过但 Agent 诊断路由失败，说明身份解析已经成功，问题更可能集中在 permission-admin 对 `/api/agent/**` 诊断路由的授权、gateway route rewrite、Python Runtime 下游可达性或 Java agent-runtime 下游可达性。
- 如果 Gateway 日志出现 `NoLoadBalancerClientFilter` 或 `Unable to find instance for <service>`，先确认 `gateway/pom.xml` 保留 `spring-cloud-starter-loadbalancer`，再确认 Nacos 中存在健康实例并重启 Gateway。仅引入 Nacos Discovery 只能完成注册发现，不能替代 Gateway 执行 `lb://` 路由所需的 Reactive LoadBalancer。
- Gateway 的 `GlobalExceptionHandler` 应保留 Spring 已判定的 404/503 状态码。503 表示下游暂时不可用，调用方可以执行受控重试或熔断；500 表示 Gateway 自身未知错误，两者不应混淆。公开响应只返回通用状态消息，不回传内部服务名、实例地址或异常 reason。

2026-07-01 当前本地验证基线：

```text
.\scripts\local-e2e-smoke-check.ps1 -CheckServiceAccountToken -CheckAgentGatewayDiagnostics
PASS=89, WARN=0, FAIL=0
```

该结果证明当前本机的 Keycloak、Gateway、permission-admin、Java 服务、Python Runtime、Prometheus/Grafana，以及认证后的 Python/Java Agent 控制面只读链路可以贯通。它仍然不是生产发布证明；生产环境还需要独立完成高可用部署、容量压测、故障演练、Secret 管理、TLS/mTLS、正式 IdP、备份恢复和升级回滚验证。

需要特别注意：`sync-service + password grant` 只服务于本地开发 smoke。生产环境不应使用 password grant 或仓库内样例密码，服务间调用应改为 OIDC client credentials、企业 IdP 托管服务账号、mTLS 或 service mesh 身份，并把 client secret 放入 Secret Manager、Kubernetes Secret 或企业密钥库。

脚本安全边界：

- 不创建任务。
- 不调用 `POST /sync-workers/run-once`。
- 不调用 datasource-management run-once。
- 不读取源端数据。
- 不写入目标端数据。
- 不调用 Agent Runtime 的 publish、refresh、dispatch、requeue、ack、enqueue 或会话创建入口。
- 不打印 token、client secret、数据库密码、SQL、样本数据、prompt、模型输出或内部请求正文。

## 6. 关键探针清单

| 能力 | 默认地址 | 通过含义 |
| --- | --- | --- |
| Keycloak realm metadata | `http://localhost:18080/realms/datasmart/.well-known/openid-configuration` | 本地 OIDC realm 可访问；宿主机 token issuer 保持 localhost，gateway 容器通过 `DATASMART_GATEWAY_OIDC_JWK_SET_URI` 使用 `keycloak:18080` 拉取 JWKS |
| Gateway health | `http://localhost:8080/actuator/health` | 网关进程存活 |
| Gateway auth capabilities | `http://localhost:8080/auth/capabilities` | 认证中心配置可被只读查看 |
| Permission Admin health | `http://localhost:8085/actuator/health` | 授权中心进程存活 |
| Task Management health | `http://localhost:8081/actuator/health` | 任务中心进程存活 |
| Task receipt query | `http://localhost:8081/internal/data-sync-worker-execution-receipts?limit=1` | DataSync receipt 投影查询入口可访问 |
| Datasource Management health | `http://localhost:8082/actuator/health` | 数据源执行面进程存活 |
| Data Sync health | `http://localhost:8086/actuator/health` | 数据同步控制面进程存活 |
| Data Sync capabilities | `http://localhost:8086/sync-connectors/capabilities` | 连接器能力目录可查询 |
| Agent Runtime health | `http://localhost:8091/actuator/health` | Agent Java 控制面进程存活 |
| Agent Runtime sessions query | `http://localhost:8091/agent-runtime/sessions` | 会话控制面只读列表入口可访问，不创建会话或运行 |
| Agent Runtime tool descriptors | `http://localhost:8091/agent-runtime/tools/descriptors` | 工具目录机器可读描述符可查询，支撑 Python Runtime 和智能网关规划前检查 |
| Agent Runtime Skill publication manifest | `http://localhost:8091/agent-runtime/skills/publication/manifest` | Java Skill Manifest 可查询，支撑 Python Runtime Skill Publication 消费链路 |
| Agent Runtime model routes | `http://localhost:8091/agent-runtime/models/routes` | Java 模型路由控制面可查询，支撑模型网关与运行时治理对齐 |
| Agent Runtime runtime event diagnostics | `http://localhost:8091/agent-runtime/runtime-events/diagnostics` | runtime event consumer/projection 诊断可查询 |
| Agent Runtime Skill visibility diagnostics | `http://localhost:8091/agent-runtime/runtime-events/skill-visibility-snapshots/diagnostics` | Skill 可见性快照索引诊断可查询 |
| Agent Runtime tool event outbox diagnostics | `http://localhost:8091/agent-runtime/tool-execution-events/outbox/diagnostics` | 工具事件 outbox 堆积、失败和阻断诊断可查询 |
| Agent Runtime async command outbox diagnostics | `http://localhost:8091/agent-runtime/async-task-commands/outbox/diagnostics` | 异步命令 outbox 投递、失败和恢复状态诊断可查询 |
| Python Runtime closure readiness | `http://localhost:8090/agent/capabilities/closure-readiness` | Agent Host 能力闭口门禁可查询 |
| Python Runtime Skill Manifest diagnostics | `http://localhost:8090/agent/skills/publication/diagnostics` | Python 是否看见 Java Skill Manifest、缓存和 fallback 状态可查询 |
| Python Runtime inference optimization diagnostics | `http://localhost:8090/agent/models/inference-optimization/diagnostics` | 模型推理优化控制面缺口可查询 |
| Prometheus ready | `http://localhost:9090/-/ready` | 指标系统可接收查询 |
| Grafana health | `http://localhost:3000/api/health` | 看板系统可访问 |

## 7. 什么时候才触发真实 worker loop

默认情况下，不要为了 smoke check 直接触发：

```http
POST http://localhost:8086/internal/sync-workers/run-once
```

只有满足以下条件时才建议手动触发：

- task-management 已经存在可被 data-sync 消费的 command outbox。
- data-sync 已经创建对应 sync task、template 和 execution。
- template 的 source/target datasource、对象定位、写入策略、字段映射和 checkpoint 约束完整。
- datasource-management 可以访问源端和目标端，并且凭据由服务端安全读取。
- 已确认这是测试库、测试表或可回滚环境。
- 已开启或配置服务账号签名、HMAC、OIDC service account、mTLS 或可信内网边界。
- 已确认当前模式属于最小闭环支持范围，例如 FULL/ONE_TIME_MIGRATION 单批场景。

如果上述条件不满足，worker loop 应该 fail-closed，而不是“尽力猜测”如何同步。

## 8. 当前闭环缺口

当前项目已经开始从能力扩展转向闭环收敛，但还不能把本地 runbook 视为商业部署完成态。主要缺口包括：

- 数据库迁移仍是手动执行，缺少 Flyway/Liquibase 级别的 schema 版本治理。
- 服务到服务调用仍在逐步从临时 Header/HMAC 迁移到 OIDC service account、mTLS 或 service mesh 身份。
- data-sync receipt 已具备本地 outbox/retry/dead-letter；后续仍需要在真实联调中验证 task-management 故障、恢复和死信告警路径。
- data-sync 最小执行闭环主要支持 FULL/ONE_TIME_MIGRATION 单批，增量 checkpoint handoff、多批循环和分片并发仍需谨慎收敛。
- Agent 侧 tools、skills、memory、query engine、context、permission、sub-agent、sessions、command、hook、LLM provider 还需要整理成最小闭口清单。
- Compose 是本地开发工具，不是生产部署方案；生产仍需要 Kubernetes/Helm、Secret Manager、TLS、外部数据库、审计、备份和容量规划。

## 9. 下一步收敛建议

建议后续不要再围绕某个局部模块无限扩展，而是按闭环优先级推进：

1. 完成一次本地最小链路实际启动验证，记录哪些服务能启动、哪些依赖配置仍阻塞。
2. 为数据库迁移引入统一版本管理，减少新环境搭建时的 schema 漂移。
3. 把 Agent 能力整理为最小闭口清单：tools、skills、memory、query engine、context、permission、sub-agent、sessions、command、hook、LLM provider。
4. 对模型层保持 provider-neutral 策略，优先接入成熟推理服务、缓存、限流、重试、token budget 和可观测，而不是在本项目内做底层算法或微调。
5. 做一次真实故障演练：暂停 task-management、让 data-sync 产生 receipt、确认 outbox RETRY_WAIT、恢复 task-management 后确认 DELIVERED。

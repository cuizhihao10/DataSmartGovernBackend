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

核心 Java 服务的 PostgreSQL schema 已按服务隔离并接入 Flyway；全新数据库卷会由各服务按版本执行迁移。MySQL 初始化与 `docker/mysql/migrations` 只保留给尚未下线的兼容路径，历史存量导入仍需按迁移文档执行并对账。本地联调必须检查 Flyway 版本、校验和和目标 schema，不能只看到容器健康就假定数据库与源码一致。

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

### 4.6 数据同步闭环验收总入口

当只想确认“数据同步主链路是否仍然闭合”，但暂时不想启动 Docker、Nacos、真实 Java 服务进程或真实数据库写入时，建议优先运行统一闭环验收脚本：

```powershell
.\scripts\local-data-sync-closure-suite.ps1
```

该脚本默认按以下顺序运行快速、稳定、低副作用的守门项：

- `data-sync` 控制面 run-once 闭环与 OBJECT_LIST 失败对象选择性重试 E2E；
- `data-sync -> datasource-management` run-once HTTP 契约 E2E；
- `datasource-management` H2/JDBC connector runtime E2E；
- `data-sync + datasource-management` 编译守门。

这四层验证的含义不同：

- 控制面 E2E 证明 `data-sync` 能把模板、execution、worker plan 转换为可执行计划，并正确处理多批次、对象账本、部分成功和选择性重试；
- HTTP 契约 E2E 证明 `HttpDatasourceRunOnceClient` 会用真实 `RestClient` 发出 internal Header、JSON 请求体并消费 datasource-management 风格的 `code/message/data` envelope；
- H2/JDBC 执行面 E2E 证明 datasource-management 的 Java Reader/Writer 真的可以在 JDBC 路径上执行过滤、字段映射、批次推进和目标写入；
- 编译守门用于发现接口、DTO、依赖或 JDK 21 语法层面的破坏性变更。

脚本安全边界：

- 默认不启动 Docker；
- 默认不连接真实 MySQL/PostgreSQL；
- 默认不创建任务、不触发 worker loop、不读取源端业务数据、不写入目标业务数据；
- 默认不打印数据库密码、完整 JDBC URL、SQL 正文、样本行、token、内部响应正文或敏感诊断信息。

如果只想查看脚本计划，不执行 Maven：

```powershell
.\scripts\local-data-sync-closure-suite.ps1 -PlanOnly
```

如果本地已经通过 `local-e2e-start-runtime.ps1` 或手动方式启动了 `task-management`、`datasource-management` 与 `data-sync`，可以追加服务进程 readiness 探针：

```powershell
.\scripts\local-data-sync-closure-suite.ps1 -CheckServiceReadiness
```

该探针会做以下只读/无副作用检查：

- `GET /actuator/health` 验证三个服务进程是否可访问；
- `GET /internal/sync-batch-runs/run-once` 验证 datasource-management 的 internal run-once POST 路由是否存在，预期返回 `405/401/403`；
- `GET /internal/sync-workers/run-once` 验证 data-sync 的 internal worker POST 路由是否存在，预期返回 `405/401/403`；
- `GET /internal/data-sync-worker-execution-receipts?limit=1` 验证 task-management 的 data-sync receipt 查询入口是否可访问或被保护。

为什么这里使用 `GET` 而不是 `POST`：

- `POST /internal/sync-workers/run-once` 会真实触发 worker loop，可能认领 execution；
- `POST /internal/sync-batch-runs/run-once` 会真实触发 datasource-management 读写；
- readiness 阶段只应该确认服务进程与路由合同存在，不能把有副作用的业务动作伪装成健康检查。

如果希望把 `data-sync` 与 `datasource-management` 的模块全量测试也纳入同一次验收：

```powershell
.\scripts\local-data-sync-closure-suite.ps1 -IncludeModuleTestSuites
```

如果已经准备好 Docker 与专用 E2E 数据库，并且明确接受“创建/覆盖专用 E2E 表”的写入行为，可以显式开启真实 MySQL -> PostgreSQL JDBC 验收：

```powershell
.\scripts\local-data-sync-closure-suite.ps1 -IncludeRealJdbc
```

如果真实数据库容器已经由其他流程启动，只希望复用现有依赖：

```powershell
.\scripts\local-data-sync-closure-suite.ps1 -IncludeRealJdbc -SkipDependencyStartForRealJdbc
```

如果已经启动 `datasource-management`、`data-sync`，并且希望进一步把“创建数据源 -> 创建同步模板 -> 预检查 -> 创建任务 -> 触发 worker loop -> 分片账本 -> 失败分片重试 -> dirty replay -> PostgreSQL 目标表最终断言”串成一条平台/API 级 E2E，可以显式开启：

```powershell
.\scripts\local-data-sync-closure-suite.ps1 -IncludePlatformApiE2E -UseDirectServiceUrlsForPlatformApiE2E
```

也可以直接运行专用脚本：

```powershell
.\scripts\local-data-sync-platform-e2e.ps1 -UseDirectServiceUrls
```

平台/API 级 E2E 的设计含义：

- 它通过 HTTP API 创建 MySQL 源端数据源与 PostgreSQL 目标端数据源，不再只依赖同 JVM 测试或 datasource-management 单模块 runner。
- 它创建 `FULL + SINGLE_OBJECT + AUTO_SPLIT_PK` 同步模板，并携带字段映射、where/filter 条件、`splitPk=id`、`shardCount`、`channel`、`taskGroupSize`、脏数据条数阈值与脏数据比例阈值。
- 它调用 data-sync 预检查，确认模板能进入 worker 执行链路，再创建任务并触发 `POST /internal/sync-workers/run-once`。
- 首轮执行会故意制造一个失败分片和一个少量 dirty row：`id=11..15` 因目标端 `CHECK(amount >= 0)` 超过 dirty ratio 进入失败分片，`id=7` 因目标端 `name NOT NULL` 形成结构化 dirty sample。
- 脚本随后修复源端 `id=11..15`，只重试失败分片，不重跑已成功分片；再修复 `id=7`，通过 `PRIMARY_KEY_EQ` dirty replay 精确重放该坏行。
- 最终断言 PostgreSQL 目标表包含 20 条完整数据，并且 `id=7` 写入修复后的值。

平台/API 级 E2E 的安全边界：

- 这不是只读 smoke check，会创建/覆盖专用 E2E 表：MySQL `datasmart_govern.datasmart_e2e_platform_orders` 与 PostgreSQL `datasmart_e2e.orders_platform_clean`。
- 它会在平台数据库中创建数据源、同步模板、同步任务、execution、分片账本、错误样本和 replay execution 等测试对象。
- 它会真实触发 data-sync worker loop，因此只能在本地测试库、可回滚环境或专用 E2E 环境执行。
- 脚本不会打印数据库密码、完整 JDBC URL、SQL 正文、样本行、token、HTTP 响应正文或底层堆栈。

直连模式与网关模式的选择：

- `-UseDirectServiceUrls` 适合本地服务联调早期阶段，脚本直接访问 `datasource-management:8082` 与 `data-sync:8086`，并注入本地 E2E Header；它绕过 gateway/Keycloak，但能最快验证多服务 HTTP 合同与真实 JDBC 数据面。
- 不加 `-UseDirectServiceUrls` 时，脚本会走 `gateway:8080 + Keycloak:18080`，更接近真实认证授权入口；该模式要求 gateway、Keycloak、permission-admin、data-sync、datasource-management 均已启动且路由策略可用。
- 如果只想确认脚本计划，不启动容器、不写库、不调用 API，可以运行：

```powershell
.\scripts\local-data-sync-platform-e2e.ps1 -PlanOnly
```

当前默认闭环套件仍然不是“完整多服务启动型 E2E”。它不会自动启动 `data-sync`、`datasource-management`、`task-management` 的真实服务进程；平台/API 级 E2E 是显式 opt-in 阶段，要求服务进程已由 `local-e2e-start-runtime.ps1` 或手动方式启动。这样设计是为了把低副作用守门和真实写入验收分开，避免开发者只想快速回归时意外触发 worker loop 或覆盖测试表。

### 4.7 六专业 Agent 受治理闭环验收

六专业 Agent 的黑盒入口是：

```powershell
.\scripts\local-six-agent-governed-e2e.ps1 -PlanOnly
```

`-PlanOnly` 不访问 Keycloak、不调用 Agent API、不创建任务，适合先核对脚本模式。真实验收必须满足以下前置条件：

- 应用 overlay 已启动，Gateway、Keycloak、permission-admin、agent-runtime、datasource-management、data-sync 和 Python Runtime 均健康；
- Python Runtime 使用真实 `agent_reasoning` provider，且 `DATASMART_AI_OPENAI_COMPATIBLE_BASE_URL`、`DATASMART_AI_OPENAI_COMPATIBLE_API_KEY`、`DATASMART_AI_AGENT_REASONING_MODEL` 均已由当前环境注入；空值或 dry-run provider 只支持启动诊断，不支持六 Agent 验收；
- Agent Runtime `V5__specialist_agent_turn_facts.sql` 与 permission-admin `V48__specialist_agent_turn_fact_route_policy.sql` 已成功迁移；
- 应用 Compose 显式使用 `DATASMART_LANGGRAPH_CHECKPOINT_STORE=postgresql`、受限于 `ai_memory` search path 的 DSN 和 `DATASMART_LANGGRAPH_CHECKPOINT_FAIL_OPEN=false`；permission-admin V52 已应用，checkpoint latest/events 读取必须同时通过 Gateway HMAC 与 Python 对象范围校验；
- project-owner 可见的源/目标数据源名称、真实表映射和同步模式写入参数已经准备好。密码只放在当前进程的凭据环境中，不放进 objective、脚本输出或文档。

六个角色按业务相关性分两类场景验收，不要求一次请求调用无关角色：

|场景|本场景必须实际执行的角色|关键通过条件|
|---|---|---|
|Success|`DATASOURCE_AGENT`、`DATA_SYNC_AGENT`、`PRECHECK_AGENT`、`MONITOR_AGENT`|数据源和元数据经授权读取；DATA_SYNC bridge 接受 Java ToolPlan；Java 的权限/审批/outbox/worker receipt 反馈产生可信 task/execution 后，再执行只读 PRECHECK/MONITOR；每个实际 turn 有 durable fact|
|Recovery（六 Agent 历史黑盒）|`KNOWLEDGE_AGENT`、`RECOVERY_AGENT`、`MONITOR_AGENT`|失败事实与 grounded RAG 证据进入 Recovery；该历史场景的恢复 bridge 进入审批/Java handoff 等待态，`directExecution=false` 且未接受审批；缺参动作跳过但完整只读预览可继续；实际 turn 有 durable fact。它不是随后加入的 Autopilot retry/quarantine E2E。|

Success 和 Recovery 应针对隔离的本地验收数据、且可关联到同一项目范围的真实控制面事实；Recovery 需要已有失败 `TaskId` 或 `ExecutionId`，不会自动制造或批准恢复动作。执行时只设置 `DATASMART_KEYCLOAK_LOCAL_USER_PASSWORD` 等本地凭据变量，脚本会把 token、响应正文、prompt、SQL、工具参数和连接信息收敛为低敏摘要。

脚本按阶段选择 durable fact 门禁：Success 的 Planning 只要求 `DATASOURCE_AGENT` 和 `DATA_SYNC_AGENT`，显式确认并取得真实资源后才要求四个 Success 角色；Recovery 只要求知识、恢复、监控三个相关角色。每项 durable fact 按 session/run 的角色、状态、范围、checkpoint 和 handoff/bridge 引用回放，不读取或打印事实正文。Recovery bridge 对每项动作独立校验：缺少可验证配置时产生 `RECOVERY_ACTION_INPUT_INCOMPLETE` 并跳过该项，其他完整只读预览继续；若没有任何完整动作，则停在补参等待态而不创建 Java ToolPlan。

当前可复现的回归命令与结果口径：

```powershell
python -m pytest python-ai-runtime\tests -q
# 1099 passed；1 Starlette/TestClient deprecation warning

.\mvnw test
# JDK 21 Java Reactor: BUILD SUCCESS；Surefire 1323 tests，0 failures，0 errors，9 skipped

powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\local-six-agent-governed-e2e.ps1 -RunSpecialistStatusAggregationRegressionTest
# PASS: 脚本内低敏夹具的 specialist 状态聚合回归

powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\local-six-agent-governed-e2e-exit-code-regression.ps1
# PASS: PlanOnly/状态聚合/异名对象映射成功为 0；本地参数 FAIL 为非 0；子进程输出不含凭据测试哨兵
```

上述第三、四条不访问 Keycloak、Agent API 或 Docker，前两条是测试回归口径。第四条只使用一个不是真实密码的进程内测试哨兵，子进程输出只用于断言且不会被回显；它保护 CI 的退出码契约，而不构成 Docker 黑盒 E2E 通过证据。只有在满足本节前置条件后显式运行 `-Execute`，并完成 Success 与 Recovery 两场景，才可以记录真实黑盒验收结果。

**历史验证（已被下文的后续复验取代）：** 2026-08-10 较早的源码镜像曾在真实模型调用处收到 Provider HTTP 401，Success 请求 `six-agent-success-20260810-checkpoint-final` 与 Recovery 请求 `six-agent-recovery-20260810-checkpoint-final` 因而 fail-closed。该记录仍用于证明外部失败不会越过 Java 审批边界，但不再代表当前 Provider 或 E2E 状态。

同一历史验证还通过独立 `powershell.exe -File` 子进程确认 Provider 故障返回退出码 `1`，证明外部失败不会被 PowerShell 包装误报为成功。当前退出码回归继续保留该非零传播合同。

同一轮还完成了 RAG/LangGraph 持久化恢复实测：Gateway `POST /api/agent/rag/query` 在项目 101 返回 2 条 citation，PostgreSQL 为 thread `rag-e2e-postgresql-20260810` 写入 3 个 checkpoint 和 3 个 event；重启 Python Runtime 后，project-owner 经 Gateway `latest/events` 仍读到最终 version 3 和 3 个事件，而同项目其他 actor 返回 403。该结果验证持久化、路由策略、HMAC 和对象级范围，不能替代模型 Provider 恢复后的六 Agent success/recovery 复跑。

### 4.4 2026-08-10 六 Agent 黑盒门禁关闭证据

后续使用恢复后的真实 Provider、当前 `gpt-5.6-sol`/`xhigh`/Responses 路由和 PostgreSQL fail-closed LangGraph checkpointer，重新执行了两个隔离场景：

- Success 请求 `six-agent-success-type-normalized-20260810112629` 创建任务 `91`、执行 `2245`，worker 为 `SUCCEEDED`，读写 `20/20`、失败 `0`。18 项脚本检查为 `0 fail`，唯一 warning 是该 Success 目标未要求 RAG，因此按需 `KNOWLEDGE_AGENT` 未触发；`DATASOURCE_AGENT`、`DATA_SYNC_AGENT`、`PRECHECK_AGENT`、`MONITOR_AGENT` 与本轮实际产生的知识事实均有 durable COMPLETED 证据。
- Recovery 请求 `six-agent-recovery-rag-durable-20260810214832` 针对既有失败任务/执行 `76/1805`，获得 2 条 grounded citation、2 条 durable evidence reference、1 个 Java 只读 preview，后置 PRECHECK/MONITOR 均为 `EXECUTED`，durable-fact 脚本计数为 8。11 项检查为 `0 fail / 0 warning`，独立子进程退出码为 `0`。
- 独立数据库审计确认 permission approval、approval confirmation、submission fact 和 async command outbox 本轮均为 `0`。任务 `76` 仍只有 `1805 FAILED` 与 `1806 SUCCEEDED`；恢复计划 `9` 早于本轮请求，不是 Agent 自动创建。8 个 Java 工具审计全部为 `LOW/readOnly/SUCCEEDED`，两条 KNOWLEDGE durable 引用均为 `rag:sha256:`；LangGraph 的 `rag_retrieve_knowledge -> rag_evidence_gate -> rag_grounded_answer_completed` 三节点完整。

因此本地真实六 Agent Success/只读 Recovery 黑盒门禁已经关闭；该结论不包含真实 Kafka/Python Autopilot `FAILED -> Recovery -> retry -> SUCCEEDED -> RECOVERED` 写重跑。生产发布门禁也未全部关闭：Secret Manager 注入与轮换、备份恢复、容量压测、故障演练、镜像签名/SBOM 和客户环境迁移仍需按各生产 runbook 单独补证。

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

这里的 `run-once` 不是 Autopilot Kafka trigger consumer；当受限链路把失败对象重新排队后，它才是处理这些对象的既有 data-sync worker。单独一条 `AUTO_APPROVED`、本节 worker 入口或 smoke 成功都不能证明 Kafka 消费、Python 规划、重试和最终 receipt 已构成无人值守恢复 E2E。

## 8. 当前闭环缺口

当前项目已经开始从能力扩展转向闭环收敛，但还不能把本地 runbook 视为商业部署完成态。主要缺口包括：

- PostgreSQL 服务 schema 已由模块级 Flyway 管理；仍需在客户环境对 MySQL 存量导入、版本校验和、回滚点与新增 V20 等增量迁移保留实际执行证据，不能把源码中的迁移文件当作已部署事实。
- 服务到服务调用仍在逐步从临时 Header/HMAC 迁移到 OIDC service account、mTLS 或 service mesh 身份。
- data-sync receipt 已具备本地 outbox/retry/dead-letter；后续仍需要在真实联调中验证 task-management 故障、恢复和死信告警路径。
- data-sync 最小执行闭环主要支持 FULL/ONE_TIME_MIGRATION 单批，增量 checkpoint handoff、多批循环和分片并发仍需谨慎收敛。
- Autopilot 的当前工作树已具备 V20-V25 durable schema、data-sync controller/outbox/scheduler、Agent Runtime Kafka consumer、Java 到 Python 规划调用、证据与双策略复核、受限失败对象重试、preview/selector/receipt 约束的 quarantine、worker/final receipt、sidecar compensation，以及专用低基数指标和 Prometheus 告警。V25 只投影模型自主 `SEARCH`/`SKIP`、检索策略和 evidence-ID digest，不保存证据正文。目标环境仍需保留实际 Flyway、broker/HTTP/worker/receipt、补偿与告警演练证据；`AUTO_APPROVED` 不能代替最终 receipt，`WAITING_APPROVAL` 与 `ATTENTION_REQUIRED` 必须保留为明确停点。
- Agent 侧 tools、skills、memory、query engine、context、permission、sub-agent、sessions、command、hook、LLM provider 还需要整理成最小闭口清单。
- Compose 是本地开发工具，不是生产部署方案；生产仍需要 Kubernetes/Helm、Secret Manager、TLS、外部数据库、审计、备份和容量规划。

### 8.1 2026-08-11 Autopilot 触发投递静态审计补充

**已验证（源码与模块回归）**：当前工作树已有 `V20`-`V25` PostgreSQL migration、失败 execution 到 `SyncAutopilotRecoveryTriggerPublisher` 的调用、本地 durable outbox、`KafkaTemplate` producer、consumer result、V23 sidecar compensation、V24 quarantine receipt、V25 retrieval evidence projection 和调度器；完整 Compose 显式开启 `datasmart.agent.autopilot-recovery-trigger.v1` listener。consumer 会重新校验 session/root Run/授权快照，再以内部服务令牌调用 Python 的 Recovery/RAG 规划入口。Python 候选返回 Java 后还要经过诊断证据和 Java/data-sync 双策略；当前执行分支只允许首次授权范围内的 `RETRY_EXECUTION`，以及带真实 preview、精确 selector 和 durable receipt 的 `APPLY_QUARANTINE`，Python 本身不执行 data-sync 写操作。普通规划将 RAG 作为模型可选能力而非规则强制计划。2026-08-11 本机 `data-sync` 模块回归为 `279` tests、`2` skipped，`agent-runtime` 为 `645/645`，没有失败。

**仍需本轮运行验证**：当前运行数据库只应用到 V22，V23/V24/V25 表或列尚未出现，说明现有 data-sync 容器早于最新源码。2026-08-13 源码已经把真实 retry receipt 后的 PRECHECK/MONITOR 复核接到固定内部入口，并以 checkpoint、turn ID 和 Java fact sink 保证重放幂等和失败向 Kafka 传播；模块回归为 Python `1150 passed / 1 skipped`、Agent Runtime `693 passed`。仍必须重建并重启 data-sync/agent-runtime/Python Runtime，确认 V23/V24/V25 Flyway、broker 投递/重投递、可信状态重建、Provider 自主 `SEARCH`/`SKIP` 规划、失败对象 retry/quarantine、worker 处理、最终 receipt、恢复写动作后的 durable fact、指标和告警；不能从源码、producer、outbox 或 `AUTO_APPROVED` 推断无人值守恢复已经在真实环境发生。

**当前环境状态**：本机 Compose、Kafka、PostgreSQL、Gateway、Agent Runtime、Python Runtime、data-sync 和前端均在运行且健康，完整 Compose 已显式开启 Autopilot listener；但镜像仍需按最新源码重建，V23/V24 尚未应用。该差距属于待执行部署验证，不是模块测试失败。

**剩余事项**：用最新源码镜像验证 V23/V24、消费后可信状态重建、授权/作用域/循环/风险复核和 Python 规划调用；再以真实失败 execution 验证模型 `SEARCH`/`SKIP`、双策略、同幂等键失败对象 retry、真实 preview/selector/receipt quarantine、worker 处理与成功/失败 receipt 收敛。随后验证死信、重复投递、过期授权、Provider 失败、V23 compensation、V24 receipt 回放和审批停点的指标/告警，并分别证明低风险自动 retry/quarantine 及高风险人工审批 E2E。

## 9. 下一步收敛建议

建议后续不要再围绕某个局部模块无限扩展，而是按闭环优先级推进：

1. 完成一次本地最小链路实际启动验证，记录哪些服务能启动、哪些依赖配置仍阻塞。
2. 为数据库迁移引入统一版本管理，减少新环境搭建时的 schema 漂移。
3. 把 Agent 能力整理为最小闭口清单：tools、skills、memory、query engine、context、permission、sub-agent、sessions、command、hook、LLM provider。
4. 对模型层保持 provider-neutral 策略，优先接入成熟推理服务、缓存、限流、重试、token budget 和可观测，而不是在本项目内做底层算法或微调。
5. 做一次真实故障演练：暂停 task-management、让 data-sync 产生 receipt、确认 outbox RETRY_WAIT、恢复 task-management 后确认 DELIVERED。

### 8.2 2026-08-13 Autopilot 自主检索与无人值守恢复复核

本轮已完成并复核以下代码闭环：普通同步规划和 Recovery 规划都把 RAG 暴露为模型可见工具，由模型根据当前证据自主选择 `SEARCH` 或 `SKIP`；选择 `SEARCH` 时最多执行一次受控检索并要求持久化 evidence 后重评，选择 `SKIP` 不会绕过 Java/data-sync 门禁。恢复动作仍由 Java/data-sync 控制面执行，Python 只负责受约束的决策与证据编排。首次授权后，授权盒子内的低风险 `RETRY_EXECUTION` 可以无人值守排队、执行和有界重试；达到最大循环次数、授权过期、证据不足、双策略不一致或高风险动作时，系统会停在 `ATTENTION_REQUIRED`/审批边界，不会无限循环或越权写入。

本轮验证证据：

- Python 全量回归：`1162 passed, 1 skipped`；Recovery 聚焦回归：`24 passed`。
- JDK 21 下 `agent-runtime`、`data-sync` 及其依赖模块编译成功。
- Frontend `lint`、`build` 以及 API/WebSocket、Agent control-plane、Specialist audit、confirmation gate、live contract 和 data-sync locator 合同测试全部通过。
- 使用最新源码构建并启动的 `data-sync`、`agent-runtime`、`python-ai-runtime` 容器均为 healthy；Agent Runtime 日志确认 Autopilot 主 topic、两级 retry topic 和 DLT consumer 已加入 Kafka consumer group。
- durable Specialist fact 已 fail-closed：事实未持久化时 Python 返回 HTTP `503`，Kafka 消费不 ACK，交给既有有界重试/DLT；相同可信 binding 的 terminal checkpoint 重放保持幂等。
- 离线六 Specialist 合同回归证明了模型可自主 `SEARCH`/`SKIP`、失败对象可受治理重排队，以及最大循环后进入 `ATTENTION_REQUIRED`。

本轮没有宣称完成真实生产写动作 E2E。宿主机未配置 `DATASMART_KEYCLOAK_LOCAL_USER_PASSWORD`，因此没有执行需要 project-owner 登录的真实 `-Execute -ConfirmAndExecute -EnableAutopilot` Recovery 黑盒。仍需在隔离环境补充真实 Keycloak 授权、V20-V25 Flyway 状态、Kafka 投递/重投递、Python Provider/RAG、worker receipt、post-action `PRECHECK_AGENT`/`MONITOR_AGENT` durable fact、指标告警以及低风险自动 retry/quarantine 和高风险审批停点证据。

### 8.3 2026-08-13 Keycloak 密码与真实 Success/Autopilot 复核

随后使用本机 Keycloak `project-owner` 的已配置密码，通过进程级环境变量完成真实认证；密码没有写入仓库、日志或提交。当前项目 `101` 的真实低敏数据源为 MySQL `55` 和 PostgreSQL `56`，二者均返回 `VIEW/USE/MANAGE`。本次真实执行使用这两个稳定 ID，不依赖已经失效的历史示例名称。

真实 Success + 首次确认 + `EnableAutopilot` 请求 `local-six-agent-20260813053638265` 已通过：

- 首次确认建立了绑定 root Run 的 LOW 风险 Autopilot 授权盒，最大 3 轮、120 分钟，允许动作仅为 `RETRY_EXECUTION` 和 `APPLY_QUARANTINE`。
- `sync.task.draft.save`、`sync.task.precheck`、`sync.task.publish` 和 `sync.task.run` 均由 Java 控制面成功执行；真实 task `97`、execution `2619` 已可信定位。
- 真实 worker 将 execution 收敛为 `SUCCEEDED`，读取 20、写入 20、失败 0，对象账本 1 个且全部成功。
- 资源产生后的 `PRECHECK_AGENT` 和 `MONITOR_AGENT` 均为 `EXECUTED`，本轮查询到 4 条低敏 durable Specialist facts，无受治理等待角色。

同一环境还验证了阻断行为：使用 `INSERT + FULL` 时，目标表已有 20 行，Java precheck 返回 `BLOCKED` 和 `METADATA_TARGET_NOT_EMPTY_FOR_INSERT_FULL`，没有发布、运行或覆盖目标数据；改用用户明确选择的 `UPDATE/merge` 后才通过并完成上述 Success。该结果证明预检查会阻止高风险/不安全配置，而不是让 Agent 猜测或强行执行。

这条证据证明了真实 Keycloak、Gateway、权限中心、六 Specialist、Java 控制面、data-sync worker、首次 Autopilot 授权和后置复核链路。它仍不是 Recovery 写重跑证据：本次首次 execution 成功，没有进入失败对象的 Kafka Recovery trigger。真实 Recovery 仍需使用隔离的失败 execution 验证 `SEARCH`、失败对象 retry/quarantine、最终 receipt、post-action facts、指标告警和有界 `RECOVERED`/`ATTENTION_REQUIRED` 收敛。

### 8.4 2026-08-13 Recovery transient transport 演练补充

当前 Recovery 检索合同不是“每次强制调用 RAG”。模型必须对检索作出显式 `SEARCH`/`SKIP` 决策：结构化诊断足够时可选择 `SKIP`；陌生、重复或低置信度错误可选择 `SEARCH`，随后只能执行一次受控检索并用 durable evidence 重评。该决策只进入审计和候选规划，不绕过 Java/data-sync 的授权、风险、作用域、预算、幂等与事实账本。

run-once transport 演练前已补齐异常分类：仅连接拒绝、connect/read timeout 这类 Spring transport failure 写为 `DATASOURCE_RUN_ONCE_TRANSPORT_UNAVAILABLE`、`retryable=true`；HTTP rejection、无效 envelope、权限、凭证和契约失败继续为 `DATASOURCE_RUN_ONCE_UNAVAILABLE`、`retryable=false`。可执行聚焦命令为：

```powershell
mvn -pl data-sync -am "-Dtest=SyncBatchRunOnceHttpContractE2ETest,SyncAutopilotRecoveryCaseServiceTest,SyncAutopilotRecoveryPolicyEvaluatorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test "-DskipTests=false" "-Dcheckstyle.skip=true"
```

当前相关聚焦回归共 `21 tests / 0 failures / 0 errors`。测试使用关闭的本地端口制造真实 transport 拒绝，不依赖外部 DNS；同时验证远端 HTTP/业务/契约 rejection 仍不可重试，并覆盖失败工作单元重排和成功探测后的账本对账。

真实请求 `six-agent-autopilot-transient-20260813230035361` 创建 task `106` / execution `2714`。本次在 `datasource-management` 暂停期间，`AUTO_SPLIT_PK` 范围探测先于 run-once 执行并返回 `PARTITION_SHARD_CONTRACT_BLOCKED`；精确状态为 `outbox_state=DELIVERED`、`consumer_result_status=ATTENTION_REQUIRED`，记录 `SEARCH`、`EXACT_SEARCH` 和 2 条 evidence 引用，但没有 recovery case、`RECOVERY_STARTED` 或 retry receipt。服务已恢复，未留下故意停止的容器。

因此下一次隔离演练必须避免自动范围探测抢先失败，并收集以下连续证据后才可关闭门禁：首轮 execution 的 transport failure、trigger outbox、Kafka 消费、模型 `SEARCH` 或 `SKIP`、Java/data-sync 双事实复核、`AUTO_APPROVED`、`RECOVERY_STARTED`、新 execution 自动重跑成功、`RECOVERED`、后置 `PRECHECK_AGENT`/`MONITOR_AGENT` durable facts。任一项缺失都只能记录为 fail-closed 或部分链路证据。

### 8.5 2026-08-14 AUTO_SPLIT_PK range-probe 失败工作单元复核

当前实现已覆盖范围探测早于真实分片账本初始化的边界：仅 transport-only 失败会持久化一条低敏 `workUnitType=PARTITION_RANGE_PROBE`、`objectState=FAILED` 临时工作单元，并记录 `DATASOURCE_PARTITION_RANGE_PROBE_TRANSPORT_UNAVAILABLE` 与 `retryable=true` 的错误事实。既有失败对象重试入口可以重置该单元并重新排队 execution；HTTP/业务/契约/无效范围失败不会创建自动重试资格。

重试时 range-probe 成功，data-sync 会在同一事务中删除临时 `PARTITION_RANGE_PROBE` 单元，然后幂等生成真实 `PARTITION_SHARD`（自适应单分片则为对象）账本。成功分片不会重复插入，临时单元也不会进入父 execution 汇总。该生命周期与 run-once/Recovery 分类由 `21 tests / 0 failures / 0 errors` 聚焦回归覆盖。

本 runbook 必须继续把真实 Kafka/Python `FAILED -> Recovery -> retry -> SUCCEEDED -> RECOVERED` 作为当前主 Agent 的未完成黑盒验证项。当前已有的是代码、聚焦测试、容器健康和 fail-closed 的部分证据；在收集真实 trigger outbox 投递、Kafka 消费、Recovery `SEARCH`/`SKIP`、worker receipt、重跑 execution 和最终 `RECOVERED` 之前，不得把自治恢复写重跑宣称为成功。

### 8.6 2026-08-14 幂等重放修复后的复验方法与当前阻塞

复验 task `107` 时，必须先保证完整字段映射。其目标表 `orders_platform_clean.name` 为非空列，因此源端 `customer_name` 必须显式映射到 `name`；缺少该映射会产生 20 条 dirty record，并把“字段规划错误”混入 transport Recovery 演练。更稳妥的隔离方式是使用源端同名四字段目标表，并在 objective 中明确 `id`、`customer_name`、`amount`、`region` 及主键和写策略。

Kafka 重投的 diagnosis/preview AgentPlan 现已使用 `investigation:v3` 有界幂等身份。同一 event、阶段和真实策略会回放首次 Java audit；不同 recovery cycle 或真实预览参数会创建新受治理 Run。键只保留固定阶段前缀和 SHA-256 语义摘要，以满足 Java 128 字符合同；原始 eventId 仍保留在请求与审计事实中。不要通过关闭 Java 指纹冲突检查、变更随机幂等键或让 Python 直连 data-sync 来“修复”重放，这些做法会破坏审批、审计和副作用边界。

本轮可重复门禁：

```powershell
mvn test
Set-Location .\python-ai-runtime
pytest -q
Set-Location ..\..\DataSmartGovernFrontend
npm run test:api-adapter-contract
npm run test:agent-control-plane
npm run test:agent-specialist-audit
npm run test:agent-confirmation-gate
npm run test:agent-console-live-contract
npm run test:data-sync-agent-locator
npm run lint
npm run build
```

2026-08-14 实际结果为 Java `1515 tests / 9 skipped`、Python `1171 passed / 1 skipped`，前端全部脚本和构建通过。运行时镜像也已重建并健康，Kafka consumer 已就绪。

**历史阻塞（已由 8.7 节关闭）：** 当时模型 Provider 返回 HTTP `401`，规划在创建 task 前 fail-closed。该记录仍用于验证外部鉴权失败不会产生副作用。

### 8.7 2026-08-14 真实 RECOVERED 演练结果

Provider 切换到 `https://qa.dashun9527.com/v1` 并通过 `/models`、`/responses` HTTP `200` 后，task `108` / execution `2770` 首先复现了生产 eventId 导致 preview 幂等键超过 Java 128 字符限制的问题。诊断审计成功、preview 在 Bean Validation 阶段被拒绝，Kafka 有界重试后进入 DLT。修复方法是 `investigation:v3` 固定阶段前缀加 SHA-256 语义摘要，不是放宽 Java DTO 或绕过 ingestion。

新镜像上的 task `109` / execution `2775` 按以下顺序完成：关闭 worker scheduler；通过 Gateway 规划、确认并建立 LOW 风险、3 轮、120 分钟授权盒；暂停 datasource-management；重新开启 worker 形成真实 range-probe transport failure；确认失败账本和 outbox `DELIVERED` 后恢复 datasource-management；之后不再进行人工操作。系统自主选择 `SKIP / STRUCTURED_DIAGNOSTIC`，完成 Java diagnosis/preview，返回 `RECOVERY_STARTED / AUTOPILOT_FAILED_OBJECTS_REQUEUED`，在 cycle `1/3` 自动将 case 收敛为 `RECOVERED`。

最终 execution `2775` 为 `SUCCEEDED`，读 `20`、写 `20`、失败 `0`，单对象成功。源目标聚合一致：行数 `20`、唯一 ID `20`、ID 合计 `210`、金额合计 `2210.00`。Gateway 公开 API 复核 execution、对象、19 条日志、Recovery 快照和两个 session 的 8 条 durable facts 成功；恢复后 `PRECHECK_AGENT`、`MONITOR_AGENT` 再次完成。脚本末尾一次只读 execution 查询遇到 permission-admin 瞬时不可用并非零退出，服务恢复后相同公开查询全部成功。当前脚本只对 GET 的 502/503/504 以及明确“权限中心暂时不可用”的 403 最多重试 3 次；确认、重试、隔离等 POST 和普通权限拒绝仍立即失败，相关离线回归已通过。

### 8.8 2026-08-14 受治理修复动作演练要求

Recovery Loop 现在可以在首次授权盒内提出并执行以下低风险修复：回滚到最近一次成功策略；降低 channel/batch 或在声明硬上限内增加 timeout；刷新元数据并重新预检；恢复最新持久 checkpoint；只重放失败分片；应用元数据唯一证明的字段映射修复。动作不能只依赖模型文字结论，必须同时具备当前授权、case/cycle、作用域、动作指纹、幂等回执、Java/data-sync 双策略和持久事实。

每次演练应在低敏验收记录中确认以下诊断字段：结构化错误与对象统计、当前策略、最近成功策略、连接器运行时版本和来源、当前限制、硬容量是否声明，以及 RAG `SEARCH/SKIP` 决策。检索证据必须包含 `sourceType`、`sourceRef`、`retrievedAt`、`confidence`、`confidenceBasis`；若任何字段缺失，期望结果应为 fail-closed，而不是继续自动修复。

字段类夹具应至少覆盖：大小写字段名自动归一化；目标未占用、类型兼容、主键属性一致、双方有序号时序号一致且候选唯一时自动修复列重命名；目标非空且无默认值字段缺失映射时以 `METADATA_REQUIRED_TARGET_FIELD_NOT_MAPPED` 阻断；目标字段可空/有默认值/自动生成且非主键时安全移除失效映射；存在歧义候选时停止；不得生成默认值、修改 DDL 或禁用外键。外键关系当前未纳入完整元数据合同，因此外键错误的期望结果是退出 Loop，并给出根因、证据、所需权限、操作步骤、影响、回滚和验证方法。

推荐的重复门禁为：

```powershell
mvn test
Set-Location .\python-ai-runtime
pytest -q
Set-Location ..
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\local-six-agent-governed-e2e-exit-code-regression.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\local-e2e-smoke-check.ps1 `
  -Strict `
  -CheckServiceAccountToken `
  -CheckAgentGatewayDiagnostics
```

2026-08-14 当前结果为 Java `1544 tests / 0 failures / 0 errors / 9 skipped`、Python `1174 passed / 1 skipped`、六 Agent 离线退出码/公开合同回归通过、认证 smoke `PASS=89 / WARN=0 / FAIL=0`。Frontend 的六项合同脚本、lint 和 build 同样通过。`datasource-management`、`data-sync`、`agent-runtime`、`python-ai-runtime` 最新镜像均为 healthy。

真实 E2E 口径：task `109` 已关闭 transport failure 自动重试主链路门禁；新增修复目录必须按动作分别做隔离故障注入。演练期间禁止手工调用 retry/quarantine/replay 来冒充自治恢复，禁止修改凭据、权限、DDL、约束、目标数据或同步范围。涉及这些高风险边界时，验收目标应是系统正确退出 Loop 并生成完整人工处置说明，而不是强行得到 `RECOVERED`。

### 8.9 2026-08-15 字段重命名自治修复演练

本演练使用现有 MySQL 源端和 PostgreSQL 目标端创建独立目标表。建任务时源目标都包含 `id`、`customer_name`、`amount`、`region`，先确认首次映射和预检均有效；随后暂停 worker，把目标列 `customer_name` 改名为 `name`，再恢复 worker。该结构变化只用于隔离环境故障注入，不应在生产任务上手工制造。

预期链路不是直接重试：首轮写入应因旧字段映射失败；cycle 1 可以先选择 `REFRESH_METADATA`，完整预检仍失败时不得伪报修复成功；下一轮只有在目标列未占用、类型兼容、主键属性一致、序号一致且唯一候选成立时，才允许 `REPAIR_FIELD_MAPPING` 更新任务定义并重排 execution。候选多于一个或任何约束不满足时，预期结果是退出自动动作并保留明确原因，不允许模型猜列名。

2026-08-15 的真实结果为 task `119` / execution `2860`。outbox `28` 在 cycle 1 以 `REFRESH_METADATA` 返回 `AUTOPILOT_REFRESHED_METADATA_PRECHECK_FAILED`；outbox `29` 在 cycle 2 以 `REPAIR_FIELD_MAPPING` 返回 `RECOVERY_STARTED / AUTOPILOT_GOVERNED_REPAIR_APPLIED_AND_REQUEUED`。两次均 `DELIVERED`，模型均选择 `SKIP / STRUCTURED_DIAGNOSTIC / evidence=0`。case `14` 最终 `RECOVERED`，execution 和对象账本均 `SUCCEEDED`，读 `20`、写 `20`、失败 `0`，目标表 20 行，持久映射为 `customer_name -> name`。整个恢复阶段没有手工调用 retry、repair 或 replay。

task `118` 是必须保留的失败证据：旧实现更新任务定义时使用 `WHERE id`，而真实主键列是 `task_id`，因此第二轮修复进入 Kafka DLT。修复必须由 SQL 片段回归证明条件使用 `task_id`，不能通过忽略数据库异常或放宽 Kafka 重试次数掩盖。修复后的 data-sync 聚焦组为 `58 tests / 0 failures / 0 errors`。

演练中若主动重建 data-sync 容器，Nacos 可能短暂保留旧实例地址。Gateway 应原样传播授权放行后的下游连接异常，不能把它改写成权限中心不可用；对应聚焦回归为 `33 tests`。服务注册稳定后，再通过 Gateway 公开 API 复核 execution、对象、Recovery 快照和 Specialist durable facts。

动作后 `PRECHECK_AGENT`/`MONITOR_AGENT` 在重排回执后立即执行，不阻塞 Kafka 等待长任务结束。它们证明修复已被受治理执行面接收并形成持久观察事实；最终成功必须另外核对 `case=RECOVERED`、repair receipt、`execution=SUCCEEDED`、对象账本、读写统计和目标数据。若将来要求 Specialist 输出终态成功摘要，应新增 worker 终态事件驱动的 finalization，而不是延长 Kafka 消费事务。

本次演练后的完整重复门禁结果为：JDK 21 Reactor `1563 tests / 0 failures / 0 errors / 9 skipped`，Python `1178 passed / 1 skipped`，Frontend 六项合同、lint 和 build 全部通过，离线 E2E 退出码合同全部通过，严格只读 smoke 为 `PASS=89 / WARN=0 / FAIL=0`。这些数字应和演练 task/execution/case 标识一并记录，不能只保留“测试通过”的笼统结论。

### 8.10 统一全链路状态图验收

查询某次已授权可见的 execution：

```powershell
$taskId = 119
$executionId = 2860
Invoke-RestMethod `
  -Headers @{ Authorization = "Bearer $accessToken" } `
  -Uri "http://localhost:8080/api/sync/sync-tasks/$taskId/executions/$executionId/lifecycle-graph"
```

响应必须满足以下约束：

1. `graphType=SYNC_EXECUTION_LIFECYCLE`，节点顺序覆盖用户目标、Agent、命令投递、Java 审计、根 worker、Recovery Kafka、Recovery、当前 worker 重放和最终验证；没有恢复重放时根 worker 与当前 worker 是同一个节点。
2. 每条 evidence 都有 `source`、`occurredAt`（尚未发生时允许为空）、`confidence` 和低敏 `reference`；不得出现 prompt、SQL、凭据、工具参数、命令 payload 或原始错误正文。
3. Agent 触发的新 execution 应能通过 V26/V27 关联找到精确 `entryMode/sessionId/runId/auditId`。`ASYNC_AGENT_COMMAND` 还必须有 `commandId`；`DIRECT_AGENT_TOOL` 的 `commandId` 必须为空。两种入口都必须回查到同一条权威 Java 工具审计。
4. Agent command outbox 节点类型必须为 `COMMAND_DISPATCH`。其 `PUBLISHED` 只证明 dispatcher 已投递，不能作为 Kafka 消费成功证据。只有 Recovery trigger outbox/consumer 的持久事实才能形成 `KAFKA_EVENT`；没有触发 Recovery 时该节点应明确显示未发生。
5. 手工执行或 V26 之前的历史 execution 允许返回 `sourceStatus=NOT_LINKED`；Agent Runtime 暂不可用、异步入口 command/audit 缺失时允许返回 `PARTIAL`。直接入口只要求 audit，不得因为没有初始 command 被误判为 `PARTIAL`。这些观察状态不能使 worker 主流程失败。
6. `overallState=VERIFIED` 只在当前 worker 为 `SUCCEEDED`，且存在 Recovery 时 case 已为 `RECOVERED` 后出现；根 execution 的首次失败必须在 Recovery 之前保留，不能被当前成功 execution 覆盖。
7. 前端必须使用服务端 `edges` 展示上游关系；`PARTIAL` 按低频间隔继续轮询，`NOT_LINKED` 停止轮询，避免稳定历史数据造成永久请求。

数据库可使用下列低敏查询核对关联是否存在，禁止在排障记录中导出其他业务正文：

```sql
SELECT tenant_id, project_id, sync_task_id, sync_execution_id,
       entry_mode, command_id, session_id, run_id, audit_id, trace_id, create_time
FROM data_sync.data_sync_agent_execution_correlation
WHERE sync_task_id = :task_id
  AND sync_execution_id = :execution_id;
```

本接口是只读聚合，不替代现有规划图、Specialist 图、RAG 图、执行门禁图或 Recovery 图。验收时应把统一图作为总览，再按 evidence/reference 下钻专用页面；不得通过修改图响应来推进业务状态。

### 8.11 2026-08-15 本机复验结果与待补证项

本轮在 Docker Desktop 恢复后，按 `docker-compose.yml + docker-compose.application.yml + docker-compose.local-e2e.yml` 重建并启动 `data-sync`、`agent-runtime`、`gateway` 和 `frontend`。四个服务均为 healthy，PostgreSQL 已存在 `data_sync.data_sync_agent_execution_correlation`。严格只读 smoke 结果为 `PASS=89 / WARN=0 / FAIL=0`。

由于 V26 上线前的历史 execution 没有关联行，本轮先直连 data-sync 验证兼容路径。task `8` / execution `2882` 的实际响应为：

- `graphType=SYNC_EXECUTION_LIFECYCLE`；
- `overallState=VERIFIED`；
- `sourceStatus=NOT_LINKED`，`missingReason=AGENT_EXECUTION_NOT_LINKED`；
- Agent、初始 Kafka 和 Java 审计节点为 `NOT_APPLICABLE`；
- worker 为 `SUCCEEDED`，最终验证为 `VERIFIED`；
- evidence 只包含 `WORKER_EXECUTION` 的权威低敏引用。

这个结果是历史兼容成功证据，不是 Agent 完整链成功证据。随后使用两个新 RequestId 通过 Keycloak、Gateway 和真实模型执行 Success 规划，两次都在 Planning 阶段以 `DATA_SYNC_SPECIALIST_MODEL_FAILED` 停止；脚本真实退出码为 `1`，低敏 Provider 诊断显示实际模型为 `gpt-5.6-sol` 且当前路由为 `degraded`。两次请求都没有进入确认、Kafka、worker，也没有创建可供关联的新 execution，因而不得把当前关联表为空解释为代码漏写。

Provider 恢复后按以下顺序补证：

1. 使用新的 RequestId 运行 Success 场景并显式确认；不要复用这两次失败请求冒充新调用。
2. 记录新 task、root execution、current execution、session、run、command 和 audit 的稳定 ID，不记录 prompt、模型正文或工具参数。
3. 查询关联表，确认同一租户、任务、execution 和 audit 只存在一条关联。
4. 通过 Gateway 查询 lifecycle graph，要求 `sourceStatus=COMPLETE`。若六 Specialist 主流程经 `sync.task.run` 直接执行，应得到 `entryMode=DIRECT_AGENT_TOOL`、`commandId=null` 和 `COMMAND_DISPATCH=NOT_APPLICABLE`；若使用异步 `data-sync.execute`，则逐项核对对象级 command outbox、Java audit、worker、Recovery Kafka（若发生）、Recovery case 和最终验证。
5. 若 Agent Runtime 暂不可用，期望结果是 `PARTIAL`；不得通过手工插入关联行、修改图响应或放宽内部令牌检查取得假成功。

本轮重复门禁为 Java `1569/0/0/9`、Python `1178 passed / 1 skipped`、Frontend 全合同/类型/lint/build 通过。完整关联 E2E 的唯一剩余项是外部 Provider 恢复后的第 1 至 4 步；它不影响已通过的历史兼容、权限、迁移和低敏合同，但在补证前不能宣称本轮 `COMPLETE` 黑盒门禁已关闭。

### 8.12 2026-08-15 V27 与 Recovery 证据复验

使用完整 local-e2e Compose 叠加文件重建 `data-sync` 与 `frontend` 后，先确认 `data-sync`、`frontend`、`task-management`、`agent-runtime`、`gateway`、`permission-admin` 均为 healthy，并确认宿主机只通过 `127.0.0.1:8086` 暴露 data-sync。随后执行低敏数据库核对：

```sql
SELECT version, success
FROM data_sync.flyway_schema_history
WHERE version IN ('26', '27')
ORDER BY installed_rank;

SELECT column_name, is_nullable, column_default
FROM information_schema.columns
WHERE table_schema = 'data_sync'
  AND table_name = 'data_sync_agent_execution_correlation'
  AND column_name IN ('command_id', 'entry_mode')
ORDER BY column_name;
```

本机结果为 V26、V27 均成功，`command_id` 可空，`entry_mode` 非空且默认 `ASYNC_AGENT_COMMAND`。直接入口应写 `DIRECT_AGENT_TOOL` 且不创建初始 command；异步入口必须写 commandId，并使用 session/run/command 对象级观察接口核对命令。两种入口都必须命中同一条权威 Java 工具审计。

Recovery 取证必须分别检查 trigger outbox/consumer 和 recovery case。仅查到 case 时，期望图节点为 `KAFKA_EVENT=NOT_RECORDED`、`reasonCode=RECOVERY_KAFKA_NOT_RECORDED`，`occurredAt` 为空且没有 Kafka evidence；不得从 case 创建时间推断消息投递时间。查到真实 outbox/consumer 后，才允许生成 `AUTOPILOT_RECOVERY_KAFKA` 证据；case 自身使用独立的 `AUTOPILOT_RECOVERY_CASE` 证据。初始 command outbox 无论状态为何都只属于 `COMMAND_DISPATCH`。

本轮最终回归为 Java `1583 tests / 0 failures / 0 errors / 9 skipped`，Python `1178 passed / 1 skipped`，Frontend 六项合同、API adapter、类型检查、lint 和生产构建全部通过，严格 smoke 为 `PASS=89 / WARN=0 / FAIL=0`。历史 task `8` / execution `2882` 继续返回 8 个节点、7 条边、`overallState=VERIFIED`、`sourceStatus=NOT_LINKED`，只包含 worker 权威证据。Provider degraded 期间不要反复复用失败 RequestId；恢复后按 8.11 的步骤创建全新任务补齐 `sourceStatus=COMPLETE`。

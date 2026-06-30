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

### 4.3 启动 Java 微服务

建议每个服务使用独立终端启动，便于观察日志：

```powershell
mvn -pl permission-admin -am spring-boot:run "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"
mvn -pl task-management -am spring-boot:run "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"
mvn -pl datasource-management -am spring-boot:run "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"
mvn -pl data-sync -am spring-boot:run "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"
mvn -pl gateway -am spring-boot:run "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"
```

如果要验证 Agent 控制面投影，再启动：

```powershell
mvn -pl agent-runtime -am spring-boot:run "-Dmaven.repo.local=D:\Desktop\DataSmart-Govern\DataSmartGovernBackend\.m2"
```

### 4.4 启动 Python AI Runtime

Python Runtime 默认不强绑定 FastAPI 依赖，便于离线单测和学习。如果要验证 gateway 到 Python 的 Agent Host 诊断链路，需要先安装可选 API 依赖，并在 `8090` 端口启动：

```powershell
$env:PYTHONPATH = "$PWD\python-ai-runtime\src"
python -m pip install -e ".\python-ai-runtime[api]"
python -m uvicorn "datasmart_ai_runtime.api:create_app" --factory --host 0.0.0.0 --port 8090
```

启动后可以直连这些低敏诊断接口：

```text
GET http://localhost:8090/agent/capabilities/closure-readiness
GET http://localhost:8090/agent/skills/publication/diagnostics
GET http://localhost:8090/agent/models/inference-optimization/diagnostics
```

这些接口只用于闭口检查和运行时诊断，不会执行工具、不创建任务、不读取源端数据、不写 worker outbox，也不会返回 prompt、SQL、工具参数、样本数据、模型输出、token、内部 endpoint 或长期记忆正文。

启动顺序说明：

- `permission-admin` 应先于 `gateway` 启动，否则 gateway 的强授权模式会无法访问授权中心。
- `task-management` 应先于需要投递 receipt 的 `data-sync` 运行，否则 receipt 投影只能走低敏失败日志。
- `datasource-management` 应先于触发 data-sync worker loop 运行，否则 run-once dispatch 会 fail-closed。
- `data-sync` 的 worker loop scheduler 默认不建议开启，避免服务启动后无意触发真实数据搬运。

## 5. Smoke Check

仓库提供了只读 smoke 脚本：

```powershell
.\scripts\local-e2e-smoke-check.ps1
```

脚本默认检查：

- 关键文件是否存在，例如根 `pom.xml`、JDK 21 文档、Compose 文件、Keycloak realm、关键 MySQL 迁移。
- 关键容器是否运行，例如 MySQL、Redis、Kafka、Nacos、Keycloak、Prometheus、Grafana。
- 关键 HTTP 探针是否可访问，例如 `/actuator/health`、Keycloak realm metadata、gateway auth capabilities、data-sync connector capabilities、task-management receipt query。

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

如果需要进一步验证“认证后的统一 gateway 入口是否能访问 Python AI Runtime 低敏诊断接口”，可以在服务账号探针基础上追加 gateway Agent 诊断探针：

```powershell
.\scripts\local-e2e-smoke-check.ps1 -CheckServiceAccountToken -CheckAgentGatewayDiagnostics
```

该探针会继续使用本地 `sync-service` 样例账号获取 Bearer token，然后通过 gateway 调用以下只读入口：

```text
GET http://localhost:8080/api/agent/capabilities/closure-readiness
GET http://localhost:8080/api/agent/skills/publication/diagnostics
GET http://localhost:8080/api/agent/models/inference-optimization/diagnostics
```

设计意图是验证真实入口链路中的 `Keycloak -> gateway OIDC -> permission-admin route authorization -> gateway route rewrite -> Python Runtime` 是否贯通，而不是验证 Python Runtime 直连端口本身。脚本只检查 HTTP 状态码，不解析、不保存、不打印诊断响应正文；即使后续诊断字段继续扩展，也不会把 prompt、SQL、工具参数、样本数据、模型输出、token、内部 endpoint 或长期记忆正文带到终端日志里。

故障判断建议：

- 如果返回 `401/403`，优先检查 Keycloak realm、`aud=datasmart-gateway`、DataSmart 必需 claim、gateway OIDC 配置和 permission-admin 路由策略。
- 如果返回 `502/503` 或超时，优先检查 gateway 路由顺序、`python-ai-runtime-runtime-diagnostics` 路由是否仍位于通用 `/api/agent/** -> agent-runtime` 之前，以及 Python Runtime 是否已在 `8090` 端口启动。
- 如果 `/auth/session` 通过但 Agent 诊断路由失败，说明身份解析已经成功，问题更可能集中在 permission-admin 对 `/api/agent/**` 诊断路由的授权、gateway route rewrite 或 Python Runtime 下游可达性。

需要特别注意：`sync-service + password grant` 只服务于本地开发 smoke。生产环境不应使用 password grant 或仓库内样例密码，服务间调用应改为 OIDC client credentials、企业 IdP 托管服务账号、mTLS 或 service mesh 身份，并把 client secret 放入 Secret Manager、Kubernetes Secret 或企业密钥库。

脚本安全边界：

- 不创建任务。
- 不调用 `POST /sync-workers/run-once`。
- 不调用 datasource-management run-once。
- 不读取源端数据。
- 不写入目标端数据。
- 不打印 token、client secret、数据库密码、SQL、样本数据、prompt、模型输出或内部请求正文。

## 6. 关键探针清单

| 能力 | 默认地址 | 通过含义 |
| --- | --- | --- |
| Keycloak realm metadata | `http://localhost:18080/realms/datasmart/.well-known/openid-configuration` | 本地 OIDC realm 可访问，gateway 可基于 issuer 获取 JWKS |
| Gateway health | `http://localhost:8080/actuator/health` | 网关进程存活 |
| Gateway auth capabilities | `http://localhost:8080/auth/capabilities` | 认证中心配置可被只读查看 |
| Permission Admin health | `http://localhost:8085/actuator/health` | 授权中心进程存活 |
| Task Management health | `http://localhost:8081/actuator/health` | 任务中心进程存活 |
| Task receipt query | `http://localhost:8081/internal/data-sync-worker-execution-receipts?limit=1` | DataSync receipt 投影查询入口可访问 |
| Datasource Management health | `http://localhost:8082/actuator/health` | 数据源执行面进程存活 |
| Data Sync health | `http://localhost:8086/actuator/health` | 数据同步控制面进程存活 |
| Data Sync capabilities | `http://localhost:8086/sync-connectors/capabilities` | 连接器能力目录可查询 |
| Agent Runtime health | `http://localhost:8091/actuator/health` | Agent Java 控制面进程存活 |
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

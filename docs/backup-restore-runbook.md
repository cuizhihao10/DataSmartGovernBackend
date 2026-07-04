# DataSmart Govern 备份恢复 Runbook

## 1. 文档定位

本 Runbook 用于把 DataSmart Govern 从“本地能跑通”推进到“客户环境可恢复”。它不是新的业务功能规划，也不是要求在开发机上直接备份生产数据；它定义的是正式商用部署前必须具备的备份范围、恢复顺序、RPO/RTO 目标、演练方式和验收口径。

备份恢复的核心思想是：系统是否生产可用，不只看服务能否启动，还要看数据库损坏、对象存储误删、认证中心配置丢失、Kafka backlog 异常、向量索引损坏时，团队能否在可预期时间内恢复到可审计、可验证的状态。

## 2. 基线目标

### 2.1 RPO 与 RTO

`RPO`（Recovery Point Objective，恢复点目标）描述最多允许丢失多久的数据。`RTO`（Recovery Time Objective，恢复时间目标）描述从故障发生到核心服务恢复可用最多允许多久。

建议第一版商业交付目标如下：

| 范围 | 建议 RPO | 建议 RTO | 说明 |
|---|---:|---:|---|
| PostgreSQL 目标库 | 15 分钟以内 | 60 分钟以内 | 已迁移业务 schema、Agent 控制面、AI Memory、LangGraph durable state 和 Keycloak 独立库都应进入该目标恢复范围。 |
| MySQL 迁移期兼容库 | 15 分钟以内 | 60 分钟以内 | 尚未迁移的历史服务、Nacos 外置存储或存量迁移源仍依赖它；迁移完成后应逐步退出核心恢复范围。 |
| Keycloak 认证中心 | 24 小时以内 | 60 分钟以内 | Realm、client、角色、服务账号和密钥轮换历史必须可恢复。 |
| MinIO 对象存储 | 1 小时以内 | 4 小时以内 | 报告、导出文件、工具 artifact 可能涉及客户交付证据。 |
| Kafka 消息与 offset | 取决于 topic 保留期 | 2 小时以内 | 重点是恢复 backlog、offset 与重放策略，不一定追求永久保留全部消息。 |
| Neo4j 图谱 | 24 小时以内 | 4 小时以内 | 血缘、知识图谱和业务关系可备份，也可由上游元数据重建，但重建耗时必须量化。 |
| Chroma 向量索引 | 24 小时以内 | 4 小时以内 | 若向量可从源文档/记忆事件重建，应明确重建脚本、耗时和一致性边界。 |
| Redis 会话/短状态 | 可丢或 15 分钟以内 | 30 分钟以内 | 必须区分可丢缓存、必须恢复的会话和运行态 checkpoint。 |
| Prometheus/Grafana/Alertmanager | 24 小时以内 | 2 小时以内 | 指标历史可按客户要求外置，dashboard 与告警配置应尽量 config-as-code。 |

这些数值不是最终 SLA，只是当前项目进入生产化交付时的第一版保守基线。真实客户环境应按行业、数据规模、合规要求和值班能力重新确认。

## 3. 组件级备份策略

### 3.1 MySQL

MySQL 是当前 Java 业务事实的主存储，包含任务、权限、数据源配置、数据同步控制面、质量规则、Agent Runtime 投影等关键数据。它应采用“全量备份 + 增量日志 + 迁移版本登记”的组合策略。

最低要求：

- 每日至少一次全量备份，并保留最近 7 到 30 天。
- 开启 binlog 或客户等价能力，支持 `PITR`（Point-in-Time Recovery，按时间点恢复）。
- 备份文件必须加密存储，访问权限只授予 DBA、平台管理员或受控恢复服务账号。
- 恢复后必须执行数据库迁移版本检查，避免代码版本与 schema 版本错配。
- 恢复验收必须包含 gateway health、Java 服务 health、核心只读 API、Python Runtime readiness 和只读 smoke。

### 3.2 Keycloak

Keycloak 承担 OIDC 认证中心职责。当前本地 Compose 已改为 PostgreSQL-backed 存储，Keycloak 的 realm、client、用户、角色、服务账号、mapper、密码哈希和密钥轮换历史会落入 PostgreSQL 的独立 `keycloak` database，而不是容器文件目录。生产环境如果使用自建 Keycloak，必须备份该 Keycloak 数据库、realm/config-as-code 导出、client 配置、服务账号准入策略和密钥轮换历史。如果客户使用企业 IdP，则需要备份的是本平台侧的 OIDC/SAML 对接配置和服务账号准入策略。

最低要求：

- Realm 配置应尽量通过 config-as-code 或导出文件版本化。
- Keycloak 数据库应使用独立 database/user，不应复用 DataSmart 业务服务账号；恢复时应先恢复 PostgreSQL 中的 Keycloak database，再启动 Keycloak 验证 realm。
- 客户生产密码、client secret 和 signing key 不得进入 Git。
- 恢复后必须验证服务账号 token、用户 token、issuer、JWKS、gateway 资源服务器验签和角色 claim。
- 如果发生 signing key 轮换，必须确认旧 token 过期窗口和新 token 生效窗口。

### 3.3 MinIO

MinIO 保存报告、导出文件、工具 artifact、数据质量报告等对象。生产环境应优先使用客户已有对象存储或企业 MinIO 集群。

最低要求：

- bucket、policy、生命周期规则和对象版本策略必须有记录。
- 关键 bucket 应启用版本保留或跨可用区复制。
- 恢复后必须验证对象列表、关键报告下载、artifact metadata 和权限策略。
- 大对象恢复需要估算带宽与耗时，避免只给出“可以备份”的空泛说明。

### 3.4 Kafka

Kafka 是 Java 服务与 Python AI Runtime、worker command、receipt、runtime event、outbox 之间的异步骨干。它的恢复目标不只是“broker 能启动”，还包括 topic、partition、副本、offset、保留周期、死信队列和重放策略。

最低要求：

- topic 配置应以文档或脚本登记，包括 partition、retention、cleanup policy 和 DLQ。
- 重要事件流应明确是否允许重放，重放时如何保证幂等。
- 恢复后必须验证 consumer group、lag/backlog、关键 topic 可读写和 outbox/receipt 投影是否恢复。
- 本地 Compose 只有单 broker，不能代表生产高可用 Kafka。

### 3.5 Redis

Redis 保存会话、缓存、短期协调状态、nonce、部分 AI Runtime 事件存储或 checkpoint。它不是所有数据都必须恢复，但必须明确哪些 key 可以丢，哪些 key 会影响用户会话、工具幂等或恢复执行。

最低要求：

- 区分缓存、会话、nonce、checkpoint、短期事件流。
- 需要恢复的数据启用 AOF/RDB 或客户托管 Redis 的快照能力。
- 可丢缓存恢复后应有自动回源或重新构建策略。
- 恢复后必须验证 gateway 会话、签名 nonce 边界、Python Runtime 事件/checkpoint 降级行为。

### 3.6 Neo4j

Neo4j 用于血缘、知识图谱、业务关系和 GraphRAG 相关图结构。它可以采用备份恢复，也可以通过 MySQL 元数据、数据源扫描结果和离线任务重建，但必须明确重建成本。

最低要求：

- 明确哪些图谱数据是主数据，哪些是可重建索引。
- 如果使用备份，应定期验证 dump/load 或企业备份工具。
- 如果依赖重建，应提供重建任务入口、耗时基线和失败重试策略。
- 恢复后必须验证核心图查询、血缘查询和 GraphRAG 降级/恢复路径。

### 3.7 Chroma

Chroma 保存向量索引、长期记忆语义索引和 RAG 检索材料。当前项目已经把 Chroma 抽象为可替换向量存储方向，因此恢复策略也应区分“索引数据备份”和“从源事实重建索引”。

最低要求：

- 明确 collection 命名、namespace、tenant/workspace metadata filter。
- 向量数据如需备份，应记录持久化目录和版本兼容性。
- 如果允许重建，必须保留源文档、记忆事件或二级索引，并记录重建耗时。
- 恢复后必须验证长期记忆检索、metadata filter 隔离和低基数指标。

### 3.8 Nacos

Nacos 在本地 Compose 中承担服务注册与配置中心职责。生产环境可以继续使用 Nacos，也可以替换为客户已有服务发现或配置平台，但恢复边界必须清楚。

最低要求：

- 外置数据库模式下，Nacos 配置数据应纳入 MySQL 备份。
- 本地 `nacos_data` 与 `nacos_logs` 只是开发环境持久化，不等于生产高可用。
- 恢复后必须验证服务注册、gateway 路由发现、配置读取和 Java 服务启动顺序。

### 3.9 Prometheus、Grafana 与 Alertmanager

可观测性组件的恢复策略要区分“配置”和“历史数据”。配置应尽量保存在 Git 中；指标历史可按客户要求进入远端长期存储。

最低要求：

- Prometheus scrape config、alert rules、Grafana provisioning、dashboard JSON、Alertmanager templates 应尽量 config-as-code。
- Grafana 运行态用户、组织、dashboard 修改如不走 Git，需要备份数据库或导出。
- 恢复后必须验证指标抓取、告警规则加载、dashboard 可见和 Alertmanager 路由。

## 4. 恢复顺序

推荐恢复顺序如下：

1. 恢复基础网络、DNS、Secret Manager、证书和企业镜像仓库访问。
2. 恢复 MySQL，并执行 schema/migration 版本检查。
3. 恢复 Keycloak 或企业 IdP 对接，验证 OIDC token、issuer、JWKS 和角色 claim。
4. 恢复 Redis、Kafka、Nacos 等协调和异步基础设施。
5. 恢复 MinIO、Neo4j、Chroma 等对象、图谱和向量能力。
6. 启动 Java 微服务，检查 Actuator health、注册发现和只读控制面。
7. 启动 Python AI Runtime，检查 closure readiness、LangGraph/Memory/Skill 诊断和指标。
8. 执行 gateway 只读路由、服务账号 token、Agent 诊断和全平台只读 smoke。
9. 记录恢复耗时、失败点、数据缺口、人工步骤和后续修复项。

这个顺序的原则是先恢复事实来源，再恢复认证授权，再恢复异步协调，最后恢复 AI Runtime 和可重建索引。不要先恢复 Agent 或向量索引再恢复 MySQL，因为那会把可重建索引误当成系统事实来源。

## 5. 当前仓库静态检查

当前仓库提供备份恢复就绪检查脚本：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\backup-restore-check.ps1
```

默认模式只做静态检查，不连接数据库、不读取 Secret、不导出数据、不恢复覆盖、不触发 worker。它会验证：

- 本 Runbook 是否存在并覆盖核心组件。
- Compose 是否为关键有状态组件声明持久化 volume，或为 Keycloak 这类组件声明 PostgreSQL-backed 存储契约。
- Keycloak realm、Prometheus、Grafana、Alertmanager 等配置是否具备 config-as-code 路径。
- `.env.application.example` 是否明确真实 Secret 不进入 Git。
- 生产加固 runbook 与最终收敛清单是否已经纳入该检查脚本。

如果要在恢复演练环境中检查本机工具，可追加：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\backup-restore-check.ps1 -CheckLocalTools
```

如果要生成不含 Secret 的恢复范围清单，可追加：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\backup-restore-check.ps1 -WriteRecoveryInventory
```

输出会写入 Git 忽略的 `target/backup-restore/datasmart-recovery-inventory.json`。该文件只包含组件、volume、PostgreSQL-backed database contract、config-as-code 路径和 runbook 引用，不包含密码、token、数据库行、对象内容、prompt、模型输出或业务数据。

## 6. 真正恢复演练的验收口径

静态检查只能证明仓库已经具备恢复计划，不能证明生产恢复真的成功。正式客户验收至少应完成一次恢复演练：

1. 在临时环境准备同版本镜像、配置、Secret 和备份制品。
2. 按恢复顺序恢复 PostgreSQL、MySQL、Keycloak、Redis、Kafka、MinIO、Neo4j、Chroma、Nacos 和可观测性组件。
3. 启动 gateway、Java 微服务、Python AI Runtime。
4. 获取服务账号 token，并通过 gateway 访问只读控制面。
5. 执行 `local-e2e-smoke-check.ps1` 或客户环境等价只读 smoke。
6. 对照 RPO/RTO 记录恢复点、恢复耗时、失败步骤和人工操作。
7. 对未达标项形成修复任务，而不是只在会议纪要中描述风险。

## 7. 后续收敛建议

备份恢复完成静态闭环后，下一步应继续处理：

- Kubernetes/Helm：把当前 Compose 的有状态组件与应用组件迁移为可审查的 chart/values 边界。
- 容量基线：测量备份、恢复、索引重建、对象恢复和只读 smoke 的耗时。
- 故障演练：验证 MySQL、Keycloak、Kafka、Python Runtime、Chroma/Neo4j 不可用时的降级与恢复路径。

这些工作属于生产可靠性交付，不应再转回“新增功能越多越好”的节奏。

# Agent Runtime 可观测性 Runbook

本文档记录 `agent-runtime` 当前接入 Prometheus/Grafana/Alertmanager 的最小可运营能力。

## 1. 当前范围

当前重点监控 Java Agent Runtime 中两条生产风险较高的链路：

第一条是异步 command dispatcher pre-check：

- command 投递前是否通过执行前复核；
- 是否因为确认记录过期、策略缺失、沙箱拒绝或运行时保护暂缓而阻断/退避；
- 是否出现未知 issueCode，提示代码、指标白名单和告警规则没有同步更新。

第二条是 Skill 可见性快照专用索引：

- Python Runtime 写出的 `SKILL_VISIBILITY_SNAPSHOT_RECORDED` 是否成功进入 Java 专用索引；
- dedicated index 是否持续失败，是否大量退回通用 runtime event projection；
- Manifest 绑定状态是否稳定，是否频繁出现本地默认、远端不可用、诊断不可用或未知状态；
- 查询链路是否仍能支撑治理页、Skill Marketplace 统计和事故复盘。

这不是完整 Agent Runtime 观测体系。后续仍需要补 outbox 积压、最老 PENDING 年龄、dispatcher 失败率、runtime event 持久化、模型网关 provider 健康、会话级 Skill cache 和多 Agent 协作指标。

## 2. Prometheus 抓取关系

配置文件：`docker/prometheus/prometheus.yml`

当前新增抓取 job：

```yaml
- job_name: 'agent-runtime'
  static_configs:
    - targets: ['host.docker.internal:8091']
  metrics_path: '/actuator/prometheus'
```

端口约定：

- `agent-runtime` 默认端口：`8091`。
- `python-ai-runtime` 默认端口：`8090`。
- 两者不能共用端口，因为 Java 使用 `/actuator/prometheus`，Python 使用 `/agent/metrics`。

本地如果必须改端口，可以覆盖：

```powershell
$env:DATASMART_AGENT_RUNTIME_SERVER_PORT='8091'
```

生产环境建议使用 Nacos、内网网关或服务网格地址，不要让 Prometheus 长期依赖开发机 localhost 端口。

## 3. 指标名称

### `datasmart_agent_runtime_async_command_precheck_verdict_total`

含义：每条异步 command dispatcher pre-check verdict 计数。

标签：

- `decision`：`ALLOW_EXECUTION`、`BLOCKED`、`DEFERRED`、`OTHER`。
- `targetService`：平台服务分组，例如 `task-management`、`data-sync`、`data-quality`、`datasource-management`、`OTHER`。

用途：

- 计算通过率、阻断率、暂缓率。
- 判断 dispatcher pre-check 开启后是否出现大量历史 command 不兼容。
- 判断容量/熔断是否导致大量 `DEFERRED`。

### `datasmart_agent_runtime_async_command_precheck_issue_total`

含义：pre-check issueCode 分布计数。

标签：

- `decision`：阻断或暂缓决策。
- `issueCode`：稳定原因码，例如 `CONFIRMATION_EXPIRED`、`CURRENT_POLICY_ITEM_MISSING`、`RUNTIME_PROTECTION_DEFERRED_BEFORE_WORKER`。
- `targetService`：平台服务分组。

用途：

- 区分“用户需要重新确认”和“系统容量/依赖暂不可用”。
- 支撑 Alertmanager 告警分类。
- 发现新增 issueCode 未同步指标白名单时的 `OTHER`。

### `datasmart_agent_runtime_skill_visibility_index_materialization_total`

含义：Skill 可见性快照进入专用索引的物化计数。

标签：

- `outcome`：`materialized`、`duplicate`、`skipped`、`failed`。
- `store`：`memory`、`mysql`、`none`、`OTHER`。
- `bindingStatus`：Manifest 绑定状态白名单，例如 `BOUND_REMOTE_MANIFEST`、`REMOTE_UNAVAILABLE_FALLBACK`、`DIAGNOSTICS_UNAVAILABLE`、`UNKNOWN`、`OTHER`。

用途：

- 判断 dedicated index 是否真正接住了 Python Runtime 的会话级能力边界事实；
- 判断 MySQL 表结构、连接池、字段映射或 JSON 序列化是否持续失败；
- 观察 Manifest 绑定是否异常，例如生产环境频繁退回本地默认 Skill 目录；
- 为 Skill Marketplace、能力包灰度和事故复盘提供低敏运维信号。

### `datasmart_agent_runtime_skill_visibility_index_query_total`

含义：Skill 可见性快照查询次数。

标签：

- `source`：`dedicated` 或 `fallback`。
- `result`：`success` 或 `failed`。
- `store`：`memory`、`mysql`、`none`、`OTHER`。

用途：

- 计算 dedicated index 查询是否健康；
- 判断治理页是否大量 fallback 到通用 runtime event projection；
- 区分“索引没启用”和“索引启用但查询失败”。

### `datasmart_agent_runtime_skill_visibility_index_query_result_total`

含义：Skill 可见性快照查询返回记录数的累积计数。

标签：

- `source`：`dedicated` 或 `fallback`。
- `store`：`memory`、`mysql`、`none`、`OTHER`。

用途：

- 辅助判断查询是否持续返回空窗口；
- 辅助 Grafana 面板展示 dedicated/fallback 查询结果趋势；
- 注意它是 counter，不是当前索引大小。当前索引大小应读取诊断接口里的 `currentIndexSize`。

## 4. 低基数原则

Prometheus 标签禁止包含：

- `tenantId`
- `projectId`
- `commandId`
- `outboxId`
- `runId`
- `sessionId`
- `traceId`
- `workspaceId`
- `actorId`
- `requestId`
- `manifestFingerprint`
- 原始 URL、SQL、prompt、payload、工具参数或异常全文

原因：

这些字段会随着租户、会话、命令和请求数量持续增长，导致 Prometheus 生成大量时序。商业化系统中，高基数指标会拖慢查询、增加存储成本，严重时会影响告警可靠性。

单条 command 排障应使用：

- runtime event replay；
- command outbox 诊断接口；
- selected-node confirmation 查询；
- 结构化日志；
- 未来的审计 outbox 或持久化事件表。

## 5. 告警规则

规则文件：`docker/prometheus/rules/agent-runtime-alerts.yml`

核心告警：

- `AgentRuntimeMetricsDown`：Prometheus 无法抓取 agent-runtime。
- `AgentRuntimeMetricsTargetMissing`：Prometheus 未发现 agent-runtime job。
- `AgentRuntimeAsyncCommandPreCheckBlockedDetected`：近期出现 pre-check 阻断。
- `AgentRuntimeAsyncCommandPreCheckDeferredRatioHigh`：pre-check 暂缓比例偏高。
- `AgentRuntimeAsyncCommandConfirmationExpired`：确认记录过期。
- `AgentRuntimeAsyncCommandRuntimeProtectionDeferred`：运行时保护频繁暂缓。
- `AgentRuntimeAsyncCommandPolicyItemMissing`：无法读取当前策略项。
- `AgentRuntimeAsyncCommandPreCheckUnknownIssueCode`：出现未纳入白名单的 issueCode。
- `AgentRuntimeSkillVisibilityIndexMaterializationFailureDetected`：Skill 可见性索引出现新的物化失败。
- `AgentRuntimeSkillVisibilityIndexMaterializationFailureRatioHigh`：Skill 可见性索引物化失败比例偏高。
- `AgentRuntimeSkillVisibilityIndexFallbackQueryRatioHigh`：Skill 可见性查询大量退回通用 projection。
- `AgentRuntimeSkillVisibilityIndexDedicatedQueryFailureDetected`：dedicated index 查询失败。
- `AgentRuntimeSkillVisibilityIndexManifestBindingUnhealthy`：新物化快照出现未知、fallback 或诊断不可用的 Manifest 绑定状态。

## 6. 排障建议

### 6.1 抓取失败

优先检查：

- `agent-runtime` 是否启动；
- `DATASMART_AGENT_RUNTIME_SERVER_PORT` 是否为 `8091`；
- `/actuator/prometheus` 是否可访问；
- `agent-runtime/pom.xml` 是否包含 `spring-boot-starter-actuator` 和 `micrometer-registry-prometheus`；
- Prometheus 容器是否重新加载配置；
- docker 网络是否能访问 `host.docker.internal:8091`。

### 6.2 阻断升高

优先检查：

- runtime event 中的 `tool_pre_check_blocked`；
- issueCode 是否集中在 `CONFIRMATION_EXPIRED` 或 `CONFIRMATION_ID_MISSING`；
- selected-node confirmation 是否已经成为生产主路径；
- 是否把 `dispatcher-pre-check-enabled=true` 提前打开到了历史 Run 级 command；
- 用户是否需要重新 dry-run 和 selected-node confirmation。

### 6.3 暂缓比例升高

优先检查：

- issueCode 是否为 `RUNTIME_PROTECTION_DEFERRED_BEFORE_WORKER`；
- targetService 是否处于容量满额或熔断；
- `tool-runtime-protection` 并发上限是否过小；
- 是否需要拆批、扩容或暂停某些高成本工具；
- 下游 `task-management/data-sync/data-quality` 是否健康。

### 6.4 出现 `OTHER`

`OTHER` 通常表示新增了业务 issueCode 或 targetService，但没有同步指标白名单、告警和 runbook。

处理方式：

- 检查 `AgentAsyncTaskCommandPreCheckMetricsService` 的 `ALLOWED_ISSUE_CODES`；
- 检查新增 issueCode 是否稳定、低敏、低基数；
- 同步更新 Prometheus 告警、Grafana 面板、当前 runbook 和测试。

### 6.5 Skill 可见性索引物化失败

优先检查：

- `GET /agent-runtime/runtime-events/skill-visibility-snapshots/diagnostics`；
- `lastFailureStage` 和 `lastFailureReason` 是否指向 materialization；
- `activeStore` 是否符合预期，例如生产是否应为 `mysql`；
- `DATASMART_AGENT_RUNTIME_SKILL_VISIBILITY_INDEX_ENABLED` 是否为 `true`；
- `DATASMART_AGENT_RUNTIME_SKILL_VISIBILITY_INDEX_STORE` 是否为 `mysql` 或预期 store；
- `DATASMART_AGENT_RUNTIME_PERSISTENCE_DATABASE_ENABLED` 是否已打开；
- `agent_skill_visibility_snapshot_index` 迁移脚本是否已经执行；
- Kafka 是否还有重放机会，避免 projection 成功但索引失败后永久漏写。

如果失败集中在 MySQL，可临时切回 `memory` 保住本地治理页可用性，但生产环境需要尽快恢复持久化索引，否则跨实例、跨重启审计会退化。

### 6.6 fallback 查询比例偏高

优先检查：

- 专用索引是否被关闭；
- Store Bean 是否因为条件配置未装配；
- MySQL store 是否要求数据库总开关但环境未打开；
- 查询 API 是否走到了 `/skill-visibility-snapshots`，而不是通用 runtime event replay；
- 本地学习环境是否故意关闭专用索引。

生产环境长期 fallback 的风险是：治理页仍可能显示结果，但这些结果来自通用 runtime event 热窗口，不能代表长期、跨实例、跨重启的 Skill 可见性事实。

### 6.7 Manifest 绑定状态异常

优先检查：

- Python Runtime 的 Skill Publication Manifest 诊断是否能访问 Java `agent-runtime`；
- 远端 Manifest 是否有 `contentFingerprint`；
- `REMOTE_UNAVAILABLE_FALLBACK` 是否由网络、网关签名、权限或启动顺序导致；
- `DIAGNOSTICS_UNAVAILABLE` 是否由 Python 诊断服务异常导致；
- 是否处于开发模式，允许本地默认 Skill fallback；
- 生产环境是否应该把 Manifest 读取配置为 required fail-closed。

该告警是 `info` 级别，因为本地开发和灰度阶段可能允许 fallback。但在正式客户环境里，频繁出现 fallback 通常意味着 Python 使用的 Skill 能力目录与 Java 控制面的发布事实源不一致。

## 7. Grafana 查询建议

当前还没有为 agent-runtime 生成独立 dashboard JSON。临时面板可先使用以下查询：

- 服务可用性：`up{job="agent-runtime"}`。
- pre-check 暂缓比例：`sum(rate(datasmart_agent_runtime_async_command_precheck_verdict_total{decision="DEFERRED"}[15m])) / clamp_min(sum(rate(datasmart_agent_runtime_async_command_precheck_verdict_total[15m])), 1)`。
- Skill 索引物化失败率：`sum(rate(datasmart_agent_runtime_skill_visibility_index_materialization_total{outcome="failed"}[30m])) / clamp_min(sum(rate(datasmart_agent_runtime_skill_visibility_index_materialization_total{outcome=~"materialized|duplicate|failed"}[30m])), 1)`。
- Skill 查询 fallback 比例：`sum(rate(datasmart_agent_runtime_skill_visibility_index_query_total{source="fallback",result="success"}[30m])) / clamp_min(sum(rate(datasmart_agent_runtime_skill_visibility_index_query_total{result="success"}[30m])), 1)`。
- Manifest 绑定异常增量：`sum(increase(datasmart_agent_runtime_skill_visibility_index_materialization_total{outcome=~"materialized|duplicate",bindingStatus=~"UNKNOWN|UNBOUND_UNKNOWN|DIAGNOSTICS_UNAVAILABLE|REMOTE_UNAVAILABLE_FALLBACK|LOCAL_DEFAULT_OR_FALLBACK|OTHER"}[30m]))`。

## 8. 当前边界

- 当前指标是 JVM 实例级指标，不代表全局分布式配额。
- 当前没有把 runtime event 持久化到数据库，JVM 重启后内存投影仍会丢失。
- 当前告警阈值偏向本地和早期集成环境，生产需要结合租户规模、QPS、worker 数量和 SLA 调整。
- 当前没有提供 agent-runtime 专属 Grafana dashboard JSON，本阶段先提供 Prometheus 规则、runbook 和查询建议。
- Skill 可见性索引仍没有 outbox/dispatcher 式持久补偿队列；告警只能提示异常，不能自动补偿所有漏写。

## 9. 下一步

建议路线：

1. 设计 gateway 会话级 READY Skill cache，减少 `/agent/plans` 高频路径重复做能力过滤。
2. 为 async command outbox 增加积压量、最老 pending 年龄、失败率、blocked 数等指标。
3. 将 Skill 可见性索引失败补偿升级为 outbox/dispatcher，而不是继续依赖同步 consumer 重试。
4. 切换一部分开发精力到长期记忆二级索引 worker 或智能网关多 Agent 协作，避免 Java Agent Runtime 局部无限打磨。

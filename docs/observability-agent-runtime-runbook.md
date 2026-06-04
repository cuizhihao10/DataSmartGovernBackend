# Agent Runtime 可观测性 Runbook

本文档记录 `agent-runtime` 当前接入 Prometheus/Grafana/Alertmanager 的最小可运营能力。

## 1. 当前范围

当前重点监控 Java Agent Runtime 中的异步 command dispatcher pre-check：

- command 投递前是否通过执行前复核；
- 是否因为确认记录过期、策略缺失、沙箱拒绝或运行时保护暂缓而阻断/退避；
- 是否出现未知 issueCode，提示代码、指标白名单和告警规则没有同步更新。

这不是完整 Agent Runtime 观测体系。后续仍需要补 outbox 积压、最老 PENDING 年龄、dispatcher 失败率、runtime event 持久化、模型网关 provider 健康和多 Agent 协作指标。

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

## 7. 当前边界

- 当前指标是 JVM 实例级指标，不代表全局分布式配额。
- 当前没有把 runtime event 持久化到数据库，JVM 重启后内存投影仍会丢失。
- 当前告警阈值偏向本地和早期集成环境，生产需要结合租户规模、QPS、worker 数量和 SLA 调整。
- 当前没有提供 Grafana dashboard JSON。

## 8. 下一步

建议路线：

1. 为 async command outbox 增加积压量、最老 pending 年龄、失败率、blocked 数等指标。
2. 将 runtime event 投影迁移到持久化 store，形成审计 replay 基础。
3. 切换一部分开发精力到 MCP/Skill 发布流、长期记忆 Chroma/Neo4j 二级索引 worker 或智能网关多 Agent 协作，避免 Java Agent Runtime 局部无限打磨。

# DataSmart Govern 容量基线 Runbook

## 1. 文档目标

本文档用于把 DataSmart Govern 从“本地 smoke 能跑通”推进到“生产部署前有第一版容量依据”。容量基线不是追求极限压测数字，也不是证明系统永远不会慢，而是为客户环境的资源 requests/limits、扩容策略、告警阈值、SLO 和发布验收提供一组可复现的初始证据。

当前项目已经完成本地 Compose 闭环、OIDC、gateway、Java 微服务、Python AI Runtime、LangGraph、多智能体控制面、Prometheus/Grafana 和只读 smoke。下一步容量工作应该沿着“安全、可观测、可复现、逐步扩大”的顺序推进，不能一开始就打开真实 worker、dispatcher、数据清洗写入或工具提交链路。

## 2. 容量基线的核心原则

### 2.1 先测只读控制面，再测写入业务面

第一阶段只覆盖只读和诊断链路，例如 health、Actuator、Python Runtime readiness、Agent plan dry-run、工具描述符读取、同步诊断、质量报告查询、权限策略查询和只读 smoke。这样可以先建立系统入口、认证、路由、序列化、指标导出和观测链路的性能底线。

第二阶段再覆盖受控异步链路，例如 Kafka command/outbox backlog、receipt 投影、runtime event envelope、memory retrieval 和 LangGraph execution gate。此阶段仍应优先使用隔离测试数据，不应消费客户真实业务任务。

第三阶段才进入写入型链路，例如 DataSync run-once、DataQuality remediation、Agent tool submit、outbox dispatcher 和真实 worker loop。该阶段必须先具备审批、审计、幂等、死信、回滚、备份恢复和隔离测试数据。

### 2.2 容量数字必须能被指标解释

容量报告不能只有 QPS。每个场景至少要同时记录：

- P95 和 P99 延迟，用于判断普通用户体验和尾延迟风险。
- 错误率，用于区分“变慢”和“失败”。
- CPU、内存、线程、连接池和 GC，用于判断服务自身瓶颈。
- Kafka consumer lag、topic backlog 和 dead letter，用于判断异步链路积压。
- MySQL 慢查询、Redis 命中率、MinIO 延迟、Chroma 检索耗时、Neo4j 查询耗时，用于判断存储层瓶颈。
- Agent plan、LangGraph gate、memory retrieval、model provider diagnostics，用于判断 AI Runtime 编排链路瓶颈。

### 2.3 先给保守 SLO，再用真实客户环境校正

第一版 SLO 建议保守，不要直接把本地开发机数字当生产承诺。建议把容量基线分为三类：

- 开发/演示基线：用于证明链路可用，不作为客户 SLA。
- 预生产基线：使用接近生产的资源规格、镜像版本和测试数据，作为上线评审依据。
- 客户生产基线：使用客户真实部署拓扑、监控系统和安全策略，作为正式容量规划依据。

## 3. 建议场景矩阵

| 场景 | 范围 | 首轮目标 | 核心指标 |
|---|---|---|---|
| Gateway 入口 | OIDC 鉴权、路由、Agent 控制面代理 | 证明统一入口不会成为第一瓶颈 | P95/P99、401/403/5xx、route RPS |
| Java 服务 | permission、task、datasource、data-sync、data-quality、agent-runtime、observability | 证明核心 API 在只读/诊断模式下稳定 | HTTP 延迟、JVM 内存、线程、连接池 |
| Python Runtime | Agent plan、LangGraph gate、长期记忆检索、指标导出 | 证明 AI 编排控制面可观测、可降级 | plan P95、gate P95、memory retrieval P95、错误率 |
| Kafka | command、receipt、runtime event、outbox | 证明异步消息不会静默积压 | consumer lag、topic backlog、dead letter |
| MySQL/Redis | 业务主存储、缓存、会话、短期状态 | 证明查询、连接池、缓存命中率稳定 | 慢查询、连接数、Redis hit rate |
| MinIO | 报告、artifact、导出文件 | 证明文件型制品读写链路可扩展 | 上传/下载延迟、错误率、对象数 |
| Chroma/Neo4j | 长期记忆、GraphRAG、血缘图谱 | 证明检索和图查询可以支撑 AI 场景 | 检索 P95、查询 P95、重建耗时 |
| Agent plan | 多智能体编排、runtime event envelope | 证明复杂控制面不会无限放大上下文和事件体积 | plan latency、event count、envelope bytes |

## 4. 推荐执行阶段

### 4.1 Stage 1：只读控制面基线

建议先运行现有只读 smoke，确认服务全部健康：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\local-e2e-smoke-check.ps1 `
  -CheckServiceAccountToken `
  -CheckAgentGatewayDiagnostics
```

然后选择少量只读接口进行轻压，例如 gateway health、服务 health、Python Runtime metrics、Agent diagnostics。此阶段不要启用真实 worker、dispatcher 或写入型执行器。

### 4.2 Stage 2：诊断 API 与 AI 控制面基线

覆盖 Agent plan、LangGraph execution gate、memory retrieval、model provider diagnostics、tool descriptor list、gateway agent diagnostics 等链路。重点观察 Python Runtime CPU、内存、事件 envelope 体积、低基数指标和 gateway 转发延迟。

### 4.3 Stage 3：异步积压与恢复基线

在隔离测试环境中构造 command/outbox/receipt/runtime event 的可控负载，观察 Kafka backlog、consumer lag、dead letter 和 Java outbox 投影延迟。此阶段仍不建议直接接入客户真实业务任务。

### 4.4 Stage 4：客户批准后的写入链路基线

只有当备份恢复、审计、审批、幂等、回滚和测试数据都准备好后，才进入真实 DataSync、DataQuality remediation、tool submit、worker loop 等写入链路。该阶段必须有清晰的停止条件和回滚方案。

## 5. 工具建议

推荐工具可以按客户环境选择，不强制绑定某一个：

- `k6`：适合 HTTP 场景编排、阈值断言和 CI 报告。
- `hey`：适合轻量 HTTP 基线和开发/预生产快速检查。
- `wrk`：适合 Linux 压测 runner 上的高吞吐 HTTP 测试。
- `ab`：适合作为最低依赖的 HTTP fallback 工具。
- Prometheus/Grafana：用于关联服务指标、JVM 指标、Python Runtime 指标和 Kafka backlog。

本仓库提供 `scripts/capacity-baseline-check.ps1` 做就绪检查。默认不运行压测工具；如果要检查本机工具链，可追加 `-CheckLocalTools`。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\capacity-baseline-check.ps1
```

生成不含 Secret 的容量基线计划：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\capacity-baseline-check.ps1 -WriteBaselinePlan
```

## 6. 报告模板

每次容量基线至少记录以下内容：

- 环境信息：Git commit、镜像 tag、部署方式、节点规格、CPU/内存限制、JDK/Python 版本、模型 Provider 配置。
- 测试范围：场景名称、接口路径、请求方法、是否只读、是否触发 worker、是否使用测试数据。
- 压测参数：并发数、持续时间、预热时间、请求总数、超时、重试策略。
- 结果：QPS、P50/P95/P99、最大延迟、错误率、状态码分布。
- 资源指标：CPU、内存、线程、GC、连接池、Kafka lag、MySQL 慢查询、Redis 命中率。
- 结论：是否达到 SLO，瓶颈在哪里，下一步扩容或优化建议是什么。
- 风险：是否使用真实数据，是否触发写入链路，是否有回滚方案，是否影响其他环境。

## 7. 第一版建议阈值

以下阈值只适合作为预生产起点，不能直接作为对客户承诺：

- Gateway 健康和只读路由：P95 小于 300ms，错误率小于 1%。
- Java 只读诊断接口：P95 小于 500ms，错误率小于 1%。
- Python Runtime Agent plan dry-run：P95 小于 1500ms，错误率小于 2%。
- LangGraph execution gate：P95 小于 800ms，错误率小于 1%。
- Memory retrieval：P95 小于 1000ms，错误率小于 2%。
- Kafka consumer lag：稳定压测结束后应可回落，不允许持续增长。
- MySQL 慢查询：压测窗口内应可解释，不允许出现持续阻塞连接池的慢查询。

## 8. 验收边界

容量基线通过不代表“生产性能永远足够”。它只代表当前版本在指定环境、指定负载、指定数据规模、指定安全开关下有一组可复现证据。

上线前仍应继续完成故障演练，并把容量结果接入告警阈值、HPA/扩容策略、资源 requests/limits、错误预算和事故复盘流程。容量基线与故障演练是同一枚硬币的两面：前者告诉我们正常压力下系统能承受多少，后者告诉我们异常压力下系统如何失败、如何恢复。

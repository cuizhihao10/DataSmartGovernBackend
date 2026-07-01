# DataSmart Govern 故障演练 Runbook

## 1. 文档目标

本文档定义 DataSmart Govern 在预生产或客户批准环境中的故障演练方法。演练的目标不是证明组件“永不故障”，而是验证故障能够被发现、影响范围可控、平台能够按设计降级、值班人员知道如何恢复，并且恢复时间与数据损失满足约定的 RTO/RPO。

仓库提供的 `scripts/failure-drill-check.ps1` 默认只检查演练就绪条件，不停止容器、不修改网络、不删除 volume、不损坏数据、不读取 Secret，也不触发 Java/Python worker。真实故障注入必须在隔离环境中由人工批准后执行。

## 2. 演练治理原则

### 2.1 先限定 blast radius

每次只演练一个主要故障，明确 tenant、namespace、服务实例、数据集和时间窗口。禁止在共享生产环境中同时停止多个事实存储或同时破坏认证、入口与消息链路。

### 2.2 先定义停止条件（stop condition）

出现以下任一情况应立即终止注入并进入恢复：

- 影响超出批准的服务、租户或 namespace。
- 出现不可解释的数据写入、数据丢失或审计缺口。
- 错误率、积压、恢复时间超过演练批准阈值。
- 监控、告警或回滚通道本身失效。
- 值班负责人、安全负责人或客户代表要求停止。

### 2.3 恢复优先于“把演练做完”

恢复顺序遵循“身份与配置 -> 数据与消息 -> 应用控制面 -> AI Runtime -> 只读 smoke -> 受控写入”的原则。恢复后先验证 OIDC token、health、readiness、核心查询和只读 Agent plan，再决定是否恢复 worker、dispatcher、同步和质量整改执行器。

### 2.4 证据必须可审计

每次演练至少归档 Git commit、镜像 tag、审批单、开始/结束时间、注入动作、Prometheus/Grafana 截图、告警时间线、日志 traceId、Kafka lag、RTO/RPO 结果、恢复命令、实际影响和复盘行动项。归档中不得包含密码、token、prompt、模型输出、工具参数或客户数据样本。

## 3. 演练前置条件

- 已通过 `production-readiness-check.ps1`、`backup-restore-check.ps1` 和 `capacity-baseline-check.ps1`。
- 已确认备份制品可用，并在临时环境完成过恢复验证。
- 已准备隔离测试数据、测试账号、OIDC client 和告警接收人。
- 已冻结演练窗口内的非必要发布和数据库迁移。
- 已记录基线健康状态、Kafka backlog、数据库连接、缓存命中率和 Agent Runtime 指标。
- 已关闭不在本次范围内的高风险写入开关，避免故障期间产生额外变量。
- 已指定演练负责人、观察员、恢复负责人和最终停止决策人。

## 4. 核心场景矩阵

| 场景 | 注入方式示例 | 主要观测信号 | 期望降级 | 恢复与验收 |
|---|---|---|---|---|
| Keycloak/企业 IdP 不可用 | 在隔离 namespace 缩容 IdP 或阻断测试客户端访问 | gateway 401/503、JWKS/issuer 错误、认证告警 | 已有短期 token 按策略工作，新登录失败且不绕过认证 | 恢复 IdP，验证 discovery、JWKS、用户 token 与 service account token |
| Gateway 实例故障 | 终止一个 gateway Pod/容器 | upstream 5xx、实例健康、入口 P95/P99 | 负载均衡转移到健康实例，不暴露内部服务 | 实例恢复后先 readiness，再加入流量并执行只读路由 smoke |
| Java 核心服务故障 | 终止 permission/task/data-sync/data-quality/agent-runtime 单个实例 | Actuator health、路由 5xx、线程与连接池、业务告警 | gateway 返回明确错误；异步命令不丢失、不被重复执行 | 恢复服务，验证数据库迁移、health、幂等投影与积压回落 |
| Python Runtime 故障 | 终止 python-ai-runtime 实例或让模型 Provider 超时 | readiness、Agent plan 错误率、provider timeout、LangGraph 指标 | Java 控制面保持可用，Agent 请求明确降级或失败，不绕过执行门禁 | 恢复 Runtime/Provider，验证 diagnostics、metrics、只读 Agent plan |
| Kafka broker 不可用 | 在隔离集群停止 broker 或阻断测试 producer | producer error、consumer lag、outbox backlog、dead letter | 业务事实先持久化；outbox 保留待发送记录；消费者不静默丢消息 | 恢复 broker，验证 topic、consumer group、积压回落和幂等消费 |
| MySQL 主库不可用 | 停止临时数据库或撤销测试连接 | 连接池耗尽、SQL error、服务 health、事务失败 | 写入快速失败且不产生半事务；只读能力按拓扑降级 | 恢复/切换数据库，验证 migration、核心表、事务与只读 smoke |
| Redis 不可用 | 停止测试 Redis 或阻断应用访问 | cache error、session miss、限流/幂等指标 | 不把缓存当事实主存储；可降级路径受控，安全策略不得 fail-open | 恢复 Redis，验证 session、缓存重建、TTL 和 key namespace |
| MinIO 不可用 | 停止测试 MinIO 或阻断 bucket | artifact 错误、上传/下载延迟、对象告警 | 报告/制品操作明确失败或排队，不把不完整对象标记成功 | 恢复服务，验证 bucket、对象校验和待处理任务 |
| Chroma 不可用 | 停止向量存储或制造查询超时 | memory retrieval error/latency、fallback 指标 | 长期记忆检索降级，不跨 namespace 检索，不阻断必要控制面 | 恢复后验证 collection、namespace filter、检索与重建策略 |
| Neo4j 不可用 | 停止图数据库或制造查询超时 | GraphRAG/血缘查询错误、查询 P95 | 图增强能力降级，业务事实仍以 MySQL 为准 | 恢复后验证约束、索引、图查询与重建来源 |
| Nacos 不可用 | 停止测试 Nacos 或阻断注册发现 | 注册/配置拉取错误、服务实例列表 | 已运行实例使用受控本地配置继续服务，不接受未验证配置 | 恢复后验证配置版本、实例注册与配置一致性 |

## 5. 执行流程

1. 运行静态就绪门禁并生成不含敏感信息的演练计划。
2. 由演练负责人选择一个场景，填写批准范围、RTO/RPO、blast radius、停止条件和恢复负责人。
3. 记录故障前基线，并确认备份、告警、日志、指标和回滚通道可用。
4. 在隔离环境手工执行批准的注入动作，完整记录时间线。
5. 验证告警是否触发、系统是否按预期降级、数据与消息是否满足一致性边界。
6. 达到观察窗口或停止条件后立即恢复。
7. 按组件恢复步骤执行只读 smoke，再逐步恢复异步与写入开关。
8. 输出复盘，行动项必须有 owner、优先级、截止时间和复验方式。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\failure-drill-check.ps1
```

生成演练计划：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\failure-drill-check.ps1 -WriteDrillPlan
```

在专用演练 runner 中检查工具链：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\failure-drill-check.ps1 -CheckLocalTools
```

## 6. 演练报告模板

- 基本信息：场景、环境、Git commit、镜像 tag、负责人、审批单、开始/结束时间。
- 范围：服务、实例、namespace、测试数据、blast radius、明确排除项。
- 目标：预期信号、预期降级、RTO、RPO、停止条件。
- 时间线：注入、首次指标异常、首次告警、人工响应、恢复开始、服务恢复、积压清零。
- 结果：实际影响、数据一致性、消息一致性、告警有效性、RTO/RPO 是否达标。
- 证据：脱敏日志、指标图、告警通知、只读 smoke 结果、恢复验证结果。
- 行动项：问题、风险等级、owner、截止时间、修复提交和复验计划。

## 7. 验收边界

静态门禁通过仅代表仓库具备演练方法、场景、观测与恢复前置条件，不代表真实故障演练已经完成。正式上线前至少应在接近生产的隔离环境完成身份入口、MySQL、Kafka 和 Python Runtime 四类 P0 场景；其他组件按客户实际启用范围执行。

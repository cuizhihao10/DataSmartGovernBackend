# DataSmart Govern 最终全平台闭环审计

## 1. 审计结论

截至 2026-07-02，DataSmart Govern 已达到“工程发布候选（Engineering Release Candidate）”状态：

- Java 微服务、Python AI Runtime、OIDC/Keycloak、Gateway、Kafka 异步控制面、LangGraph、多智能体、长期记忆、模型网关、可观测性、Compose 与 Helm 均已有真实源码和测试证据。
- Python Runtime 全量测试为 `597 passed`。
- Maven JDK 21 reactor 全量测试为 `868 tests, 0 failures, 0 errors, 0 skipped`。
- 生产静态就绪门禁为 `PASS=33, WARN=0, FAIL=0`。
- 完成四批有界职责拆分后，最终闭环证据门禁为 `PASS=93, WARN=0, FAIL=0`；
  Java/Python 生产源码与测试文件已全部满足单文件不超过 500 物理行的工程约束。

这里的“闭环”表示既定产品范围已具备代码、合同、测试、部署和运维制品，不表示已经替客户完成生产上线。真实 Secret 注入、企业 IdP 联调、标准 SBOM、镜像签名、Kubernetes 集群部署、容量压测、备份恢复和故障注入仍必须在客户或预生产环境执行。

## 2. 产品与业务模块

| 能力域 | 当前结论 | 核心证据 | 边界 |
|---|---|---|---|
| Gateway | 已闭合控制面 | OIDC/JWT、路由、授权缓存、内部端点保护、签名、WebSocket 保护、限流测试 | 客户 TLS、WAF、Ingress 与企业 IdP 需要环境联调 |
| permission-admin | 已闭合核心权限面 | RBAC、项目成员、Agent 工具预算、Skill 准入、审批事实、审计能力 | 客户组织目录、套餐和外部审批系统需要适配 |
| task-management | 已闭合任务控制面 | 任务生命周期、队列、异步命令、worker/outbox、回执、恢复与管理接口 | 高风险 worker 默认关闭，生产启用前需容量与故障演练 |
| datasource-management | 已闭合核心产品面 | 数据源、连接诊断、元数据、连接器能力、权限、同步模板与管理入口 | 新连接器应通过 SPI/能力矩阵扩展，不继续侵入主服务 |
| data-sync | 已闭合同步控制面 | 任务、执行、租约、worker loop、回调、outbox、恢复、事故与告警 | 真实 CDC/大规模写入依赖客户源端、目标端和容量验证 |
| data-quality | 已闭合质量治理面 | 规则、执行、异常、报告、导出、整改任务、worker receipt 与治理概览 | 真实客户规则准确率和写入整改需数据集验收 |
| observability | 已闭合平台观测面 | 指标、告警、通知模板、Grafana、Prometheus、运行手册 | 生产日志/Trace 后端和告警接收人由客户环境配置 |
| platform-common | 已闭合共享契约 | 统一响应、异常、租户/操作者上下文等共享基础 | 保持轻量，禁止继续演化成业务逻辑集中地 |

## 3. AI Agent 能力审计

用户要求的完整 Agent 能力域已经进入统一能力矩阵，但成熟度并不全部相同：

| Agent 能力域 | 当前实现 | 审计结论 |
|---|---|---|
| tools | 文件读写、网页搜索治理、工具规划、参数校验、checkpoint、受控命令 runner | 控制面与本地受控执行已闭合；容器级沙箱和真实搜索 Provider 属生产增强 |
| skills | Registry、准入、发布生命周期、Manifest、可见性缓存与诊断 | 核心控制面已闭合；灰度发布和客户审批流属于环境集成 |
| memory | 短期/长期记忆、低敏用户画像、SQLite FTS、Chroma、m-create/m-retrieve、物化 worker | 核心读写检索链路已闭合；用户画像已具备候选/激活/上下文注入基座，生产 HA、画像持久化与真实向量规模待验收 |
| query engine | API、stream 事件、cache、error、retry、rate-limit、token-limit | 单实例控制面已闭合；分布式限流与真实 tokenizer/serving 指标待环境接入 |
| context | system/tool/model context、micro-compact、敏感裁剪与预算 | 已闭合 |
| permission | read/write/exec/network 治理、dangerous-path、safe-cmd、HITL 与 fail-closed | 控制面已闭合；客户策略、组织和服务账号需要 IdP 联调 |
| sub-agent | roster、A2A、handoff、LangGraph 协作图、执行前工作项 | 多 Agent 控制面已闭合；不宣称每个 Agent 都是独立常驻进程 |
| sessions | session/run/event、replay、WebSocket、checkpoint 与调度 | 核心闭合；多实例生产状态依赖 Redis/Kafka/Java 持久化配置 |
| command | proposal、payloadReference、outbox、lease/fencing、receipt、sandbox admission | 受控闭合；真实副作用默认关闭，容器级沙箱是上线增强项 |
| hook | runtime event、before/after 语义、指标、告警与审计投影 | 事件化 Hook 已闭合；暂不开放任意第三方 Hook 插件 |
| tech stack / LLM | Provider-neutral 路由、OpenAI-compatible、健康、fallback、能力矩阵、推理优化诊断 | 控制面已闭合；具体 DeepSeek/Qwen/GLM SKU 和 vLLM/SGLang 性能需真实 Provider 验证 |

### 3.1 LangGraph

LangGraph 已参与四类真实图能力，而不是只存在于依赖文件：

- Agent planning workflow：目标接收、治理门禁、既有编排器交接与结果收敛。
- Multi-agent collaboration/execution plan：角色分配、依赖边、权限守门、记忆支持、运维观察和 handoff。
- Execution gate workflow：根据 readiness、审批、澄清、容量和恢复事实执行条件路由。
- Memory retrieval workflow：加载检索上下文、评估 scope、汇总结果、绑定 `MEMORY_AGENT` 上下文并输出低基数指标。

LangGraph 不直接写业务数据库、不执行工具、不派发 worker，也不替代 Java 控制面。这个边界是生产安全设计，不是能力缺失。

### 3.2 多智能体交付范围

- 必做且已实现控制面：`MASTER_ORCHESTRATOR`、`DATASOURCE_AGENT`、`DATA_QUALITY_AGENT`、`PERMISSION_AGENT`、`TASK_AGENT`。
- 应做且已按受控范围实现：`MEMORY_AGENT`、`OPS_AGENT`、`DATA_SYNC_AGENT`。
- 暂缓或轻量化：`ETL_DEVELOPMENT_AGENT`、`DATA_ASSET_AGENT`、`COMPLIANCE_MASKING_AGENT`、`REFLECTION_OPTIMIZATION_AGENT`。

轻量角色保留产品目录、职责和扩展路线，但不得在交付材料中宣称为完整独立业务 Agent。

## 4. 验证证据

本轮最终审计执行：

```powershell
python -m pytest python-ai-runtime\tests -q
.\mvnw.cmd -q test
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\production-readiness-check.ps1
```

Maven 测试日志中的模拟异常、fail-closed、重试和死信日志属于测试预期；最终判断以进程退出码和 Surefire XML 的 failures/errors 为准。

最终审计脚本：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\final-platform-closure-audit.ps1
```

需要复跑测试并生成证据时：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\final-platform-closure-audit.ps1 `
  -RunPythonTests `
  -RunMavenTests `
  -WriteEvidence
```

## 5. 受控关闭能力

以下能力已有代码和合同，但生产默认关闭：

- Java/Python worker loop 与 outbox dispatcher。
- DataSync/DataQuality 真实写入执行器。
- Agent 真实工具提交、命令执行和 artifact 正文读取。
- 自动终态回调、自动补偿和高风险恢复动作。

启用前必须同时具备权限、审批、审计、租约、幂等、死信、回滚、容量、告警和值班流程。禁止为了演示“完整”而在默认配置中打开这些开关。

## 6. 冻结前 P1 完成情况

当前没有阻断工程发布候选的 P0 代码失败，冻结前识别出的文件规模与测试工具链 P1
已经全部完成：

- 首批 7 个原审计目标已经完成职责拆分：
  `AgentCommandTaskFinalStateCallbackDispatchService.java`、`AgentToolSandboxPolicyService.java`、
  `agent_orchestrator.py`、`services/__init__.py` 和 3 个测试文件均已降至 500 行以内。
- Maven Surefire 已显式使用 Mockito Core JAR 作为 `-javaagent`，不再依赖未来 JDK
  将禁止的动态 attach；多模块测试仍为 `868/0/0/0`。
- 审计脚本已改用 `ReadAllLines` 统计物理行，并将源码发现锚定到仓库根目录。旧算法
  `Get-Content.Count` 会对部分历史编码文件少计行数，且 `-Include` 空集合曾被误判为
  “零超限”；因此旧文档中的“只剩 4 个生产文件、3 个测试文件”结论已废止。
- 第二批已按职责成组拆分 5 个超限文件：
  - Gateway 授权配置将 data-quality 与内部服务默认目录提取到
    `GatewayAuthorizationDefaultCatalog`，配置属性类从 633 行降至 495 行；
  - Gateway 授权过滤器提取不可变授权语义 record，测试提取可复用记录型 chain，
    过滤器与测试分别降至 500 行和 478 行；
  - 模型能力注册表将诊断算法与默认模型画像数据分离，从 547 行降至 389 行；
  - Agent Runtime 配置将工具输入字段 schema 提取为独立值对象，并用兼容子类保留旧引用，
    从 545 行降至 497 行。
- 第三批已按纯计算与低敏值对象边界拆分 7 个 Agent Runtime 生产文件：
  - 工具执行审计提取只读视图映射器；
  - Skill Manifest 提取指纹与过滤值支持，Skill Lifecycle 提取草稿校验器；
  - 质量整改提交提取低敏 JSON 值转换和错误摘要支持；
  - 命令提案提取证据 record 与摘要支持，命令安全预检提取低敏信号 record；
  - Skill 可见性投影提取索引大小探测 record；
  - 状态推进、事务 outbox、持久化和网络提交仍留在原服务中。
- 第四批完成最后 7 个生产源码和 3 个测试文件的职责拆分：
  - Memory lease 将重试决策、SQL 行映射与时间解析从租约存储中分离，持久化和租约状态机保持不变；
  - Session scheduler 与 command worker lease 将展示摘要和推荐动作提取为纯展示模块，
    调度、租约和 fencing 语义保持不变；
  - Workspace payload 将操作类型、路径/内容校验结果、摘要和值规范化提取为包内值对象与支持类，
    原服务继续拥有工作区隔离和物化流程；
  - permission-admin 项目成员复制、task-management 异步工具预检值处理提取为无副作用支持类；
  - 三个超限测试按澄清事实、Agent 工具夹具和 checkpoint 图运行夹具拆分，覆盖场景没有删除。
- 审计脚本现已确认生产源码与测试源码两个规模门禁均为 `PASS`，准确剩余超限数量为零。
- 最终证据模式为 `PASS=93, WARN=0, FAIL=0`，Python 为 `597 passed`，
  Maven 为 `868 tests, 0 failures, 0 errors, 0 skipped`。

本轮代码范围至此冻结。后续只允许 P0/P1 缺陷、安全与兼容性修复，或客户环境接入；
不得以继续“完善”为由新增产品分支、修改公开 API 或重新设计已稳定状态机。

## 7. 最终冻结规则

1. 只接受 P0/P1 缺陷修复、安全修复、兼容性修复和客户环境接入。
2. 新 Agent、新连接器、新模型 SKU 和新业务域进入下一版本 backlog，不进入本轮闭环。
3. 所有真实副作用能力继续默认关闭，直到环境级验收通过。
4. 生产上线结论必须附带客户环境证据，不能只引用本仓库静态门禁。

## 8. 2026-08-10 后续复核说明

本文件前文的角色名册、源码行数和测试计数是 2026-07 冻结批次的历史证据，不应再被当作当前工作区统计。后续双仓迁移复核以当前六 Specialist roster（`KNOWLEDGE_AGENT`、`DATASOURCE_AGENT`、`DATA_SYNC_AGENT`、`PRECHECK_AGENT`、`RECOVERY_AGENT`、`MONITOR_AGENT`）及其 Java bridge 边界为准；长期八 Agent 描述仍是产品路线，不是当前验收名册。

2026-08-10 当前复验结果为 Python Runtime `1099 passed`（一条弃用警告）、JDK 21 Maven Reactor `1323 tests / 0 failures / 0 errors / 9 skipped`，Frontend 6 个合同脚本、lint 和 build 全部通过。真实 Success `six-agent-success-type-normalized-20260810112629` 与只读 Recovery `six-agent-recovery-rag-durable-20260810214832` 均通过脚本门禁；Recovery 的独立数据库审计确认没有审批、提交、异步命令或恢复副作用，因此该历史结果不包含真实 Kafka/Python Autopilot `FAILED -> Recovery -> retry -> SUCCEEDED -> RECOVERED` 写重跑。该本地黑盒结论不改变第 7 节第 4 条：生产上线仍必须补客户环境、Secret 轮换、备份恢复、容量、故障演练和供应链证据。

## 9. 2026-08-12 当前工作树勘误

本节覆盖前文的历史冻结口径，不删除其历史证据。当前工作树新增了 Autopilot 恢复控制面 V20-V25、Kafka 触发/消费、Python Recovery 规划和受限 Java/data-sync 执行分支；V25 只保存模型 `SEARCH`/`SKIP`、策略、证据计数和 evidence-ID digest，不保存 RAG 正文或模型推理。当前普通规划也已将 RAG 改为模型可见、模型按需调用的工具，不能由规则回填成强制计划。

但本审计不能宣布“全流程无人值守恢复已完成”：当前 Python `AutopilotRecoveryCoordinator` 没有在恢复写动作完成后调用 `PRECHECK_AGENT` 或 `MONITOR_AGENT`。2026-08-10 的历史 Recovery E2E 已验证只读 preview 后的后置复核，却没有产生 Autopilot 写副作用。主线必须先补 durable post-action finalization，再以 V20-V25 Flyway、Kafka、Python Provider、worker receipt、PRECHECK/MONITOR fact、指标告警和高风险审批停点的真实 E2E 重做结论。

## 10. 2026-08-13 当前复核勘误

上述第 9 节记录的是 2026-08-12 的审计快照。本轮已经补齐 Recovery 写动作后的 durable post-action finalization，并以 checkpoint、turn ID 和 Java durable fact sink 保证 Specialist 事实登记、终态重放与失败传播的幂等边界。普通规划和 Recovery 规划都由模型自主选择 RAG `SEARCH`/`SKIP`；Python 不直接写 data-sync，恢复执行继续由 Java/data-sync 双策略和授权盒子控制。

2026-08-13 验证结果：Python 全量 `1162 passed, 1 skipped`，Recovery 聚焦 `24 passed`；JDK 21 的 `agent-runtime`/`data-sync` 编译成功；Frontend lint、build、API/WebSocket 与 Agent 控制面合同测试通过；最新 `data-sync`、`agent-runtime`、`python-ai-runtime` 容器 healthy，Kafka Autopilot 主 topic、retry-1000、retry-2000 和 DLT consumer 已启动。durable fact 缺失的 HTTP 503、Kafka 不 ACK、有界重试/DLT、terminal checkpoint 重放和 post-recovery PRECHECK/MONITOR 投影均有回归覆盖。

仍不能把本轮证据扩大为“客户生产环境真实恢复已验收”：宿主机没有 `DATASMART_KEYCLOAK_LOCAL_USER_PASSWORD`，真实 project-owner 授权后的 `-Execute -ConfirmAndExecute -EnableAutopilot` 写动作未运行。真实环境仍需验证 V20-V25 Flyway、Kafka 消费/重投递、Provider/RAG、worker receipt、低风险自动 retry/quarantine、高风险审批停点、重复投递/过期授权/Provider 失败、补偿、指标告警和备份恢复。默认高风险与真实副作用开关继续保持受控关闭。

## 11. 2026-08-13 真实 Success 复核补充

随后提供的本机 `project-owner` 密码已验证有效。使用项目 `101` 下真实数据源 ID `55`/`56`，本机真实请求 `local-six-agent-20260813053638265` 完成了首次确认、LOW 风险 Autopilot 授权、Java 生命周期控制面和 data-sync worker 执行：task `97`、execution `2619`，读取 20、写入 20、失败 0，单对象成功；后置 `PRECHECK_AGENT`/`MONITOR_AGENT` 为 `EXECUTED`，4 条 durable Specialist facts 已落库。

一次 `INSERT + FULL` 运行被 `METADATA_TARGET_NOT_EMPTY_FOR_INSERT_FULL` 正确阻断，随后由用户明确改为 `UPDATE/merge` 才完成 Success。这是实际的 fail-closed 预检查证据。由于该 execution 首次即成功，当前证据关闭的是“首次授权后的正常执行盒子”，不是“失败后 Kafka Recovery 自动重跑”门禁；后者仍需隔离失败 execution、真实 Recovery trigger、模型检索、retry/quarantine receipt、worker 最终回执和 `RECOVERED`/`ATTENTION_REQUIRED` 收敛证据。

## 12. 2026-08-13 Recovery 检索与 transient retry 最终复核

第 10 节中“Recovery 仍强制检索”是旧口径。当前源码和回归证明 Recovery 强制的是模型对 `SEARCH`/`SKIP` 作出显式、可持久化的受治理决策，而不是每次机械调用 RAG。`SEARCH` 最多执行一次并要求 durable evidence 后重评；`SKIP` 只表示现有结构化诊断足够，不会跳过 Java/data-sync 双策略、授权盒、风险、循环、幂等或账本事实校验。普通规划同样由模型在已开放的检索工具中自主决定是否调用。

本轮新增 transport-only retry 分类：Spring `ResourceAccessException` 被收敛为专用 `DatasourceRunOnceTransportUnavailableException` 或 range-probe 专用 transport exception，data-sync 只对这类 transport 故障写入 `retryable=true`；HTTP rejection、无效 envelope、权限、凭证、契约和无效范围错误保持不可重试。自动 retry 还要求 Python 的低敏事实投影、Agent Runtime 事实校验和 data-sync 对 execution/object/error ledger 的独立复核同时成立。Java range-probe/run-once/Recovery 聚焦合同回归 `21 tests` 全部通过；Python Recovery/Specialist/checkpointer 聚焦回归 `37 passed`。

真实故障尝试 `six-agent-autopilot-transient-20260813230035361` 的 task `106` / execution `2714` 在 run-once 前因 `AUTO_SPLIT_PK` 范围探测不可用而被 `PARTITION_SHARD_CONTRACT_BLOCKED` 阻断。精确状态是 `outbox_state=DELIVERED`、`consumer_result_status=ATTENTION_REQUIRED`，检索投影为 `SEARCH` / `EXACT_SEARCH`，evidence count 为 2；没有 recovery case 或 retry receipt。该结果证明系统不会把前置契约失败误判为可自动重试，但没有证明无人值守恢复成功。因此目前不能宣称真实黑盒 `FAILED -> Kafka -> Recovery -> retry -> SUCCEEDED -> RECOVERED` 已闭合；提交和发布说明必须继续列为当前主 Agent 待验证的剩余 E2E 门禁。

## 13. 2026-08-14 range-probe 失败工作单元审计结论

源码审计确认，`AUTO_SPLIT_PK` 的 transport failure 发生在真实 shard rows 初始化之前时，data-sync 会幂等写入一条 `PARTITION_RANGE_PROBE`/`FAILED` 临时工作单元，并把 transport error 作为可重试事实纳入既有失败对象重试入口。该入口重排 execution 后，成功的 range-probe 会在事务内删除临时单元并幂等生成真实分片/自适应对象行；因此临时单元不会伪装成真实分片，也不会在成功重跑时造成重复账本。

本结论由 `21 tests / 0 failures / 0 errors` 聚焦回归支持，且继续区分 transport failure 与 HTTP/业务/契约/无效范围拒绝。它是源码和模块回归证据，不是运行环境证据。真实 Kafka/Python `FAILED -> Recovery -> retry -> SUCCEEDED -> RECOVERED` 黑盒 E2E 仍未由当前主 Agent 验证，任何提交、发布或产品说明都不得把它写成已成功。

## 14. 2026-08-14 Recovery AgentPlan 幂等与环境阻塞审计

task `107` 的运行证据补充了两个必须分开处理的问题。首先，首次 transport 故障后的 range-probe 重排、Kafka outbox 和 Recovery 消费均已发生；随后写入失败是因为任务定义缺少 `customer_name -> name` 映射，而目标表 `name` 为 `NOT NULL`，不是 transport retry 本身失效。其次，Kafka 重投会重新调用模型，同一业务策略可能生成不同 actionId、摘要、置信度或证据说明；旧实现把这些瞬态字段带入 AgentPlan，同时复用固定 diagnosis/preview 幂等键，触发 Java 正确的请求指纹冲突保护。

当前实现通过“语义稳定化 + 分阶段版本化身份”修复该问题：同一 event/stage/strategy 的 ToolPlan 请求保持字节级语义一致，真实策略参数变化或新 recovery cycle 获得新身份；诊断阶段不再被预览参数污染，最终键通过 `investigation:v3` 固定阶段前缀和 SHA-256 摘要满足 Java 128 字符合同。Java `AgentPlanIngestionIdempotencySupport` 的冲突拒绝保持不变，Python 仍不能直接写 data-sync。聚焦测试覆盖同策略重放、策略变化、新 cycle、非法样本选择器以及 bridge 接入，investigation、coordinator、runtime adapter、bridge 四个测试文件合计 `73 passed`。

最新全量审计结果：Java Reactor `1515 tests / 0 failures / 0 errors / 9 skipped`，Python `1171 passed, 1 skipped`；Frontend 六项合同脚本、lint、TypeScript 与 Vite build 全通过；三个最新运行时镜像健康，Kafka 四组 consumer 已就绪。新增和本轮修改的重要方法注释已统一为中文，文档增量也使用中文记录。

**历史结论（已由第 15 节更新）：** 当时 Provider 返回 HTTP `401`，三次真实规划在任务创建前均 fail-closed。它仍是外部鉴权失败不会产生数据副作用的证据，但不再是当前黑盒结论。

## 15. 2026-08-14 Provider 恢复与真实自治恢复审计

新的 OpenAI-compatible Provider 端点 `https://qa.dashun9527.com/v1` 已在容器内通过 `/models` 和 `/responses` HTTP `200` 验证，密钥仍只在运行时注入。task `108` 的第一轮真实演练发现 preview 幂等键超过 Java 128 字符限制；该事件最终进入 DLT 且没有 recovery case，证明 Bean Validation 与 Kafka 有界停止均按设计工作。Python 随后使用 `investigation:v3` 有界摘要键修复，生产长度 eventId 回归由失败转为通过，未修改 Java 限制或冲突保护。

task `109` / execution `2775` 提供了最终黑盒证据：首轮 `PARTITION_RANGE_PROBE` transport failure、outbox `DELIVERED`、Recovery `SKIP / STRUCTURED_DIAGNOSTIC / evidence=0`、Java diagnosis/preview 成功、consumer `RECOVERY_STARTED`、data-sync 失败对象重排、case cycle `1/3` 为 `RECOVERED`、execution `SUCCEEDED`。最终 worker 统计为读 `20`、写 `20`、失败 `0`，对象账本成功，源目标四项聚合完全一致。全过程没有人工调用恢复写接口。

Gateway 公开查询确认 execution、对象、运行日志、recovery 快照和 8 条 Specialist durable facts；后置 `PRECHECK_AGENT` 与 `MONITOR_AGENT` 在恢复后再次 `COMPLETED`。E2E 脚本最后一次 execution GET 曾因 permission-admin 瞬时不可用退出，但服务恢复后相同身份和项目范围的公开查询全部通过。脚本现已为只读 GET 增加最多 3 次瞬态重试，仅识别 502/503/504 和带固定低敏故障标记的权限中心不可用 403；POST 与普通权限拒绝不会重放。对应离线合同、AST 和退出码回归通过，审计结论更新为无人值守恢复主链路与验收脚本韧性均已收敛。

## 16. 2026-08-14 自治修复目录与证据可追溯性审计

本轮针对“Loop 是否只能重试”完成增量审计。当前 Recovery 诊断已经统一收集结构化错误与对象统计、当前策略和最近成功策略差异、连接器运行时版本及来源、当前 channel/batch/timeout 限制，以及可选的 runbook/历史事故证据。模型自主决定 `SEARCH` 或 `SKIP`，规则只负责开放工具和限制最多一次受控检索；因此当前路线属于 Graph 编排、Loop 有界恢复和 Harness 工具治理的组合，不是固定脚本重试器。

证据对象强制携带来源类型、稳定来源引用、检索时间、`0..1` 置信度和置信度依据。Python 生成后，Java `AgentAutopilotRecoveryEvidenceVerifier` 再做结构与时间校验。没有来源、时间或可信度解释的检索片段不能进入自动动作门禁。诊断同时显式区分“连接器未声明硬容量”和“已知限制”，避免模型把当前配置值误当成平台容量。

平台自动动作上限现包括 `ROLLBACK_EXECUTION_POLICY`、`TUNE_EXECUTION_POLICY`、`REFRESH_METADATA`、`RESUME_FROM_CHECKPOINT`、`REPLAY_FAILED_SHARDS` 和 `REPAIR_FIELD_MAPPING`，同时保留已有失败对象重试和精确 quarantine。每个动作都需要当前授权快照、case/cycle/截止时间、任务与 execution 作用域、动作指纹、幂等回执、双策略和持久事实共同通过。临时策略 override 在 Recovery 成功后自动停用，但不删除审计。

字段映射自动修复严格限制为元数据可唯一证明的低风险变更：允许大小写归一化；允许在目标未占用、类型兼容、主键属性一致、双方有序号时序号一致且候选唯一时修复列重命名；只有目标列可空、有默认值、自动生成且非主键时，才能移除已经失效的映射。必填目标列缺少映射会在预检阶段阻断；系统不生成业务默认值、不执行 DDL、不禁用非空/主键/外键约束。凭据、权限、DDL、外键、覆盖或删除数据、扩大同步范围继续退出 Loop，并要求操作员接收根因、证据、权限、步骤、影响、回滚与验证说明。

审计门禁结果：Java Reactor `1544/0/0/9`，Python `1174 passed, 1 skipped`，Frontend 合同、lint、TypeScript 和 Vite build 全通过。四个最新运行时镜像健康；严格认证 smoke 为 `PASS=89, WARN=0, FAIL=0`。全量 Java 回归发现并修复了非 Recovery `CUSTOM_SQL` 被误路由到恢复重载的兼容性回归，证明本轮没有只依赖新增测试。task `109` 继续作为 transport 自动重试的真实黑盒证据；新增的六类修复动作目前是源码、合同和回归级证据，后续仍应逐动作补真实故障注入，不能提前宣称所有数据库约束错误都已完成无人值守黑盒验收。

## 17. 2026-08-15 字段映射修复与 Gateway 错误边界审计

task `117`、`118`、`119` 形成了先发现能力缺口、再暴露持久化合同错误、最后完成真实闭环的连续证据。当前字段映射修复器只在元数据能够唯一证明时处理列重命名；候选歧义、类型不兼容、主键属性不同或序号冲突都会停止。task `118` 暴露的 `WHERE id` 与真实主键列 `task_id` 不一致问题由先失败的 SQL 片段回归锁定，修复后 data-sync 聚焦组 `58 tests` 通过；Java 幂等、策略和 Kafka 有界停止边界均未放宽。

task `119` / execution `2860` 的首轮 20 条字段写入失败先经历 cycle 1 的 `REFRESH_METADATA` 预检失败，再由 cycle 2 的 `REPAIR_FIELD_MAPPING` 自动把 `customer_name -> customer_name` 修复为 `customer_name -> name` 并重排执行。两个 outbox 均 `DELIVERED`，模型均选择 `SKIP / STRUCTURED_DIAGNOSTIC`；case `14` 最终 `RECOVERED`，execution 与对象账本均 `SUCCEEDED`，读写统计为 `20/20/0`，目标实际 20 行。该证据首次把字段映射动作从源码/回归级提升为真实黑盒验收级，不能外推为其他五类修复动作也已完成真实故障注入。

Gateway 另有一个错误边界缺陷：授权成功后的下游连接异常被同一个 `onErrorResume` 捕获并改写为权限中心故障。修复后只物化 permission-admin 调用本身，授权异常继续 fail-closed，下游异常不再被伪装；聚焦回归 `33 tests` 通过。task `119` 演练中容器重建后的短暂 Nacos 旧地址正好提供了真实故障证据，服务注册稳定后公开 API 对 execution、对象、Recovery 和 durable facts 的查询全部通过。

动作后 PRECHECK/MONITOR 是重排回执后的非阻塞观察，不是长任务终态裁决。终态以 data-sync 的 case、repair receipt、execution、对象账本和业务目标对账为准。将来如需 Specialist 单独产出终态摘要，应由 worker 终态事件触发新的 finalization；不应让 Kafka 恢复消费者同步等待业务任务完成。

最终回归审计为 JDK 21 Reactor `1563/0/0/9`、Python `1178 passed / 1 skipped`、Frontend 六项合同加 lint/build 全部通过；六 Agent 离线退出码合同通过，严格只读 smoke 为 `PASS=89 / WARN=0 / FAIL=0`。上述结果与 task `119` 的真实运行证据共同关闭字段映射动作门禁，但策略回滚、限界调参、checkpoint 恢复和失败分片重放仍需各自的真实故障注入，生产发布规则继续服从第 7 节。

## 18. 2026-08-15 统一生命周期运维投影审计

平台已新增 execution 级统一生命周期图，把用户目标、Agent 受治理调用、初始命令投递、Java 工具审计、根 worker、Recovery Kafka、Recovery、当前 worker 重放和最终验证串联。公开入口为 `GET /api/sync/sync-tasks/{taskId}/executions/{executionId}/lifecycle-graph`，权限语义固定为只读 `SYNC_EXECUTION/VIEW`。图查询复用任务数据范围并再次校验 execution 归属，不提供 mutation 参数，也不会触发 Kafka、worker、审批或恢复动作。

审计结论是“统一投影已实现”，不是“新状态机已实现”。V26/V27 关联表只解决原有 `entryMode/session/run/audit/command` 与 `syncExecutionId` 无法持久反查的问题；各域状态仍由原表和原服务负责。异步 command outbox 是 `COMMAND_DISPATCH`，其 `PUBLISHED` 不代表消费者成功；只有 data-sync Recovery trigger outbox/consumer 能形成 `KAFKA_EVENT`。六 Specialist 成功主流程使用直接 `sync.task.run` 时没有初始 command，图以 `DIRECT_AGENT_TOOL` 明确说明而不是伪造 Kafka 事实。

Agent Runtime 的命令观察接口按 session/run/command 精确授权和查询，只返回状态、尝试次数与时间；不再分页读取整个 Run。data-sync 内部 Agent 写入口使用来源白名单、共享服务令牌和 Agent Runtime 权威审计三重约束，并核对租户、项目、session、run、audit 与工具名。浏览器不能伪造 Agent audit Header，data-sync 宿主机端口也只绑定回环地址。这些约束保护的是执行入口，不会赋予 data-sync 审批或修改 Agent 审计的权限。

证据合同统一包含来源、时间、可信度等级和低敏引用。`AUTHORITATIVE` 表示数据库持久事实或成功读取的控制面审计，`UNAVAILABLE` 表示当前无法验证来源；接口不会把模型 confidence 当作状态事实可信度。老数据没有关联时明确返回 `NOT_LINKED`，跨服务读取失败或找不到精确 command/audit 时返回 `PARTIAL`。这使运维人员能够区分业务失败、链路尚未推进和观察来源不可用，避免过去需要在多张图和多个状态机之间人工猜测。

当前统一图的 Agent 节点是总览级受治理提交节点，详细的六 Specialist turn、RAG 决策和 post-bridge finalization 仍下钻到既有专用图和 durable fact 页面。这样既形成端到端总览，又不复制 Specialist 状态解释规则。后续若需要在同一画布展开六角色，应复用专用投影的稳定 ID 做下钻，不应在 data-sync 中重建 Specialist 状态机。

## 19. 2026-08-15 运行态审计结论

运行态已经证明以下事实：V26 在真实 PostgreSQL schema 中成功应用；四个受影响镜像使用现有 local-e2e overlay 重建后全部 healthy；task `8` / execution `2882` 的只读查询准确区分历史未关联、worker 成功和最终验证成功。内部 Agent Runtime HTTP 合同测试还使用包含额外敏感字段的响应夹具，断言客户端只保留状态、风险、审批、时间、稳定错误码和低敏引用，不会透传 payload、参数、endpoint 或正文。V27、直接入口关联、对象级命令观察与根/当前 execution 顺序属于本次后续增量，最终运行态结果以本节之后的复验记录为准。

权限审计未发现新写入口。`data-sync` 只被加入 Agent Runtime 的只读来源允许表，没有加入自动执行服务白名单；共享内部令牌只从环境注入，日志只记录异常类型。前端只展示服务端已经给出的节点、来源、时间、可信度与引用，不解析日志文本，也不自行推断跨服务状态。查询来源失败会降级为 `PARTIAL`，历史无关联降级为 `NOT_LINKED`，两者都不会反向影响同步执行。

当前运行证据仍有一项未关闭：连续两次真实 Success 规划都因外部模型路由失败停在 `DATA_SYNC_SPECIALIST_MODEL_FAILED`，没有创建可用于 V26 的新 execution。系统正确 fail-closed，未自动确认，也未产生错误的关联、Kafka 投递或 worker 副作用。故本轮可以认定“统一投影实现、迁移、历史兼容和低敏边界已通过”，不能认定“新 Agent execution 的 `COMPLETE` 投影已完成黑盒验收”。Provider 恢复后的补证步骤见本地 E2E runbook 8.11。

## 20. 2026-08-15 安全与证据最终收敛

真实数据库已确认 V26、V27 均成功，直接工具入口允许 `command_id` 为空，但 `entry_mode` 始终必填。内部执行入口在来源白名单和服务令牌之外，还必须回查 Agent Runtime 的权威工具审计；缺少令牌以及只伪造来源的请求均返回 HTTP `403`。Gateway 会剥离外部伪造的 Agent audit Header，data-sync 端口仅绑定宿主机回环地址，因此前端不能绕过 Gateway 或 Agent 控制面建立关联。

Recovery Kafka 与 Recovery case 已拆分为两类证据。case 只证明恢复控制面已经形成持久案件，不证明 trigger outbox 已投递或 consumer 已处理；当没有 outbox/consumer 事实时，统一图必须显示 `KAFKA_EVENT=NOT_RECORDED`，不填时间、不附 Kafka 证据。该规则由“仅有 Recovery case 不得编造 Kafka 事实”的最小回归覆盖。异步命令的 `COMMAND_DISPATCH` 同样不再被误标为 Kafka 消费。

最终门禁结果为 JDK 21 Reactor `1583/0/0/9`、Python `1178 passed / 1 skipped`、Frontend 全合同/类型/lint/build 通过、严格 smoke `PASS=89 / WARN=0 / FAIL=0`。六个核心应用服务 healthy，V27 字段约束和历史 task `8` / execution `2882` 的 8 节点、7 边、`NOT_LINKED` 兼容投影均已运行态复核。新 Agent execution 的 `COMPLETE` 黑盒样本仍受外部 Provider degraded 阻塞，审计结论继续保持为“实现与历史兼容已关闭，Provider 恢复后的新链路补证未关闭”。

## 21. 2026-08-15 Specialist 动态 Send 与子图组合审计

六个 Specialist 继续作为稳定能力目录存在，但实际执行路径已经不再由固定六角色边或 Python 线程池决定。`SpecialistAgentCoordinator` 仍先执行租户/项目/应用双主体绑定、checkpoint、依赖、工具白名单和最大并发判断；每个依赖已满足的实际波次随后交给 `LangGraphSpecialistFanoutExecutor`。父图根据本波次的真实角色集合生成 N 个 LangGraph `Send`，每个分支调用同一份 `prepare -> execute -> finalize` Specialist 私有状态子图，再由带 reducer 的父状态在 super-step barrier 后汇总。实现遵循 LangGraph 官方 [Graph API 的 Send 语义](https://docs.langchain.com/oss/python/langgraph/graph-api)和[子图组合边界](https://docs.langchain.com/oss/python/langgraph/use-subgraphs)。

本次没有把所有治理图机械改成动态边。审批、checkpoint、执行门禁和最终验证属于确定性控制流程，继续使用静态节点和条件边更利于审计；运行时角色数量、角色输入和并行波次才使用动态 Send。父图包装节点只向每个私有状态子图传递当前受控 DTO，并只把单元素结果写回 reducer，避免并发分支覆盖父状态。子图不配置 checkpointer，不会把函数 sink、模型上下文或专业输入持久化；原有 Java durable fact sink、异常转 `FAILED`、幂等和副作用边界保持不变。

公开响应的 `specialistAgentExecution.runtimeFanout` 现在记录 `engine`、`dispatchMode`、Send/子图计数、实际角色和稳定图结构。统一 Runtime Event 另记录一条更窄的低敏编排事实，只包含有界枚举和计数；Prompt、模型正文、工具参数、业务对象和未知字段由白名单丢弃。六 Agent E2E 脚本已经把 `langgraph + DYNAMIC_SEND_SUBGRAPH + Send 数等于子图收口数 + 三个父图节点` 纳入强制断言。聚焦回归为 `18 passed`，六 Agent/post-bridge/应用装配等更宽回归为 `35 passed`，离线 E2E 动态编排断言通过；最新 `python-ai-runtime` 镜像健康，并在容器内确认运行 LangGraph `1.2.11` 与 `Send:N` 图边。

最终全量门禁为 JDK 21 Reactor `1583 tests / 0 failures / 0 errors / 9 skipped`、Python `1182 passed / 1 skipped`；Frontend 六项合同、lint、TypeScript 与生产构建全部通过。当前运行态补证仍受 Provider 认证阻塞。重建后的容器直接使用当前配置地址和运行时凭据探测 `/models` 与最小 `/responses`，两者均返回 HTTP `401`；未输出或持久化凭据，也没有复用旧 RequestId。故本轮不能新增 `sourceStatus=COMPLETE` 样本，更不能把离线动态图回归冒充真实模型黑盒。Provider 凭据恢复后，必须用全新请求同时通过动态 fan-out E2E 断言、任务确认、Java 审计、worker 执行和 lifecycle graph `COMPLETE` 门禁。

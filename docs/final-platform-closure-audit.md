# DataSmart Govern 最终全平台闭环审计

## 1. 审计结论

截至 2026-07-02，DataSmart Govern 已达到“工程发布候选（Engineering Release Candidate）”状态：

- Java 微服务、Python AI Runtime、OIDC/Keycloak、Gateway、Kafka 异步控制面、LangGraph、多智能体、长期记忆、模型网关、可观测性、Compose 与 Helm 均已有真实源码和测试证据。
- Python Runtime 全量测试为 `597 passed`。
- Maven JDK 21 reactor 全量测试为 `868 tests, 0 failures, 0 errors, 0 skipped`。
- 生产静态就绪门禁为 `PASS=33, WARN=0, FAIL=0`。
- 修正物理行统计后，最终闭环证据门禁为 `PASS=91, WARN=2, FAIL=0`；两个 warning
  只表示仍有 18 个生产源码和 4 个测试文件超过 500 行，不表示功能或测试失败。

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
| memory | 短期/长期记忆、profile 语义、SQLite FTS、Chroma、m-create/m-retrieve、物化 worker | 核心读写检索链路已闭合；生产 HA 与真实向量规模待验收 |
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

## 6. 冻结前剩余 P1

当前没有阻断工程发布候选的 P0 代码失败。剩余 P1 应限制为小规模整改：

- 首批 7 个原审计目标已经完成职责拆分：
  `AgentCommandTaskFinalStateCallbackDispatchService.java`、`AgentToolSandboxPolicyService.java`、
  `agent_orchestrator.py`、`services/__init__.py` 和 3 个测试文件均已降至 500 行以内。
- Maven Surefire 已显式使用 Mockito Core JAR 作为 `-javaagent`，不再依赖未来 JDK
  将禁止的动态 attach；多模块测试仍为 `868/0/0/0`。
- 审计脚本已改用 `ReadAllLines` 统计物理行，并将源码发现锚定到仓库根目录。旧算法
  `Get-Content.Count` 会对部分历史编码文件少计行数，且 `-Include` 空集合曾被误判为
  “零超限”；因此旧文档中的“只剩 4 个生产文件、3 个测试文件”结论已废止。
- 当前准确剩余 18 个生产源码和 4 个测试文件，共 22 个：

| 行数 | 文件 |
|---:|---|
| 633 | `gateway/src/main/java/com/czh/datasmart/govern/gateway/config/GatewayAuthorizationProperties.java` |
| 547 | `python-ai-runtime/src/datasmart_ai_runtime/services/model_gateway/model_capability_registry.py` |
| 545 | `agent-runtime/src/main/java/com/czh/datasmart/govern/agent/config/AgentRuntimeProperties.java` |
| 538 | `agent-runtime/src/main/java/com/czh/datasmart/govern/agent/service/AgentToolExecutionAuditService.java` |
| 536 | `python-ai-runtime/src/datasmart_ai_runtime/services/memory/memory_materialization_lease_store.py` |
| 528 | `agent-runtime/src/main/java/com/czh/datasmart/govern/agent/service/runtime/AgentWorkspaceFilePayloadMaterializationService.java` |
| 521 | `agent-runtime/src/main/java/com/czh/datasmart/govern/agent/service/runtime/AgentToolActionCommandProposalService.java` |
| 516 | `agent-runtime/src/main/java/com/czh/datasmart/govern/agent/service/AgentSkillPublicationManifestService.java` |
| 516 | `python-ai-runtime/src/datasmart_ai_runtime/services/memory/memory_materialization_lease_sql_store.py` |
| 513 | `permission-admin/src/main/java/com/czh/datasmart/govern/permission/service/impl/PermissionProjectMembershipServiceImpl.java` |
| 512 | `agent-runtime/src/main/java/com/czh/datasmart/govern/agent/service/skill/AgentSkillPublicationLifecycleService.java` |
| 511 | `gateway/src/main/java/com/czh/datasmart/govern/gateway/filter/GatewayAuthorizationFilter.java` |
| 510 | `agent-runtime/src/main/java/com/czh/datasmart/govern/agent/service/tool/QualityRemediationTaskCommandSubmissionService.java` |
| 510 | `python-ai-runtime/src/datasmart_ai_runtime/services/agent_gateway/session_scheduler.py` |
| 510 | `python-ai-runtime/src/datasmart_ai_runtime/services/tools/command_worker_lease.py` |
| 510 | `task-management/src/main/java/com/czh/datasmart/govern/task/service/agent/AgentAsyncToolExecutionPreCheckService.java` |
| 505 | `agent-runtime/src/main/java/com/czh/datasmart/govern/agent/service/runtime/AgentSkillVisibilitySnapshotProjectionService.java` |
| 505 | `agent-runtime/src/main/java/com/czh/datasmart/govern/agent/service/runtime/AgentToolActionCommandSafetyPrecheckService.java` |
| 511 | `agent-runtime/src/test/java/com/czh/datasmart/govern/agent/service/runtime/AgentRuntimeEventDisplaySupportTest.java` |
| 509 | `gateway/src/test/java/com/czh/datasmart/govern/gateway/filter/GatewayAuthorizationFilterTest.java` |
| 504 | `agent-runtime/src/test/java/com/czh/datasmart/govern/agent/service/AgentSessionServiceTest.java` |
| 502 | `python-ai-runtime/tests/test_tool_action_execution_checkpoint_api.py` |

这些整改不得引入新产品功能、修改公开 API 或重新设计已稳定的状态机。

## 7. 最终冻结规则

1. 只接受 P0/P1 缺陷修复、安全修复、兼容性修复和客户环境接入。
2. 新 Agent、新连接器、新模型 SKU 和新业务域进入下一版本 backlog，不进入本轮闭环。
3. 所有真实副作用能力继续默认关闭，直到环境级验收通过。
4. 生产上线结论必须附带客户环境证据，不能只引用本仓库静态门禁。

# DataSmart Govern Python AI Runtime

这个目录是 DataSmart Govern 的 Python 智能运行时初始骨架，定位不是替代 Java 微服务，而是承接后续 `Agent 编排`、`模型路由`、`RAG/GraphRAG 检索`、`工具计划生成`、`OpenClaw/LangGraph 风格状态流转` 等 AI 能力。

## 当前边界

- Java 微服务继续负责权限、任务、数据源、数据同步、质量治理等可审计业务控制面。
- Python AI Runtime 负责“如何理解用户目标、选择模型、组织上下文、规划工具调用、识别是否需要人工审批”。
- 两层之间后续优先通过 HTTP/Kafka/gRPC 等明确契约交互，避免 Python 层直接访问 Java 模块内部数据库导致耦合过高。

## 当前已落地能力

- `ModelRouteRegistry`：按工作负载选择模型路由，避免业务代码写死某个模型名称。
- `ToolPlanner`：根据目标、变量和工具注册表生成工具计划，先采用可解释的规则式骨架，后续可替换为 LLM 规划器。
- `AgentOrchestrator`：以状态节点方式串联目标接收、模型选择、上下文构建、工具规划、审批判断和响应生成。
- 长期记忆治理：已具备记忆召回计划、候选生成、审批/拒绝、候选 SQL store、低敏摘要正式落成和 store-backed 检索骨架；候选和正式记忆都会携带 `workspaceKey/memoryNamespace`，检索时按当前 Agent 工作空间过滤，避免同项目不同 workspace 或 session 沙箱误共享记忆。当前正式记忆 store 默认仍为内存实现，后续再按类型接入 Chroma、Neo4j、MySQL 和 MinIO。
- `api.create_app()`：提供可选 FastAPI 入口。当前测试不依赖 FastAPI，安装 API 依赖后即可启动服务。

## 为什么先做这个骨架

商业化的数据治理平台不能把智能能力写成“一个 prompt 调一个模型”的 demo。真实产品里至少要提前留出这些扩展点：

- 模型会变化：主推理模型、代码模型、多模态模型、Embedding、Reranker 应按能力拆开。
- 场景会变多：数据源分析、质量规则生成、任务创建、权限审批、合规解释都可能走不同模型与工具链。
- 风险要分级：只读分析可以自动执行，高风险变更必须进入人工审批。
- 工具要可注册：Java 控制面的能力应作为工具被 Agent 调用，而不是由 Agent 随意拼接内部逻辑。
- 状态要可观测：每个 Agent 节点都应能被记录、追踪和排错。

## 本地验证

```powershell
python -m compileall python-ai-runtime/src python-ai-runtime/tests
python -m unittest discover -s python-ai-runtime/tests
```

## 下一步建议

- 接入 Java `agent-runtime` 的工具注册表接口，让 Python 层从真实注册表获取工具定义。
- 将规则式 `ToolPlanner` 抽象为策略接口，增加 LLM 规划器实现。
- 增加 RAG/GraphRAG 上下文构建器，区分元数据检索、权限事实检索、质量规则案例检索。
- 增加模型 Provider 适配器，优先兼容 OpenAI-compatible、vLLM、SGLang 等部署形态。
- 为正式长期记忆增加持久化 receipt/outbox 表、失败重试、补偿查询和向量库 namespace 过滤适配器。

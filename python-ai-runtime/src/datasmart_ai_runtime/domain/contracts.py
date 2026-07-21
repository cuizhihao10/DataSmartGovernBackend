"""Python AI Runtime 的核心领域契约。

这里使用 `dataclass` 与 `Enum`，而不是一开始就强依赖 Pydantic/FastAPI/LangGraph，
是为了让智能层的最小闭环可以在任何开发环境中被编译和单元测试。等 API 层、消息消费层
或 LangGraph 运行时接入后，再在边界层做请求/响应模型转换即可。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any

from datasmart_ai_runtime.domain.context import ContextBlock
from datasmart_ai_runtime.domain.events import AgentRuntimeEvent
from datasmart_ai_runtime.domain.intent import IntentAnalysis
from datasmart_ai_runtime.domain.memory import AgentMemoryPlan, AgentMemoryRetrievalReport
from datasmart_ai_runtime.domain.skills import AgentSkillPlan


class WorkloadType(str, Enum):
    """模型工作负载类型。

    商业化产品里不建议“所有事情都丢给一个聊天模型”。不同任务对模型能力、成本、延迟和
    可观测性的要求不同，所以这里先把常见能力拆成独立枚举：
    - `AGENT_REASONING`：面向多步骤推理、工具规划和状态决策。
    - `GOVERNANCE_QA`：面向数据治理知识问答、规则解释、审计说明。
    - `CODE_GENERATION`：面向 SQL、脚本、清洗方案等代码/DSL 生成。
    - `EMBEDDING`：面向向量化召回，通常应使用专用 Embedding 模型。
    - `RERANK`：面向检索结果重排，提升 RAG/GraphRAG 的上下文质量。
    - `MULTIMODAL_UNDERSTANDING`：面向截图、文档、图片表格等多模态理解。
    """

    AGENT_REASONING = "agent_reasoning"
    GOVERNANCE_QA = "governance_qa"
    CODE_GENERATION = "code_generation"
    EMBEDDING = "embedding"
    RERANK = "rerank"
    MULTIMODAL_UNDERSTANDING = "multimodal_understanding"


class ProviderType(str, Enum):
    """模型服务提供形态。

    这里描述的是“怎么调用模型”，不是“模型是谁”。同一个 Qwen3.5 或 DeepSeek-V3.2
    可以被 vLLM、SGLang、OpenAI-compatible 网关或本地 Python 进程承载。把提供形态与
    模型名称拆开，后续切换部署方式时不会影响 Agent 业务编排。
    """

    DRY_RUN = "dry_run"
    OPENAI_COMPATIBLE = "openai_compatible"
    VLLM = "vllm"
    SGLANG = "sglang"
    PYTHON_LOCAL = "python_local"


class ModelLatencyTier(str, Enum):
    """模型延迟等级。

    商业化模型网关不能只按“哪个模型最强”做路由，还要知道这次请求对延迟的容忍度：
    - `INTERACTIVE`：面向前端实时会话、参数澄清、短答案，要求较低首包延迟；
    - `STANDARD`：面向大多数治理规划和解释任务，在质量、成本和延迟之间取平衡；
    - `BATCH`：面向离线批处理、长报告、批量规则生成，可以接受更高延迟换取成本或吞吐。
    """

    INTERACTIVE = "interactive"
    STANDARD = "standard"
    BATCH = "batch"


class ModelCostTier(str, Enum):
    """模型成本等级。

    这里先用粗粒度等级，而不是直接写单价。真实成本会随供应商、上下文长度、量化方式、GPU 型号
    和折扣变化。先抽象成等级，可以让 Agent 编排层表达“优先低成本”或“允许高质量高成本”，而不
    把计费细节写死在业务逻辑里。
    """

    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"


class ModelCacheKeyScope(str, Enum):
    """模型推理缓存可复用范围。

    prefix cache / KV cache 可以显著降低长 prompt 的 prefill 成本，但在企业数据治理场景中，缓存
    绝不能跨越权限边界复用。这里先把范围写进模型路由契约，后续无论接 vLLM、SGLang 还是模型
    网关，都能按同一字段生成 cache key：
    - `GLOBAL_SAFE`：只包含公开系统提示、通用工具 schema 等全局安全内容；
    - `TENANT_SAFE`：只允许同一租户复用，例如租户级术语和策略；
    - `PROJECT_SAFE`：只允许同一项目复用，例如项目数据源元数据、质量规则上下文；
    - `SESSION_ONLY`：只允许当前会话复用，例如用户临时目标、审批草案、敏感样本；
    - `NO_CACHE`：不允许缓存，适合高度敏感或一次性导出/脱敏场景。
    """

    GLOBAL_SAFE = "global_safe"
    TENANT_SAFE = "tenant_safe"
    PROJECT_SAFE = "project_safe"
    SESSION_ONLY = "session_only"
    NO_CACHE = "no_cache"


class ToolRiskLevel(str, Enum):
    """工具风险等级。

    Agent 调用工具时必须区分“只读观察”和“会改变业务状态”的动作。这个风险等级会影响
    是否需要人工审批、是否允许自动执行、是否需要更详细审计，以及未来是否需要租户级开关。
    """

    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    CRITICAL = "critical"


class ToolExecutionMode(str, Enum):
    """工具执行模式。

    - `SYNC`：同步只读或轻量操作，适合元数据读取、规则解释等。
    - `ASYNC_TASK`：长耗时任务，后续应通过任务系统或 Kafka 跟踪状态。
    - `DRAFT_ONLY`：只生成草稿，不直接落库或执行，常用于 SQL/规则/任务方案生成。
    - `APPROVAL_REQUIRED`：必须先进入 Java 控制面的审批闭环。
    """

    SYNC = "sync"
    ASYNC_TASK = "async_task"
    DRAFT_ONLY = "draft_only"
    APPROVAL_REQUIRED = "approval_required"


class ToolParameterIssueAction(str, Enum):
    """工具参数问题的处理动作。

    真实 Agent 产品不能只告诉用户“参数缺失”，还要说明缺失参数应该如何处理。这里把处理动作
    做成枚举，是为了让前端、审批流和后续 Java 控制面能用稳定字段做判断：
    - `MUST_CLARIFY`：必须追问用户或阻断自动执行，例如读取数据源元数据却没有 datasourceId。
    - `ALLOW_DRAFT`：允许先生成草案，但不能直接执行，例如质量规则草案可以先保留待补字段。
    - `CAN_FILL_FROM_CONTEXT`：理论上可由 RAG/GraphRAG、Java 控制面或会话上下文补齐，当前缺失
      表示上下文质量还不够，需要继续检索或让用户选择。
    """

    MUST_CLARIFY = "must_clarify"
    ALLOW_DRAFT = "allow_draft"
    CAN_FILL_FROM_CONTEXT = "can_fill_from_context"


@dataclass(frozen=True)
class ModelRoute:
    """模型路由定义。

    一个路由表达“某类工作负载默认应该交给哪个模型服务”。这里不直接保存密钥、Token 或
    敏感连接串，生产环境应通过环境变量、密钥中心或部署平台注入，避免配置进入仓库。
    """

    workload: WorkloadType
    provider_name: str
    provider_type: ProviderType
    model_name: str
    endpoint: str | None = None
    max_context_tokens: int = 32768
    timeout_seconds: int = 60
    priority: int = 100
    fallback_group: str = "default"
    latency_tier: ModelLatencyTier = ModelLatencyTier.STANDARD
    cost_tier: ModelCostTier = ModelCostTier.MEDIUM
    cache_key_scope: ModelCacheKeyScope = ModelCacheKeyScope.SESSION_ONLY
    health_check_path: str = "/health"
    notes: str = ""


@dataclass(frozen=True)
class ModelMessage:
    """模型消息。

    该对象刻意保持接近 OpenAI-compatible Chat Completions 的消息结构，因为 vLLM、SGLang、
    LiteLLM、很多企业内部模型网关都会兼容类似协议。领域层只描述 role/content，不直接依赖
    某个 SDK，后续替换推理框架时可以减少迁移成本。

    工具回填相关字段说明：
    - `tool_call_id`：当 `role="tool"` 时，用来关联前一轮 assistant `tool_calls` 中的某个调用 ID；
      没有这个 ID，模型无法判断“这个工具结果对应哪个工具调用”，OpenAI-compatible 网关也可能拒绝请求；
    - `name`：工具消息可选的工具名，方便某些兼容网关、日志和调试面板识别来源；
    - `tool_calls`：当 `role="assistant"` 且模型上一轮提出工具调用时，用于把“模型曾提出哪些工具调用”
      放回下一轮上下文。真实多步 Agent loop 通常是：
      assistant(tool_calls) -> tool(tool_call_id=result) -> assistant(继续推理)。
    """

    role: str
    content: str
    tool_call_id: str | None = None
    name: str | None = None
    tool_calls: tuple["ModelToolCall", ...] = ()


@dataclass(frozen=True)
class ModelInvocationRequest:
    """模型调用请求。

    这个请求对象把“业务想让模型做什么”和“模型如何被调用”拆开：调用方提供 route、messages
    和可选参数，Provider 决定如何把它转换成具体 HTTP 请求、Python 本地推理或批处理任务。

    工具相关字段说明：
    - `available_tools`：本轮允许暴露给模型的工具定义。它必须是 Agent loop 根据租户、项目、角色、
      权限和意图裁剪后的结果，不能把全平台工具表无差别传入；
      - `tool_choice`：OpenAI-compatible 的工具选择策略，默认 `auto`，表示模型可以自行决定输出文本
        还是提出工具调用；传入 `None` 时 Provider 只发送 `tools`，不发送 `tool_choice`；
      - `strict_tool_schema`：是否给 function tool 加 strict schema。部分新模型支持更严格 schema，
        但不同 OpenAI-compatible 网关兼容程度不同，所以默认关闭，由部署环境逐步开启。
      - `provider_metadata`：传给模型 Provider 或智能网关的治理元数据。它不应包含 prompt、工具结果、
        样本值、连接密钥等敏感内容，只保存 trace、缓存、预算、工作负载等可审计策略字段。
        这样 Agent 主链不用绑定 LiteLLM/vLLM/SGLang 的具体协议，Provider 适配层可以按能力把这些字段
        转成请求体 metadata、HTTP Header、网关标签或运行指标。
      """

    route: ModelRoute
    messages: tuple[ModelMessage, ...]
    temperature: float = 0.2
    max_output_tokens: int = 2048
    trace_id: str | None = None
    available_tools: tuple["ToolDefinition", ...] = ()
    tool_choice: str | dict[str, Any] | None = "auto"
    strict_tool_schema: bool = False
    provider_metadata: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class ModelToolCall:
    """模型返回的完整工具调用意图。

    这不是“已经执行过的工具结果”，而是模型在一次响应中提出的结构化动作建议。真实的 Codex /
    Claude Code 类 Agent 一般会经历几个阶段：模型提出工具调用、运行时校验参数、权限系统判断是否允许、
    高风险动作进入人工审批、工具执行器执行、再把工具结果回填给模型继续推理。

    字段说明：
    - `call_id`：模型侧生成的工具调用 ID，后续工具结果消息需要用它和本次调用对应起来；
    - `type`：工具类型，OpenAI-compatible Chat Completions 当前常见为 `function`，也预留 custom；
    - `name`：模型希望调用的工具名称，后续需要和 DataSmart 工具注册表中的 `ToolDefinition.name` 对齐；
    - `arguments`：模型生成的原始参数字符串，通常是 JSON，但不能默认可信，后续必须做 schema 校验；
    - `raw_call`：保留原始 Provider payload，便于审计、问题排查和兼容不同 OpenAI-compatible 实现。
    """

    call_id: str | None = None
    type: str = "function"
    name: str = ""
    arguments: str = ""
    raw_call: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class ModelToolCallDelta:
    """模型流式返回的工具调用增量。

    streaming tool calling 与普通文本 token 类似，参数字符串往往会被拆成很多片段逐步返回。更重要的是，
    `id`、`type`、`function.name` 这些字段通常只会在第一个 delta 出现，后续 delta 可能只有一小段
    `function.arguments`。因此这里把字段命名为 `*_delta`，提醒调用方必须按 `index` 聚合后才能得到
    可执行的完整工具调用。

    字段说明：
    - `index`：同一次模型响应中第几个工具调用，是聚合 delta 的核心键；
    - `call_id`：模型侧工具调用 ID，可能只在第一个片段出现；
    - `type`：工具类型，可能只在第一个片段出现；
    - `name_delta`：工具名称增量，通常首片段有值，后续为空；
    - `arguments_delta`：参数字符串增量，需要拼接后再做 JSON/schema 校验；
    - `raw_delta`：保留原始片段，便于调试不同模型网关的 streaming 差异。
    """

    index: int
    call_id: str | None = None
    type: str | None = None
    name_delta: str = ""
    arguments_delta: str = ""
    raw_delta: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class ModelInvocationResult:
    """模型调用结果。

    结果中保留 provider/model/latency/usage/error 字段，是为了让后续做可观测性和成本统计时
    有统一入口。真实商业化环境里，模型调用不是黑盒：需要知道哪个供应商慢、哪个模型失败、
    哪个租户消耗高、哪类任务容易超时。

    当模型返回 `tool_calls` 时，表示模型不只是生成文本，而是在建议运行时调用一个或多个工具。
    这里仍只保存“意图”，不直接执行工具；执行前还需要 DataSmart 的工具注册表、权限系统、审批策略、
    参数 schema 校验和审计链路共同判断。
    """

    provider_name: str
    model_name: str
    content: str
    latency_ms: int = 0
    prompt_tokens: int | None = None
    completion_tokens: int | None = None
    # Provider 可能复用相同前缀的 KV/prompt cache。该字段来自 Provider usage，和
    # DataSmart Query Engine 的完整响应缓存不是同一层能力，必须分别计量和展示。
    cached_prompt_tokens: int | None = None
    error_code: str | None = None
    tool_calls: tuple[ModelToolCall, ...] = ()


@dataclass(frozen=True)
class ModelInvocationChunk:
    """模型流式调用片段。

    Codex、Claude Code 这类 Agent 产品不会等完整回答生成完才让用户看到反馈，而是会持续输出状态、
    中间文本、工具调用意图或错误信息。这里先定义最小 chunk 契约，供 OpenAI-compatible streaming、
    未来工具调用事件和前端实时展示复用。

    字段说明：
    - `content_delta`：本片段新增文本，前端可以按顺序追加展示；
    - `finish_reason`：模型返回的结束原因，例如 stop、length、tool_calls；
    - `sequence`：Provider 本地生成的片段序号，用于测试、日志和未来断点续传；
    - `error_code`：流式调用中发生错误时写入，不再抛给上层破坏整个 Agent 状态机；
    - `tool_call_deltas`：模型流式输出的工具调用增量，后续 Agent loop 需要按 index 聚合；
    - `raw_event`：保留原始 SSE JSON 片段，便于后续支持 tool/function calling 时解析更丰富字段。
    """

    provider_name: str
    model_name: str
    content_delta: str = ""
    finish_reason: str | None = None
    sequence: int = 0
    error_code: str | None = None
    tool_call_deltas: tuple[ModelToolCallDelta, ...] = ()
    raw_event: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class ToolDefinition:
    """Agent 可调用工具的注册定义。

    Python 层不直接持有 Java Service，而是持有“工具契约”。后续真实实现时，这些契约可以
    来自 Java `agent-runtime` 的工具注册表接口，也可以来自本地插件清单。这样 Agent 只关心
    工具能力，不关心工具内部由哪个微服务实现。

    字段设计说明：
    - `input_schema/output_schema`：描述工具输入输出，不直接绑定某个 Python 函数签名，便于从 Java
      descriptor、MCP server 或插件市场同步。
    - `required_permissions/allowed_actions`：描述调用工具需要的权限语义，真正授权仍由 Java 控制面
      和 gateway 执行；Python 只用它们做规划解释和审批提示。
    - `tenant_scoped/project_scoped`：描述工具是否必须带租户/项目上下文。Agent 工具绝不能默认跨
      租户或跨项目复用上下文，否则后续 prefix cache、记忆召回和审计回放都会有越界风险。
    - `sensitive_fields`：描述哪些输入字段需要脱敏展示或进入更严格审批，例如 SQL、datasourceId、
      sampleData、exportPath 等。
    - `memory_write_policy/cache_policy`：把工具结果是否能写入记忆、缓存能复用到什么范围提前写入
      契约，为后续语义记忆、情节记忆、prefix cache / KV cache 治理预留入口。
    """

    name: str
    description: str
    risk_level: ToolRiskLevel
    execution_mode: ToolExecutionMode
    input_schema: dict[str, Any] = field(default_factory=dict)
    output_schema: dict[str, Any] = field(default_factory=dict)
    required_permissions: tuple[str, ...] = ()
    target_service: str = ""
    display_name: str = ""
    target_endpoint: str = ""
    read_only: bool = False
    requires_approval: bool = False
    idempotent: bool = False
    timeout_ms: int | None = None
    max_retries: int | None = None
    allowed_actions: tuple[str, ...] = ()
    schema_version: str = ""
    descriptor_type: str = ""
    protocol_hint: str = ""
    tool_type: str = ""
    tenant_scoped: bool = True
    project_scoped: bool = True
    sensitive_fields: tuple[str, ...] = ()
    memory_write_policy: str = "none"
    cache_policy: str = "session_only"


@dataclass(frozen=True)
class ToolParameterIssue:
    """单个工具参数问题。

    该对象用于解释某个工具计划为什么还不能安全执行，或者为什么只能作为草案展示。相比只返回
    `missingParameters=["datasourceId"]`，这里额外保留 expectedType、action 和 message，便于
    前端展示中文原因，也便于后续审批策略判断哪些问题必须在审批前解决。
    """

    parameter_name: str
    expected_type: str
    action: ToolParameterIssueAction
    message: str


@dataclass(frozen=True)
class ToolParameterValidationResult:
    """工具参数校验结果。

    `can_execute` 表示从参数完整性角度是否允许进入真实执行链路；它不等于“无需审批”。例如一个
    高风险工具可能参数完整但仍需审批。`can_create_draft` 表示是否允许保留为草案，方便平台先让
    用户看到计划，再补齐缺失字段。
    """

    can_execute: bool = True
    can_create_draft: bool = True
    issues: tuple[ToolParameterIssue, ...] = ()


@dataclass(frozen=True)
class AgentRequest:
    """Agent 规划请求。

    这个对象对应用户的一次自然语言或表单化治理目标。字段设计时保留了租户、项目、操作者、
    变量和偏好的模型负载类型，目的是让后续权限、审计、上下文隔离、模型成本控制都能接入。
    """

    tenant_id: str
    project_id: str
    actor_id: str
    objective: str
    variables: dict[str, Any] = field(default_factory=dict)
    preferred_workload: WorkloadType = WorkloadType.AGENT_REASONING
    locale: str = "zh-CN"
    # 客户端可在开始流式读取前生成请求 ID，使“先建立进度流、再执行耗时模型调用”能够关联到同一轮。
    # 该值只用于幂等、事件关联和审计，不得参与租户、项目或权限判断；未提供时由运行时生成 UUID。
    request_id: str | None = None

    def __post_init__(self) -> None:
        """Normalize JSON boundary values before the request enters orchestration.

        HTTP payloads naturally deserialize enum fields as strings. Keeping the
        conversion here guarantees that model routing and LangGraph nodes always
        receive a ``WorkloadType`` even when callers use the public JSON contract.
        """

        if isinstance(self.preferred_workload, WorkloadType):
            return
        raw_value = str(self.preferred_workload).strip().lower()
        try:
            normalized = WorkloadType(raw_value)
        except ValueError as exc:
            allowed = ", ".join(workload.value for workload in WorkloadType)
            raise ValueError(
                f"Unsupported preferred_workload '{raw_value}'. Allowed values: {allowed}."
            ) from exc
        object.__setattr__(self, "preferred_workload", normalized)


@dataclass(frozen=True)
class ToolPlan:
    """单个工具调用计划。

    工具计划不是最终执行结果，而是 Agent 对“下一步可能需要做什么”的结构化表达。Java 控制面
    可以基于这个计划生成审计记录、进入审批流，或由用户确认后再执行。

    `governance_hints` 是从工具定义透传出来的治理摘要，不参与 Python 本地执行。它的价值在于：
    - 前端可以在确认页提示“该工具需要项目范围”“这些字段敏感”“结果只能会话级缓存”；
    - Java 控制面收到计划后可以把这些 hint 写入审批单或审计流水；
    - 后续模型网关和记忆层可以根据 `cachePolicy/memoryWritePolicy` 控制是否复用上下文。
    """

    tool_name: str
    reason: str
    arguments: dict[str, Any] = field(default_factory=dict)
    risk_level: ToolRiskLevel = ToolRiskLevel.LOW
    execution_mode: ToolExecutionMode = ToolExecutionMode.SYNC
    requires_human_approval: bool = False
    parameter_validation: ToolParameterValidationResult = field(default_factory=ToolParameterValidationResult)
    governance_hints: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class AgentPlan:
    """Agent 编排输出。

    这个对象是当前 Python AI Runtime 的核心产物：它把模型选择、状态节点、工具计划、审批要求
    和下一步建议放在一起，便于 API 层返回给 Java 或前端，也便于后续写入运行时审计日志。
    """

    request_id: str
    selected_route: ModelRoute | None
    state_trace: tuple[str, ...]
    tool_plans: tuple[ToolPlan, ...]
    requires_human_approval: bool
    response_summary: str
    next_actions: tuple[str, ...] = ()
    model_intent_summary: str = ""
    # 模型面向用户生成的公开决策摘要。它来自专门要求“不要输出隐藏思维链”的 assistant 文本，
    # 与 Provider 内部 reasoning token/chain-of-thought 不同，可用于 Codex 风格过程流解释本轮判断。
    model_decision_summary: str = ""
    # 本字段只保存模型调用的低敏治理事实，例如是否真的调用 Provider、模型名、耗时、token 和错误码。
    # 它绝不能保存 prompt、原始模型输出、工具参数或隐藏推理过程；前端据此区分真实模型与规则降级。
    model_invocation_summary: dict[str, Any] = field(default_factory=dict)
    # 面向用户的模型交互审计视图：只包含脱敏后的用户目标、公开指令摘要、结构化基线、
    # 可见工具名、上下文标题、模型公开回复和工具来源。系统提示词、隐藏推理、凭据、
    # 原始 Provider payload 与工具参数永远不进入该字段。
    model_interaction_summary: dict[str, Any] = field(default_factory=dict)
    context_blocks: tuple[ContextBlock, ...] = ()
    intent_analysis: IntentAnalysis | None = None
    model_gateway_decision: Any | None = None
    skill_plan: AgentSkillPlan = field(default_factory=AgentSkillPlan)
    memory_plan: AgentMemoryPlan = field(default_factory=AgentMemoryPlan)
    memory_retrieval_report: AgentMemoryRetrievalReport = field(default_factory=AgentMemoryRetrievalReport)
    user_profile_context: dict[str, Any] = field(default_factory=dict)
    runtime_events: tuple[AgentRuntimeEvent, ...] = ()
    workflow_diagnostics: dict[str, Any] = field(default_factory=dict)

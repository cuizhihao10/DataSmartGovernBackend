"""Python API 默认 Agent 编排器与控制面客户端装配。

`api.py` 应尽量聚焦 FastAPI 应用创建、路由注册和生命周期管理；如果把工具目录加载、Skill 目录加载、
模型上下文策略、permission-admin 预算策略等都塞在一个文件里，API bootstrap 会很快超过 500 行并变得难以学习。

本文件把“如何构建默认 AgentOrchestrator”单独拆出，同时保持原有函数名不变。`api.py` 会继续 re-export 这些函数，
因此既有测试和外部调用方仍可从 `datasmart_ai_runtime.api` 导入，不需要感知内部拆分。
"""

from __future__ import annotations

import os
from typing import Any

from datasmart_ai_runtime.api.agent.skill_admission import build_skill_admission_policy
from datasmart_ai_runtime.config import default_skill_registry, default_tool_registry, model_routes_from_env
from datasmart_ai_runtime.domain.context import ContextSensitivityLevel
from datasmart_ai_runtime.domain.contracts import ToolDefinition
from datasmart_ai_runtime.domain.model_gateway import ModelGatewayBudgetPolicy
from datasmart_ai_runtime.services.agent_orchestrator import AgentOrchestrator
from datasmart_ai_runtime.services.hybrid_context_builder import ContextSelectionPolicy, HybridContextBuilder
from datasmart_ai_runtime.services.memory.memory_planner import AgentMemoryPlanner
from datasmart_ai_runtime.services.model_gateway import ModelGatewayGovernanceService
from datasmart_ai_runtime.services.model_gateway.model_provider import model_provider_registry_from_env
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.model_gateway.model_tool_call_budget_policy_provider import (
    EnvAndRequestModelToolCallBudgetPolicyProvider,
    JavaPermissionAdminToolBudgetPolicyClient,
    ModelToolCallBudgetPolicyProvider,
    RemoteThenLocalModelToolCallBudgetPolicyProvider,
)
from datasmart_ai_runtime.services.agent_runtime_tool_feedback_client import (
    JavaAgentRuntimeToolFeedbackClient,
    JavaAgentRuntimeToolFeedbackProvider,
)
from datasmart_ai_runtime.services.skill_registry import AgentSkillRegistry
from datasmart_ai_runtime.services.skill_registry_client import JavaAgentSkillRegistryClient, SkillRegistryClientError
from datasmart_ai_runtime.services.tool_registry_client import JavaAgentToolRegistryClient, ToolRegistryClientError
from datasmart_ai_runtime.services.tool_planner import ToolPlanner
from datasmart_ai_runtime.services.tools import (
    RemoteThenLocalToolExecutionReadinessPolicyProvider,
    ToolExecutionReadinessPolicyProvider,
    ToolExecutionReadinessPolicyProviderProtocol,
)


def load_tool_registry(
    tool_registry_base_url: str | None = None,
    prefer_remote_tools: bool = False,
    allow_remote_fallback: bool = True,
    trace_id: str | None = None,
    tool_registry_client: Any | None = None,
) -> tuple[ToolDefinition, ...]:
    """加载 Agent 工具目录。

    加载策略分三层：
    1. 如果调用方显式传入 `tool_registry_client`，优先使用它，便于单元测试和未来依赖注入；
    2. 如果配置了 `tool_registry_base_url` 或环境变量 `DATASMART_AGENT_RUNTIME_BASE_URL`，尝试读取 Java 工具目录；
    3. 如果未配置远程目录，或远程目录失败且允许降级，则使用本地默认工具清单。

    如果远程客户端支持 `list_tool_descriptors(...)`，这里会优先读取 Java 的 MCP-style descriptor，
    因为 descriptor 包含敏感字段、租户/项目范围、缓存策略和记忆写入策略，比旧 `list_tools(...)`
    更适合 Agent 规划。旧接口仍作为兼容路径保留，避免某些环境 Java 服务尚未升级时 Python
    Runtime 无法启动。
    """

    client = tool_registry_client
    resolved_base_url = tool_registry_base_url or os.getenv("DATASMART_AGENT_RUNTIME_BASE_URL")
    if client is None and resolved_base_url:
        client = JavaAgentToolRegistryClient(base_url=resolved_base_url)

    if client is None:
        return default_tool_registry()

    try:
        if hasattr(client, "list_tool_descriptors"):
            return client.list_tool_descriptors(enabled_only=True, trace_id=trace_id)
        return client.list_tools(enabled_only=True, trace_id=trace_id)
    except ToolRegistryClientError:
        if prefer_remote_tools and not allow_remote_fallback:
            raise
        return default_tool_registry()


def load_skill_registry(
    skill_registry_base_url: str | None = None,
    prefer_remote_skills: bool = False,
    allow_remote_fallback: bool = True,
    trace_id: str | None = None,
    skill_registry_client: Any | None = None,
) -> tuple[Any, ...]:
    """加载 Agent Skill 注册表。

    加载策略与工具目录保持一致，但使用独立函数，避免把工具与 Skill 的降级语义混在一起：
    - 集成/生产环境优先从 Java `agent-runtime` 读取 `/agent-runtime/skills/descriptors`；
    - 本地开发或 Java 服务不可用时，可以回退 `default_skill_registry()`；
    - 如果调用方设置 `prefer_remote_skills=True` 且关闭 fallback，则远程失败会直接抛出，适合 CI 或集成环境。
    """

    client = skill_registry_client
    resolved_base_url = skill_registry_base_url or os.getenv("DATASMART_AGENT_RUNTIME_BASE_URL")
    if client is None and resolved_base_url:
        client = JavaAgentSkillRegistryClient(base_url=resolved_base_url)

    if client is None:
        return default_skill_registry()

    try:
        return client.list_skill_descriptors(enabled_only=True, trace_id=trace_id)
    except SkillRegistryClientError:
        if prefer_remote_skills and not allow_remote_fallback:
            raise
        return default_skill_registry()


def build_context_selection_policy(
    context_max_tokens: int | None = None,
    allowed_context_sensitivity_levels: tuple[ContextSensitivityLevel, ...] | None = None,
) -> ContextSelectionPolicy:
    """构建默认上下文选择策略。

    这里把上下文预算独立出来，而不是写死在 `HybridContextBuilder` 或 `AgentOrchestrator` 中。
    原因是不同部署环境的模型上下文长度、成本预算和合规策略不同：
    - 本地开发可以用较小预算快速测试；
    - 生产环境可以按模型、租户、套餐或任务类型配置不同预算；
    - 高合规环境可以收紧允许进入模型的敏感级别。
    """

    resolved_max_tokens = context_max_tokens
    if resolved_max_tokens is None:
        raw_value = os.getenv("DATASMART_AI_CONTEXT_MAX_TOKENS")
        if raw_value:
            resolved_max_tokens = int(raw_value)

    return ContextSelectionPolicy(
        max_tokens=resolved_max_tokens or 2048,
        allowed_sensitivity_levels=allowed_context_sensitivity_levels
        or (
            ContextSensitivityLevel.PUBLIC,
            ContextSensitivityLevel.INTERNAL,
            ContextSensitivityLevel.CONFIDENTIAL,
        ),
    )


def build_default_orchestrator(
    tool_registry_base_url: str | None = None,
    prefer_remote_tools: bool = False,
    allow_remote_fallback: bool = True,
    trace_id: str | None = None,
    tool_registry_client: Any | None = None,
    tool_registry: tuple[ToolDefinition, ...] | None = None,
    skill_registry_base_url: str | None = None,
    prefer_remote_skills: bool = False,
    allow_remote_skill_fallback: bool = True,
    skill_registry_client: Any | None = None,
    model_gateway: ModelGatewayGovernanceService | None = None,
    context_max_tokens: int | None = None,
    allowed_context_sensitivity_levels: tuple[ContextSensitivityLevel, ...] | None = None,
    enable_remote_tool_feedback: bool | None = None,
    model_tool_call_budget_policy_provider: ModelToolCallBudgetPolicyProvider | None = None,
    permission_admin_base_url: str | None = None,
    enable_remote_tool_budget_policy: bool | None = None,
    allow_remote_tool_budget_policy_fallback: bool = True,
    enable_remote_skill_admission_policy: bool | None = None,
    allow_remote_skill_admission_fallback: bool = True,
    memory_retriever: Any | None = None,
) -> AgentOrchestrator:
    """创建默认 Agent 编排器。

    这个函数把默认模型路由和工具注册表组装起来，方便 API、命令行、测试或未来 Kafka Consumer
    复用同一套启动逻辑。生产环境中可以：
    - 通过 `DATASMART_AGENT_RUNTIME_BASE_URL` 或显式参数从 Java `agent-runtime` 动态拉取工具目录；
    - 通过 `DATASMART_AI_CONTEXT_MAX_TOKENS` 或显式参数控制上下文 token 预算；
    - 通过 `allowed_context_sensitivity_levels` 控制哪些敏感级别允许进入模型上下文。
    """

    # `tool_registry` 是给 API bootstrap 或测试显式传入的“已解析工具目录快照”。
    # 为什么需要这个参数：
    # - `/agent/plans` 主链路、MCP tools/call preview、未来工具确认页都应该基于同一份启动期工具目录；
    # - 如果每个组件各自调用远程 Java agent-runtime 加载工具，网络抖动或远端灰度发布可能导致同一进程内
    #   不同入口看到的工具集合不一致；
    # - 显式注入快照后，调用方可以在启动阶段集中处理远程优先/本地回退策略，orchestrator 只负责消费目录。
    tools = tool_registry if tool_registry is not None else load_tool_registry(
        tool_registry_base_url=tool_registry_base_url,
        prefer_remote_tools=prefer_remote_tools,
        allow_remote_fallback=allow_remote_fallback,
        trace_id=trace_id,
        tool_registry_client=tool_registry_client,
    )
    skills = load_skill_registry(
        skill_registry_base_url=skill_registry_base_url or tool_registry_base_url,
        prefer_remote_skills=prefer_remote_skills,
        allow_remote_fallback=allow_remote_skill_fallback,
        trace_id=trace_id,
        skill_registry_client=skill_registry_client,
    )
    context_policy = build_context_selection_policy(
        context_max_tokens=context_max_tokens,
        allowed_context_sensitivity_levels=allowed_context_sensitivity_levels,
    )
    resolved_base_url = tool_registry_base_url or os.getenv("DATASMART_AGENT_RUNTIME_BASE_URL")
    remote_feedback_enabled = (
        truthy_env("DATASMART_AGENT_RUNTIME_TOOL_FEEDBACK_ENABLED")
        if enable_remote_tool_feedback is None
        else enable_remote_tool_feedback
    )
    tool_feedback_provider = None
    if remote_feedback_enabled and resolved_base_url:
        tool_feedback_provider = JavaAgentRuntimeToolFeedbackProvider(
            JavaAgentRuntimeToolFeedbackClient(base_url=resolved_base_url),
            trace_id=trace_id,
            auto_execute_sync_enabled=truthy_env("DATASMART_AGENT_RUNTIME_SYNC_AUTO_EXECUTION_ENABLED"),
            auto_execute_dry_run=truthy_env("DATASMART_AGENT_RUNTIME_SYNC_AUTO_EXECUTION_DRY_RUN"),
            max_auto_executions=optional_positive_int_env("DATASMART_AGENT_RUNTIME_SYNC_AUTO_EXECUTION_MAX"),
        )
    budget_policy_provider = model_tool_call_budget_policy_provider or build_tool_call_budget_policy_provider(
        permission_admin_base_url=permission_admin_base_url,
        trace_id=trace_id,
        enable_remote=enable_remote_tool_budget_policy,
        allow_remote_fallback=allow_remote_tool_budget_policy_fallback,
    )
    skill_admission_policy = build_skill_admission_policy(
        permission_admin_base_url=permission_admin_base_url,
        trace_id=trace_id,
        enable_remote=enable_remote_skill_admission_policy,
        allow_remote_fallback=allow_remote_skill_admission_fallback,
    )
    return AgentOrchestrator(
        model_routes=ModelRouteRegistry(model_routes_from_env()),
        tool_planner=ToolPlanner(tools),
        model_providers=model_provider_registry_from_env(),
        context_builder=HybridContextBuilder(policy=context_policy),
        memory_planner=AgentMemoryPlanner(),
        memory_retriever=memory_retriever,
        model_gateway=model_gateway,
        skill_registry=AgentSkillRegistry(skills, admission_policy=skill_admission_policy),
        model_tool_call_budget_policy_provider=budget_policy_provider,
        tool_execution_feedback_provider=tool_feedback_provider,
    )


def build_tool_call_budget_policy_provider(
    permission_admin_base_url: str | None = None,
    *,
    trace_id: str | None = None,
    enable_remote: bool | None = None,
    allow_remote_fallback: bool = True,
) -> ModelToolCallBudgetPolicyProvider:
    """构建工具调用预算策略 provider。

    默认返回本地 env/request provider；只有显式启用远程且存在 permission-admin 地址时，才调用 Java 控制面。
    这样本地学习环境不需要启动 Java 服务，生产环境又可以把策略来源切到 permission-admin。
    """

    local_provider = EnvAndRequestModelToolCallBudgetPolicyProvider()
    remote_enabled = (
        truthy_env("DATASMART_PERMISSION_ADMIN_TOOL_BUDGET_ENABLED")
        if enable_remote is None
        else enable_remote
    )
    base_url = permission_admin_base_url or os.getenv("DATASMART_PERMISSION_ADMIN_BASE_URL")
    if not remote_enabled or not base_url:
        return local_provider
    remote_client = JavaPermissionAdminToolBudgetPolicyClient(
        base_url=base_url,
        timeout_seconds=positive_int_env("DATASMART_PERMISSION_ADMIN_TOOL_BUDGET_TIMEOUT_SECONDS", 3),
    )
    return RemoteThenLocalModelToolCallBudgetPolicyProvider(
        remote_client,
        local_provider=local_provider,
        allow_remote_fallback=allow_remote_fallback,
        trace_id=trace_id,
    )


def build_tool_execution_readiness_policy_provider(
    permission_admin_base_url: str | None = None,
    *,
    trace_id: str | None = None,
    enable_remote: bool | None = None,
    allow_remote_fallback: bool = True,
) -> ToolExecutionReadinessPolicyProviderProtocol:
    """构建工具执行准备度策略 provider。

    与 `build_tool_call_budget_policy_provider(...)` 的关系：
    - `toolCallBudget` 控制模型本轮最多提出和保留多少工具调用；
    - `toolExecutionReadinessPolicy` 控制已经形成的 ToolPlan 是否可执行、等待审批、等待澄清、入队、限流或阻断。

    两者都来自 permission-admin 的同一个策略接口，但作用阶段不同，所以这里提供独立 builder：
    - API 响应层可以单独注入 readiness provider；
    - 旧预算链路继续保持向后兼容；
    - 后续如果 gateway 一次性注入完整策略 envelope，也只需要替换这个 builder 或 provider，而不需要改
      `api/agent/plan_response.py` 的主流程。

    环境变量：
    - `DATASMART_PERMISSION_ADMIN_TOOL_READINESS_POLICY_ENABLED`：优先控制 readiness 远程策略；
    - 如果未显式配置，则复用 `DATASMART_PERMISSION_ADMIN_TOOL_BUDGET_ENABLED`，方便 5.39 迁移期共用开关；
    - timeout 可用 `DATASMART_PERMISSION_ADMIN_TOOL_READINESS_TIMEOUT_SECONDS` 单独配置，未配置时复用预算 timeout。
    """

    local_provider = ToolExecutionReadinessPolicyProvider()
    remote_enabled = (
        _tool_readiness_remote_enabled_from_env()
        if enable_remote is None
        else enable_remote
    )
    base_url = permission_admin_base_url or os.getenv("DATASMART_PERMISSION_ADMIN_BASE_URL")
    if not remote_enabled or not base_url:
        return local_provider
    remote_client = JavaPermissionAdminToolBudgetPolicyClient(
        base_url=base_url,
        timeout_seconds=positive_int_env(
            "DATASMART_PERMISSION_ADMIN_TOOL_READINESS_TIMEOUT_SECONDS",
            positive_int_env("DATASMART_PERMISSION_ADMIN_TOOL_BUDGET_TIMEOUT_SECONDS", 3),
        ),
    )
    return RemoteThenLocalToolExecutionReadinessPolicyProvider(
        remote_client,
        local_provider=local_provider,
        allow_remote_fallback=allow_remote_fallback,
        trace_id=trace_id,
    )


def _tool_readiness_remote_enabled_from_env() -> bool:
    """读取 readiness 远程策略开关。

    迁移期允许复用旧预算开关，是为了让已经启用 permission-admin 工具预算的环境自动获得标准 readiness policy
    消费能力；但只要显式设置了 readiness 专用开关，就以专用开关为准，方便生产环境分阶段灰度。
    """

    readiness_value = os.getenv("DATASMART_PERMISSION_ADMIN_TOOL_READINESS_POLICY_ENABLED")
    if readiness_value is not None:
        return truthy_env("DATASMART_PERMISSION_ADMIN_TOOL_READINESS_POLICY_ENABLED")
    return truthy_env("DATASMART_PERMISSION_ADMIN_TOOL_BUDGET_ENABLED")


def truthy_env(name: str) -> bool:
    """解析布尔环境变量。

    Python 的 `bool("false")` 会得到 True，因此生产开关不能直接用 bool 转换。
    这里集中处理常见写法，避免运维在 `.env` 中写 `false` 却意外启用远程反馈查询。
    """

    value = os.getenv(name)
    if value is None:
        return False
    return str(value).strip().lower() in {"1", "true", "yes", "on", "enabled"}


def positive_int_env(name: str, default: int) -> int:
    """读取正整数环境变量。"""

    value = os.getenv(name)
    if value is None or not value.strip():
        return default
    parsed = int(value)
    return parsed if parsed > 0 else default


def optional_positive_int_env(name: str) -> int | None:
    """读取可选正整数环境变量。

    该 helper 用于“Python 可以不传，让 Java 控制面按服务端配置决定”的场景。
    例如同步自动执行最大数量：如果 Python 写死默认值，可能会覆盖 Java 侧按环境、租户或灰度配置的上限。
    """

    value = os.getenv(name)
    if value is None or not value.strip():
        return None
    parsed = int(value)
    return parsed if parsed > 0 else None


__all__ = [
    "build_context_selection_policy",
    "build_default_orchestrator",
    "build_tool_call_budget_policy_provider",
    "build_tool_execution_readiness_policy_provider",
    "load_skill_registry",
    "load_tool_registry",
    "optional_positive_int_env",
    "positive_int_env",
    "truthy_env",
]

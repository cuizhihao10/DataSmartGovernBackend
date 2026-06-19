"""模型网关能力包。

模型网关是 DataSmart Python AI Runtime 的“模型访问控制面”，它不只是把请求转发给某个 LLM Provider。
在真实商业化 Agent 产品中，模型网关至少要同时承担这些职责：

- 按工作负载、风险、成本、可用性和租户策略选择模型路由；
- 抽象 OpenAI-compatible、vLLM、SGLang、本地 dry-run 等不同 provider；
- 对工具调用做 schema 暴露、候选规划、增量流组装、预算限制和治理事件记录；
- 对工具执行反馈、模型输出上下文和敏感引用做二次过滤，防止把不可见数据重新送回模型；
- 为后续 KV cache、prompt cache、provider health、token budget、租户套餐和智能网关策略留出统一边界。

本包把原先散落在 `services/` 顶层的 `model_*` 与 `openai_compatible_provider` 文件集中起来。迁移后：

- `model_router`、`model_gateway`、`model_gateway_cache` 和 `model_gateway_context` 负责路由、治理与上下文；
- `model_provider`、`openai_compatible_provider` 和 `model_provider_metadata` 负责 provider 抽象和元信息；
- `model_tool_*` 文件负责模型原生 tool-call 的 schema、planning、aggregation、budget guard、feedback 和事件记录；
- `model_result_context_filter` 负责模型输出进入后续上下文前的低敏过滤。

目录治理原则：
第一阶段仍保留历史文件名，避免一次性重命名类和测试造成高风险回归。后续当模型网关继续扩展时，可以在
本包内部拆成 `providers/`、`routing/`、`tool_calls/`、`budget/`、`cache/`、`safety/` 等子包。
"""

from datasmart_ai_runtime.services.model_gateway.model_gateway import (
    InMemoryModelBudgetLedger,
    ModelGatewayGovernanceService,
)
from datasmart_ai_runtime.services.model_gateway.model_gateway_cache import ModelGatewayCachePlanner
from datasmart_ai_runtime.services.model_gateway.model_gateway_context import build_model_gateway_context
from datasmart_ai_runtime.services.model_gateway.model_capability_registry import (
    CapabilitySupport,
    ModelCapabilityProfile,
    ModelCapabilityRegistry,
    ModelRouteCapabilityAssessment,
    ModelServingEngine,
    default_model_capability_registry,
)
from datasmart_ai_runtime.services.model_gateway.model_provider import (
    DryRunModelProvider,
    ModelProviderRegistry,
    OpenAICompatibleModelProvider,
    OpenAICompatibleProviderSettings,
    model_provider_registry_from_env,
)
from datasmart_ai_runtime.services.model_gateway.model_provider_health import (
    InMemoryModelProviderHealthRegistry,
    ModelProviderHealthPolicy,
    ModelProviderInvocationHealthEvent,
)
from datasmart_ai_runtime.services.model_gateway.model_provider_health_probe import (
    ModelProviderHealthProbeResult,
    ModelProviderHealthProbeService,
    ModelProviderHealthProbeSettings,
    model_provider_health_probe_settings_from_env,
)
from datasmart_ai_runtime.services.model_gateway.model_provider_health_probe_metrics import (
    render_model_provider_health_probe_diagnostics_prometheus,
    render_model_provider_health_probe_prometheus,
)
from datasmart_ai_runtime.services.model_gateway.model_provider_metadata import build_model_provider_metadata
from datasmart_ai_runtime.services.model_gateway.model_result_context_filter import (
    ModelResultContextFilter,
    ModelResultContextFilterPolicy,
    ModelResultContextFilterReport,
    ModelResultContextFilterResult,
)
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.model_gateway.model_tool_call_aggregator import (
    ModelToolCallAssemblyIssue,
    ModelToolCallAssemblyReport,
    ModelToolCallDeltaAggregator,
)
from datasmart_ai_runtime.services.model_gateway.model_tool_call_budget_guard import (
    ModelToolCallBudgetGuard,
    ModelToolCallBudgetGuardReport,
    ModelToolCallBudgetPolicy,
)
from datasmart_ai_runtime.services.model_gateway.model_tool_call_budget_policy_provider import (
    EnvAndRequestModelToolCallBudgetPolicyProvider,
    ModelToolCallBudgetPolicyProvider,
    RemoteThenLocalModelToolCallBudgetPolicyProvider,
)
from datasmart_ai_runtime.services.model_gateway.permission_admin_tool_budget_policy_client import (
    JavaPermissionAdminToolBudgetPolicyClient,
    PermissionAdminToolBudgetPolicyClientError,
    PermissionAdminToolBudgetPolicyResponse,
)
from datasmart_ai_runtime.services.model_gateway.model_tool_call_events import (
    ModelToolCallEventRecordingSummary,
    record_model_tool_call_planning_events,
)
from datasmart_ai_runtime.services.model_gateway.model_tool_call_planner import (
    ModelToolCallCandidate,
    ModelToolCallGovernanceIssue,
    ModelToolCallPlanner,
    ModelToolCallPlanningReport,
)
from datasmart_ai_runtime.services.model_gateway.model_tool_feedback_provider import (
    ModelToolExecutionFeedbackProvider,
    SimulatedModelToolExecutionFeedbackProvider,
)
from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import (
    ModelToolResultFeedbackBuilder,
    ToolExecutionFeedback,
    ToolExecutionFeedbackMessageBundle,
    ToolExecutionFeedbackStatus,
)
from datasmart_ai_runtime.services.model_gateway.model_tool_schema import (
    ModelToolSchemaExposurePolicy,
    OpenAICompatibleToolSchemaBuilder,
)

__all__ = [
    "CapabilitySupport",
    "DryRunModelProvider",
    "EnvAndRequestModelToolCallBudgetPolicyProvider",
    "InMemoryModelBudgetLedger",
    "InMemoryModelProviderHealthRegistry",
    "ModelCapabilityProfile",
    "ModelCapabilityRegistry",
    "JavaPermissionAdminToolBudgetPolicyClient",
    "ModelGatewayCachePlanner",
    "ModelGatewayGovernanceService",
    "ModelProviderRegistry",
    "ModelProviderHealthPolicy",
    "ModelProviderHealthProbeResult",
    "ModelProviderHealthProbeService",
    "ModelProviderHealthProbeSettings",
    "ModelProviderInvocationHealthEvent",
    "ModelRouteCapabilityAssessment",
    "ModelResultContextFilter",
    "ModelResultContextFilterPolicy",
    "ModelResultContextFilterReport",
    "ModelResultContextFilterResult",
    "ModelRouteRegistry",
    "ModelServingEngine",
    "ModelToolCallAssemblyIssue",
    "ModelToolCallAssemblyReport",
    "ModelToolCallBudgetGuard",
    "ModelToolCallBudgetGuardReport",
    "ModelToolCallBudgetPolicy",
    "ModelToolCallBudgetPolicyProvider",
    "ModelToolCallCandidate",
    "ModelToolCallDeltaAggregator",
    "ModelToolCallEventRecordingSummary",
    "ModelToolCallGovernanceIssue",
    "ModelToolCallPlanner",
    "ModelToolCallPlanningReport",
    "ModelToolExecutionFeedbackProvider",
    "ModelToolResultFeedbackBuilder",
    "ModelToolSchemaExposurePolicy",
    "OpenAICompatibleModelProvider",
    "OpenAICompatibleProviderSettings",
    "OpenAICompatibleToolSchemaBuilder",
    "PermissionAdminToolBudgetPolicyClientError",
    "PermissionAdminToolBudgetPolicyResponse",
    "RemoteThenLocalModelToolCallBudgetPolicyProvider",
    "SimulatedModelToolExecutionFeedbackProvider",
    "ToolExecutionFeedback",
    "ToolExecutionFeedbackMessageBundle",
    "ToolExecutionFeedbackStatus",
    "build_model_gateway_context",
    "build_model_provider_metadata",
    "default_model_capability_registry",
    "model_provider_health_probe_settings_from_env",
    "model_provider_registry_from_env",
    "record_model_tool_call_planning_events",
    "render_model_provider_health_probe_diagnostics_prometheus",
    "render_model_provider_health_probe_prometheus",
]

"""智能运行时服务层。

服务层承载可替换的业务算法与编排逻辑，例如模型路由、工具规划、上下文构建和 Agent 状态流转。
这里的类应尽量保持“小而专注”，避免未来出现一个巨大的 `AgentServiceImpl` 式文件。
"""

from datasmart_ai_runtime.services.agent_orchestrator import AgentOrchestrator
from datasmart_ai_runtime.services.agent_model_intent_node import AgentModelIntentNode, AgentModelIntentNodeResult
from datasmart_ai_runtime.services.agent_workspace import (
    AgentWorkspaceContext,
    AgentWorkspaceContextBuilder,
    AgentWorkspaceIsolationLevel,
)
from datasmart_ai_runtime.domain.contracts import ModelInvocationChunk, ModelToolCall, ModelToolCallDelta
from datasmart_ai_runtime.services.context_builder import DefaultContextBuilder
from datasmart_ai_runtime.services.hybrid_context_builder import ContextSelectionPolicy, HybridContextBuilder
from datasmart_ai_runtime.services.intent_analyzer import RuleBasedIntentAnalyzer
from datasmart_ai_runtime.services.memory_planner import AgentMemoryPlanner
from datasmart_ai_runtime.services.memory_retriever import AgentMemoryRetriever, InMemoryAgentMemoryRetriever
from datasmart_ai_runtime.services.memory_store import (
    AgentMemoryStore,
    AgentMemoryStoreEntry,
    AgentMemoryStoreWriteResult,
    InMemoryAgentMemoryStore,
)
from datasmart_ai_runtime.services.memory_store_retriever import StoreBackedAgentMemoryRetriever
from datasmart_ai_runtime.services.memory_write_governance import (
    AgentMemoryWriteGovernanceService,
    approve_memory_write_candidate,
    reject_memory_write_candidate,
)
from datasmart_ai_runtime.services.memory_write_materializer import (
    AgentApprovedMemoryWriteMaterializer,
    AgentMemoryMaterializationOutcome,
    AgentMemoryMaterializationResult,
)
from datasmart_ai_runtime.services.memory_write_candidate_store import (
    AgentMemoryWriteCandidateStore,
    InMemoryAgentMemoryWriteCandidateStore,
)
from datasmart_ai_runtime.services.memory_write_components import (
    AgentMemoryWriteStoreRuntime,
    AgentMemoryWriteStoreSettings,
    build_memory_write_store_runtime,
    memory_write_store_diagnostics,
    memory_write_store_settings_from_env,
)
from datasmart_ai_runtime.services.memory_write_sql_store import SqlAgentMemoryWriteCandidateStore
from datasmart_ai_runtime.services.model_provider import (
    DryRunModelProvider,
    ModelProviderRegistry,
    OpenAICompatibleModelProvider,
    OpenAICompatibleProviderSettings,
    model_provider_registry_from_env,
)
from datasmart_ai_runtime.services.model_provider_metadata import build_model_provider_metadata
from datasmart_ai_runtime.services.model_tool_schema import (
    ModelToolSchemaExposurePolicy,
    OpenAICompatibleToolSchemaBuilder,
)
from datasmart_ai_runtime.services.model_tool_call_aggregator import (
    ModelToolCallAssemblyIssue,
    ModelToolCallAssemblyReport,
    ModelToolCallDeltaAggregator,
)
from datasmart_ai_runtime.services.model_tool_call_planner import (
    ModelToolCallCandidate,
    ModelToolCallGovernanceIssue,
    ModelToolCallPlanner,
    ModelToolCallPlanningReport,
)
from datasmart_ai_runtime.services.model_tool_call_events import (
    ModelToolCallEventRecordingSummary,
    record_model_tool_call_planning_events,
)
from datasmart_ai_runtime.services.model_tool_result_feedback import (
    ModelToolResultFeedbackBuilder,
    ToolExecutionFeedback,
    ToolExecutionFeedbackMessageBundle,
    ToolExecutionFeedbackStatus,
)
from datasmart_ai_runtime.services.model_result_context_filter import (
    ModelResultContextFilter,
    ModelResultContextFilterPolicy,
    ModelResultContextFilterReport,
    ModelResultContextFilterResult,
)
from datasmart_ai_runtime.services.model_tool_feedback_provider import (
    ModelToolExecutionFeedbackProvider,
    SimulatedModelToolExecutionFeedbackProvider,
)
from datasmart_ai_runtime.services.agent_runtime_tool_feedback_client import (
    AgentRuntimeToolFeedbackClientError,
    AgentRuntimeToolAutoExecutionSummary,
    AgentRuntimeToolExecutionPolicy,
    AgentRuntimeToolExecutionPolicyItem,
    JavaAgentRuntimeToolFeedbackClient,
)
from datasmart_ai_runtime.services.agent_runtime_tool_feedback_provider import (
    JavaAgentRuntimeToolFeedbackProvider,
)
from datasmart_ai_runtime.services.agent_runtime_event_replay_client import (
    AgentRuntimeEventReplayClientError,
    JavaAgentRuntimeEventReplayClient,
)
from datasmart_ai_runtime.services.agent_runtime_event_feedback import (
    AgentRuntimeEventFeedbackAugmentation,
    AgentRuntimeEventFeedbackBridge,
)
from datasmart_ai_runtime.services.agent_plan_ingestion_client import (
    AgentPlanIngestionClientError,
    AgentPlanIngestionResult,
    AgentToolAuditReference,
    JavaAgentPlanIngestionClient,
)
from datasmart_ai_runtime.services.agent_control_plane_feedback import (
    AgentControlPlaneFeedbackCollector,
    AgentControlPlaneFeedbackItem,
    AgentControlPlaneFeedbackSnapshot,
)
from datasmart_ai_runtime.services.agent_loop_control_policy import (
    AgentLoopControlAction,
    AgentLoopControlDecision,
    AgentLoopControlPolicy,
    AgentLoopControlPolicyEvaluator,
    AgentLoopControlState,
)
from datasmart_ai_runtime.services.agent_second_turn_orchestrator import (
    AgentSecondTurnOrchestrator,
    AgentSecondTurnResult,
)
from datasmart_ai_runtime.services.model_gateway import (
    InMemoryModelBudgetLedger,
    InMemoryModelProviderHealthRegistry,
    ModelGatewayGovernanceService,
)
from datasmart_ai_runtime.services.model_gateway_cache import ModelGatewayCachePlanner
from datasmart_ai_runtime.services.model_gateway_context import build_model_gateway_context
from datasmart_ai_runtime.services.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.runtime_event_authorization import (
    RuntimeEventAccessContext,
    RuntimeEventAuthorizationDecision,
    RuntimeEventSubscriptionAuthorizer,
)
from datasmart_ai_runtime.services.runtime_event_checkpoint_store import (
    InMemoryRuntimeEventCheckpointStore,
    RedisRuntimeEventCheckpointStore,
    RuntimeEventCheckpointStore,
    RuntimeEventSubscriptionCheckpoint,
)
from datasmart_ai_runtime.services.runtime_event_components import (
    RuntimeEventComponentSettings,
    RuntimeEventRuntimeComponents,
    build_runtime_event_components,
    runtime_event_component_diagnostics,
    runtime_event_settings_from_env,
)
from datasmart_ai_runtime.services.runtime_event_live_push import RuntimeEventLivePushHub
from datasmart_ai_runtime.services.runtime_event_outbox_store import (
    InMemoryRuntimeEventOutboxStore,
    RedisRuntimeEventOutboxStore,
    RuntimeEventOutboxStore,
)
from datasmart_ai_runtime.services.runtime_event_publisher import (
    KafkaRuntimeEventPublisher,
    NoopRuntimeEventPublisher,
    RuntimeEventPublisher,
    build_default_kafka_producer,
)
from datasmart_ai_runtime.services.runtime_event_store import InMemoryRuntimeEventStore, RedisStreamRuntimeEventStore
from datasmart_ai_runtime.services.runtime_event_control import (
    RuntimeEventControlHandler,
    RuntimeEventControlMessageError,
    control_message_from_payload,
)
from datasmart_ai_runtime.services.runtime_event_session import (
    RuntimeEventSessionError,
    RuntimeEventSessionManager,
    RuntimeEventSessionSnapshot,
)
from datasmart_ai_runtime.services.runtime_event_websocket import (
    RuntimeEventWebSocketFrame,
    RuntimeEventWebSocketFrameType,
    build_websocket_frames_from_control_response,
    frames_to_payloads,
)
from datasmart_ai_runtime.services.runtime_event_recorder import RuntimeEventRecorder
from datasmart_ai_runtime.services.runtime_event_replay_source import (
    RuntimeEventAckSink,
    RuntimeEventReplayCollection,
    RuntimeEventReplayCoordinator,
    RuntimeEventReplaySource,
)
from datasmart_ai_runtime.services.runtime_event_transport import RuntimeEventTransportBuilder
from datasmart_ai_runtime.services.runtime_event_visibility import (
    RuntimeEventVisibilityLevel,
    RuntimeEventVisibilityPolicy,
    RuntimeEventVisibilityStats,
)
from datasmart_ai_runtime.services.resource_reference_resolver import (
    AgentResourceContextPolicy,
    AgentResourceReferenceDecision,
    AgentResourceReferenceResolution,
    AgentResourceReferenceResolver,
)
from datasmart_ai_runtime.services.skill_registry import AgentSkillRegistry
from datasmart_ai_runtime.services.skill_registry_client import JavaAgentSkillRegistryClient
from datasmart_ai_runtime.services.tool_parameter_validator import ToolParameterValidator
from datasmart_ai_runtime.services.tool_planner import ToolPlanner
from datasmart_ai_runtime.services.tool_registry_client import JavaAgentToolRegistryClient

__all__ = [
    "AgentOrchestrator",
    "AgentModelIntentNode",
    "AgentModelIntentNodeResult",
    "AgentWorkspaceContext",
    "AgentWorkspaceContextBuilder",
    "AgentWorkspaceIsolationLevel",
    "AgentMemoryPlanner",
    "AgentMemoryRetriever",
    "InMemoryAgentMemoryRetriever",
    "AgentMemoryStore",
    "AgentMemoryStoreEntry",
    "AgentMemoryStoreWriteResult",
    "InMemoryAgentMemoryStore",
    "StoreBackedAgentMemoryRetriever",
    "AgentApprovedMemoryWriteMaterializer",
    "AgentMemoryMaterializationOutcome",
    "AgentMemoryMaterializationResult",
    "AgentMemoryWriteGovernanceService",
    "AgentMemoryWriteCandidateStore",
    "InMemoryAgentMemoryWriteCandidateStore",
    "SqlAgentMemoryWriteCandidateStore",
    "DefaultContextBuilder",
    "ContextSelectionPolicy",
    "HybridContextBuilder",
    "RuleBasedIntentAnalyzer",
    "JavaAgentToolRegistryClient",
    "AgentSkillRegistry",
    "JavaAgentSkillRegistryClient",
    "InMemoryRuntimeEventStore",
    "RedisStreamRuntimeEventStore",
    "InMemoryRuntimeEventOutboxStore",
    "NoopRuntimeEventPublisher",
    "KafkaRuntimeEventPublisher",
    "DryRunModelProvider",
    "ModelProviderRegistry",
    "ModelInvocationChunk",
    "ModelToolCall",
    "ModelToolCallDelta",
    "ModelToolCallAssemblyIssue",
    "ModelToolCallAssemblyReport",
    "ModelToolCallCandidate",
    "ModelToolCallDeltaAggregator",
    "ModelToolCallGovernanceIssue",
    "ModelToolCallEventRecordingSummary",
    "ModelToolCallPlanner",
    "ModelToolCallPlanningReport",
    "ModelToolSchemaExposurePolicy",
    "ModelToolResultFeedbackBuilder",
    "ModelResultContextFilter",
    "ModelResultContextFilterPolicy",
    "ModelResultContextFilterReport",
    "ModelResultContextFilterResult",
    "ModelToolExecutionFeedbackProvider",
    "AgentRuntimeToolFeedbackClientError",
    "AgentRuntimeToolAutoExecutionSummary",
    "AgentRuntimeToolExecutionPolicy",
    "AgentRuntimeToolExecutionPolicyItem",
    "AgentRuntimeEventReplayClientError",
    "AgentRuntimeEventFeedbackAugmentation",
    "AgentRuntimeEventFeedbackBridge",
    "AgentPlanIngestionClientError",
    "AgentPlanIngestionResult",
    "AgentToolAuditReference",
    "AgentControlPlaneFeedbackCollector",
    "AgentControlPlaneFeedbackItem",
    "AgentControlPlaneFeedbackSnapshot",
    "AgentLoopControlAction",
    "AgentLoopControlDecision",
    "AgentLoopControlPolicy",
    "AgentLoopControlPolicyEvaluator",
    "AgentLoopControlState",
    "AgentSecondTurnOrchestrator",
    "AgentSecondTurnResult",
    "JavaAgentRuntimeToolFeedbackClient",
    "JavaAgentRuntimeToolFeedbackProvider",
    "JavaAgentRuntimeEventReplayClient",
    "JavaAgentPlanIngestionClient",
    "OpenAICompatibleModelProvider",
    "OpenAICompatibleProviderSettings",
    "OpenAICompatibleToolSchemaBuilder",
    "InMemoryModelBudgetLedger",
    "InMemoryModelProviderHealthRegistry",
    "ModelGatewayCachePlanner",
    "ModelGatewayGovernanceService",
    "ModelRouteRegistry",
    "build_model_gateway_context",
    "AgentMemoryWriteStoreRuntime",
    "AgentMemoryWriteStoreSettings",
    "approve_memory_write_candidate",
    "RuntimeEventAccessContext",
    "RuntimeEventAuthorizationDecision",
    "InMemoryRuntimeEventCheckpointStore",
    "RedisRuntimeEventCheckpointStore",
    "RuntimeEventControlHandler",
    "RuntimeEventControlMessageError",
    "RuntimeEventCheckpointStore",
    "RuntimeEventComponentSettings",
    "RuntimeEventOutboxStore",
    "RuntimeEventPublisher",
    "RuntimeEventRuntimeComponents",
    "RuntimeEventSubscriptionAuthorizer",
    "RuntimeEventSessionError",
    "RuntimeEventSessionManager",
    "RuntimeEventSessionSnapshot",
    "RuntimeEventLivePushHub",
    "RuntimeEventSubscriptionCheckpoint",
    "RedisRuntimeEventOutboxStore",
    "RuntimeEventWebSocketFrame",
    "RuntimeEventWebSocketFrameType",
    "RuntimeEventRecorder",
    "RuntimeEventAckSink",
    "RuntimeEventReplayCollection",
    "RuntimeEventReplayCoordinator",
    "RuntimeEventReplaySource",
    "RuntimeEventTransportBuilder",
    "RuntimeEventVisibilityLevel",
    "RuntimeEventVisibilityPolicy",
    "RuntimeEventVisibilityStats",
    "AgentResourceContextPolicy",
    "AgentResourceReferenceDecision",
    "AgentResourceReferenceResolution",
    "AgentResourceReferenceResolver",
    "ToolParameterValidator",
    "ToolPlanner",
    "ToolExecutionFeedback",
    "ToolExecutionFeedbackMessageBundle",
    "ToolExecutionFeedbackStatus",
    "SimulatedModelToolExecutionFeedbackProvider",
    "build_websocket_frames_from_control_response",
    "build_default_kafka_producer",
    "build_memory_write_store_runtime",
    "build_model_provider_metadata",
    "build_runtime_event_components",
    "control_message_from_payload",
    "frames_to_payloads",
    "model_provider_registry_from_env",
    "memory_write_store_diagnostics",
    "memory_write_store_settings_from_env",
    "record_model_tool_call_planning_events",
    "reject_memory_write_candidate",
    "runtime_event_component_diagnostics",
    "runtime_event_settings_from_env",
]

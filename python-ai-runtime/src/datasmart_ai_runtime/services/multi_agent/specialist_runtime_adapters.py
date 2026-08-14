"""真实专业 Agent 的生产运行适配器。

专业 Agent 的领域类只描述“如何消歧、如何规划”，不应该直接依赖 HTTP、某一家模型 Provider 或
环境变量。本模块位于领域逻辑和基础设施之间，负责把两类真实能力接入统一合同：

1. 通过 datasource-management 的项目级列表接口读取当前用户可使用的数据源候选；
2. 通过 DataSmart 统一 ModelQueryEngine 调用真实模型，并把模型文本严格解析为结构化 JSON。

适配器仍然不拥有业务写权限。数据源检索是只读调用，模型适配器只产生候选 ID 或同步配置草案；任务
保存、发布、执行以及任何外部数据库变更继续由 Java 控制面、审批事实和下游 RBAC 决定。
"""

from __future__ import annotations

import hashlib
import json
import re
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any, Protocol
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from datasmart_ai_runtime.domain.contracts import (
    ModelInvocationRequest,
    ModelMessage,
    ModelToolCall,
    ToolDefinition,
    WorkloadType,
)
from datasmart_ai_runtime.domain.model_gateway import ModelGatewayRequestContext
from datasmart_ai_runtime.services.model_gateway import ModelGatewayGovernanceService
from datasmart_ai_runtime.services.model_gateway.model_provider import ModelProviderRegistry
from datasmart_ai_runtime.services.model_gateway.model_provider_metadata import build_model_provider_metadata
from datasmart_ai_runtime.services.model_gateway.model_query_engine import ModelQueryEngine
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.config_tool_registry import default_tool_registry
from datasmart_ai_runtime.services.multi_agent.specialists.data_sync_agent import (
    SyncPlanningModelInput,
    SyncPlanningModelOutput,
)
from datasmart_ai_runtime.services.multi_agent.specialists.datasource_agent import (
    DatasourceCandidate,
    DatasourceDirection,
    DatasourceDiscoveryRequest,
    DatasourceDiscoveryResult,
    DatasourceDisambiguationDecision,
    DatasourceDisambiguationRequest,
)
from datasmart_ai_runtime.services.multi_agent.specialists.monitor_agent import (
    MonitoringModelInput,
    MonitoringModelOutput,
)
from datasmart_ai_runtime.services.multi_agent.specialists.precheck_agent import (
    PrecheckExplanationModelInput,
    PrecheckExplanationModelOutput,
)
from datasmart_ai_runtime.services.multi_agent.specialists.recovery_agent import (
    RecoveryPlanningModelInput,
    RecoveryPlanningModelOutput,
)
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import SpecialistAuditScope


class SpecialistRuntimeAdapterError(RuntimeError):
    """专业 Agent 基础设施调用失败。

    单独的异常类型让专业 Agent 能把网络、模型、响应解析问题统一收口为低敏失败码，同时避免把下游
    URL、响应正文、异常栈或模型原文直接展示给普通用户。
    """

    def __init__(
        self,
        message: str,
        *,
        reason_code: str = "MODEL_ADAPTER_ERROR",
        reason_source: str = "SPECIALIST_MODEL_ADAPTER",
    ) -> None:
        """在内部异常消息之外保留可安全公开的分类。

        调用方必须将 ``reason_code`` 和 ``reason_source`` 用于持久化的 Specialist 结果。
        异常消息仅用于诊断，且有意绝不复制到 Recovery Agent 响应、事件流或 durable fact 载荷中。
        """

        super().__init__(message)
        self.reason_code = reason_code
        self.reason_source = reason_source


@dataclass(frozen=True)
class _GovernedJsonResult:
    """一次受治理模型调用在适配层内部使用的结构化结果。"""

    payload: Mapping[str, Any]
    invocation_summary: Mapping[str, Any]
    # 这是模型提出的工具调用候选，不是工具执行结果。它只在当前 Python turn 内部回填，
    # 后续若需要执行，必须交给现有 ToolPlan/Bridge/Java Durable approval 链路重新校验。
    tool_calls: tuple[ModelToolCall, ...] = ()


class GovernedSpecialistJsonModel:
    """复用统一模型网关的专业 Agent JSON 调用器。

    该类不是新的模型网关。它把专业 Agent 请求转换成现有 ``ModelInvocationRequest``，再交给
    ``ModelQueryEngine`` 执行预算、限流、安全缓存、健康回写和 fallback。模型响应只在当前内存 turn
    中解析，低敏 ``invocation_summary`` 才允许进入专业 Agent 结果和前端过程视图。
    """

    # 这些是 QueryEngine 能够真实提供、且适合进入低敏结果的动态元数据。任何 prompt、响应正文、
    # endpoint、工具参数或 Provider 私有对象都不会因为 ``to_summary`` 返回它们而穿过这个白名单。
    _SAFE_INVOCATION_SUMMARY_KEYS = frozenset(
        {
            "schemaVersion",
            "payloadPolicy",
            "selectedProviderName",
            "selectedModelName",
            "actualModelName",
            "requestedModelName",
            "providerName",
            "modelName",
            "providerInvoked",
            "providerSucceeded",
            "responseAvailable",
            "responseSource",
            "fallbackUsed",
            "cacheHit",
            "rateLimited",
            "tokenLimited",
            "resultErrorCode",
            "latencyMs",
            "providerLatencyMs",
            "promptTokens",
            "completionTokens",
            "cachedPromptTokens",
            "totalTokens",
            "toolCallCount",
            "attemptCount",
        }
    )

    # 角色白名单是第二层边界：上游委派范围即使错误地包含了其它只读工具，
    # specialist 也只能看到自己职责所需的最小集合。这里列出的名称是“模型可以提出的
    # 只读证据请求”，不是可执行权限；保存、发布、运行、改表和清理数据等工具永远不在表内。
    _ROLE_READ_TOOL_ALLOWLIST: Mapping[str, frozenset[str]] = {
        "KNOWLEDGE_AGENT": frozenset({"knowledge.rag.query"}),
        "DATASOURCE_AGENT": frozenset({"datasource.discovery.read"}),
        "DATA_SYNC_AGENT": frozenset(
            {
                "datasource.source.metadata.read",
                "datasource.target.metadata.read",
                "sync.cdc.readiness.check",
            }
        ),
        "PRECHECK_AGENT": frozenset({"sync.task.precheck"}),
        "RECOVERY_AGENT": frozenset({"recovery.failure.diagnose"}),
        "MONITOR_AGENT": frozenset({"task.monitor.read"}),
    }
    _MAX_NATIVE_TOOL_CALLS = 4

    def __init__(
        self,
        *,
        model_routes: ModelRouteRegistry,
        model_gateway: ModelGatewayGovernanceService,
        model_providers: ModelProviderRegistry,
        query_engine: ModelQueryEngine | None = None,
        tool_registry: tuple[ToolDefinition, ...] | None = None,
    ) -> None:
        """注入应用级模型路由、治理服务和 Provider 注册表。

        所有专业 Agent 必须复用应用级实例，不能各自创建 Provider，否则预算、健康度和缓存统计会被拆成
        多份，运维页面也无法解释同一次会话为什么选择了不同模型。
        """

        self._model_routes = model_routes
        self._model_gateway = model_gateway
        self._model_providers = model_providers
        self._query_engine = query_engine or ModelQueryEngine(
            model_gateway=model_gateway,
            model_providers=model_providers,
        )
        # 使用平台默认工具目录作为模型 schema 的来源，但不把目录本身直接暴露给模型。
        # 下面的 _visible_read_tools 会再次执行角色和委派范围交集，避免新增工具后自动
        # 出现在所有 specialist 的上下文中。
        self._tool_registry = self._index_tool_registry(tool_registry or default_tool_registry())

    def invoke(
        self,
        *,
        system_instruction: str,
        public_payload: Mapping[str, Any],
        tenant_id: str,
        project_id: str | None,
        actor_id: str,
        session_id: str,
        trace_id: str | None,
        max_output_tokens: int,
        specialist_role: str | None = None,
        allowed_tool_names: tuple[str, ...] = (),
    ) -> _GovernedJsonResult:
        """执行一次严格 JSON 模型调用并返回低敏治理摘要。

        ``public_payload`` 必须由上层专业 Agent 先完成字段白名单和脱敏。本方法不会记录 payload，也不会
        把模型完整回复写入审计；解析失败时只抛稳定适配器异常，由专业 Agent 转换为用户可理解的失败。
        """

        if not isinstance(tenant_id, str) or not tenant_id.strip():
            raise SpecialistRuntimeAdapterError("专业模型调用缺少租户审计主体")
        if not isinstance(actor_id, str) or not actor_id.strip():
            raise SpecialistRuntimeAdapterError("专业模型调用缺少租户或用户审计主体")
        if not isinstance(session_id, str) or not session_id.strip():
            raise SpecialistRuntimeAdapterError("专业模型调用缺少会话审计主体")

        try:
            serialized_payload = json.dumps(public_payload, ensure_ascii=False, separators=(",", ":"))
        except (TypeError, ValueError) as exc:
            raise SpecialistRuntimeAdapterError(
                "专业模型输入不是可序列化的低敏 JSON",
                reason_code="MODEL_RESPONSE_CONTRACT_VIOLATION",
                reason_source="MODEL_RESPONSE_CONTRACT",
            ) from exc
        try:
            route = self._model_routes.route_for(WorkloadType.AGENT_REASONING)
            visible_tools = self._visible_read_tools(
                specialist_role=specialist_role,
                allowed_tool_names=allowed_tool_names,
            )
            context = ModelGatewayRequestContext(
                tenant_id=tenant_id,
                project_id=project_id or "",
                actor_id=actor_id,
                workload=WorkloadType.AGENT_REASONING,
                estimated_prompt_tokens=max(1, len(serialized_payload) // 4),
                estimated_completion_tokens=max_output_tokens,
                trace_id=trace_id,
                attributes={"sessionId": session_id, "specialistAgent": True},
            )
            model_request = ModelInvocationRequest(
                route=route,
                messages=(
                    ModelMessage(role="system", content=system_instruction),
                    ModelMessage(role="user", content=serialized_payload),
                ),
                temperature=0.1,
                max_output_tokens=max_output_tokens,
                trace_id=trace_id,
                # 工具只代表模型提出的只读证据候选。适配器不会执行，也不会把它升级为审批事实。
                available_tools=visible_tools,
                tool_choice="auto" if visible_tools else None,
                provider_metadata=build_model_provider_metadata(context),
            )
            query_result = self._query_engine.invoke(model_request, context=context)
        except SpecialistRuntimeAdapterError:
            raise
        except Exception as exc:
            reason_code, reason_source = self._classify_provider_exception(exc)
            raise SpecialistRuntimeAdapterError(
                "专业模型网关调用失败，原始异常已按低敏策略隐藏",
                reason_code=reason_code,
                reason_source=reason_source,
            ) from exc
        try:
            result = query_result.result
            if result.error_code:
                error_code = _optional_text(result.error_code)
                if not error_code or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,120}", error_code):
                    error_code = "MODEL_QUERY_FAILED"
                reason_code = "MODEL_TIMEOUT" if "TIMEOUT" in error_code.upper() else "MODEL_PROVIDER_ERROR"
                raise SpecialistRuntimeAdapterError(
                    "专业模型调用未返回可用结果",
                    reason_code=reason_code,
                    reason_source="MODEL_PROVIDER_RESPONSE",
                )
            native_tool_calls = self._govern_native_tool_calls(
                getattr(result, "tool_calls", ()),
                visible_tools,
            )
            payload = self._parse_json_object(result.content)
            raw_summary = query_result.to_summary()
        except SpecialistRuntimeAdapterError:
            raise
        except Exception as exc:
            raise SpecialistRuntimeAdapterError(
                "专业模型查询结果无法安全读取",
                reason_code="MODEL_RESULT_UNAVAILABLE",
                reason_source="MODEL_RESULT_READER",
            ) from exc
        summary = self._safe_invocation_summary(raw_summary)
        if not self._has_dynamic_model_metadata(summary):
            raise SpecialistRuntimeAdapterError(
                "专业模型调用缺少真实动态模型元数据",
                reason_code="MODEL_RESULT_UNAVAILABLE",
                reason_source="MODEL_RESULT_READER",
            )
        summary.update(
            {
                "specialistModelInvoked": True,
                "invocationCount": 1,
                "structuredJsonParsed": True,
                "responseContentStored": False,
                "rawModelOutputStored": False,
                "reasoningStored": False,
                "nativeToolCallsAllowed": bool(visible_tools),
                "nativeToolCallsParsed": bool(native_tool_calls),
                "toolCallCount": len(native_tool_calls),
                # 参数只保留在本次内存候选对象中；摘要仅保存调用身份，不能泄露过滤条件或字段名。
                "nativeToolCallNames": tuple(call.name for call in native_tool_calls),
                "nativeToolCallIds": tuple(call.call_id for call in native_tool_calls if call.call_id),
            }
        )
        return _GovernedJsonResult(
            payload=payload,
            invocation_summary=summary,
            tool_calls=native_tool_calls,
        )

    @classmethod
    def _index_tool_registry(cls, tools: tuple[ToolDefinition, ...]) -> dict[str, ToolDefinition]:
        """构建模型描述符索引，并仅补充缺失的 Specialist 只读合同。"""

        indexed = {tool.name: tool for tool in tools if isinstance(tool, ToolDefinition)}
        for tool in cls._virtual_read_tool_definitions():
            indexed.setdefault(tool.name, tool)
        return indexed

    @staticmethod
    def _virtual_read_tool_definitions() -> tuple[ToolDefinition, ...]:
        """描述具有确定性客户端但没有通用描述符的 Specialist 工具代码。"""

        from datasmart_ai_runtime.domain.contracts import ToolExecutionMode, ToolRiskLevel

        return (
            ToolDefinition(
                name="datasource.discovery.read",
                description="List authorized datasource candidates only; never read credentials.",
                risk_level=ToolRiskLevel.LOW,
                execution_mode=ToolExecutionMode.SYNC,
                input_schema={
                    "direction": {"type": "string", "required": False, "sensitive": False, "resolution": "model_optional"},
                    "datasourceType": {"type": "string", "required": False, "sensitive": False, "resolution": "model_optional"},
                    "keyword": {"type": "string", "required": False, "sensitive": False, "resolution": "model_optional"},
                },
                read_only=True,
                idempotent=True,
                allowed_actions=("VIEW",),
                tool_type="DATASOURCE_DISCOVERY",
                cache_policy="project_safe",
            ),
            ToolDefinition(
                name="recovery.failure.diagnose",
                description="Read low-sensitive failure facts and evidence references; never repair or retry.",
                risk_level=ToolRiskLevel.LOW,
                execution_mode=ToolExecutionMode.SYNC,
                input_schema={
                    "taskId": {"type": "number", "required": False, "sensitive": False, "resolution": "context_or_clarify"},
                    "executionId": {"type": "number", "required": False, "sensitive": False, "resolution": "context_or_clarify"},
                },
                read_only=True,
                idempotent=True,
                allowed_actions=("VIEW", "DIAGNOSE"),
                tool_type="DATA_SYNC_RECOVERY",
                cache_policy="no_cache",
            ),
            ToolDefinition(
                name="task.monitor.read",
                description="Read task status, progress and throughput only; never stop, retry or modify a task.",
                risk_level=ToolRiskLevel.LOW,
                execution_mode=ToolExecutionMode.SYNC,
                input_schema={
                    "taskId": {"type": "number", "required": False, "sensitive": False, "resolution": "context_or_clarify"},
                    "executionId": {"type": "number", "required": False, "sensitive": False, "resolution": "context_or_clarify"},
                },
                read_only=True,
                idempotent=True,
                allowed_actions=("VIEW",),
                tool_type="DATA_SYNC_MONITOR",
                cache_policy="no_cache",
            ),
        )

    def _visible_read_tools(
        self,
        *,
        specialist_role: str | None,
        allowed_tool_names: tuple[str, ...],
    ) -> tuple[ToolDefinition, ...]:
        """仅暴露角色策略、当前委派和注册表只读工具的交集。

        这是三重边界。模型可见工具必须是此 specialist 的固有工具、已为本轮显式委派，且被平台目录
        标记为 read_only。Provider 看到 schema 前，会排除未知角色、未知描述符和所有写工具。
        """

        role = str(specialist_role or "").strip().upper()
        role_allowlist = self._ROLE_READ_TOOL_ALLOWLIST.get(role, frozenset())
        delegated = {
            str(name).strip()
            for name in allowed_tool_names
            if isinstance(name, str) and str(name).strip()
        }
        selected: list[ToolDefinition] = []
        for name in sorted(role_allowlist.intersection(delegated)):
            tool = self._tool_registry.get(name)
            if tool is not None and tool.read_only:
                selected.append(tool)
        return tuple(selected)

    def _govern_native_tool_calls(
        self,
        tool_calls: tuple[ModelToolCall, ...],
        visible_tools: tuple[ToolDefinition, ...],
    ) -> tuple[ModelToolCall, ...]:
        """将 Provider 工具调用校验为惰性候选，并移除原始 Provider 载荷。

        此适配器有意绝不分派工具。它只检查模型是否从当前只读暴露集合中选择工具、是否遵守
        specialist 调用预算以及是否提供有界 JSON 对象。Bridge 和 Java 仍负责 schema、RBAC、审批、幂等和执行。
        """

        calls = tuple(call for call in tool_calls or () if isinstance(call, ModelToolCall))
        if not calls:
            return ()
        visible_names = {tool.name for tool in visible_tools}
        if not visible_names:
            raise SpecialistRuntimeAdapterError(
                "专业模型返回了本轮未暴露的原生工具调用",
                reason_code="MODEL_RESPONSE_CONTRACT_VIOLATION",
                reason_source="MODEL_NATIVE_TOOL_CALL_GUARD",
            )
        if len(calls) > self._MAX_NATIVE_TOOL_CALLS:
            raise SpecialistRuntimeAdapterError(
                "专业模型返回的原生工具调用数量超过 specialist 上限",
                reason_code="MODEL_NATIVE_TOOL_CALL_BUDGET_EXCEEDED",
                reason_source="MODEL_NATIVE_TOOL_CALL_GUARD",
            )
        governed: list[ModelToolCall] = []
        for call in calls:
            name = str(call.name or "").strip()
            if name not in visible_names or str(call.type or "function") != "function":
                raise SpecialistRuntimeAdapterError(
                    "专业模型返回了未授权或类型无效的原生工具调用",
                    reason_code="MODEL_RESPONSE_CONTRACT_VIOLATION",
                    reason_source="MODEL_NATIVE_TOOL_CALL_GUARD",
                )
            call_id = _optional_text(call.call_id)
            if call_id and not re.fullmatch(r"[A-Za-z0-9._:-]{1,200}", call_id):
                raise SpecialistRuntimeAdapterError(
                    "专业模型返回的原生工具调用 ID 无效",
                    reason_code="MODEL_NATIVE_TOOL_CALL_INVALID",
                    reason_source="MODEL_NATIVE_TOOL_CALL_GUARD",
                )
            governed.append(
                ModelToolCall(
                    call_id=call_id,
                    type="function",
                    name=name,
                    arguments=self._canonical_native_tool_arguments(call.arguments),
                    raw_call={},
                )
            )
        return tuple(governed)

    @staticmethod
    def _canonical_native_tool_arguments(arguments: str) -> str:
        """在拒绝原始、无效或超大参数的同时，保留规范的内存 JSON 对象。"""

        raw = str(arguments or "{}").strip() or "{}"
        if len(raw) > 8_192:
            raise SpecialistRuntimeAdapterError(
                "专业模型返回的原生工具参数超过安全上限",
                reason_code="MODEL_NATIVE_TOOL_CALL_INVALID",
                reason_source="MODEL_NATIVE_TOOL_CALL_GUARD",
            )
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise SpecialistRuntimeAdapterError(
                "专业模型返回的原生工具参数不是合法 JSON",
                reason_code="MODEL_NATIVE_TOOL_CALL_INVALID",
                reason_source="MODEL_NATIVE_TOOL_CALL_GUARD",
            ) from exc
        if not isinstance(payload, Mapping):
            raise SpecialistRuntimeAdapterError(
                "专业模型返回的原生工具参数必须是 JSON 对象",
                reason_code="MODEL_NATIVE_TOOL_CALL_INVALID",
                reason_source="MODEL_NATIVE_TOOL_CALL_GUARD",
            )
        return json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))

    @staticmethod
    def _classify_provider_exception(exc: Exception) -> tuple[str, str]:
        """在不暴露传输细节或 Provider 消息的前提下规范化 Provider 故障。

        Provider 和 HTTP 库使用异构异常类型。分类器有意仅暴露运维补救所需的超时/非超时区别；
        endpoint 名称、响应载荷、认证值和异常文本绝不离开适配器。
        """

        if isinstance(exc, TimeoutError) or "timeout" in exc.__class__.__name__.lower():
            return "MODEL_TIMEOUT", "MODEL_PROVIDER_TRANSPORT"
        return "MODEL_PROVIDER_ERROR", "MODEL_PROVIDER_TRANSPORT"

    @classmethod
    def _safe_invocation_summary(cls, raw_summary: Any) -> dict[str, Any]:
        """把 QueryEngine 的真实摘要裁剪为低敏标量。

        ``ModelQueryEngineResult.to_summary`` 已经是低敏合同，但测试替身或未来 Provider 适配器可能
        添加额外字段。再次在这里做白名单过滤，可以保证模型原文、prompt、endpoint 和工具参数不会
        因为一个不受信任的 ``to_summary`` 实现进入 specialist result。
        """

        if not isinstance(raw_summary, Mapping):
            raise SpecialistRuntimeAdapterError("模型查询引擎没有返回结构化调用摘要")
        safe: dict[str, Any] = {}
        for key, value in raw_summary.items():
            key_text = str(key)
            if key_text not in cls._SAFE_INVOCATION_SUMMARY_KEYS or not _safe_scalar(value):
                continue
            if isinstance(value, str) and len(value) > 256:
                continue
            safe[key_text] = value
        return safe

    @staticmethod
    def _has_dynamic_model_metadata(summary: Mapping[str, Any]) -> bool:
        """确认摘要至少带有一次真实 Provider/模型调用来源，而不是适配器自填的常量。"""

        return any(
            key in summary
            for key in (
                "selectedProviderName",
                "selectedModelName",
                "actualModelName",
                "providerName",
                "modelName",
                "providerInvoked",
                "responseSource",
            )
        )

    @staticmethod
    def _parse_json_object(content: str) -> Mapping[str, Any]:
        """从模型文本中提取第一个完整 JSON 对象。

        部分 OpenAI-compatible 模型会在 JSON 外添加 Markdown 代码围栏或一句说明。这里允许这些展示性
        包装，但不使用 ``eval``、不接受数组顶层，也不默默修复非法 JSON，防止错误配置进入任务草案。
        """

        text = str(content or "").strip()
        if text.startswith("```"):
            first_newline = text.find("\n")
            last_fence = text.rfind("```")
            if first_newline >= 0 and last_fence > first_newline:
                text = text[first_newline + 1:last_fence].strip()
        object_start = text.find("{")
        if object_start < 0:
            raise SpecialistRuntimeAdapterError(
                "专业模型没有返回结构化 JSON 对象",
                reason_code="MODEL_RESPONSE_INVALID_JSON",
                reason_source="MODEL_RESPONSE_PARSER",
            )
        try:
            payload, _ = json.JSONDecoder().raw_decode(text[object_start:])
        except json.JSONDecodeError as exc:
            raise SpecialistRuntimeAdapterError(
                "专业模型返回的 JSON 无法解析",
                reason_code="MODEL_RESPONSE_INVALID_JSON",
                reason_source="MODEL_RESPONSE_PARSER",
            ) from exc
        if not isinstance(payload, dict):
            raise SpecialistRuntimeAdapterError(
                "专业模型返回值必须是 JSON 对象",
                reason_code="MODEL_RESPONSE_INVALID_JSON",
                reason_source="MODEL_RESPONSE_PARSER",
            )
        return payload


class GovernedDatasourceDisambiguationModel:
    """把统一模型调用器适配为数据源候选消歧协议。"""

    def __init__(self, model: GovernedSpecialistJsonModel) -> None:
        """绑定共享的受治理 JSON 模型调用器，不创建独立 Provider 或旁路预算。"""

        self._model = model

    def disambiguate(self, request: DatasourceDisambiguationRequest) -> DatasourceDisambiguationDecision:
        """仅允许模型从工具提供的候选 ID 中选择，证据不足时返回不确定。"""

        result = self._model.invoke(
            system_instruction=(
                "你是数据源消歧专业 Agent。只根据给出的低敏候选判断；数据库类型不等于数据源名称。"
                "证据不足或存在多个合理候选时 clear 必须为 false。只返回 JSON："
                '{"clear":false,"selectedDatasourceId":null,"publicReason":"面向用户的简短原因"}。'
                "不得生成候选列表之外的 ID，不得输出连接信息、密码或隐藏推理过程。"
            ),
            public_payload={
                "direction": request.direction.value,
                "requestedConnectorType": request.requested_connector_type,
                "requestedName": request.requested_name,
                "candidates": tuple(dict(item) for item in request.candidate_summaries),
            },
            tenant_id=request.tenant_id,
            project_id=request.project_id,
            actor_id=request.actor_id,
            session_id=request.session_id,
            trace_id=request.trace_id,
            max_output_tokens=request.max_output_tokens,
        )
        payload = result.payload
        return DatasourceDisambiguationDecision(
            clear=payload.get("clear") is True,
            selected_datasource_id=_optional_text(payload.get("selectedDatasourceId")),
            public_reason=_optional_text(payload.get("publicReason")) or "模型认为当前候选仍需人工确认。",
            model_name=_optional_text(result.invocation_summary.get("selectedModelName")),
            model_invocation_id=request.trace_id,
        )


class GovernedSyncPlanningModel:
    """把统一模型调用器适配为同步规划模型协议。"""

    def __init__(self, model: GovernedSpecialistJsonModel) -> None:
        """绑定共享模型边界，使同步规划复用平台路由、缓存、预算和审计统计。"""

        self._model = model

    def plan(self, request: SyncPlanningModelInput) -> SyncPlanningModelOutput:
        """生成待确定性校验的同步配置草案，不执行保存、发布或运行。"""

        result = self._model.invoke(
            system_instruction=(
                "你是数据同步规划专业 Agent。根据目标和已验证上下文生成任务配置草案；不得声称已经保存、"
                "发布、执行或修改数据库。只返回 JSON 对象，字段为 configuration、publicSummary、"
                "requestedToolNames、requestedActions。configuration 可包含模式、数据源 ID、对象映射、"
                "字段映射、WHERE、SQL、写入方式和调度；缺失信息保持缺失，禁止编造 ID、表或字段。"
                "requestedActions 必须为空数组，且不得输出隐藏推理过程。"
            ),
            public_payload={
                "objective": request.objective,
                "context": dict(request.context),
                "allowedReadTools": request.allowed_tool_names,
            },
            tenant_id=request.tenant_id,
            project_id=request.project_id,
            actor_id=request.actor_id,
            session_id=request.session_id,
            trace_id=request.trace_id,
            max_output_tokens=request.max_output_tokens,
        )
        payload = result.payload
        configuration = payload.get("configuration")
        if not isinstance(configuration, Mapping):
            raise SpecialistRuntimeAdapterError("同步规划模型没有返回 configuration 对象")

        # 通用 JSON 模型有时会把“本轮建议做什么”误放进 configuration.action。
        # action 不是同步任务的业务配置字段；对于纯文本动作，我们把它降级为既有的
        # requestedActions 建议字段。DATA_SYNC_AGENT 后续仍会把该建议隔离，Java 控制面
        # 也不会因为它被解析出来就执行任何副作用。只有这个非常窄的纯文本兼容路径被放行；
        # 布尔值、对象、数组和嵌套 action 仍原样交给 Specialist 安全门，保持 fail-closed。
        normalized_configuration = dict(configuration)
        top_level_action = normalized_configuration.get("action")
        requested_actions = list(_string_tuple(payload.get("requestedActions")))
        if isinstance(top_level_action, str) and top_level_action.strip():
            normalized_configuration.pop("action", None)
            requested_actions.append(top_level_action.strip())
        return SyncPlanningModelOutput(
            configuration=normalized_configuration,
            public_summary=_optional_text(payload.get("publicSummary")) or "同步规划模型已生成待校验草案。",
            invocation_summary=result.invocation_summary,
            requested_tool_names=_string_tuple(payload.get("requestedToolNames")),
            requested_actions=tuple(dict.fromkeys(requested_actions)),
        )


class _GovernedProtocolModelAdapter:
    """三个 specialist 模型适配器共享的范围绑定和 JSON 调用骨架。

    审计范围现在是三个领域 ``ModelInput`` 的显式字段。适配器只从当前输入读取已经由
    ``SpecialistTurnRequest`` 生成的 ``SpecialistAuditScope``，不再接受静态 context 或跨请求
    范围解析器。因此单例模型适配器可以安全复用：每一次 ``explain/plan/summarize``
    都必须携带自己的租户、项目、用户、会话和 turn 范围。
    """

    _PROTOCOL_NAME = "SPECIALIST_MODEL"

    def __init__(
        self,
        model: GovernedSpecialistJsonModel,
    ) -> None:
        """保存共享模型调用器。

        这里不接收任何身份上下文参数。身份若在构造适配器时注入，就会把一次调用的范围错误地
        变成实例级状态；模型实例通常是应用单例，跨租户复用时会造成严重的审计和缓存串范围。
        让输入携带范围可以把身份生命周期缩短到一个 turn，并且让缺字段在调用前立即失败。
        """

        if model is None or not callable(getattr(model, "invoke", None)):
            raise ValueError("专业模型适配器必须注入 GovernedSpecialistJsonModel")
        self._model = model

    def _invoke(
        self,
        *,
        request: object,
        system_instruction: str,
        public_payload: Mapping[str, Any],
        max_output_tokens: int,
    ) -> _GovernedJsonResult:
        """先解析审计范围，再调用共享 JSON 模型；范围失败时绝不进入 QueryEngine。"""

        audit_scope = self._resolve_audit_scope(request)
        return self._model.invoke(
            system_instruction=system_instruction,
            public_payload=public_payload,
            tenant_id=audit_scope.tenant_id,
            project_id=audit_scope.project_id,
            actor_id=audit_scope.actor_id,
            session_id=audit_scope.session_id,
            trace_id=audit_scope.trace_id,
            max_output_tokens=max_output_tokens,
        )

    @staticmethod
    def _resolve_audit_scope(request: object) -> SpecialistAuditScope:
        """从 ModelInput 读取并严格校验当前 turn 的审计范围。

        这里故意不支持 Mapping、回退字段或 callable provider。若允许这些兼容路径，调用方就可能
        把旧会话范围、线程局部变量甚至用户可控 JSON 重新带入模型网关。ModelInput 的 dataclass
        已经在构造时校验字段；适配器再做一次类型检查，是为了防止测试替身或未来协议实现绕过
        dataclass 直接传入不受信对象。失败发生在 ``ModelQueryEngine.invoke`` 之前，因此不会产生
        Provider 调用、缓存命中或审计记录。
        """

        audit_scope = getattr(request, "audit_scope", None)
        if not isinstance(audit_scope, SpecialistAuditScope):
            raise SpecialistRuntimeAdapterError(
                "专业模型输入缺少当前 turn 的租户、项目、用户、会话和 trace 审计范围"
            )
        try:
            # 再次显式读取所有字段，确保范围对象没有被不完整的伪造实现替换。
            if any(
                not isinstance(value, str) or not value.strip()
                for value in (
                    audit_scope.tenant_id,
                    audit_scope.project_id,
                    audit_scope.actor_id,
                    audit_scope.session_id,
                    audit_scope.trace_id,
                )
            ):
                raise ValueError("审计范围包含空字段")
            return audit_scope
        except Exception as exc:
            raise SpecialistRuntimeAdapterError("专业模型调用范围缺失或无效") from exc


class GovernedPrecheckExplanationModel(_GovernedProtocolModelAdapter):
    """把共享 JSON 模型适配为 PRECHECK_AGENT 的解释协议。

    控制面返回的检查状态、执行闸门和检查项已经是事实；本适配器只把低敏摘要交给模型，让模型
    生成问题解释、建议和配置步骤。模型不能看到任务配置，也没有原生工具定义，因此不能自行读
    表、查字段、声称主键通过或调用保存/发布/执行工具。
    """

    _PROTOCOL_NAME = "PRECHECK_EXPLANATION_MODEL"

    def explain(self, request: PrecheckExplanationModelInput) -> PrecheckExplanationModelOutput:
        """严格按 ``PrecheckExplanationModel`` Protocol 生成解释合同。

        方法只读取 Protocol 明确列出的七个输入字段；模型返回的调用摘要会被丢弃，输出中的
        ``invocation_summary`` 始终来自 QueryEngine 的真实动态元数据。越权状态字段会 fail-closed，
        不会被静默当成控制面事实。
        """

        if not isinstance(request, PrecheckExplanationModelInput):
            raise SpecialistRuntimeAdapterError("PRECHECK 解释模型收到的输入不符合 Protocol")
        checks = tuple(self._check_payload(item) for item in request.checks)
        result = self._invoke(
            request=request,
            system_instruction=(
                "The coordinator-owned recoveryDecisionControl is authoritative for this turn. If phase is "
                "DECIDE_AFTER_SEARCH and knowledgeSearchCompleted is true, the one-shot knowledge search has "
                "already completed, remainingKnowledgeSearches is zero, and this turn must choose exactly one "
                "governed action. Do not return SEARCH or SEARCH_RECOVERY_KNOWLEDGE again. For a clearly transient "
                "connector failure, RETRY_EXECUTION may be proposed as one inert recommendation; it is never "
                "execution authority. "
                "你是 DataSmart PRECHECK_AGENT 的解释模型。控制面状态、执行闸门和 checks 是唯一事实源，"
                "你只能解释它们，不能新增或改写表、字段、主键、目标状态或执行许可。不要调用工具，"
                "不要输出 SQL、凭据、样本行、隐藏思维链或保存/发布/执行动作。只返回 JSON，允许字段为 "
                "publicSummary、problems、suggestions、configurationSteps、detailsReferences；"
                "如需报告越权企图只能使用 requestedToolNames、requestedActions 或 claims，不能执行它。"
            ),
            public_payload={
                "objective": _safe_public_text(request.objective, 2_000),
                "taskId": _optional_text(request.task_id),
                "precheckStatus": _safe_public_text(request.precheck_status, 80),
                "canStartExecution": bool(request.can_start_execution),
                "checks": checks,
                "issueCodes": _safe_string_tuple(request.issue_codes, 120),
                "maxOutputTokens": request.max_output_tokens,
            },
            max_output_tokens=request.max_output_tokens,
        )
        payload = _validated_model_payload(
            result.payload,
            protocol="PRECHECK",
            allowed=(
                "publicSummary", "summary", "explanation", "problems", "issues", "suggestions",
                "configurationSteps", "configuration_steps", "detailsReferences", "details_refs",
                "requestedToolNames", "toolNames", "requestedActions", "claims", "recommendations",
                "recommendedActions", "invocationSummary",
            ),
        )
        return PrecheckExplanationModelOutput(
            public_summary=_safe_public_text(
                _lookup(payload, "publicSummary", "summary", "explanation"),
                600,
            ),
            problems=_safe_string_tuple(_lookup(payload, "problems", "issues"), 600),
            suggestions=_safe_string_tuple(_lookup(payload, "suggestions"), 600),
            configuration_steps=_safe_string_tuple(
                _lookup(payload, "configurationSteps", "configuration_steps"),
                600,
            ),
            details_references=_safe_reference_tuple(
                _lookup(payload, "detailsReferences", "details_refs"),
            ),
            # 绝不采信 JSON 中的 invocationSummary；它必须来自真实 QueryEngine 调用。
            invocation_summary=_adapter_invocation_summary(result, self._PROTOCOL_NAME),
            requested_tool_names=_safe_string_tuple(
                _lookup(payload, "requestedToolNames", "toolNames"),
                240,
            ),
            requested_actions=_safe_string_tuple(_lookup(payload, "requestedActions"), 240),
            claims=_safe_string_tuple(_lookup(payload, "claims"), 600),
            recommendations=_safe_string_tuple(
                _lookup(payload, "recommendations", "recommendedActions"),
                600,
            ),
        )

    @staticmethod
    def _check_payload(value: Mapping[str, Any]) -> Mapping[str, Any]:
        """把单个检查项收敛到 Precheck 输入允许的低敏字段。"""

        if not isinstance(value, Mapping):
            raise SpecialistRuntimeAdapterError("PRECHECK checks 中存在非结构化检查项")
        return {
            "code": _safe_public_text(_lookup(value, "code"), 160),
            "status": _safe_public_text(_lookup(value, "status"), 80),
            "problem": _safe_public_text(_lookup(value, "problem"), 600) or None,
            "suggestion": _safe_public_text(_lookup(value, "suggestion"), 600) or None,
            "configurationSteps": _safe_string_tuple(_lookup(value, "configurationSteps"), 400),
            "detailsReference": _safe_reference(_lookup(value, "detailsReference")),
        }


class GovernedRecoveryPlanningModel(_GovernedProtocolModelAdapter):
    """把共享 JSON 模型适配为 RECOVERY_AGENT 的建议规划协议。

    诊断事实、案例证据和知识摘要由 specialist 先裁剪，本适配器只按 Protocol 字段重新组织它们。
    模型可以提出待审核动作，但没有工具定义、审批事实或执行器；最终风险分类和控制面交接仍由
    ``RecoverySpecialistAgent`` 确定性完成。
    """

    _PROTOCOL_NAME = "RECOVERY_PLANNING_MODEL"
    # 这是模型可以“建议”的动作词表，不是模型可以直接调用的工具列表。Bridge 后续仍会
    # 逐项检查平台注册、allowedActions、RBAC、参数来源、预算和审批；在这里给出有限词表
    # 只是避免模型创造诸如 FIX_EVERYTHING / RERUN_TASK 这类无法治理的自由动作名称。
    _CANONICAL_ACTION_TYPES = (
        "SEARCH_RECOVERY_KNOWLEDGE",
        "RETRY_EXECUTION",
        "ROLLBACK_EXECUTION_POLICY",
        "TUNE_EXECUTION_POLICY",
        "REFRESH_METADATA",
        "RESUME_FROM_CHECKPOINT",
        "REPLAY_FAILED_SHARDS",
        "REPAIR_FIELD_MAPPING",
        "PREVIEW_QUARANTINE",
        "APPLY_QUARANTINE",
        "REPLAY_DIRTY_RECORDS",
        "PREVIEW_SCHEMA_REPAIR",
        "ALTER_TARGET_SCHEMA",
        "PREVIEW_CREATE_TARGET_TABLE",
        "CREATE_TARGET_TABLE",
        "RENAME_TASK",
    )
    # Java diagnosis 的 recommendedRepairActions 是确定性事实，不是模型输出。模型明确返回
    # actions 时始终优先采用模型建议；只有模型返回空数组时，才允许从下表生成“只读预览”
    # 模型没有选择动作时，只允许平台把确定性诊断收敛为这里列出的安全候选。
    # REPAIR_FIELD_MAPPING 虽然是写动作，但 Java 端仍会重新读取元数据并证明修复唯一、范围不扩大、完整预检通过；
    # 无法证明时返回 applied=false 并退出 Loop，因此它不等同于把诊断文本直接当成执行授权。
    _SAFE_PREVIEW_FALLBACKS = {
        "PREVIEW_DIRTY_RECORD_QUARANTINE": "PREVIEW_QUARANTINE",
        "PREVIEW_TARGET_VARCHAR_WIDEN": "PREVIEW_SCHEMA_REPAIR",
        "REPAIR_FIELD_MAPPING": "REPAIR_FIELD_MAPPING",
        "PREVIEW_TARGET_DROP_NOT_NULL_OR_FIX_SOURCE_VALUE": "REPAIR_FIELD_MAPPING",
        "PREVIEW_TARGET_ADD_NULLABLE_COLUMN_OR_REPAIR_FIELD_MAPPING": "REPAIR_FIELD_MAPPING",
    }
    _SCHEMA_ACTIONS_NARROWABLE_TO_FIELD_MAPPING = frozenset(
        {"PREVIEW_SCHEMA_REPAIR", "ALTER_TARGET_SCHEMA"}
    )
    _TRUSTED_FIELD_MAPPING_REPLAN_ISSUES = frozenset(
        {"METADATA_TARGET_FIELD_NOT_FOUND", "METADATA_REQUIRED_TARGET_FIELD_NOT_MAPPED"}
    )

    def plan(self, request: RecoveryPlanningModelInput) -> RecoveryPlanningModelOutput:
        """严格按 ``RecoveryPlanningModel`` Protocol 返回建议合同，不返回模型原文摘要。"""

        if not isinstance(request, RecoveryPlanningModelInput):
            raise SpecialistRuntimeAdapterError("RECOVERY 规划模型收到的输入不符合 Protocol")
        result = self._invoke(
            request=request,
            system_instruction=(
                "The coordinator-owned recoveryDecisionControl is authoritative for this turn. If phase is "
                "DECIDE_AFTER_SEARCH and knowledgeSearchCompleted is true, the one-shot knowledge search has "
                "already completed, remainingKnowledgeSearches is zero, and this turn must choose exactly one "
                "governed action. Do not return SEARCH or SEARCH_RECOVERY_KNOWLEDGE again. For a clearly transient "
                "connector failure, RETRY_EXECUTION may be proposed as one inert recommendation; it is never "
                "execution authority. When structured facts identify a deterministic configuration failure, "
                "prefer one narrowly scoped governed action: ROLLBACK_EXECUTION_POLICY restores only the latest "
                "successful runtime-policy snapshot; TUNE_EXECUTION_POLICY may only lower channel/read/write batch "
                "or request a bounded timeout increase; REFRESH_METADATA performs a fresh read and precheck; "
                "RESUME_FROM_CHECKPOINT requires an existing persisted checkpoint; REPLAY_FAILED_SHARDS selects "
                "only failed partition-shard ledgers; REPAIR_FIELD_MAPPING is allowed only for metadata-proven "
                "case normalization, uniquely resolvable mappings, or omission of a target column that already has "
                "a database default. Never use these actions to alter DDL, invent values, bypass NOT NULL or foreign "
                "keys, broaden fields/rows, change credentials, overwrite targets, or delete data. "
                "你是 DataSmart RECOVERY_AGENT 的建议模型。只能根据给出的失败事实和外部证据生成待审核"
                "恢复建议；不得创建审批、调用工具、执行、重试、发布或修改任务。不要输出 SQL、凭据、"
                "样本行、原始日志或隐藏思维链。只返回 JSON，允许字段为 actions、publicSummary、"
                "failureReason、nextStep、retrievalDecision、retrievalStrategy、ragReason、confidence；actions "
                "只是建议，不能表示已执行。retrievalDecision 必须为 SEARCH 或 SKIP：根据错误新颖度、"
                "诊断事实覆盖度、已有引用和置信度自主决定；retrievalStrategy 可选 STRUCTURED_DIAGNOSTIC、"
                "EXACT_SEARCH、RAG、WIKI 或 GIT_HISTORY。不要因为这是 Recovery 就固定选择 SEARCH，也不要"
                "把 RAG 当成唯一有效证据。已知错误码、数据库约束或网络错误可依据结构化 API/日志选择 SKIP；"
                "陌生错误、低置信度或重复失败应选择 SEARCH，并切换检索或修复策略。"
                "若 diagnosticFacts.autopilotIssueCodes 包含 PREVIOUS_REPAIR_ACTION_ 前缀，说明该动作刚刚在"
                "安全预检中未能应用；不得原样重复该动作。应结合其余问题码自主选择一个不同的受治理动作，"
                "证据不足或只有越权方案时返回空 actions 并说明人工处理条件。若该列表还同时包含"
                "METADATA_TARGET_FIELD_NOT_FOUND、METADATA_REQUIRED_TARGET_FIELD_NOT_MAPPED，且"
                "recommendedRepairActions 包含 REPAIR_FIELD_MAPPING，应优先选择 REPAIR_FIELD_MAPPING；"
                "它只请求 Java 重新读取元数据并证明唯一映射，不得建议 ALTER_TARGET_SCHEMA。"
                "没有知识证据且选择 SEARCH 时，只返回"
                "SEARCH_RECOVERY_KNOWLEDGE，不得同时建议修复。已有足够事实时可选择 SKIP。每个 action 的 actionType "
                "必须从 public payload 的 canonicalActionTypes 中选择，不要创造近义词；toolName 可以省略，"
                "因为系统会按 actionType 在 Bridge 中执行确定性映射。若证据不足以选择动作，应返回空 actions "
                "并在人话 nextStep 中说明需要补充什么，不得猜测执行动作。若 diagnosticFacts 中的"
                "recommendedRepairActions 明确包含 PREVIEW_DIRTY_RECORD_QUARANTINE，应优先建议"
                "PREVIEW_QUARANTINE；这只是只读范围预览，不表示已经隔离或重放数据。每一轮最多返回一个"
                "最小下一动作：先调查、再依据真实工具回执决定修复，不能在同一轮同时建议 preview 与 apply/retry/"
                "replay，也不能一次并行多个 preview。后续动作不会丢失，而应在下一轮看到新证据后重新决策。"
            ),
            public_payload={
                "objective": _safe_public_text(request.objective, 4_000),
                "diagnosticFacts": _require_low_sensitive_mapping(request.diagnostic_facts, "diagnosticFacts"),
                "caseEvidence": _require_low_sensitive_mapping(request.case_evidence, "caseEvidence"),
                "knowledgeSummary": _require_low_sensitive_mapping(request.knowledge_summary, "knowledgeSummary"),
            # 这是由 MONITOR_AGENT 生成的精简确定性快照，绝非原始日志载荷或第二个模型的文本。
            # RECOVERY 仅用它使其建议与 coordinator 已门控的观察到的失败执行保持一致。
                "monitoringSummary": _require_low_sensitive_mapping(request.monitoring_summary, "monitoringSummary"),
                "evidenceAudit": _require_low_sensitive_mapping(request.evidence_audit, "evidenceAudit"),
                "evidenceReferences": _safe_reference_tuple(request.evidence_references),
                "allowedToolNames": _safe_string_tuple(request.allowed_tool_names, 240),
                "maxOutputTokens": request.max_output_tokens,
                "failureCode": _safe_public_text(request.failure_code, 160) or None,
                 "failureReason": _safe_public_text(request.failure_reason, 1_000),
                 "canonicalActionTypes": self._CANONICAL_ACTION_TYPES,
                 # 仅回显 coordinator 拥有的阶段事实。模型可据此选择下一项惰性建议，
                 # 而 Java 仍负责每一项权限和副作用检查。
                 "recoveryDecisionControl": {
                     "phase": request.decision_phase,
                     "knowledgeSearchCompleted": request.knowledge_search_completed,
                     "retrievalAlreadyPerformed": request.retrieval_already_performed,
                     "remainingKnowledgeSearches": request.remaining_knowledge_searches,
                     "mustChooseSingleGovernedAction": request.must_choose_single_governed_action,
                 },
             },
            max_output_tokens=request.max_output_tokens,
        )
        payload = _validated_model_payload(
            result.payload,
            protocol="RECOVERY",
            allowed=(
                "actions", "repairActions", "repair_actions", "plans", "publicSummary", "public_summary",
                "summary", "failureReason", "failure_reason", "nextStep", "next_step", "invocationSummary",
                "requestedToolNames", "requested_tool_names", "requestedActions", "requested_actions",
                "ragDecision", "rag_decision", "retrievalDecision", "retrieval_decision",
                "retrievalStrategy", "retrieval_strategy", "ragReason", "rag_reason", "confidence", "modelConfidence",
            ),
        )
        raw_actions = _lookup(payload, "actions", "repairActions", "repair_actions", "plans") or ()
        if not isinstance(raw_actions, (list, tuple)):
            raise SpecialistRuntimeAdapterError("RECOVERY 模型没有返回 actions 数组")
        actions = tuple(_recovery_action_payload(item) for item in raw_actions)
        narrowed_actions = self._narrow_schema_action_to_field_mapping(request, actions)
        narrowing_count = 1 if narrowed_actions != actions else 0
        actions = narrowed_actions
        fallback_actions = self._safe_preview_fallback_actions(request) if not actions else ()
        invocation_summary = dict(_adapter_invocation_summary(result, self._PROTOCOL_NAME))
        invocation_summary["deterministicPreviewFallbackCount"] = len(fallback_actions)
        invocation_summary["deterministicGovernanceNarrowingCount"] = narrowing_count
        return RecoveryPlanningModelOutput(
            actions=actions or fallback_actions,
            public_summary=_safe_public_text(_lookup(payload, "publicSummary", "public_summary", "summary"), 1_200),
            failure_reason=_safe_public_text(_lookup(payload, "failureReason", "failure_reason"), 1_000),
            next_step=_safe_public_text(_lookup(payload, "nextStep", "next_step"), 1_000),
            invocation_summary=invocation_summary,
            requested_tool_names=_safe_string_tuple(
                _lookup(payload, "requestedToolNames", "requested_tool_names"),
                240,
            ),
            requested_actions=_safe_string_tuple(
                _lookup(payload, "requestedActions", "requested_actions"),
                240,
            ),
            rag_decision=_safe_public_text(_lookup(payload, "ragDecision", "rag_decision"), 24) or "AUTO",
            rag_reason=_safe_public_text(_lookup(payload, "ragReason", "rag_reason"), 600),
            confidence=_optional_confidence(_lookup(payload, "confidence", "modelConfidence")),
            retrieval_decision=(
                _safe_public_text(_lookup(payload, "retrievalDecision", "retrieval_decision"), 24) or None
            ),
            retrieval_strategy=(
                _safe_public_text(_lookup(payload, "retrievalStrategy", "retrieval_strategy"), 48) or "AUTO"
            ),
        )

    @classmethod
    def _safe_preview_fallback_actions(
        cls,
        request: RecoveryPlanningModelInput,
    ) -> tuple[Mapping[str, Any], ...]:
        """把 Java 确定性诊断建议映射为最多一个平台复核型安全候选。

        兜底只在真实模型已成功调用、JSON 已通过边界校验且模型 actions 为空时运行。输入必须是
        ``diagnostic_facts.recommendedRepairActions`` 的短稳定编码；未知编码被忽略。最多返回一个
        候选，避免一次 abstain 扩成多个并行动作。写动作仍需经过 coordinator、Java 授权盒、动作指纹、
        元数据唯一性与完整预检，不能由本适配器直接执行。
        """

        raw_codes = request.diagnostic_facts.get("recommendedRepairActions")
        codes = raw_codes if isinstance(raw_codes, (list, tuple, set)) else ()
        for raw_code in codes:
            code = str(raw_code or "").strip().upper()
            action_type = cls._SAFE_PREVIEW_FALLBACKS.get(code)
            if action_type is None:
                continue
            action_id_prefix = "deterministic-preview" if action_type.startswith("PREVIEW_") else "deterministic-safe"
            return ({
                "actionId": f"{action_id_prefix}-{action_type.lower().replace('_', '-')}",
                "actionType": action_type,
                "reason": "模型未选择动作；依据 Java 确定性诊断生成平台复核型安全候选。",
            },)
        return ()

    @classmethod
    def _narrow_schema_action_to_field_mapping(
        cls,
        request: RecoveryPlanningModelInput,
        actions: tuple[Mapping[str, Any], ...],
    ) -> tuple[Mapping[str, Any], ...]:
        """把证据充分的 schema 变更建议收窄为 Java 可证明的字段映射修复。

        该规则只处理一个非常窄的恢复事实组合：上一轮 ``REFRESH_METADATA`` 已由 data-sync 真实执行但
        预检未通过，服务端同时报告目标字段不存在、必填字段未映射，并明确把
        ``REPAIR_FIELD_MAPPING`` 列入确定性建议。模型此时若选择 ``PREVIEW_SCHEMA_REPAIR`` 或
        ``ALTER_TARGET_SCHEMA``，平台不会自动放行 DDL，而是把意图收窄成固定参数的映射修复候选。

        返回值仍不是执行授权。coordinator、Java Agent Runtime 和 data-sync 会继续校验首次授权盒、动作指纹、
        项目范围和元数据唯一性；无法证明安全映射时修复服务返回 ``applied=false`` 并进入下一轮或有界停止。
        缺少任一服务端问题码、确定性建议或上一动作标记时保持模型原建议，使高风险动作正常进入人工审批。
        """

        if len(actions) != 1:
            return actions
        action_type = str(actions[0].get("actionType") or "").strip().upper()
        if action_type not in cls._SCHEMA_ACTIONS_NARROWABLE_TO_FIELD_MAPPING:
            return actions

        raw_recommendations = request.diagnostic_facts.get("recommendedRepairActions")
        recommendations = {
            str(value or "").strip().upper()
            for value in (raw_recommendations if isinstance(raw_recommendations, (list, tuple, set)) else ())
        }
        raw_issue_codes = request.diagnostic_facts.get("autopilotIssueCodes")
        issue_codes = {
            str(value or "").strip().upper()
            for value in (raw_issue_codes if isinstance(raw_issue_codes, (list, tuple, set)) else ())
        }
        if (
            "REPAIR_FIELD_MAPPING" not in recommendations
            or "PREVIOUS_REPAIR_ACTION_REFRESH_METADATA" not in issue_codes
            or not cls._TRUSTED_FIELD_MAPPING_REPLAN_ISSUES.issubset(issue_codes)
        ):
            return actions

        return ({
            "actionId": "deterministic-narrow-repair-field-mapping",
            "actionType": "REPAIR_FIELD_MAPPING",
            "reason": (
                "模型提出目标 schema 变更；平台依据 data-sync 的字段缺失、必填未映射与元数据刷新回执，"
                "收窄为由 Java 再次证明唯一性的字段映射修复候选。"
            ),
        },)


class GovernedMonitoringSummaryModel(_GovernedProtocolModelAdapter):
    """把共享 JSON 模型适配为 MONITOR_AGENT 的事实摘要协议。

    监控状态、进度、健康度和异常由确定性快照与阈值计算产生。模型只可把这些已经白名单化的事实
    写成用户说明和建议；它没有监控读取、停止、重试或补数工具，也不能通过输出字段改写结构化事实。
    """

    _PROTOCOL_NAME = "MONITORING_SUMMARY_MODEL"

    def summarize(self, request: MonitoringModelInput) -> MonitoringModelOutput:
        """严格按 ``MonitoringSummaryModel`` Protocol 生成摘要和建议。"""

        if not isinstance(request, MonitoringModelInput):
            raise SpecialistRuntimeAdapterError("MONITOR 摘要模型收到的输入不符合 Protocol")
        task_kind = getattr(request.task_kind, "value", request.task_kind)
        anomalies = tuple(
            _require_low_sensitive_mapping(item, "anomalies")
            for item in request.anomalies
        )
        result = self._invoke(
            request=request,
            system_instruction=(
                "你是 DataSmart MONITOR_AGENT 的摘要模型。只能解释输入中的事实和异常，不能生成或修改"
                "status、progress、health、terminal、anomalies 等事实字段。不得调用任何原生工具，不得"
                "停止、重试、补数或重放任务，不得输出 SQL、凭据、样本行或隐藏思维链。只返回 JSON，允许"
                "字段为 publicSummary 和 recommendedActions；建议只是给主 Agent 的文字建议。"
            ),
            public_payload={
                "objective": _safe_public_text(request.objective, 2_000),
                "taskId": _safe_public_text(request.task_id, 240),
                "taskKind": _safe_public_text(task_kind, 80),
                "facts": _require_low_sensitive_mapping(request.facts, "facts"),
                "anomalies": anomalies,
                "allowedToolNames": _safe_string_tuple(request.allowed_tool_names, 240),
                "maxOutputTokens": request.max_output_tokens,
            },
            max_output_tokens=request.max_output_tokens,
        )
        payload = _validated_model_payload(
            result.payload,
            protocol="MONITOR",
            allowed=(
                "publicSummary", "public_summary", "summary", "recommendedActions", "recommended_actions",
                "suggestions", "invocationSummary", "modelInvocationSummary",
            ),
        )
        return MonitoringModelOutput(
            public_summary=_safe_public_text(
                _lookup(payload, "publicSummary", "public_summary", "summary"),
                600,
            ),
            recommended_actions=_safe_string_tuple(
                _lookup(payload, "recommendedActions", "recommended_actions", "suggestions"),
                600,
            ),
            invocation_summary=_adapter_invocation_summary(result, self._PROTOCOL_NAME),
        )


class HttpDatasourceDiscoveryTool:
    """通过 datasource-management 读取当前委派范围内的数据源候选。

    客户端只调用分页列表接口，并再次过滤 ``effectiveActions``。它不访问详情、连接串或密码。下游服务
    仍按 tenant/project/actor 和实例授权表执行真实数据范围校验，因此 Python 的候选列表不是权限事实的
    唯一来源，也不能扩大用户本来拥有的权限。
    """

    def __init__(
        self,
        base_url: str,
        *,
        timeout_seconds: float = 3.0,
        service_token: str | None = None,
    ) -> None:
        """初始化受控服务地址和可选内部服务凭证。"""

        normalized_url = str(base_url or "").strip().rstrip("/")
        if not normalized_url:
            raise ValueError("数据源发现客户端必须配置 base_url")
        self._base_url = normalized_url
        self._timeout_seconds = max(0.1, float(timeout_seconds))
        self._service_token = _optional_text(service_token)

    def discover(self, request: DatasourceDiscoveryRequest) -> DatasourceDiscoveryResult:
        """按受治理范围读取候选，并让显式 ID 优先于可能过期的显示名称。

        调用方可能同时携带用户已经确认的 ``datasource_id`` 和页面缓存的 ``name``。数据源重命名后，
        ID 仍是稳定业务身份，而旧名称仅是低敏提示；若把两者同时发送给分页接口，服务端 keyword 会先
        把正确 ID 过滤掉。存在显式 ID 时，本方法因此不发送 keyword，而是在服务端完成 tenant/project、
        SOURCE/TARGET、connector type 和 ACTIVE 状态过滤后，再在本地精确匹配 ID。

        这不是绕过授权：返回记录仍必须通过下游项目范围校验，且 `_can_use_record` 只接受 USE/MANAGE
        或 owner 关系。没有显式 ID 时才使用名称 keyword 缩小候选，再由 Specialist/模型在授权集合内消歧。
        """

        query: dict[str, str | int] = {
            "current": 1,
            "size": 100,
            "tenantId": request.tenant_id,
            "projectId": request.project_id or "",
            "usagePurpose": request.direction.value,
            "status": "ACTIVE",
        }
        if request.connector_type:
            query["type"] = request.connector_type
        if request.name and not request.datasource_id:
            query["keyword"] = request.name
        url = f"{self._base_url}/datasources?{urlencode(query)}"
        http_request = Request(url=url, headers=self._headers(request), method="GET")
        try:
            with urlopen(http_request, timeout=self._timeout_seconds) as response:  # noqa: S310 - 地址来自部署配置。
                payload = json.loads(response.read().decode("utf-8"))
        except Exception as exc:  # pragma: no cover - 网络异常由集成环境覆盖。
            raise SpecialistRuntimeAdapterError("数据源控制面暂时无法完成授权候选检索") from exc

        records = self._records_from_envelope(payload)
        candidates: list[DatasourceCandidate] = []
        for record in records:
            datasource_id = _optional_text(record.get("id"))
            if request.datasource_id and datasource_id != str(request.datasource_id):
                continue
            if not self._can_use_record(record, request.actor_id):
                continue
            name = _optional_text(record.get("name"))
            connector_type = _optional_text(record.get("type"))
            if not datasource_id or not name or not connector_type:
                continue
            candidates.append(
                DatasourceCandidate(
                    datasource_id=datasource_id,
                    name=name,
                    connector_type=connector_type,
                    supported_directions=(request.direction,),
                    display_status=_optional_text(record.get("lastTestStatus") or record.get("status")),
                )
            )

        digest_material = ",".join(candidate.datasource_id for candidate in candidates)
        digest = hashlib.sha256(digest_material.encode("utf-8")).hexdigest()[:16]
        return DatasourceDiscoveryResult(
            candidates=tuple(candidates),
            evidence_reference=f"datasource-discovery:{request.run_id}:{request.direction.value.lower()}:{digest}",
        )

    def _headers(self, request: DatasourceDiscoveryRequest) -> dict[str, str]:
        """构造服务间范围头；这些值来自已验证委派，不接受模型覆盖。"""

        headers = {
            "Accept": "application/json",
            "X-DataSmart-Tenant-Id": request.tenant_id,
            "X-DataSmart-Project-Id": request.project_id or "",
            "X-DataSmart-Actor-Id": request.actor_id,
            "X-DataSmart-Data-Scope-Level": "PROJECT",
            "X-DataSmart-Authorized-Project-Ids": request.project_id or "",
            "X-DataSmart-Source-Service": "python-ai-runtime",
            "X-DataSmart-Trace-Id": request.turn_id,
        }
        if self._service_token:
            headers["X-DataSmart-Internal-Service-Token"] = self._service_token
        return {name: value for name, value in headers.items() if value}

    @staticmethod
    def _records_from_envelope(payload: Any) -> tuple[Mapping[str, Any], ...]:
        """解析平台统一响应和 MyBatis-Plus 分页结构，非法响应按失败关闭。"""

        if not isinstance(payload, Mapping) or payload.get("code") != 0:
            raise SpecialistRuntimeAdapterError("数据源控制面拒绝或无法处理候选检索")
        page = payload.get("data")
        if not isinstance(page, Mapping):
            raise SpecialistRuntimeAdapterError("数据源控制面没有返回分页结果")
        records = page.get("records") or page.get("content") or ()
        if not isinstance(records, (list, tuple)):
            raise SpecialistRuntimeAdapterError("数据源控制面候选列表格式无效")
        return tuple(record for record in records if isinstance(record, Mapping))

    @staticmethod
    def _can_use_record(record: Mapping[str, Any], actor_id: str) -> bool:
        """仅保留拥有 USE/MANAGE 或所有者关系的候选，VIEW 授权不足以创建同步任务。"""

        actions = {
            str(action).strip().upper()
            for action in (record.get("effectiveActions") or ())
            if str(action).strip()
        }
        owner_id = _optional_text(record.get("ownerId"))
        return bool(actions.intersection({"USE", "MANAGE"})) or owner_id == str(actor_id)


_SENSITIVE_KEY_PARTS = (
    "apikey",
    "authorization",
    "credential",
    "jdbc",
    "password",
    "privatekey",
    "refreshtoken",
    "secret",
    "token",
    "rawsql",
    "sampledata",
    "samplerow",
    "rowsdata",
    "sql",
    "query",
    "statement",
    "rawlog",
    "logbody",
    "prompt",
    "messagebody",
    "documentbody",
    "snippet",
    "chainofthought",
    "reasoningtrace",
)
_SENSITIVE_VALUE_PATTERN = re.compile(
    r"(?is)(?:password|passwd|token|secret|api[_ -]?key|credential|authorization)\s*[:=]\s*[^,;\s]+"
)
_SQL_VALUE_PATTERN = re.compile(
    r"(?is)\b(?:select|with|insert|update|delete|drop|alter|truncate|create|merge)\b"
    r".{0,800}\b(?:from|into|where|set|table)\b"
)
_SAMPLE_VALUE_PATTERN = re.compile(r"(?is)(?:sample\s*rows?|row\s*data|样本行|示例行)\s*[:=]")
_FORBIDDEN_MODEL_OUTPUT_KEYS = frozenset(
    {
        "action",
        "actions",
        "approved",
        "approval",
        "canstartexecution",
        "check",
        "checks",
        "checkitems",
        "execute",
        "executed",
        "execution",
        "facts",
        "health",
        "progress",
        "terminal",
        "status",
        "precheckstatus",
        "persist",
        "publish",
        "run",
        "save",
        "success",
        "succeeded",
        "toolcall",
        "toolcalls",
        "tools",
        "availabletools",
        "toolchoice",
        "anomalies",
        "fields",
        "tables",
        "primarykey",
        "targettable",
        "query",
        "sql",
        "messages",
        "chainofthought",
        "reasoningtrace",
        "credentials",
    }
)


def _normalized_key(value: Any) -> str:
    """把 camelCase、snake_case 和大小写差异压缩为稳定比较键。"""

    return re.sub(r"[^a-z0-9]", "", str(value or "").lower())


def _lookup(mapping: Mapping[str, Any] | None, *names: str) -> Any:
    """按精确名或归一化名读取结构化字段，不把整段对象当作身份文本。"""

    if not isinstance(mapping, Mapping):
        return None
    for name in names:
        if name in mapping:
            return mapping[name]
    wanted = {_normalized_key(name) for name in names}
    for key, value in mapping.items():
        if _normalized_key(key) in wanted:
            return value
    return None


def _optional_text(value: Any) -> str | None:
    """把标量规范为非空文本，拒绝把嵌套对象 repr 带入 ID 或摘要。"""

    if value is None or isinstance(value, (Mapping, list, tuple, set)):
        return None
    normalized = str(value).strip()
    return normalized or None


def _optional_confidence(value: Any) -> float | None:
    """解析模型自报置信度；缺失可兼容，非法值不能静默变成高置信。"""

    if value is None:
        return None
    if isinstance(value, bool):
        raise SpecialistRuntimeAdapterError("RECOVERY confidence 必须是 0 到 1 的数值")
    try:
        confidence = float(value)
    except (TypeError, ValueError) as exc:
        raise SpecialistRuntimeAdapterError("RECOVERY confidence 必须是 0 到 1 的数值") from exc
    if not 0.0 <= confidence <= 1.0:
        raise SpecialistRuntimeAdapterError("RECOVERY confidence 必须位于 0 到 1")
    return confidence


def _safe_scalar(value: Any) -> bool:
    """判断值是否适合进入低敏调用摘要；嵌套对象一律不保留。"""

    return value is None or isinstance(value, (str, int, float, bool))


def _safe_public_text(value: Any, limit: int) -> str:
    """限制并清理模型输入/输出中的公开文本。

    SQL、样本行和常见凭据赋值不会进入模型消息或结果正文。这里使用固定占位符而不是原始异常，
    使上层仍能知道某段内容被策略裁剪，同时不保存原文。
    """

    if value is None or isinstance(value, (Mapping, list, tuple, set)):
        return ""
    if hasattr(value, "value") and not isinstance(value, (str, bytes)):
        value = getattr(value, "value")
    text = str(value).strip()[: max(0, int(limit))]
    if not text:
        return ""
    if _SQL_VALUE_PATTERN.search(text) or re.search(r"(?i)\bsql\b", text):
        return "[REDACTED_SQL]"
    if _SAMPLE_VALUE_PATTERN.search(text):
        return "[REDACTED_DATA_ROWS]"
    text = _SENSITIVE_VALUE_PATTERN.sub("[REDACTED_SECRET]", text)
    text = re.sub(r"(?is)\bjdbc:[^\s,;]+", "[REDACTED_CONNECTION]", text)
    return text[: max(0, int(limit))]


def _is_sensitive_key(value: Any) -> bool:
    """判断结构化字段名是否可能携带凭据、原文或隐藏推理。"""

    normalized = _normalized_key(value)
    return normalized in {"rows", "samples", "samplerows", "sampledata"} or any(
        part in normalized for part in _SENSITIVE_KEY_PARTS
    )


def _safe_low_sensitive_value(value: Any, *, depth: int = 0) -> Any:
    """递归保留低敏标量和结构，同时删除敏感字段并限制载荷大小。"""

    if depth > 4:
        return "[TRUNCATED]"
    if isinstance(value, Mapping):
        result: dict[str, Any] = {}
        for index, (key, item) in enumerate(value.items()):
            if index >= 64 or _is_sensitive_key(key):
                continue
            key_text = _safe_public_text(key, 120)
            if not key_text:
                continue
            result[key_text] = _safe_low_sensitive_value(item, depth=depth + 1)
        return result
    if isinstance(value, (list, tuple, set)):
        return tuple(_safe_low_sensitive_value(item, depth=depth + 1) for item in list(value)[:32])
    if hasattr(value, "value") and not isinstance(value, (str, bytes)):
        return _safe_low_sensitive_value(getattr(value, "value"), depth=depth)
    if isinstance(value, str):
        return _safe_public_text(value, 1_200)
    if isinstance(value, (int, float, bool)) or value is None:
        return value
    return _safe_public_text(value, 240)


def _require_low_sensitive_mapping(value: Any, field_name: str) -> Mapping[str, Any]:
    """要求 Protocol 映射字段确实是对象，并返回裁剪后的低敏副本。"""

    if not isinstance(value, Mapping):
        raise SpecialistRuntimeAdapterError(f"{field_name} 不符合低敏结构化对象合同")
    sanitized = _safe_low_sensitive_value(value)
    return sanitized if isinstance(sanitized, Mapping) else {}


def _safe_string_tuple(value: Any, limit: int) -> tuple[str, ...]:
    """把 Protocol 的字符串数组限制长度、脱敏并去重。"""

    if isinstance(value, str):
        values = (value,)
    elif isinstance(value, (list, tuple, set)):
        values = value
    else:
        return ()
    result: list[str] = []
    for item in values:
        if not isinstance(item, str):
            continue
        text = _safe_public_text(item, limit)
        if text and text not in result:
            result.append(text)
    return tuple(result)


def _safe_reference(value: Any) -> str | None:
    """保留详情定位符本身，不展开 URL、日志正文或敏感查询参数。"""

    reference = _optional_text(value)
    if not reference or len(reference) > 256:
        return None
    if _SENSITIVE_VALUE_PATTERN.search(reference) or _SQL_VALUE_PATTERN.search(reference):
        return None
    return reference


def _safe_reference_tuple(value: Any) -> tuple[str, ...]:
    """规范化引用数组并丢弃疑似凭据、SQL 或过长的引用。"""

    if isinstance(value, str):
        values = (value,)
    elif isinstance(value, (list, tuple, set)):
        values = value
    else:
        return ()
    result: list[str] = []
    for item in values:
        reference = _safe_reference(item)
        if reference and reference not in result:
            result.append(reference)
    return tuple(result)


def _validated_model_payload(
    value: Any,
    *,
    protocol: str,
    allowed: tuple[str, ...],
) -> Mapping[str, Any]:
    """把 JSON 顶层裁剪为 Protocol 输出字段，并拒绝事实/副作用越权字段。

    未知的普通字段会被丢弃，避免 Provider 自定义元数据进入 specialist 合同；status、execute、
    SQL、工具调用等高风险字段则直接失败关闭，因为静默丢弃会掩盖模型已经越过边界的事实。
    ``invocationSummary`` 允许出现在 JSON 中只是为了兼容通用模型模板，但调用方永远不会采信它。
    """

    if not isinstance(value, Mapping):
        raise SpecialistRuntimeAdapterError(f"{protocol} 模型没有返回 JSON 对象")
    allowed_keys = {_normalized_key(item) for item in allowed}
    selected: dict[str, Any] = {}
    for key, item in value.items():
        normalized = _normalized_key(key)
        if (
            normalized in _FORBIDDEN_MODEL_OUTPUT_KEYS
            and normalized not in allowed_keys
        ) or _is_sensitive_key(normalized):
            raise SpecialistRuntimeAdapterError(f"{protocol} 模型输出越过解释/建议合同")
        if normalized in allowed_keys:
            selected[str(key)] = item
    return selected


def _recovery_action_payload(value: Any) -> Any:
    """保留恢复动作建议合同字段，并在进入 RecoverySpecialistAgent 前拒绝越权字段。"""

    if isinstance(value, str):
        text = _safe_public_text(value, 160)
        if not text:
            raise SpecialistRuntimeAdapterError("RECOVERY 动作建议不能为空")
        return text
    if not isinstance(value, Mapping):
        raise SpecialistRuntimeAdapterError("RECOVERY actions 必须由对象或文本组成")

    allowed = {
        "actiontype", "kind", "type", "name", "toolname", "executortool", "arguments", "parameters",
        "change", "reason", "why", "explanation", "originalvalues", "originalvalue", "proposedvalues",
        "proposedvalue", "suggestedvalues", "actionid", "evidencereferences", "evidencereference",
    }
    selected: dict[str, Any] = {}
    for key, item in value.items():
        normalized = _normalized_key(key)
        if normalized in _FORBIDDEN_MODEL_OUTPUT_KEYS or _is_sensitive_key(normalized):
            raise SpecialistRuntimeAdapterError("RECOVERY 动作建议包含越权或敏感字段")
        if normalized in allowed:
            selected[str(key)] = _safe_low_sensitive_value(item)
    return selected


def _adapter_invocation_summary(result: _GovernedJsonResult, protocol: str) -> Mapping[str, Any]:
    """只在输出合同中附加适配器标签，动态 Provider 元数据仍全部来自共享调用器。"""

    summary = dict(result.invocation_summary)
    summary["adapterProtocol"] = protocol
    summary["rawModelOutputStored"] = False
    return summary


def _string_tuple(value: Any) -> tuple[str, ...]:
    """把模型返回的字符串集合压缩为去重元组，拒绝嵌套对象。"""

    if not isinstance(value, (list, tuple)):
        return ()
    return tuple(dict.fromkeys(str(item).strip() for item in value if isinstance(item, str) and item.strip()))


__all__ = [
    "GovernedDatasourceDisambiguationModel",
    "GovernedMonitoringSummaryModel",
    "GovernedPrecheckExplanationModel",
    "GovernedRecoveryPlanningModel",
    "GovernedSpecialistJsonModel",
    "GovernedSyncPlanningModel",
    "HttpDatasourceDiscoveryTool",
    "SpecialistRuntimeAdapterError",
]

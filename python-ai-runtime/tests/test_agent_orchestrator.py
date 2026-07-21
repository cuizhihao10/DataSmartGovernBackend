import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api import build_default_orchestrator
from datasmart_ai_runtime.config import default_model_routes, default_skill_registry, default_tool_registry
from datasmart_ai_runtime.domain.context import ContextSourceType
from datasmart_ai_runtime.domain.contracts import (
    AgentRequest,
    ModelInvocationChunk,
    ModelInvocationResult,
    ModelToolCall,
    ModelToolCallDelta,
    WorkloadType,
)
from datasmart_ai_runtime.domain.events import AgentRuntimeEventSeverity, AgentRuntimeEventType
from datasmart_ai_runtime.domain.intent import GovernanceDomain
from datasmart_ai_runtime.domain.memory import AgentMemoryScope, AgentMemoryType
from datasmart_ai_runtime.services.agent_orchestrator import AgentOrchestrator
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.skill_registry import AgentSkillRegistry
from datasmart_ai_runtime.services.tool_planner import ToolPlanner


class AgentOrchestratorTest(unittest.TestCase):
    def test_client_request_id_and_progress_sink_follow_the_same_event_sequence(self) -> None:
        """前端预生成的 requestId 必须贯穿所有流式事件与最终计划。"""

        orchestrator = build_default_orchestrator()
        delivered = []
        plan = orchestrator.plan(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                objective="请分析数据源表结构",
                variables={"datasourceId": "ds-001"},
                request_id="browser-request-001",
            ),
            event_sink=delivered.append,
        )

        self.assertEqual("browser-request-001", plan.request_id)
        self.assertEqual(plan.runtime_events, tuple(delivered))
        self.assertEqual(AgentRuntimeEventType.AGENT_PLAN_STARTED, delivered[0].event_type)
        self.assertEqual(AgentRuntimeEventType.AGENT_PLAN_COMPLETED, delivered[-1].event_type)
        self.assertTrue(all(event.request_id == plan.request_id for event in delivered))

    def test_datasource_metadata_plan_does_not_require_approval(self) -> None:
        orchestrator = build_default_orchestrator()

        plan = orchestrator.plan(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                objective="请先分析这个 MySQL 数据源的表结构",
                variables={"datasourceId": "ds-001"},
            )
        )

        self.assertFalse(plan.requires_human_approval)
        self.assertEqual("datasource.metadata.read", plan.tool_plans[0].tool_name)
        self.assertIn("build_context", plan.state_trace)
        self.assertIn("analyze_intent", plan.state_trace)
        self.assertIn("select_skills", plan.state_trace)
        self.assertIn("invoke_model_intent", plan.state_trace)
        self.assertIn("plan_memory", plan.state_trace)
        self.assertIn("retrieve_memory", plan.state_trace)
        self.assertIn("route_model_gateway", plan.state_trace)
        self.assertIn("候选工具", plan.model_intent_summary)
        self.assertIsNotNone(plan.intent_analysis)
        self.assertIsNotNone(plan.model_gateway_decision)
        self.assertTrue(plan.model_gateway_decision.budget_decision.allowed)
        self.assertIn(GovernanceDomain.DATASOURCE, plan.intent_analysis.governance_domains)
        self.assertIn(ContextSourceType.DATASOURCE_METADATA, {block.source_type for block in plan.context_blocks})
        self.assertIn("ready_for_control_plane_execution", plan.state_trace)
        self.assertIn(AgentRuntimeEventType.CONTEXT_SELECTED, {event.event_type for event in plan.runtime_events})
        self.assertIn(AgentRuntimeEventType.INTENT_ANALYZED, {event.event_type for event in plan.runtime_events})
        self.assertIn(AgentRuntimeEventType.SKILL_ADMISSION_EVALUATED, {event.event_type for event in plan.runtime_events})
        self.assertIn(AgentRuntimeEventType.MODEL_GATEWAY_ROUTED, {event.event_type for event in plan.runtime_events})
        self.assertIn(AgentRuntimeEventType.TOOL_PLANNED, {event.event_type for event in plan.runtime_events})
        self.assertIn(AgentRuntimeEventType.MEMORY_RETRIEVED, {event.event_type for event in plan.runtime_events})
        self.assertTrue(plan.skill_plan.selected_skills)
        self.assertEqual("datasource.profiling", plan.skill_plan.selected_skills[0].skill_code)
        skill_event = next(
            event for event in plan.runtime_events if event.event_type == AgentRuntimeEventType.SKILL_ADMISSION_EVALUATED
        )
        self.assertGreaterEqual(skill_event.attributes["selectedSkillCount"], 1)
        self.assertEqual(0, skill_event.attributes["rejectedSkillCount"])
        self.assertIn(
            AgentMemoryType.SEMANTIC,
            {target.memory_type for target in plan.memory_plan.retrieval_targets},
        )
        self.assertEqual(AgentMemoryScope.PROJECT, plan.memory_plan.default_scope)
        self.assertEqual(0, plan.memory_retrieval_report.total_retrieved)
        self.assertTrue(all(event.request_id == plan.request_id for event in plan.runtime_events))
        self.assertEqual(
            tuple(range(1, len(plan.runtime_events) + 1)),
            tuple(event.sequence for event in plan.runtime_events),
        )

    def test_runtime_events_carry_session_id_when_request_has_session_variable(self) -> None:
        orchestrator = build_default_orchestrator()

        plan = orchestrator.plan(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                objective="请分析数据源结构",
                variables={"datasourceId": "ds-001", "sessionId": "session-abc"},
            )
        )

        self.assertTrue(plan.runtime_events)
        self.assertTrue(all(event.session_id == "session-abc" for event in plan.runtime_events))
        gateway_event = next(
            event for event in plan.runtime_events if event.event_type == AgentRuntimeEventType.MODEL_GATEWAY_ROUTED
        )
        self.assertTrue(gateway_event.attributes["cachePlanEnabled"])
        self.assertIn("session:session-abc", gateway_event.attributes["cachePlanNamespace"])

    def test_quality_rule_and_task_creation_requires_approval(self) -> None:
        orchestrator = build_default_orchestrator()

        plan = orchestrator.plan(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="owner-a",
                objective="请为客户主数据生成质量规则，并创建一个同步任务执行",
                variables={
                    "datasourceId": "ds-002",
                    "businessGoal": "客户主数据完整性与手机号格式校验",
                    "createTask": True,
                    "sessionId": "session-metadata",
                },
            )
        )

        tool_names = {item.tool_name for item in plan.tool_plans}
        self.assertTrue(plan.requires_human_approval)
        self.assertIn("quality.rule.suggest", tool_names)
        self.assertIn("task.create.draft", tool_names)
        self.assertIn("wait_human_approval", plan.state_trace)
        self.assertTrue(plan.memory_plan.approval_required_for_write)
        self.assertIn(AgentMemoryType.EPISODIC, plan.memory_plan.writable_memory_types)
        self.assertIn(
            "quality.rule.design",
            {selection.skill_code for selection in plan.skill_plan.selected_skills},
        )
        approval_event = next(
            event for event in plan.runtime_events if event.event_type == AgentRuntimeEventType.APPROVAL_WAITING
        )
        self.assertEqual(AgentRuntimeEventSeverity.AUDIT, approval_event.severity)

    def test_missing_tool_parameters_are_reflected_in_next_actions(self) -> None:
        orchestrator = build_default_orchestrator()

        plan = orchestrator.plan(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="analyst-a",
                objective="请生成客户主数据质量规则",
                variables={"businessGoal": "客户主数据完整性校验"},
            )
        )

        quality_plan = next(item for item in plan.tool_plans if item.tool_name == "quality.rule.suggest")
        self.assertFalse(quality_plan.parameter_validation.can_execute)
        self.assertIn("prepare_draft_with_missing_parameters", plan.state_trace)
        self.assertNotIn("ready_for_control_plane_execution", plan.state_trace)
        self.assertIn("参数需要补齐", plan.response_summary)
        self.assertTrue(any("datasourceId" in action for action in plan.next_actions))
        self.assertIn(
            AgentRuntimeEventType.TOOL_PARAMETER_VALIDATED,
            {event.event_type for event in plan.runtime_events},
        )

    def test_model_intent_node_receives_curated_available_tools(self) -> None:
        """模型意图节点应拿到按意图和 Skill 裁剪后的候选工具。

        这条测试把 3.96 的 Provider tools 请求体能力真正接到 Agent 编排链路上：模型调用节点不应只
        收到自然语言 messages，还应收到本轮允许暴露的 `available_tools`。但这个集合仍是候选工具，
        不是执行许可；后续执行仍由 Java agent-runtime 做权限、审批和审计。
        """

        provider = CapturingModelProviderRegistry()
        orchestrator = AgentOrchestrator(
            model_routes=ModelRouteRegistry(default_model_routes()),
            tool_planner=ToolPlanner(default_tool_registry()),
            model_providers=provider,
            skill_registry=AgentSkillRegistry(default_skill_registry()),
        )

        plan = orchestrator.plan(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="owner-a",
                objective="请为客户主数据生成质量规则，并创建一个同步任务执行",
                variables={
                    "datasourceId": "ds-002",
                    "businessGoal": "客户主数据完整性与手机号格式校验",
                    "createTask": True,
                    "sessionId": "session-metadata",
                },
            )
        )

        self.assertIn("invoke_model_intent", plan.state_trace)
        self.assertIsNotNone(provider.last_request)
        self.assertEqual(
            (
                "datasource.metadata.read",
                "quality.rule.suggest",
                "task.create.draft",
                "task.draft.persist",
            ),
            tuple(tool.name for tool in provider.last_request.available_tools),
        )
        cache_plan = provider.last_request.provider_metadata["cachePlan"]
        self.assertTrue(cache_plan["enabled"])
        self.assertEqual("session_only", cache_plan["scope"])
        self.assertIn("session:session-metadata", cache_plan["namespace"])

    def test_explicit_false_disables_streaming_model_intent(self) -> None:
        """显式关闭 streaming 时必须调用 invoke，不能因 False 被 `or` 丢弃而误走 stream。"""

        provider = StreamingToolCallingModelProviderRegistry(chunks=())
        orchestrator = AgentOrchestrator(
            model_routes=ModelRouteRegistry(default_model_routes()),
            tool_planner=ToolPlanner(default_tool_registry()),
            model_providers=provider,
            skill_registry=AgentSkillRegistry(default_skill_registry()),
        )

        plan = orchestrator.plan(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                objective="请分析这个数据源的表结构",
                variables={"datasourceId": "ds-001", "streamModelIntent": False},
            )
        )

        self.assertEqual(1, len(provider.requests))
        self.assertIn("模型节点摘要：captured", plan.model_intent_summary)
        self.assertNotIn("未返回任何 chunk", plan.model_intent_summary)

    def test_model_tool_calls_are_governed_recorded_and_merged_into_plan(self) -> None:
        """模型返回 tool_calls 时，应先治理和记录事件，再合并进最终工具计划。

        这条测试覆盖从“Provider 返回结构化工具调用意图”到“AgentPlan 出现模型生成 ToolPlan”的主链路。
        注意测试并不期待工具被执行：当前阶段只负责让模型工具调用进入 DataSmart 治理语义，真实执行仍
        要交给 Java agent-runtime 做审批、审计、幂等和业务微服务调用。
        """

        provider = ToolCallingModelProviderRegistry(
            tool_calls=(
                ModelToolCall(
                    call_id="call_quality_from_model",
                    name="quality_rule_suggest",
                    arguments="{\"datasourceId\":\"ds-009\",\"businessGoal\":\"模型提出的客户主数据完整性校验\"}",
                ),
            )
        )
        orchestrator = AgentOrchestrator(
            model_routes=ModelRouteRegistry(default_model_routes()),
            tool_planner=ToolPlanner(default_tool_registry()),
            model_providers=provider,
            skill_registry=AgentSkillRegistry(default_skill_registry()),
        )

        plan = orchestrator.plan(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="owner-a",
                objective="请为客户主数据生成质量规则",
                variables={
                    "datasourceId": "ds-009",
                    "businessGoal": "规则式业务目标",
                },
            )
        )

        self.assertIn("govern_model_tool_calls", plan.state_trace)
        quality_plan = next(item for item in plan.tool_plans if item.tool_name == "quality.rule.suggest")
        self.assertEqual("model_tool_call", quality_plan.governance_hints["source"])
        self.assertEqual("call_quality_from_model", quality_plan.governance_hints["modelToolCallId"])
        self.assertEqual("quality-rule-suggest", quality_plan.governance_hints["planNodeId"])
        self.assertEqual(("datasource-metadata-read",), quality_plan.governance_hints["dependsOn"])
        self.assertEqual(("datasource.metadata.read",), quality_plan.governance_hints["dependsOnTools"])
        self.assertEqual("模型提出的客户主数据完整性校验", quality_plan.arguments["businessGoal"])
        self.assertIn("datasource.metadata.read", tuple(item.tool_name for item in plan.tool_plans))
        self.assertIn(
            AgentRuntimeEventType.MODEL_TOOL_CALL_PROPOSED,
            {event.event_type for event in plan.runtime_events},
        )
        self.assertIn(
            AgentRuntimeEventType.MODEL_TOOL_CALL_ACCEPTED,
            {event.event_type for event in plan.runtime_events},
        )
        planned_event = next(event for event in plan.runtime_events if event.event_type == AgentRuntimeEventType.TOOL_PLANNED)
        self.assertEqual(1, planned_event.attributes["modelGeneratedToolCount"])
        self.assertIn(
            AgentRuntimeEventType.TOOL_RESULT_FEEDBACK_BUILT,
            {event.event_type for event in plan.runtime_events},
        )
        self.assertIn(
            AgentRuntimeEventType.MODEL_SECOND_TURN_COMPLETED,
            {event.event_type for event in plan.runtime_events},
        )
        self.assertEqual("tool", provider.requests[-1].messages[-1].role)
        self.assertEqual("call_quality_from_model", provider.requests[-1].messages[-1].tool_call_id)
        self.assertEqual("none", provider.requests[-1].tool_choice)

    def test_streaming_tool_call_deltas_are_aggregated_governed_and_merged_into_plan(self) -> None:
        """流式 tool_call_deltas 应聚合后进入与非流式 tool_calls 相同的治理链路。"""

        provider = StreamingToolCallingModelProviderRegistry(
            chunks=(
                ModelInvocationChunk(
                    provider_name="stream-provider",
                    model_name="stream-model",
                    content_delta="正在生成质量规则工具调用。",
                    sequence=1,
                    tool_call_deltas=(
                        ModelToolCallDelta(
                            index=0,
                            call_id="call_stream_quality",
                            type="function",
                            name_delta="quality_",
                            arguments_delta="{\"datasourceId\":\"ds-stream\",",
                        ),
                    ),
                ),
                ModelInvocationChunk(
                    provider_name="stream-provider",
                    model_name="stream-model",
                    sequence=2,
                    finish_reason="tool_calls",
                    tool_call_deltas=(
                        ModelToolCallDelta(
                            index=0,
                            name_delta="rule_suggest",
                            arguments_delta="\"businessGoal\":\"流式客户主数据完整性\"}",
                        ),
                    ),
                ),
            )
        )
        orchestrator = AgentOrchestrator(
            model_routes=ModelRouteRegistry(default_model_routes()),
            tool_planner=ToolPlanner(default_tool_registry()),
            model_providers=provider,
            skill_registry=AgentSkillRegistry(default_skill_registry()),
        )

        plan = orchestrator.plan(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="owner-a",
                objective="请为客户主数据生成质量规则",
                variables={
                    "datasourceId": "ds-stream",
                    "businessGoal": "规则式业务目标",
                },
            )
        )

        self.assertIn("govern_model_tool_calls", plan.state_trace)
        self.assertIsNotNone(provider.last_request)
        quality_plan = next(item for item in plan.tool_plans if item.tool_name == "quality.rule.suggest")
        self.assertEqual("call_stream_quality", quality_plan.governance_hints["modelToolCallId"])
        self.assertEqual("流式客户主数据完整性", quality_plan.arguments["businessGoal"])
        self.assertIn("正在生成质量规则工具调用", plan.model_intent_summary)
        event_types = {event.event_type for event in plan.runtime_events}
        self.assertIn(AgentRuntimeEventType.MODEL_TOOL_CALL_PROPOSED, event_types)
        self.assertIn(AgentRuntimeEventType.MODEL_TOOL_CALL_ACCEPTED, event_types)
        self.assertIn(AgentRuntimeEventType.TOOL_RESULT_FEEDBACK_BUILT, event_types)
        self.assertIn(AgentRuntimeEventType.MODEL_SECOND_TURN_COMPLETED, event_types)
        self.assertEqual("tool", provider.requests[-1].messages[-1].role)
        self.assertEqual("call_stream_quality", provider.requests[-1].messages[-1].tool_call_id)

    def test_model_tool_call_budget_guard_blocks_excess_auto_tools_in_main_flow(self) -> None:
        """主编排链路应应用工具调用预算守卫，而不是只在独立组件测试中生效。

        这里让模型一次提出两个低风险同步工具。默认预算允许最多 3 个自动推进工具，因此测试使用
        AgentModelIntentNode 的默认策略不容易触发阻断；为避免把策略调得过于苛刻影响正常用例，
        本测试通过重复返回 4 个只读元数据工具调用，验证尾部候选会被预算事件阻断。
        """

        provider = ToolCallingModelProviderRegistry(
            tool_calls=tuple(
                ModelToolCall(
                    call_id=f"call_metadata_{index}",
                    name="datasource_metadata_read",
                    arguments=f'{{"datasourceId":"ds-{index}"}}',
                )
                for index in range(4)
            )
        )
        orchestrator = AgentOrchestrator(
            model_routes=ModelRouteRegistry(default_model_routes()),
            tool_planner=ToolPlanner(default_tool_registry()),
            model_providers=provider,
            skill_registry=AgentSkillRegistry(default_skill_registry()),
        )

        plan = orchestrator.plan(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="owner-a",
                objective="请连续读取多个数据源元数据",
                variables={"datasourceId": "ds-rule"},
            )
        )

        budget_event = next(
            event
            for event in plan.runtime_events
            if event.stage == "guard_model_tool_call_budget"
        )

        self.assertEqual(AgentRuntimeEventType.MODEL_TOOL_CALL_BUDGET_GUARDED, budget_event.event_type)
        self.assertEqual(3, budget_event.attributes["acceptedCountAfterGuard"])
        self.assertIn(
            "MODEL_TOOL_CALL_BUDGET_AUTO_EXECUTABLE_COUNT_EXCEEDED",
            budget_event.attributes["budgetIssueCodes"],
        )
        accepted_model_call_ids = {
            plan.governance_hints.get("modelToolCallId")
            for plan in plan.tool_plans
            if plan.governance_hints.get("source") == "model_tool_call"
        }
        self.assertNotIn("call_metadata_3", accepted_model_call_ids)

    def test_model_route_uses_new_generation_placeholder_not_qwen2(self) -> None:
        orchestrator = build_default_orchestrator()

        plan = orchestrator.plan(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                objective="解释当前数据治理方案",
                preferred_workload=WorkloadType.GOVERNANCE_QA,
            )
        )

        self.assertIn("Qwen3.5", plan.selected_route.model_name)
        self.assertNotIn("Qwen2", plan.selected_route.model_name)

    def test_public_model_exchange_distinguishes_model_text_from_rule_tool_fallback(self) -> None:
        """模型只回答文本时，最终规则工具必须明确标记为系统兜底。"""

        provider = CapturingModelProviderRegistry()
        orchestrator = AgentOrchestrator(
            model_routes=ModelRouteRegistry(default_model_routes()),
            tool_planner=ToolPlanner(default_tool_registry()),
            model_providers=provider,
            skill_registry=AgentSkillRegistry(default_skill_registry()),
        )

        plan = orchestrator.plan(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="owner-a",
                objective="请创建一个全量数据同步任务",
                variables={"streamModelIntent": False, "sessionId": "session-public-exchange"},
            )
        )

        interaction = plan.model_interaction_summary
        self.assertEqual("请创建一个全量数据同步任务", interaction["request"]["objective"])
        self.assertEqual("captured", interaction["response"]["content"])
        self.assertEqual(0, interaction["response"]["toolCallCount"])
        self.assertEqual("SYSTEM_RULE_FALLBACK", interaction["planning"]["toolSelectionSource"])
        self.assertEqual(0, interaction["planning"]["modelGeneratedToolCount"])
        self.assertIn("task.create.draft", interaction["planning"]["ruleGeneratedToolNames"])
        planned_event = next(
            event for event in plan.runtime_events if event.event_type == AgentRuntimeEventType.TOOL_PLANNED
        )
        self.assertEqual("SYSTEM_RULE_FALLBACK", planned_event.attributes["toolSelectionSource"])


class CapturingModelProviderRegistry:
    """测试用模型 Provider 注册表。

    它不访问真实模型，只记录 AgentOrchestrator 传入的 `ModelInvocationRequest`。相比在测试里解析
    dry-run 文本，直接检查 request 更能保护编排层契约：我们关心的是候选工具是否被传给模型节点。
    """

    def __init__(self) -> None:
        self.last_request = None
        self.requests = []

    def invoke(self, request):
        self.last_request = request
        self.requests.append(request)
        return ModelInvocationResult(
            provider_name=request.route.provider_name,
            model_name=request.route.model_name,
            content="captured",
        )


class ToolCallingModelProviderRegistry(CapturingModelProviderRegistry):
    """测试用工具调用 Provider。

    它模拟 OpenAI-compatible Provider 已经解析出 `message.tool_calls` 的结果。这样测试可以专注验证
    AgentOrchestrator 是否把模型工具调用接到治理链路，而不需要构造 HTTP/SSE 原始协议。
    """

    def __init__(self, tool_calls: tuple[ModelToolCall, ...]) -> None:
        super().__init__()
        self._tool_calls = tool_calls

    def invoke(self, request):
        self.last_request = request
        self.requests.append(request)
        return ModelInvocationResult(
            provider_name=request.route.provider_name,
            model_name=request.route.model_name,
            content="captured with tool calls",
            tool_calls=self._tool_calls,
        )


class StreamingToolCallingModelProviderRegistry(CapturingModelProviderRegistry):
    """测试用流式工具调用 Provider。

    它模拟真实 OpenAI-compatible SSE 场景：工具名和 arguments 会分散在多个 chunk 的
    `tool_call_deltas` 中。测试主链只关心聚合后的治理结果，不需要依赖真实网络流。
    """

    def __init__(self, chunks: tuple[ModelInvocationChunk, ...]) -> None:
        super().__init__()
        self._chunks = chunks

    def stream(self, request):
        self.last_request = request
        self.requests.append(request)
        yield from self._chunks


if __name__ == "__main__":
    unittest.main()

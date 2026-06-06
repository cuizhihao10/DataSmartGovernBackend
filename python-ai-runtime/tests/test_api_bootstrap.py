import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api import (
    build_default_orchestrator,
    build_event_replay_response,
    build_plan_response,
    load_skill_registry,
    load_tool_registry,
)
from datasmart_ai_runtime.domain.context import ContextSensitivityLevel, ContextSourceType
from datasmart_ai_runtime.domain.contracts import (
    AgentRequest,
    ToolDefinition,
    ToolExecutionMode,
    ToolRiskLevel,
)
from datasmart_ai_runtime.domain.event_transport import (
    RuntimeEventAckMode,
    RuntimeEventChannel,
    RuntimeEventDeliveryMode,
    RuntimeEventSubscriptionRequest,
)
from datasmart_ai_runtime.domain.events import (
    AgentRuntimeEvent,
    AgentRuntimeEventSeverity,
    AgentRuntimeEventType,
)
from datasmart_ai_runtime.domain.intent import GovernanceDomain
from datasmart_ai_runtime.domain.memory import AgentMemoryType
from datasmart_ai_runtime.domain.model_gateway import ModelGatewayBudgetPolicy
from datasmart_ai_runtime.domain.skills import AgentSkillDescriptor
from datasmart_ai_runtime.config import default_model_routes
from datasmart_ai_runtime.services.model_gateway import InMemoryModelBudgetLedger, ModelGatewayGovernanceService
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.runtime_events.runtime_event_store import InMemoryRuntimeEventStore
from datasmart_ai_runtime.services.skill_registry_client import SkillRegistryClientError
from datasmart_ai_runtime.services.tool_registry_client import ToolRegistryClientError


class FakeToolRegistryClient:
    def __init__(
        self,
        tools: tuple[ToolDefinition, ...] | None = None,
        should_fail: bool = False,
        supports_descriptors: bool = False,
    ) -> None:
        self._tools = tools or ()
        self._should_fail = should_fail
        self._supports_descriptors = supports_descriptors
        self.descriptor_called = False

    def list_tools(self, enabled_only: bool = True, trace_id: str | None = None) -> tuple[ToolDefinition, ...]:
        if self._should_fail:
            raise ToolRegistryClientError("模拟 Java 工具目录不可用")
        return self._tools

    def list_tool_descriptors(self, enabled_only: bool = True, trace_id: str | None = None) -> tuple[ToolDefinition, ...]:
        self.descriptor_called = True
        if not self._supports_descriptors:
            return self.list_tools(enabled_only=enabled_only, trace_id=trace_id)
        if self._should_fail:
            raise ToolRegistryClientError("模拟 Java 工具描述符不可用")
        return self._tools


class FakeSkillRegistryClient:
    def __init__(
        self,
        skills: tuple[AgentSkillDescriptor, ...] | None = None,
        should_fail: bool = False,
    ) -> None:
        self._skills = skills or ()
        self._should_fail = should_fail
        self.descriptor_called = False

    def list_skill_descriptors(self, enabled_only: bool = True, trace_id: str | None = None) -> tuple[AgentSkillDescriptor, ...]:
        self.descriptor_called = True
        if self._should_fail:
            raise SkillRegistryClientError("模拟 Java Skill 描述符不可用")
        return self._skills


class FakeRuntimeEventPublisher:
    """记录 API 层是否把规划事件交给异步发布器。"""

    def __init__(self) -> None:
        self.published_batches: list[int] = []

    def publish(self, events) -> int:
        self.published_batches.append(len(events))
        return len(events)


class FakeReplaySource:
    source_name = "java-agent-runtime-event-projection"

    def replay(self, request: RuntimeEventSubscriptionRequest) -> tuple[AgentRuntimeEvent, ...]:
        return (
            AgentRuntimeEvent(
                event_type=AgentRuntimeEventType.TOOL_EXECUTION_STATE_CHANGED,
                stage="tool_completed",
                message="Java 控制面工具事件",
                severity=AgentRuntimeEventSeverity.INFO,
                tenant_id=request.tenant_id,
                project_id=request.project_id,
                actor_id=request.actor_id,
                run_id=request.run_id,
                session_id=request.session_id,
                sequence=3,
                attributes={"toolCode": "datasource.metadata.read"},
            ),
        )


class ApiBootstrapTest(unittest.TestCase):
    def test_build_orchestrator_can_use_injected_remote_tool_registry(self) -> None:
        remote_tools = (
            ToolDefinition(
                name="task.create.draft",
                description="来自 Java 工具目录的任务草稿创建工具",
                risk_level=ToolRiskLevel.HIGH,
                execution_mode=ToolExecutionMode.APPROVAL_REQUIRED,
                requires_approval=True,
                target_service="task-management",
            ),
        )

        orchestrator = build_default_orchestrator(
            prefer_remote_tools=True,
            tool_registry_client=FakeToolRegistryClient(remote_tools),
        )
        plan = orchestrator.plan(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="owner-a",
                objective="请创建任务",
                variables={"createTask": True},
            )
        )

        self.assertTrue(plan.requires_human_approval)
        self.assertEqual(("task.create.draft",), tuple(item.tool_name for item in plan.tool_plans))

    def test_remote_tool_registry_failure_can_fallback_to_default_tools(self) -> None:
        tools = load_tool_registry(
            prefer_remote_tools=True,
            allow_remote_fallback=True,
            tool_registry_client=FakeToolRegistryClient(should_fail=True),
        )

        self.assertGreaterEqual(len(tools), 1)
        self.assertIn("datasource.metadata.read", {tool.name for tool in tools})

    def test_load_tool_registry_prefers_descriptor_contract_when_client_supports_it(self) -> None:
        client = FakeToolRegistryClient(
            tools=(
                ToolDefinition(
                    name="datasource.metadata.read",
                    description="来自 Java descriptor 的数据源元数据读取工具",
                    risk_level=ToolRiskLevel.LOW,
                    execution_mode=ToolExecutionMode.SYNC,
                    protocol_hint="MCP_STYLE",
                    memory_write_policy="semantic",
                    cache_policy="project_safe",
                ),
            ),
            supports_descriptors=True,
        )

        tools = load_tool_registry(prefer_remote_tools=True, tool_registry_client=client)

        self.assertTrue(client.descriptor_called)
        self.assertEqual("MCP_STYLE", tools[0].protocol_hint)
        self.assertEqual("project_safe", tools[0].cache_policy)

    def test_load_skill_registry_prefers_java_descriptor_contract(self) -> None:
        client = FakeSkillRegistryClient(
            skills=(
                AgentSkillDescriptor(
                    skill_code="quality.rule.design",
                    display_name="质量规则设计 Skill",
                    description="来自 Java Skill descriptor 的能力包",
                    domain=GovernanceDomain.DATA_QUALITY,
                    required_tools=("quality.rule.suggest",),
                    memory_dependencies=(AgentMemoryType.SEMANTIC, AgentMemoryType.EPISODIC),
                    approval_policy="DRAFT_REVIEW",
                ),
            )
        )

        skills = load_skill_registry(prefer_remote_skills=True, skill_registry_client=client)

        self.assertTrue(client.descriptor_called)
        self.assertEqual("quality.rule.design", skills[0].skill_code)
        self.assertEqual((AgentMemoryType.SEMANTIC, AgentMemoryType.EPISODIC), skills[0].memory_dependencies)

    def test_remote_skill_registry_failure_can_fallback_to_default_skills(self) -> None:
        skills = load_skill_registry(
            prefer_remote_skills=True,
            allow_remote_fallback=True,
            skill_registry_client=FakeSkillRegistryClient(should_fail=True),
        )

        self.assertGreaterEqual(len(skills), 1)
        self.assertIn("quality.rule.design", {skill.skill_code for skill in skills})

    def test_build_orchestrator_can_use_injected_remote_skill_registry(self) -> None:
        remote_skill = AgentSkillDescriptor(
            skill_code="remote.datasource.skill",
            display_name="远程数据源 Skill",
            description="来自 Java Skill descriptor 的数据源能力包",
            domain=GovernanceDomain.DATASOURCE,
            required_tools=("datasource.metadata.read",),
            memory_dependencies=(AgentMemoryType.SEMANTIC,),
            trigger_keywords=("数据源",),
        )

        orchestrator = build_default_orchestrator(
            prefer_remote_skills=True,
            skill_registry_client=FakeSkillRegistryClient((remote_skill,)),
        )
        plan = orchestrator.plan(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                objective="请分析这个数据源结构",
                variables={"datasourceId": "ds-001"},
            )
        )

        self.assertEqual(("remote.datasource.skill",), tuple(item.skill_code for item in plan.skill_plan.selected_skills))

    def test_default_orchestrator_uses_hybrid_context_budget(self) -> None:
        orchestrator = build_default_orchestrator(context_max_tokens=1)

        plan = orchestrator.plan(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                objective="请先分析这个 MySQL 数据源的表结构",
                variables={"datasourceId": "ds-001"},
            )
        )

        self.assertEqual(1, len(plan.context_blocks))
        self.assertEqual(ContextSourceType.USER_OBJECTIVE, plan.context_blocks[0].source_type)

    def test_default_orchestrator_can_restrict_context_sensitivity(self) -> None:
        orchestrator = build_default_orchestrator(
            allowed_context_sensitivity_levels=(ContextSensitivityLevel.PUBLIC,),
        )

        plan = orchestrator.plan(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                objective="解释当前权限边界",
            )
        )

        self.assertEqual((), plan.context_blocks)

    def test_plan_response_wraps_runtime_events_in_http_snapshot_envelope(self) -> None:
        orchestrator = build_default_orchestrator()
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="请先分析这个 MySQL 数据源的表结构",
            variables={"datasourceId": "ds-001"},
        )

        response = build_plan_response(request, orchestrator)

        self.assertIn("plan", response)
        self.assertIn("eventEnvelope", response)
        self.assertEqual(response["plan"]["request_id"], response["eventEnvelope"]["request_id"])
        self.assertEqual(RuntimeEventChannel.HTTP_RESPONSE, response["eventEnvelope"]["channel"])
        self.assertEqual(RuntimeEventDeliveryMode.SNAPSHOT, response["eventEnvelope"]["delivery_mode"])
        self.assertEqual(RuntimeEventAckMode.NONE, response["eventEnvelope"]["ack_mode"])
        self.assertEqual(1, response["eventEnvelope"]["sequence_from"])
        self.assertEqual(
            len(response["plan"]["runtime_events"]),
            response["eventEnvelope"]["sequence_to"],
        )
        self.assertEqual(
            response["plan"]["runtime_events"],
            response["eventEnvelope"]["events"],
        )
        self.assertIn("modelGatewayGovernance", response)
        self.assertTrue(response["modelGatewayGovernance"]["available"])
        self.assertTrue(response["modelGatewayGovernance"]["budgetAllowed"])
        self.assertIsNotNone(response["modelGatewayGovernance"]["selectedModel"])

    def test_plan_response_exposes_tool_execution_readiness_without_sensitive_values(self) -> None:
        """Agent plan 响应应展示工具执行准备度，但不能泄露工具参数真实值。"""

        orchestrator = build_default_orchestrator()
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="请基于客户主数据生成质量规则并创建任务",
            variables={
                "datasourceId": "ds-sensitive-001",
                "businessGoal": "手机号唯一性",
                "createTask": True,
            },
        )

        response = build_plan_response(request, orchestrator)

        readiness = response["toolExecutionReadiness"]
        self.assertEqual("TOOL_EXECUTION_READINESS", readiness["snapshotType"])
        self.assertEqual("LOW_SENSITIVE_METADATA_ONLY", readiness["payloadPolicy"])
        self.assertGreaterEqual(readiness["totalCount"], 2)
        self.assertIn("CREATE_OR_WAIT_APPROVAL", readiness["nextActions"])
        self.assertIn("SHOW_DRAFT_FOR_REVIEW", readiness["nextActions"])
        decisions_by_tool = {item["toolName"]: item["decision"] for item in readiness["items"]}
        self.assertEqual("draft_only", decisions_by_tool["quality.rule.suggest"])
        self.assertEqual("waiting_approval", decisions_by_tool["task.create.draft"])
        quality_item = next(item for item in readiness["items"] if item["toolName"] == "quality.rule.suggest")
        self.assertEqual("data-quality", quality_item["targetService"])
        self.assertIn("datasourceId", quality_item["argumentFieldNames"])
        self.assertIn("datasourceId", quality_item["sensitiveArgumentNames"])
        self.assertNotIn("ds-sensitive-001", str(readiness))
        self.assertNotIn("手机号唯一性", str(readiness))

    def test_plan_response_records_tool_execution_readiness_event(self) -> None:
        """工具执行准备度应进入 runtime event，支持后续 WebSocket replay 和 Java projection。"""

        orchestrator = build_default_orchestrator()
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="请分析这个 MySQL 数据源的表结构",
            variables={"datasourceId": "ds-sensitive-002"},
        )

        response = build_plan_response(request, orchestrator)
        events = response["plan"]["runtime_events"]
        readiness_events = [
            event
            for event in events
            if event["event_type"] == AgentRuntimeEventType.TOOL_EXECUTION_READINESS_RECORDED
        ]

        self.assertEqual(1, len(readiness_events))
        event = readiness_events[0]
        self.assertEqual("record_tool_execution_readiness", event["stage"])
        self.assertEqual("TOOL_EXECUTION_READINESS", event["attributes"]["snapshotType"])
        self.assertEqual(1, event["attributes"]["executableCount"])
        self.assertEqual(("datasource.metadata.read",), event["attributes"]["toolNames"])
        self.assertNotIn("ds-sensitive-002", str(event["attributes"]))

    def test_plan_response_uses_trusted_tool_readiness_policy_snapshot(self) -> None:
        """`/agent/plans` 应消费受控 readiness 策略快照，并把低敏策略来源写入响应和事件。"""

        orchestrator = build_default_orchestrator()
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="auditor-a",
            objective="请分析这个 MySQL 数据源的表结构",
            variables={
                "datasourceId": "ds-sensitive-004",
                "trustedControlPlane": {
                    "toolExecutionReadinessPolicy": {
                        "policyVersion": "perm-tool-readiness-v4",
                        "actorRole": "AUDITOR",
                        "tenantPlanCode": "TRIAL",
                        "workspaceRiskLevel": "HIGH",
                        "workerBacklogLevel": "CRITICAL",
                        "maxAutoSyncTools": 5,
                        "maxAsyncTools": 4,
                    }
                },
            },
        )

        response = build_plan_response(request, orchestrator)

        policy = response["toolExecutionReadinessPolicy"]
        readiness = response["toolExecutionReadiness"]
        event = next(
            event
            for event in response["plan"]["runtime_events"]
            if event["event_type"] == AgentRuntimeEventType.TOOL_EXECUTION_READINESS_RECORDED
        )
        self.assertEqual("trusted-control-plane", policy["source"])
        self.assertEqual("perm-tool-readiness-v4", policy["policyVersion"])
        self.assertEqual(0, policy["maxAutoSyncTools"])
        self.assertEqual(0, policy["maxAsyncTools"])
        self.assertIn("WORKER_BACKLOG_BLOCKS_TOOL_BUDGET", policy["influenceCodes"])
        self.assertEqual(1, readiness["throttledCount"])
        self.assertEqual("trusted-control-plane", readiness["policy"]["source"])
        self.assertEqual("trusted-control-plane", event["attributes"]["policySource"])
        self.assertEqual("perm-tool-readiness-v4", event["attributes"]["policyVersion"])
        self.assertNotIn("ds-sensitive-004", str(policy))
        self.assertNotIn("ds-sensitive-004", str(event["attributes"]))

    def test_event_replay_response_filters_events_by_subscription(self) -> None:
        orchestrator = build_default_orchestrator()
        plan = orchestrator.plan(
            AgentRequest(
                tenant_id="tenant-a",
                project_id="project-a",
                actor_id="user-a",
                objective="请先分析这个 MySQL 数据源的表结构",
                variables={"datasourceId": "ds-001", "sessionId": "session-a"},
            )
        )
        subscription = RuntimeEventSubscriptionRequest(
            client_id="client-a",
            session_id="session-a",
            after_sequence=1,
            event_types=(AgentRuntimeEventType.TOOL_PLANNED,),
        )

        response = build_event_replay_response(subscription, plan.runtime_events)

        envelope = response["eventEnvelope"]
        self.assertEqual(RuntimeEventChannel.WEBSOCKET, envelope["channel"])
        self.assertEqual(RuntimeEventDeliveryMode.REPLAY, envelope["delivery_mode"])
        self.assertEqual(RuntimeEventAckMode.CLIENT_ACK, envelope["ack_mode"])
        self.assertEqual(1, envelope["replay_from_sequence"])
        self.assertEqual((AgentRuntimeEventType.TOOL_PLANNED,), tuple(event["event_type"] for event in envelope["events"]))
        self.assertTrue(all(event["session_id"] == "session-a" for event in envelope["events"]))

    def test_event_replay_response_exposes_external_source_cursors(self) -> None:
        subscription = RuntimeEventSubscriptionRequest(
            client_id="client-a",
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            roles=("operator",),
            session_id="session-a",
            run_id="run-a",
            after_sequence=3,
            source_cursors={"java-agent-runtime-event-projection": 2},
        )

        response = build_event_replay_response(
            subscription,
            external_replay_sources=(FakeReplaySource(),),
        )

        envelope = response["eventEnvelope"]
        self.assertEqual(
            3,
            envelope["attributes"]["sourceCursors"]["java-agent-runtime-event-projection"],
        )
        self.assertEqual(
            3,
            envelope["events"][0]["attributes"]["_datasmartOriginalSequence"],
        )
        self.assertTrue(envelope["events"][0]["attributes"]["_datasmartSyntheticReplaySequence"])

    def test_plan_response_can_store_events_for_later_replay(self) -> None:
        orchestrator = build_default_orchestrator()
        store = InMemoryRuntimeEventStore()
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="请先分析这个 MySQL 数据源的表结构",
            variables={"datasourceId": "ds-001", "sessionId": "session-store"},
        )

        build_plan_response(request, orchestrator, event_store=store)
        response = build_event_replay_response(
            RuntimeEventSubscriptionRequest(
                client_id="client-a",
                session_id="session-store",
                after_sequence=0,
            ),
            event_store=store,
        )

        envelope = response["eventEnvelope"]
        self.assertTrue(envelope["events"])
        self.assertEqual("session-store", envelope["session_id"])
        self.assertEqual(1, envelope["sequence_from"])

    def test_plan_response_can_publish_runtime_events_to_async_bus(self) -> None:
        orchestrator = build_default_orchestrator()
        publisher = FakeRuntimeEventPublisher()
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="请先分析这个 MySQL 数据源的表结构",
            variables={"datasourceId": "ds-001", "sessionId": "session-publisher"},
        )

        response = build_plan_response(request, orchestrator, event_publisher=publisher)

        self.assertEqual([len(response["plan"]["runtime_events"])], publisher.published_batches)
        self.assertGreater(publisher.published_batches[0], 0)

    def test_plan_response_exposes_budget_blocked_model_gateway_summary(self) -> None:
        routes = ModelRouteRegistry(default_model_routes())
        budget_ledger = InMemoryModelBudgetLedger(
            (ModelGatewayBudgetPolicy(tenant_id="tenant-a", project_id="project-a", monthly_token_budget=1),)
        )
        model_gateway = ModelGatewayGovernanceService(routes, budget_ledger=budget_ledger)
        orchestrator = build_default_orchestrator(model_gateway=model_gateway)
        request = AgentRequest(
            tenant_id="tenant-a",
            project_id="project-a",
            actor_id="user-a",
            objective="请先分析这个 MySQL 数据源的表结构",
            variables={"datasourceId": "ds-001"},
        )

        response = build_plan_response(request, orchestrator)

        governance = response["modelGatewayGovernance"]
        self.assertFalse(governance["available"])
        self.assertFalse(governance["budgetAllowed"])
        self.assertIsNone(governance["selectedModel"])
        self.assertTrue(any("预算" in action for action in governance["recommendedActions"]))


if __name__ == "__main__":
    unittest.main()

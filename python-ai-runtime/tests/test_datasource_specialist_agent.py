from __future__ import annotations

import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.agent_gateway.session_models import AgentSessionRole
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import (
    SpecialistDelegationScope,
    SpecialistTurnRequest,
    SpecialistTurnStatus,
)
from datasmart_ai_runtime.services.multi_agent.specialists.datasource_agent import (
    DatasourceCandidate,
    DatasourceDirection,
    DatasourceDiscoveryRequest,
    DatasourceDiscoveryResult,
    DatasourceDisambiguationDecision,
    DatasourceDisambiguationRequest,
    DatasourceSpecialistAgent,
)


class _RecordingDiscoveryTool:
    """按方向返回固定候选的只读工具替身，并记录 Agent 传入的授权范围。"""

    def __init__(self, candidates_by_direction: dict[DatasourceDirection, tuple[DatasourceCandidate, ...]]) -> None:
        self._candidates_by_direction = candidates_by_direction
        self.requests: list[DatasourceDiscoveryRequest] = []

    def discover(self, request: DatasourceDiscoveryRequest) -> DatasourceDiscoveryResult:
        """模拟下游已完成用户/租户/项目授权过滤的数据源检索。"""

        self.requests.append(request)
        return DatasourceDiscoveryResult(
            candidates=self._candidates_by_direction.get(request.direction, ()),
            evidence_reference=f"audit://datasource-discovery/{request.direction.value.lower()}",
        )


class _RecordingDisambiguationModel:
    """返回固定结构化决策的模型替身，用于验证候选集合安全边界。"""

    def __init__(self, decisions: dict[DatasourceDirection, DatasourceDisambiguationDecision]) -> None:
        self._decisions = decisions
        self.requests: list[DatasourceDisambiguationRequest] = []

    def disambiguate(self, request: DatasourceDisambiguationRequest) -> DatasourceDisambiguationDecision:
        """记录模型真正看见的字段，确保测试可断言没有敏感连接配置。"""

        self.requests.append(request)
        return self._decisions[request.direction]


def _candidate(
    datasource_id: str,
    name: str,
    connector_type: str,
    direction: DatasourceDirection,
) -> DatasourceCandidate:
    """创建只支持指定同步方向的低敏候选，避免测试样板掩盖业务意图。"""

    return DatasourceCandidate(
        datasource_id=datasource_id,
        name=name,
        connector_type=connector_type,
        supported_directions=(direction,),
        display_status="可用",
    )


def _request(
    *,
    allowed_tools: tuple[str, ...] = (DatasourceSpecialistAgent.DISCOVERY_TOOL_NAME,),
    context_summary: dict[str, object] | None = None,
) -> SpecialistTurnRequest:
    """构造同时携带双主体审计范围和结构化数据源提示的专业 turn。"""

    return SpecialistTurnRequest(
        turn_id="turn-datasource-1",
        session_id="session-1",
        run_id="run-1",
        role=AgentSessionRole.DATASOURCE_AGENT,
        objective="在当前用户授权范围内确定同步任务的数据源",
        scope=SpecialistDelegationScope(
            tenant_id="tenant-10",
            application_id="datasmart-govern",
            project_id="project-101",
            actor_id="ordinary-user",
            delegation_id="delegation-1",
            allowed_tool_names=allowed_tools,
        ),
        context_summary=context_summary
        or {
            "source": {"connectorType": "mysql", "datasourceName": "订单库"},
            "target": {"connectorType": "postgresql", "datasourceName": "治理库"},
        },
    )


class DatasourceSpecialistAgentTest(unittest.TestCase):
    """验证第一批真实 DATASOURCE_AGENT 的消歧、权限和低敏输出边界。"""

    def test_unique_authorized_candidates_are_selected_without_model(self) -> None:
        """源端和目标端各只有一个候选时应直接完成，并把授权主体完整传给工具。"""

        tool = _RecordingDiscoveryTool(
            {
                DatasourceDirection.SOURCE: (
                    _candidate("11", "订单库", "MYSQL", DatasourceDirection.SOURCE),
                ),
                DatasourceDirection.TARGET: (
                    _candidate("23", "治理库", "POSTGRESQL", DatasourceDirection.TARGET),
                ),
            }
        )
        events: list[dict[str, object]] = []

        result = DatasourceSpecialistAgent(tool).execute(_request(), events.append)

        self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
        self.assertEqual("11", result.structured_output["sourceDatasourceId"])
        self.assertEqual("23", result.structured_output["targetDatasourceId"])
        self.assertFalse(result.model_invocation_summary["invoked"])
        self.assertEqual(2, len(tool.requests))
        self.assertEqual("tenant-10", tool.requests[0].tenant_id)
        self.assertEqual("project-101", tool.requests[0].project_id)
        self.assertEqual("ordinary-user", tool.requests[0].actor_id)
        self.assertTrue(result.structured_output["readOnly"])
        self.assertFalse(result.structured_output["writeOperationsPerformed"])
        self.assertEqual("SPECIALIST_TURN_COMPLETED", events[-1]["eventType"])

    def test_multiple_candidates_without_clear_model_decision_wait_for_user(self) -> None:
        """模型认为证据不足时必须展示所有低敏候选，不得选择第一项或最后一项。"""

        source_candidates = (
            _candidate("11", "MySQL 订单库 A", "MYSQL", DatasourceDirection.SOURCE),
            _candidate("12", "MySQL 订单库 B", "MYSQL", DatasourceDirection.SOURCE),
        )
        tool = _RecordingDiscoveryTool({DatasourceDirection.SOURCE: source_candidates})
        model = _RecordingDisambiguationModel(
            {
                DatasourceDirection.SOURCE: DatasourceDisambiguationDecision(
                    clear=False,
                    public_reason="两个候选名称都符合描述，无法可靠判断。",
                    model_name="test-model",
                    model_invocation_id="model-call-1",
                )
            }
        )

        result = DatasourceSpecialistAgent(tool, model).execute(
            _request(
                context_summary={
                    "requestedDirections": ["SOURCE"],
                    "source": {"connectorType": "MYSQL", "datasourceName": "MySQL 订单库"},
                }
            )
        )

        self.assertEqual(SpecialistTurnStatus.WAITING_FOR_INPUT, result.status)
        self.assertIsNone(result.structured_output["sourceDatasourceId"])
        self.assertEqual(("sourceDatasourceId",), result.required_input_fields)
        candidates = result.structured_output["resolutions"]["source"]["candidates"]
        self.assertEqual(("11", "12"), tuple(item["datasourceId"] for item in candidates))
        self.assertEqual("REJECTED_OR_UNCLEAR", result.model_invocation_summary["selections"][0]["status"])

    def test_model_may_select_only_an_id_returned_by_discovery_tool(self) -> None:
        """模型幻觉出不存在的 ID 时应拒绝采用并转人工确认，而不是污染任务草案。"""

        source_candidates = (
            _candidate("11", "订单库 A", "MYSQL", DatasourceDirection.SOURCE),
            _candidate("12", "订单库 B", "MYSQL", DatasourceDirection.SOURCE),
        )
        tool = _RecordingDiscoveryTool({DatasourceDirection.SOURCE: source_candidates})
        model = _RecordingDisambiguationModel(
            {
                DatasourceDirection.SOURCE: DatasourceDisambiguationDecision(
                    clear=True,
                    selected_datasource_id="999-not-authorized",
                    public_reason="模型错误地生成了候选之外的 ID。",
                    model_name="test-model",
                    model_invocation_id="model-call-invalid",
                )
            }
        )

        result = DatasourceSpecialistAgent(tool, model).execute(
            _request(
                context_summary={
                    "requestedDirections": ["SOURCE"],
                    "source": {"connectorType": "MYSQL"},
                }
            )
        )

        self.assertEqual(SpecialistTurnStatus.WAITING_FOR_INPUT, result.status)
        self.assertIsNone(result.structured_output["sourceDatasourceId"])
        self.assertNotIn("999-not-authorized", str(result.structured_output))
        self.assertIsNone(result.model_invocation_summary["selections"][0]["selectedDatasourceId"])
        self.assertIn("不属于授权候选集合", result.structured_output["resolutions"]["source"]["message"])

    def test_missing_tool_allowlist_fails_closed_without_calling_tool_or_model(self) -> None:
        """未授权工具时必须在第一步失败，不能把“用户可看列表”误当成 Agent 可调用权限。"""

        tool = _RecordingDiscoveryTool(
            {
                DatasourceDirection.SOURCE: (
                    _candidate("11", "订单库", "MYSQL", DatasourceDirection.SOURCE),
                )
            }
        )
        model = _RecordingDisambiguationModel({})
        events: list[dict[str, object]] = []

        result = DatasourceSpecialistAgent(tool, model).execute(
            _request(allowed_tools=("task.read",)),
            events.append,
        )

        self.assertEqual(SpecialistTurnStatus.FAILED, result.status)
        self.assertEqual("SPECIALIST_TOOL_NOT_ALLOWED", result.error_code)
        self.assertEqual([], tool.requests)
        self.assertEqual([], model.requests)
        self.assertEqual("DENIED", result.tool_activities[0].status)
        self.assertIn("SPECIALIST_TOOL_DENIED", tuple(event["eventType"] for event in events))

    def test_explicit_authorized_id_is_verified_without_model(self) -> None:
        """用户已明确给出 ID 时仍需由工具验证授权，但验证成功后不再浪费模型调用。"""

        tool = _RecordingDiscoveryTool(
            {
                DatasourceDirection.SOURCE: (
                    _candidate("12", "订单库 B", "MYSQL", DatasourceDirection.SOURCE),
                    _candidate("11", "订单库 A", "MYSQL", DatasourceDirection.SOURCE),
                )
            }
        )

        result = DatasourceSpecialistAgent(tool).execute(
            _request(
                context_summary={
                    "requestedDirections": ["SOURCE"],
                    "sourceDatasourceId": "11",
                    "sourceConnectorType": "MYSQL",
                }
            )
        )

        self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
        self.assertEqual("11", result.structured_output["sourceDatasourceId"])
        self.assertEqual(
            "EXPLICIT_AUTHORIZED_ID",
            result.structured_output["resolutions"]["source"]["selectionMethod"],
        )
        self.assertFalse(result.model_invocation_summary["invoked"])

    def test_clear_model_selection_of_existing_candidate_completes(self) -> None:
        """多候选时模型只有明确选择工具候选中的 ID，Agent 才允许完成消歧。"""

        tool = _RecordingDiscoveryTool(
            {
                DatasourceDirection.TARGET: (
                    _candidate("21", "治理测试库", "POSTGRESQL", DatasourceDirection.TARGET),
                    _candidate("22", "治理生产库", "POSTGRESQL", DatasourceDirection.TARGET),
                )
            }
        )
        model = _RecordingDisambiguationModel(
            {
                DatasourceDirection.TARGET: DatasourceDisambiguationDecision(
                    clear=True,
                    selected_datasource_id="21",
                    public_reason="用户描述明确包含测试库。",
                    model_name="test-model",
                    model_invocation_id="model-call-2",
                )
            }
        )

        result = DatasourceSpecialistAgent(tool, model).execute(
            _request(
                context_summary={
                    "requestedDirections": ["TARGET"],
                    "target": {"connectorType": "POSTGRESQL", "datasourceName": "治理测试库"},
                }
            )
        )

        self.assertEqual(SpecialistTurnStatus.COMPLETED, result.status)
        self.assertEqual("21", result.structured_output["targetDatasourceId"])
        self.assertEqual(
            "MODEL_CONFIRMED_AUTHORIZED_CANDIDATE",
            result.structured_output["resolutions"]["target"]["selectionMethod"],
        )
        self.assertEqual(1, len(model.requests))
        self.assertEqual(
            {"datasourceId", "name", "connectorType", "supportedDirections", "displayStatus"},
            set(model.requests[0].candidate_summaries[0]),
        )

    def test_no_candidate_waits_for_input_instead_of_inventing_datasource(self) -> None:
        """授权范围内没有候选时应要求补充/授权数据源，不得让模型凭名称生成 ID。"""

        tool = _RecordingDiscoveryTool({DatasourceDirection.SOURCE: ()})
        model = _RecordingDisambiguationModel({})

        result = DatasourceSpecialistAgent(tool, model).execute(
            _request(
                context_summary={
                    "requestedDirections": ["SOURCE"],
                    "source": {"connectorType": "MYSQL", "datasourceName": "不存在的库"},
                }
            )
        )

        self.assertEqual(SpecialistTurnStatus.WAITING_FOR_INPUT, result.status)
        self.assertIsNone(result.structured_output["sourceDatasourceId"])
        self.assertEqual([], model.requests)
        self.assertEqual((), result.structured_output["resolutions"]["source"]["candidates"])


if __name__ == "__main__":
    unittest.main()

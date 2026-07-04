import json
import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.api.agent.tool_action_control_flow import (
    build_tool_action_control_flow_preview_response,
)
from datasmart_ai_runtime.config import default_tool_registry
from datasmart_ai_runtime.services.tools import (
    JavaToolActionCommandProposalClient,
    JavaToolActionCommandOutboxClient,
    ToolActionCommandOutboxClientSettings,
    ToolActionCommandProposalClientSettings,
    ToolActionExecutionGraphRunner,
)


class FakeHttpResponse:
    """给启用态 runner 测试使用的最小 HTTP 响应。"""

    def __init__(self, payload: dict[str, object]) -> None:
        self._payload = payload

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback) -> bool:
        return False

    def read(self) -> bytes:
        return json.dumps(self._payload, ensure_ascii=False).encode("utf-8")


class ToolActionExecutionGraphRunnerTest(unittest.TestCase):
    """工具动作执行前图 runner 测试。

    这里验证的不是“工具已经执行”，而是 READY、DRAFT、缺证据、客户端禁用、Java proposal 成功这些分支
    能否被稳定映射成低敏图节点。真实副作用仍由后续 Java outbox writer 和 worker receipt 承接。
    """

    def setUp(self) -> None:
        self.tools = default_tool_registry()

    def test_control_flow_response_includes_graph_runner_waiting_for_evidence(self) -> None:
        """没有 graph/payload evidence 时，READY 分支应停在等待 proposal 证据。"""

        response = build_tool_action_control_flow_preview_response(
            self._ready_payload(),
            registered_tools=self.tools,
        )
        graph_run = response["toolActionExecutionGraphRun"]
        serialized = str(response)

        self.assertEqual("PRE_EXECUTION_GRAPH_RUNNER_ONLY", graph_run["executionBoundary"])
        self.assertEqual(1, graph_run["stepCount"])
        self.assertEqual("WAITING_COMMAND_PROPOSAL_EVIDENCE", graph_run["steps"][0]["stepStatus"])
        self.assertIn("GRAPH_OR_PAYLOAD_REFERENCE_OR_POLICY_EVIDENCE", graph_run["resumeRequirements"])
        self.assertFalse(graph_run["sideEffectBoundary"]["toolExecuted"])
        self.assertFalse(graph_run["sideEffectBoundary"]["outboxWritten"])
        self.assertTrue(graph_run["sideEffectBoundary"]["checkpointPersisted"])
        self.assertEqual("run-runner", graph_run["checkpoint"]["threadId"])
        self.assertEqual("MCP_TOOLS_CALL", graph_run["checkpoint"]["source"])
        self.assertNotIn("ds-runner-secret", serialized)
        self.assertNotIn("select * from hidden_table", serialized)

    def test_complete_evidence_with_disabled_client_stops_before_http(self) -> None:
        """证据齐全但默认 client 禁用时，runner 不应触发网络调用。"""

        response = build_tool_action_control_flow_preview_response(
            {
                **self._ready_payload(),
                "commandProposalEvidence": self._complete_evidence(),
            },
            registered_tools=self.tools,
        )
        graph_run = response["toolActionExecutionGraphRun"]

        self.assertEqual("COMMAND_PROPOSAL_CLIENT_DISABLED", graph_run["steps"][0]["stepStatus"])
        self.assertIn("CONTROL_PLANE_CLIENT_ENABLEMENT", graph_run["resumeRequirements"])
        self.assertFalse(graph_run["sideEffectBoundary"]["outboxWritten"])
        self.assertTrue(graph_run["sideEffectBoundary"]["checkpointPersisted"])

    def test_enabled_runner_submits_java_proposal_and_waits_for_outbox_confirmation(self) -> None:
        """当启动层注入启用后的 client 时，READY 分支可以提交 Java proposal。"""

        captured: dict[str, object] = {}

        def fake_urlopen(request, timeout: int):
            captured["url"] = request.full_url
            captured["payload"] = json.loads(request.data.decode("utf-8"))
            return FakeHttpResponse(
                {
                    "code": 0,
                    "data": {
                        "proposalId": "proposal-runner-001",
                        "proposalState": "READY_FOR_OUTBOX_CONFIRMATION",
                        "outboxWriteAllowedByPreflight": True,
                        "graphId": "graph-runner-001",
                        "toolName": "datasource.metadata.read",
                        "payloadReference": "agent-payload:runner/datasource-metadata-read",
                        "payloadPolicy": "REFERENCE_ONLY",
                        "workerReceiptRequired": True,
                        "workerReceiptMode": "REQUIRED",
                        "arguments": {"datasourceId": "ds-runner-secret"},
                    },
                }
            )

        runner = ToolActionExecutionGraphRunner(
            proposal_client=JavaToolActionCommandProposalClient(
                ToolActionCommandProposalClientSettings(
                    enabled=True,
                    base_url="http://agent-runtime.test",
                ),
                urlopen_func=fake_urlopen,
            )
        )
        response = build_tool_action_control_flow_preview_response(
            {
                **self._ready_payload(),
                "commandProposalEvidence": self._complete_evidence(),
            },
            registered_tools=self.tools,
            execution_graph_runner=runner,
        )
        graph_run = response["toolActionExecutionGraphRun"]

        self.assertEqual("http://agent-runtime.test/agent-runtime/tool-action-commands/proposals", captured["url"])
        self.assertEqual("graph-runner-001", captured["payload"]["graphId"])
        self.assertEqual("WAITING_OUTBOX_CONFIRMATION", graph_run["steps"][0]["stepStatus"])
        self.assertEqual("CALL_JAVA_OUTBOX_WRITER_AFTER_OPERATOR_OR_GRAPH_CONFIRMATION", graph_run["steps"][0]["nextAction"])
        self.assertFalse(graph_run["sideEffectBoundary"]["checkpointPersisted"])
        self.assertNotIn("checkpoint", graph_run)
        self.assertNotIn("ds-runner-secret", str(graph_run))

    def test_runner_with_disabled_outbox_client_stops_at_outbox_client_disabled(self) -> None:
        """proposal 已允许 outbox，但 outbox client 未启用时，应停在可恢复的客户端关闭节点。"""

        def fake_proposal_urlopen(request, timeout: int):
            return FakeHttpResponse(
                {
                    "code": 0,
                    "data": {
                        "proposalId": "proposal-runner-002",
                        "proposalState": "READY_FOR_OUTBOX_CONFIRMATION",
                        "outboxWriteAllowedByPreflight": True,
                        "graphId": "graph-runner-001",
                        "toolName": "datasource.metadata.read",
                        "payloadReference": "agent-payload:runner/datasource-metadata-read",
                        "payloadPolicy": "REFERENCE_ONLY",
                        "workerReceiptRequired": True,
                        "workerReceiptMode": "REQUIRED",
                    },
                }
            )

        runner = ToolActionExecutionGraphRunner(
            proposal_client=JavaToolActionCommandProposalClient(
                ToolActionCommandProposalClientSettings(enabled=True),
                urlopen_func=fake_proposal_urlopen,
            ),
            outbox_client=JavaToolActionCommandOutboxClient(urlopen_func=self._failing_urlopen),
        )
        response = build_tool_action_control_flow_preview_response(
            {
                **self._ready_payload(),
                "commandProposalEvidence": self._complete_evidence(),
            },
            registered_tools=self.tools,
            execution_graph_runner=runner,
        )
        graph_run = response["toolActionExecutionGraphRun"]

        self.assertEqual("OUTBOX_CLIENT_DISABLED", graph_run["steps"][0]["stepStatus"])
        self.assertEqual(
            "ENABLE_JAVA_OUTBOX_CLIENT_WHEN_OPERATOR_CONFIRMS_SIDE_EFFECT_BOUNDARY",
            graph_run["steps"][0]["nextAction"],
        )
        self.assertEqual("OUTBOX_CLIENT_DISABLED", graph_run["steps"][0]["outboxWrite"]["writeState"])
        self.assertFalse(graph_run["sideEffectBoundary"]["outboxWritten"])

    def test_runner_can_submit_proposal_and_write_outbox_when_clients_are_enabled(self) -> None:
        """显式启用 proposal + outbox client 后，READY 节点可以进入 Java outbox writer。"""

        captured: dict[str, object] = {}

        def fake_proposal_urlopen(request, timeout: int):
            captured["proposalUrl"] = request.full_url
            return FakeHttpResponse(
                {
                    "code": 0,
                    "data": {
                        "proposalId": "proposal-runner-003",
                        "proposalState": "READY_FOR_OUTBOX_CONFIRMATION",
                        "outboxWriteAllowedByPreflight": True,
                        "graphId": "graph-runner-001",
                        "contractId": "contract-runner-001",
                        "runId": "run-runner",
                        "toolName": "datasource.metadata.read",
                        "payloadReference": "agent-payload:runner/datasource-metadata-read",
                        "payloadPolicy": "REFERENCE_ONLY",
                        "workerReceiptRequired": True,
                        "workerReceiptMode": "REQUIRED",
                    },
                }
            )

        def fake_outbox_urlopen(request, timeout: int):
            captured["outboxUrl"] = request.full_url
            captured["outboxPayload"] = json.loads(request.data.decode("utf-8"))
            return FakeHttpResponse(
                {
                    "code": 0,
                    "data": {
                        "writerState": "ENQUEUED",
                        "enqueued": True,
                        "duplicate": False,
                        "commandId": "taoc_runner_001",
                        "proposalId": "proposal-runner-003",
                        "graphId": "graph-runner-001",
                        "contractId": "contract-runner-001",
                        "runId": "run-runner",
                        "payloadReference": "agent-payload:runner/datasource-metadata-read",
                        "proposalState": "READY_FOR_OUTBOX_CONFIRMATION",
                        "record": {
                            "commandId": "taoc_runner_001",
                            "consumerService": "task-management",
                            "payloadJson": "{\"arguments\":{\"datasourceId\":\"ds-runner-secret\"}}",
                        },
                    },
                }
            )

        runner = ToolActionExecutionGraphRunner(
            proposal_client=JavaToolActionCommandProposalClient(
                ToolActionCommandProposalClientSettings(
                    enabled=True,
                    base_url="http://agent-runtime.test",
                ),
                urlopen_func=fake_proposal_urlopen,
            ),
            outbox_client=JavaToolActionCommandOutboxClient(
                ToolActionCommandOutboxClientSettings(
                    enabled=True,
                    base_url="http://agent-runtime.test",
                ),
                urlopen_func=fake_outbox_urlopen,
            ),
        )
        response = build_tool_action_control_flow_preview_response(
            {
                **self._ready_payload(),
                "commandProposalEvidence": self._complete_evidence(),
            },
            registered_tools=self.tools,
            execution_graph_runner=runner,
        )
        graph_run = response["toolActionExecutionGraphRun"]

        self.assertEqual("http://agent-runtime.test/agent-runtime/tool-action-commands/proposals", captured["proposalUrl"])
        self.assertEqual("http://agent-runtime.test/agent-runtime/tool-action-commands/outbox/write", captured["outboxUrl"])
        self.assertEqual("graph-runner-001", captured["outboxPayload"]["graphId"])
        self.assertEqual("OUTBOX_ENQUEUED", graph_run["steps"][0]["stepStatus"])
        self.assertEqual("WAIT_FOR_DISPATCHER_AND_WORKER_RECEIPT", graph_run["steps"][0]["nextAction"])
        self.assertEqual("taoc_runner_001", graph_run["steps"][0]["outboxWrite"]["javaOutbox"]["commandId"])
        self.assertNotIn("payloadJson", str(graph_run))
        self.assertNotIn("ds-runner-secret", str(graph_run))

    def test_draft_branch_does_not_call_java_proposal(self) -> None:
        """草案分支不是 outbox preflight candidate，runner 不能调用 Java proposal。"""

        runner = ToolActionExecutionGraphRunner(
            proposal_client=JavaToolActionCommandProposalClient(
                ToolActionCommandProposalClientSettings(enabled=True),
                urlopen_func=self._failing_urlopen,
            )
        )
        response = build_tool_action_control_flow_preview_response(
            {
                "source": "MODEL_TOOL_CALL",
                "toolCalls": [
                    {
                        "id": "draft-call-001",
                        "function": {
                            "name": "quality_rule_suggest",
                            "arguments": {
                                "datasourceId": "ds-runner-secret",
                                "businessGoal": "客户手机号唯一性",
                            },
                        },
                    }
                ],
                "visibleToolNames": ["quality.rule.suggest"],
            },
            registered_tools=self.tools,
            execution_graph_runner=runner,
        )
        graph_run = response["toolActionExecutionGraphRun"]

        self.assertEqual("DRAFT_REVIEW_REQUIRED", graph_run["steps"][0]["stepStatus"])
        self.assertEqual({}, graph_run["steps"][0]["proposalSubmission"])
        self.assertNotIn("客户手机号唯一性", str(response))

    def _ready_payload(self) -> dict[str, object]:
        """生成 READY 的 MCP tools/call payload。"""

        return {
            "source": "MCP_TOOLS_CALL",
            "jsonrpc": "2.0",
            "id": "rpc-runner-001",
            "method": "tools/call",
            "params": {
                "name": "datasource.metadata.read",
                "arguments": {"datasourceId": "ds-runner-secret"},
            },
            "visibleToolNames": ["datasource.metadata.read"],
            "context": {
                "tenantId": "tenant-runner",
                "projectId": "project-runner",
                "actorId": "actor-runner",
                "requestId": "request-runner",
                "runId": "run-runner",
                "sessionId": "session-runner",
                "policyVersion": "tool-readiness-policy.v1",
            },
            "sql": "select * from hidden_table",
        }

    @staticmethod
    def _complete_evidence() -> dict[str, object]:
        """生成可以进入 Java proposal 的低敏证据。"""

        return {
            "graphId": "graph-runner-001",
            "payloadReference": "agent-payload:runner/datasource-metadata-read",
            "policyVersion": "tool-readiness-policy.v1",
            "clientRequestId": "client-runner",
        }

    @staticmethod
    def _failing_urlopen(*args, **kwargs):
        """非 READY 分支不应该触发 Java proposal HTTP。"""

        raise AssertionError("草案、审批、澄清或阻断分支不应该调用 Java proposal")


if __name__ == "__main__":
    unittest.main()

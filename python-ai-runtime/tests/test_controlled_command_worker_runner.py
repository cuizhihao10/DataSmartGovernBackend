import json
import os
import sys
import unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.tools import (
    COMMAND_WORKER_RECEIPT_PAYLOAD_POLICY,
    CommandWorkerReceiptOutcome,
    ControlledCommandWorkerRunMode,
    ControlledCommandWorkerRunRequest,
    ControlledCommandWorkerRunner,
    JavaCommandWorkerReceiptClient,
    JavaCommandWorkerReceiptClientSettings,
)


FENCING_TOKEN = "cmd-lease:7:abcdef1234567890"
LEASE_VERSION = 7
LEASE_EXPIRES_AT_MS = 1_900_000_000_000


class FakeHttpResponse:
    """单元测试用 HTTP 响应替身。

    `JavaCommandWorkerReceiptClient` 使用 `with urlopen(...) as response` 读取 Java 统一响应。测试中不启动真实
    Java 服务，只提供上下文管理器、`status` 和 `read()`，即可验证 URL、Header、payload 和响应白名单解析。
    """

    def __init__(self, payload: dict[str, object], *, status: int = 200) -> None:
        self._payload = payload
        self.status = status

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, traceback) -> bool:
        return False

    def read(self) -> bytes:
        return json.dumps(self._payload, ensure_ascii=False).encode("utf-8")


class ControlledCommandWorkerRunnerTest(unittest.TestCase):
    """受控命令 worker runner 合同测试。

    这组测试保护的是 Python/OpenClaw-style runner 到 Java 5.94 command worker receipt 的最小闭环：
    - Python 当前只生成低敏回执，不执行真实命令；
    - 安全决策未允许或仍有 issueCode 时，必须降级为执行前阻断；
    - 模拟成功回执必须满足 Java `EXECUTION_SUCCEEDED` 的副作用声明约束；
    - HTTP 客户端只能提交/解析 Java DTO 白名单字段，不能扩散命令、输出、SQL、prompt 或内部 endpoint。
    """

    def test_precheck_only_builds_java_receipt_without_side_effect(self) -> None:
        """`PRECHECK_ONLY` 只能表示 worker 复核通过，不能声明真实副作用已经发生。"""

        receipt = ControlledCommandWorkerRunner().build_receipt(self._request())
        payload = receipt.java_payload

        self.assertEqual(CommandWorkerReceiptOutcome.WORKER_PRECHECK_PASSED, receipt.outcome)
        self.assertFalse(receipt.execution_performed)
        self.assertEqual(COMMAND_WORKER_RECEIPT_PAYLOAD_POLICY, receipt.payload_policy)
        self.assertEqual("WORKER_PRECHECK_PASSED", payload["outcome"])
        self.assertTrue(payload["preCheckPassed"])
        self.assertFalse(payload["sideEffectStarted"])
        self.assertFalse(payload["sideEffectExecuted"])
        self.assertEqual("PRECHECK_ONLY", payload["workerReceiptMode"])
        self.assertNotIn("artifactReference", payload)
        self.assertNotIn("commandLine", str(payload))
        self.assertNotIn("stdout", str(payload))

    def test_simulated_success_builds_execution_succeeded_receipt(self) -> None:
        """模拟成功时必须生成 Java 允许的 `EXECUTION_SUCCEEDED` 形态，并补齐低敏 artifact 引用。"""

        receipt = ControlledCommandWorkerRunner().build_receipt(
            self._request(
                run_mode=ControlledCommandWorkerRunMode.SIMULATED_EXECUTION_SUCCESS,
                fencing_token=FENCING_TOKEN,
                worker_lease_version=LEASE_VERSION,
                worker_lease_expires_at_ms=LEASE_EXPIRES_AT_MS,
            )
        )
        payload = receipt.java_payload
        serialized = json.dumps(receipt.to_summary(), ensure_ascii=False)

        self.assertEqual(CommandWorkerReceiptOutcome.EXECUTION_SUCCEEDED, receipt.outcome)
        self.assertEqual("EXECUTION_SUCCEEDED", payload["outcome"])
        self.assertTrue(payload["preCheckPassed"])
        self.assertTrue(payload["sideEffectStarted"])
        self.assertTrue(payload["sideEffectExecuted"])
        self.assertTrue(payload["workerLeaseRequired"])
        self.assertEqual(FENCING_TOKEN, payload["fencingToken"])
        self.assertEqual(LEASE_VERSION, payload["workerLeaseVersion"])
        self.assertEqual(LEASE_EXPIRES_AT_MS, payload["workerLeaseExpiresAtMs"])
        self.assertEqual("EXECUTION_RESULT", payload["workerReceiptMode"])
        self.assertEqual("AGENT_ARTIFACT", payload["artifactReferenceType"])
        self.assertEqual("agent-artifact:run-command-001/cmd-worker-001/simulated-receipt", payload["artifactReference"])
        self.assertIn("fencingTokenPresent", serialized)
        self.assertNotIn(FENCING_TOKEN, serialized)
        self.assertNotIn("select * from", serialized.lower())
        self.assertNotIn("prompt:", serialized.lower())
        self.assertNotIn("stdout", serialized.lower())
        self.assertNotIn("stderr", serialized.lower())

    def test_simulated_side_effect_mode_requires_worker_lease(self) -> None:
        """进入模拟执行区前必须具备 lease 证据，避免未来真实 runner 绕过并发护栏。"""

        with self.assertRaises(ValueError):
            ControlledCommandWorkerRunner().build_receipt(
                self._request(run_mode=ControlledCommandWorkerRunMode.SIMULATED_EXECUTION_SUCCESS)
            )

    def test_non_allow_decision_or_open_issue_codes_fail_closed_before_execution(self) -> None:
        """安全决策不是 allow 或 issueCode 未关闭时，runner 必须生成 `FAILED_PRECHECK`。"""

        receipt = ControlledCommandWorkerRunner().build_receipt(
            self._request(
                run_mode=ControlledCommandWorkerRunMode.SIMULATED_EXECUTION_SUCCESS,
                command_safety_decision="ALLOW_CONTROLLED_EXECUTION",
                command_safety_issue_codes=("NETWORK_REQUIRES_APPROVAL",),
            )
        )
        payload = receipt.java_payload

        self.assertEqual(CommandWorkerReceiptOutcome.FAILED_PRECHECK, receipt.outcome)
        self.assertEqual("FAILED_PRECHECK", payload["outcome"])
        self.assertFalse(payload["preCheckPassed"])
        self.assertFalse(payload["sideEffectStarted"])
        self.assertFalse(payload["sideEffectExecuted"])
        self.assertEqual(["NETWORK_REQUIRES_APPROVAL"], payload["commandSafetyIssueCodes"])
        self.assertEqual("AGENT_COMMAND_WORKER_PRECHECK_REJECTED", payload["errorCode"])

    def test_sensitive_message_and_unsafe_artifact_reference_are_rejected(self) -> None:
        """低敏文本和 artifact 引用必须先在 Python 侧拦截，避免把泄露风险推给 Java。"""

        runner = ControlledCommandWorkerRunner()
        with self.assertRaises(ValueError):
            runner.build_receipt(self._request(operator_message="stdout: raw output should never enter receipt"))
        with self.assertRaises(ValueError):
            runner.build_receipt(
                self._request(
                    run_mode=ControlledCommandWorkerRunMode.SIMULATED_EXECUTION_SUCCESS,
                    artifact_reference="https://internal.example.local/raw-output?token=secret",
                )
            )

    def test_disabled_client_skips_java_post_without_network_side_effect(self) -> None:
        """客户端默认关闭时只返回 skipped 摘要，不应产生任何 HTTP 调用。"""

        receipt = ControlledCommandWorkerRunner().build_receipt(self._request())
        client = JavaCommandWorkerReceiptClient(urlopen_func=self._failing_urlopen)
        result = client.post_receipt(
            session_id="session-command-001",
            run_id="run-command-001",
            receipt=receipt,
            trace_id="trace-command-worker",
        )

        self.assertFalse(result.attempted)
        self.assertTrue(result.skipped)
        self.assertFalse(result.posted)
        self.assertEqual("WORKER_PRECHECK_PASSED", result.outcome)
        self.assertNotIn("localhost:8091", str(result.to_summary()))

    def test_enabled_client_posts_java_payload_and_parses_low_sensitive_response(self) -> None:
        """启用客户端后应 POST Java 内部回执路由，并只解析 Java 响应白名单字段。"""

        captured: dict[str, object] = {}
        receipt = ControlledCommandWorkerRunner().build_receipt(
            self._request(
                run_mode=ControlledCommandWorkerRunMode.SIMULATED_EXECUTION_SUCCESS,
                fencing_token=FENCING_TOKEN,
                worker_lease_version=LEASE_VERSION,
                worker_lease_expires_at_ms=LEASE_EXPIRES_AT_MS,
            )
        )

        def fake_urlopen(request, timeout: int):
            captured["url"] = request.full_url
            captured["timeout"] = timeout
            captured["headers"] = dict(request.header_items())
            captured["payload"] = json.loads(request.data.decode("utf-8"))
            return FakeHttpResponse(
                {
                    "code": 0,
                    "data": {
                        "accepted": True,
                        "duplicate": False,
                        "identityKey": "command-worker-receipt:run-command-001:cmd-worker-001",
                        "eventType": "agent.tool_execution.command_worker_receipt_recorded",
                        "outcome": "EXECUTION_SUCCEEDED",
                        "sideEffectExecuted": True,
                        "commandLine": "should-not-be-parsed",
                        "stdout": "should-not-be-parsed",
                    },
                }
            )

        client = JavaCommandWorkerReceiptClient(
            JavaCommandWorkerReceiptClientSettings(
                enabled=True,
                base_url="http://agent-runtime.test",
                timeout_seconds=7,
            ),
            urlopen_func=fake_urlopen,
        )
        result = client.post_receipt(
            session_id="session-command-001",
            run_id="run-command-001",
            receipt=receipt,
            trace_id="trace-command-worker",
        )
        summary = result.to_summary()

        self.assertTrue(result.attempted)
        self.assertTrue(result.posted)
        self.assertFalse(result.duplicate)
        self.assertEqual(7, captured["timeout"])
        self.assertEqual(
            "http://agent-runtime.test/internal/agent-runtime/sessions/session-command-001/"
            "runs/run-command-001/tool-executions/command-worker-receipts",
            captured["url"],
        )
        self.assertEqual("EXECUTION_SUCCEEDED", captured["payload"]["outcome"])
        self.assertTrue(captured["payload"]["sideEffectExecuted"])
        self.assertEqual(FENCING_TOKEN, captured["payload"]["fencingToken"])
        self.assertEqual("EXECUTION_SUCCEEDED", summary["outcome"])
        self.assertNotIn("commandLine", summary)
        self.assertNotIn("stdout", summary)
        self.assertNotIn(FENCING_TOKEN, str(summary))
        self.assertNotIn("agent-runtime.test", str(summary))

    def _request(self, **overrides) -> ControlledCommandWorkerRunRequest:
        """生成默认允许执行的低敏 worker 请求。"""

        values = {
            "session_id": "session-command-001",
            "run_id": "run-command-001",
            "command_id": "cmd-worker-001",
            "run_mode": ControlledCommandWorkerRunMode.PRECHECK_ONLY,
            "task_id": 9101,
            "task_run_id": 9201,
            "tenant_id": 10,
            "project_id": 20,
            "actor_id": 30,
            "command_safety_decision": "ALLOW_CONTROLLED_EXECUTION",
            "command_safety_policy_version": "command-safety-policy.v1",
            "normalized_timeout_seconds": 30,
            "normalized_output_byte_limit_bytes": 4096,
            "audit_id": "audit-command-worker-001",
            "recommended_actions": ("确认任务中心状态与低敏 artifact 元数据完成对账",),
        }
        values.update(overrides)
        return ControlledCommandWorkerRunRequest(**values)

    @staticmethod
    def _failing_urlopen(*args, **kwargs):
        """默认关闭场景不应该触发 HTTP。"""

        raise AssertionError("客户端关闭时不应该调用 Java command worker receipt 接口")


if __name__ == "__main__":
    unittest.main()

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
    JavaCommandSandboxAdmissionClient,
    JavaCommandSandboxAdmissionClientSettings,
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

    def test_sandbox_admission_success_allows_runner_to_build_execution_receipt(self) -> None:
        """执行区运行前应先请求 Java sandbox admission，并采用 Java 裁剪后的低敏资源预算。"""

        captured: dict[str, object] = {}

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
                        "decision": "ADMITTED",
                        "sandboxRunId": "sandbox-run:sha256:abcdef1234567890",
                        "normalizedTimeoutSeconds": 18,
                        "normalizedOutputByteLimitBytes": 2048,
                        "isolationMode": "NO_NETWORK_PROCESS_SANDBOX",
                        "processStarted": False,
                        "rawCommandAccepted": False,
                        "issueCodes": [],
                        "evidenceCodes": ["LEASE_VALID", "WORKSPACE_REFERENCE_VALID"],
                        "recommendedActions": ["继续执行受控命令并写回低敏 receipt"],
                        "commandLine": "should-not-be-parsed",
                        "stdout": "should-not-be-parsed",
                    },
                }
            )

        admission_client = JavaCommandSandboxAdmissionClient(
            JavaCommandSandboxAdmissionClientSettings(
                enabled=True,
                base_url="http://agent-runtime.test",
                timeout_seconds=9,
            ),
            urlopen_func=fake_urlopen,
        )
        result = ControlledCommandWorkerRunner().run(
            self._request(
                run_mode=ControlledCommandWorkerRunMode.SIMULATED_EXECUTION_SUCCESS,
                sandbox_admission_required=True,
                fencing_token=FENCING_TOKEN,
                worker_lease_version=LEASE_VERSION,
                worker_lease_expires_at_ms=LEASE_EXPIRES_AT_MS,
                workspace_reference="agent-workspace:tenant-10/project-20/run-command-001",
                normalized_timeout_seconds=90,
                normalized_output_byte_limit_bytes=65_536,
            ),
            sandbox_admission_client=admission_client,
            require_sandbox_admission=True,
            trace_id="trace-command-sandbox-admission",
        )
        receipt_payload = result.receipt.java_payload
        summary = result.to_summary()

        self.assertEqual(CommandWorkerReceiptOutcome.EXECUTION_SUCCEEDED, result.receipt.outcome)
        self.assertEqual(9, captured["timeout"])
        self.assertEqual(
            "http://agent-runtime.test/internal/agent-runtime/sessions/session-command-001/"
            "runs/run-command-001/tool-executions/command-sandbox-run-admissions",
            captured["url"],
        )
        self.assertEqual(FENCING_TOKEN, captured["payload"]["fencingToken"])
        self.assertEqual("agent-workspace:tenant-10/project-20/run-command-001", captured["payload"]["workspaceReference"])
        self.assertEqual(90, captured["payload"]["requestedTimeoutSeconds"])
        self.assertNotIn("commandLine", captured["payload"])
        self.assertNotIn("stdout", captured["payload"])
        self.assertEqual(18, receipt_payload["normalizedTimeoutSeconds"])
        self.assertEqual(2048, receipt_payload["normalizedOutputByteLimitBytes"])
        self.assertTrue(summary["sandboxAdmissionResult"]["accepted"])
        self.assertEqual("ADMITTED", summary["sandboxAdmissionResult"]["decision"])
        self.assertNotIn(FENCING_TOKEN, json.dumps(summary, ensure_ascii=False))
        self.assertNotIn("agent-runtime.test", json.dumps(summary, ensure_ascii=False))
        self.assertNotIn("should-not-be-parsed", json.dumps(summary, ensure_ascii=False))

    def test_sandbox_admission_denied_turns_side_effect_mode_into_failed_precheck(self) -> None:
        """Java admission 拒绝时，Python 不能继续声明副作用，而要生成失败预检回执。"""

        def fake_urlopen(request, timeout: int):
            return FakeHttpResponse(
                {
                    "code": 0,
                    "data": {
                        "accepted": False,
                        "decision": "DENIED_BY_WORKSPACE_POLICY",
                        "sandboxRunId": None,
                        "processStarted": False,
                        "rawCommandAccepted": False,
                        "issueCodes": ["WORKSPACE_REFERENCE_REQUIRED"],
                        "evidenceCodes": ["LEASE_VALID"],
                        "recommendedActions": ["补齐受控 workspace 引用后重新领取 lease"],
                    },
                }
            )

        admission_client = JavaCommandSandboxAdmissionClient(
            JavaCommandSandboxAdmissionClientSettings(enabled=True, base_url="http://agent-runtime.test"),
            urlopen_func=fake_urlopen,
        )
        result = ControlledCommandWorkerRunner().run(
            self._request(
                run_mode=ControlledCommandWorkerRunMode.SIMULATED_EXECUTION_SUCCESS,
                sandbox_admission_required=True,
                fencing_token=FENCING_TOKEN,
                worker_lease_version=LEASE_VERSION,
                worker_lease_expires_at_ms=LEASE_EXPIRES_AT_MS,
            ),
            sandbox_admission_client=admission_client,
            require_sandbox_admission=True,
        )
        payload = result.receipt.java_payload
        serialized = json.dumps(result.to_summary(), ensure_ascii=False)

        self.assertEqual(CommandWorkerReceiptOutcome.FAILED_PRECHECK, result.receipt.outcome)
        self.assertFalse(payload["sideEffectStarted"])
        self.assertFalse(payload["sideEffectExecuted"])
        self.assertIn("WORKSPACE_REFERENCE_REQUIRED", payload["commandSafetyIssueCodes"])
        self.assertEqual("DENIED_BY_WORKSPACE_POLICY", result.sandbox_admission_result.decision)
        self.assertNotIn(FENCING_TOKEN, serialized)
        self.assertNotIn("agent-runtime.test", serialized)

    def test_required_sandbox_admission_fails_closed_when_client_is_disabled(self) -> None:
        """调用方要求 admission 时，即使客户端默认关闭，也必须 fail-closed 而不是继续执行。"""

        result = ControlledCommandWorkerRunner().run(
            self._request(
                run_mode=ControlledCommandWorkerRunMode.SIMULATED_EXECUTION_SUCCESS,
                sandbox_admission_required=True,
                fencing_token=FENCING_TOKEN,
                worker_lease_version=LEASE_VERSION,
                worker_lease_expires_at_ms=LEASE_EXPIRES_AT_MS,
                workspace_reference="agent-workspace:tenant-10/project-20/run-command-001",
            ),
            sandbox_admission_client=JavaCommandSandboxAdmissionClient(urlopen_func=self._failing_urlopen),
            require_sandbox_admission=True,
        )
        payload = result.receipt.java_payload

        self.assertEqual(CommandWorkerReceiptOutcome.FAILED_PRECHECK, result.receipt.outcome)
        self.assertTrue(result.sandbox_admission_result.skipped)
        self.assertFalse(payload["sideEffectStarted"])
        self.assertIn("SANDBOX_ADMISSION_CLIENT_DISABLED", payload["commandSafetyIssueCodes"])
        self.assertNotIn(FENCING_TOKEN, json.dumps(result.to_summary(), ensure_ascii=False))

    def test_unsafe_workspace_reference_is_rejected_before_java_admission(self) -> None:
        """workspaceReference 只能是低敏受控引用，不能让 URL、真实路径或凭据进入 Java admission 请求。"""

        admission_client = JavaCommandSandboxAdmissionClient(
            JavaCommandSandboxAdmissionClientSettings(enabled=True, base_url="http://agent-runtime.test"),
            urlopen_func=self._failing_urlopen,
        )
        with self.assertRaises(ValueError):
            ControlledCommandWorkerRunner().run(
                self._request(
                    run_mode=ControlledCommandWorkerRunMode.SIMULATED_EXECUTION_SUCCESS,
                    sandbox_admission_required=True,
                    fencing_token=FENCING_TOKEN,
                    worker_lease_version=LEASE_VERSION,
                    worker_lease_expires_at_ms=LEASE_EXPIRES_AT_MS,
                    workspace_reference="https://internal.example.local/workspaces/root?token=secret",
                ),
                sandbox_admission_client=admission_client,
                require_sandbox_admission=True,
            )

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

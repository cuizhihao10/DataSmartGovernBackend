import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from datasmart_ai_runtime.services.tools import (
    CommandSandboxProcessRunner,
    CommandSandboxProcessRunnerSettings,
    CommandSandboxProcessRunRequest,
    CommandSandboxProcessStatus,
    CommandWorkerReceiptOutcome,
    ControlledCommandWorkerRunRequest,
    ControlledCommandWorkerRunner,
    JavaCommandSandboxAdmissionResult,
)


FENCING_TOKEN = "cmd-lease:9:abcdef1234567890"
LEASE_VERSION = 9
LEASE_EXPIRES_AT_MS = 1_900_000_100_000


class CommandSandboxProcessRunnerTest(unittest.TestCase):
    """受控命令沙箱进程外壳测试。

    这组测试保护 5.107 的关键边界：
    - 真实进程只能在 Java admission accepted 后启动；
    - 进程以 `argv + shell=False` 启动，不依赖系统 shell；
    - cwd 必须在 workspace allowlist 内；
    - stdout/stderr 正文不能进入 summary 或 worker receipt；
    - 真实进程结果可以转换为 Java command worker receipt 所需的低敏事实。
    """

    def test_successful_process_result_can_be_converted_to_worker_receipt(self) -> None:
        """admission 通过后，成功进程应生成执行成功 receipt，但不泄露 argv、输出或 workspace 路径。"""

        with tempfile.TemporaryDirectory() as workspace:
            runner = self._runner(workspace)
            result = runner.run(
                self._process_request(
                    workspace,
                    command_argv=(sys.executable, "-c", "print('sandbox-ok')"),
                )
            )
            summary = result.to_summary()
            worker_request = result.to_worker_run_request(self._worker_request())
            receipt = ControlledCommandWorkerRunner().build_receipt(worker_request)
            serialized = json.dumps({"summary": summary, "receipt": receipt.to_summary()}, ensure_ascii=False)

        self.assertEqual(CommandSandboxProcessStatus.SUCCEEDED, result.status)
        self.assertTrue(result.process_started)
        self.assertEqual(0, result.exit_code)
        self.assertGreater(result.stdout_byte_count, 0)
        self.assertEqual(CommandWorkerReceiptOutcome.EXECUTION_SUCCEEDED, receipt.outcome)
        self.assertTrue(receipt.execution_performed)
        self.assertEqual("CONTROLLED_PROCESS_RESULT", receipt.java_payload["workerReceiptMode"])
        self.assertEqual("COMMAND_OUTPUT_ARTIFACT", receipt.java_payload["artifactReferenceType"])
        self.assertTrue(receipt.java_payload["artifactReference"].startswith("command-output:"))
        self.assertNotIn("sandbox-ok", serialized)
        self.assertNotIn(sys.executable, serialized)
        self.assertNotIn(workspace, serialized)
        self.assertNotIn(FENCING_TOKEN, serialized)

    def test_process_timeout_becomes_execution_failed_receipt(self) -> None:
        """超时发生在进程启动之后，因此应写成执行失败，而不是伪装成 precheck。"""

        with tempfile.TemporaryDirectory() as workspace:
            result = self._runner(workspace).run(
                self._process_request(
                    workspace,
                    command_argv=(sys.executable, "-c", "import time; time.sleep(2)"),
                    timeout_seconds=1,
                )
            )
            receipt = ControlledCommandWorkerRunner().build_receipt(
                result.to_worker_run_request(self._worker_request())
            )

        self.assertEqual(CommandSandboxProcessStatus.TIMED_OUT, result.status)
        self.assertTrue(result.process_started)
        self.assertTrue(result.timed_out)
        self.assertEqual(CommandWorkerReceiptOutcome.EXECUTION_FAILED, receipt.outcome)
        self.assertTrue(receipt.java_payload["sideEffectStarted"])
        self.assertFalse(receipt.java_payload["sideEffectExecuted"])

    def test_output_budget_only_records_byte_counts_and_truncation(self) -> None:
        """大量输出只应留下字节计数和截断标记，不能把输出正文塞进摘要。"""

        with tempfile.TemporaryDirectory() as workspace:
            result = self._runner(workspace).run(
                self._process_request(
                    workspace,
                    command_argv=(sys.executable, "-c", "print('x' * 2048)"),
                    output_byte_limit_bytes=512,
                )
            )
            serialized = json.dumps(result.to_summary(), ensure_ascii=False)

        self.assertEqual(CommandSandboxProcessStatus.SUCCEEDED, result.status)
        self.assertGreater(result.stdout_byte_count, 512)
        self.assertTrue(result.stdout_truncated)
        self.assertNotIn("xxxxxxxx", serialized)

    def test_process_is_blocked_when_admission_is_not_accepted(self) -> None:
        """Java admission 未准入时，进程外壳必须 fail-closed，不能启动子进程。"""

        with tempfile.TemporaryDirectory() as workspace:
            result = self._runner(workspace).run(
                self._process_request(
                    workspace,
                    admission_result=self._admission(accepted=False),
                )
            )
            receipt = ControlledCommandWorkerRunner().build_receipt(
                result.to_worker_run_request(self._worker_request())
            )

        self.assertEqual(CommandSandboxProcessStatus.BLOCKED, result.status)
        self.assertFalse(result.process_started)
        self.assertIn("SANDBOX_ADMISSION_NOT_ACCEPTED", result.issue_codes)
        self.assertEqual(CommandWorkerReceiptOutcome.FAILED_PRECHECK, receipt.outcome)

    def test_disallowed_executable_and_workspace_escape_are_rejected(self) -> None:
        """可执行文件与 workspace 根目录都必须先在 Python 侧被白名单约束。"""

        with tempfile.TemporaryDirectory() as allowed_workspace:
            runner = self._runner(allowed_workspace)
            with self.assertRaises(ValueError):
                runner.run(
                    self._process_request(
                        allowed_workspace,
                        command_argv=("not-allowed-executable", "-c", "print('blocked')"),
                    )
                )
            with tempfile.TemporaryDirectory() as outside_workspace:
                with self.assertRaises(ValueError):
                    runner.run(self._process_request(outside_workspace))

    def _runner(self, workspace: str) -> CommandSandboxProcessRunner:
        return CommandSandboxProcessRunner(
            CommandSandboxProcessRunnerSettings(
                enabled=True,
                allowed_executable_names=(Path(sys.executable).name,),
                workspace_root_allowlist=(workspace,),
                max_timeout_seconds=3,
                max_output_byte_limit_bytes=2048,
            )
        )

    def _process_request(self, workspace: str, **overrides) -> CommandSandboxProcessRunRequest:
        values = {
            "session_id": "session-process-001",
            "run_id": "run-process-001",
            "command_id": "cmd-process-001",
            "command_argv": (sys.executable, "-c", "print('process-default')"),
            "workspace_root": workspace,
            "workspace_reference": "agent-workspace:tenant-10/project-20/run-process-001",
            "admission_result": self._admission(),
            "timeout_seconds": 2,
            "output_byte_limit_bytes": 1024,
        }
        values.update(overrides)
        return CommandSandboxProcessRunRequest(**values)

    def _worker_request(self) -> ControlledCommandWorkerRunRequest:
        return ControlledCommandWorkerRunRequest(
            session_id="session-process-001",
            run_id="run-process-001",
            command_id="cmd-process-001",
            tenant_id=10,
            project_id=20,
            actor_id=30,
            command_safety_decision="ALLOW_CONTROLLED_EXECUTION",
            command_safety_policy_version="command-safety-policy.v1",
            worker_lease_required=True,
            fencing_token=FENCING_TOKEN,
            worker_lease_version=LEASE_VERSION,
            worker_lease_expires_at_ms=LEASE_EXPIRES_AT_MS,
            workspace_reference="agent-workspace:tenant-10/project-20/run-process-001",
            recommended_actions=("确认受控进程结果已经写回 Java receipt",),
        )

    @staticmethod
    def _admission(accepted: bool = True) -> JavaCommandSandboxAdmissionResult:
        return JavaCommandSandboxAdmissionResult(
            attempted=True,
            accepted=accepted,
            skipped=False,
            status_code=200,
            decision="ADMITTED" if accepted else "DENIED_BY_TEST",
            sandbox_run_id="sandbox-run:sha256:process001",
            issue_codes=() if accepted else ("TEST_ADMISSION_DENIED",),
            evidence_codes=("LEASE_VALID", "WORKSPACE_REFERENCE_VALID") if accepted else (),
            recommended_actions=("继续执行受控命令并写回低敏 receipt",) if accepted else ("保持 fail-closed",),
            normalized_timeout_seconds=2,
            normalized_output_byte_limit_bytes=1024,
            isolation_mode="NO_NETWORK_PROCESS_SANDBOX",
            process_started=False,
            raw_command_accepted=False,
            payload_policy="ADMISSION_ONLY_NO_COMMAND_LINE_NO_STDIO_NO_TOOL_ARGUMENTS_NO_PROMPT_NO_SQL_NO_PAYLOAD_BODY",
            error_code=None,
            endpoint_configured=True,
            message="test admission",
        )


if __name__ == "__main__":
    unittest.main()

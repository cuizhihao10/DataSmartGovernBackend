"""受控命令沙箱进程外壳。

本模块是 DataSmart command durable action 从“控制面合同”走向“真实执行外壳”的第一步。它不是最终的容器沙箱，
也不声称已经具备完整 OS 级隔离；它的定位是一个可替换的 host-local process adapter，用来先关闭以下关键闭环：

1. 只有 Java sandbox admission 已准入时才允许启动进程；
2. 只接受 argv 数组，不接受 shell 字符串，避免模型或工具参数拼接出高风险 shell；
3. 工作目录必须来自受控 workspace root allowlist，workspaceReference 仍然只作为低敏引用进入治理合同；
4. stdout/stderr 只在进程内部短暂读取并按字节预算截断，摘要和 receipt 均不返回输出正文；
5. 进程结果可转换为 `ControlledCommandWorkerRunRequest`，再复用既有 Java receipt 写回链路。

后续如果接入 Firecracker、Kubernetes Job、Docker、nsjail 或远端 OpenClaw sandbox，本文件可以被新的 adapter 替换；
上层 receipt、admission、outbox、dead-letter 和 artifact gate 合同不应该被推翻。
"""

from __future__ import annotations

import os
import subprocess
import time
from collections.abc import Mapping
from dataclasses import dataclass, field, replace
from enum import Enum
from pathlib import Path
from threading import Thread
from typing import Any

from datasmart_ai_runtime.services.tools.controlled_command_worker_contract import (
    _is_safe_workspace_reference,
    _looks_sensitive,
    _non_negative_int,
    _safe_machine_codes,
    _safe_recommended_actions,
    _safe_text,
)
from datasmart_ai_runtime.services.tools.controlled_command_worker_runner import (
    ControlledCommandWorkerRunMode,
    ControlledCommandWorkerRunRequest,
)


COMMAND_SANDBOX_PROCESS_SCHEMA_VERSION = "datasmart.python-ai-runtime.command-sandbox-process-runner.v1"
COMMAND_SANDBOX_PROCESS_PAYLOAD_POLICY = (
    "PROCESS_METADATA_ONLY_NO_COMMAND_LINE_NO_STDIO_NO_STDERR_NO_ENV_NO_WORKSPACE_PATH"
)


class CommandSandboxProcessStatus(str, Enum):
    """受控进程外壳的低敏状态。

    这里刻意区分 `BLOCKED` 与 `FAILED`：
    - `BLOCKED` 表示进程尚未启动，通常是 admission、allowlist、workspace 或参数校验失败；
    - `FAILED/TIMED_OUT` 表示进程已经启动过，应该按真实副作用事实写回 Java receipt。
    """

    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    TIMED_OUT = "TIMED_OUT"
    BLOCKED = "BLOCKED"


@dataclass(frozen=True)
class CommandSandboxProcessRunnerSettings:
    """受控进程外壳配置。

    - `enabled` 默认关闭，防止本地学习、单测或 preview 链路误启动真实进程；
    - `allowed_executable_names` 使用可执行文件 basename 白名单，例如 `python.exe`，避免模型传入任意路径；
    - `workspace_root_allowlist` 是真实文件系统根目录白名单，只有这些目录及其子目录可以作为 cwd；
    - `max_timeout_seconds/max_output_byte_limit_bytes` 是 Python 侧二次硬上限，不能只信 Java admission 返回值；
    - `inherited_environment_keys` 只继承少量系统必需环境变量，默认不继承 PATH、token、代理或业务凭据。
    """

    enabled: bool = False
    allowed_executable_names: tuple[str, ...] = field(default_factory=tuple)
    workspace_root_allowlist: tuple[str, ...] = field(default_factory=tuple)
    default_timeout_seconds: int = 10
    max_timeout_seconds: int = 60
    default_output_byte_limit_bytes: int = 32_768
    max_output_byte_limit_bytes: int = 65_536
    inherited_environment_keys: tuple[str, ...] = ("SYSTEMROOT", "WINDIR")
    extra_environment: tuple[tuple[str, str], ...] = field(default_factory=tuple)


@dataclass(frozen=True)
class CommandSandboxProcessRunRequest:
    """一次受控进程运行请求。

    字段说明：
    - `command_argv` 是短生命周期内部输入，只能进入 `subprocess.Popen`，不能写入 summary、event 或 receipt；
    - `workspace_root` 是真实文件系统目录，只用于 cwd 校验和进程启动，也不能出现在 summary；
    - `workspace_reference` 是可审计的低敏引用，应与 Java admission 使用同一引用；
    - `admission_result` 必须来自 `JavaCommandSandboxAdmissionClient` 或等价测试替身，且 accepted 为 true；
    - `environment` 只允许少量显式变量，默认不继承调用进程环境，避免泄露凭据或代理地址。
    """

    session_id: str
    run_id: str
    command_id: str
    command_argv: tuple[str, ...]
    workspace_root: str
    workspace_reference: str
    admission_result: Any
    timeout_seconds: int | None = None
    output_byte_limit_bytes: int | None = None
    environment: tuple[tuple[str, str], ...] = field(default_factory=tuple)
    artifact_reference: str | None = None


@dataclass(frozen=True)
class CommandSandboxProcessRunResult:
    """受控进程运行后的低敏结果。

    结果对象只保存进程事实元数据：退出码、耗时、输出字节数、是否截断和 artifact 引用。它不保存 argv、不保存真实 cwd、
    不保存 stdout/stderr 正文，也不保存环境变量。
    """

    status: CommandSandboxProcessStatus
    process_started: bool
    exit_code: int | None
    timed_out: bool
    duration_ms: int
    stdout_byte_count: int
    stderr_byte_count: int
    stdout_truncated: bool
    stderr_truncated: bool
    artifact_reference: str | None
    issue_codes: tuple[str, ...]
    evidence_codes: tuple[str, ...]
    recommended_actions: tuple[str, ...]
    payload_policy: str = COMMAND_SANDBOX_PROCESS_PAYLOAD_POLICY

    def to_summary(self) -> dict[str, Any]:
        """输出低敏进程摘要，供测试、诊断和后续 runtime event 使用。"""

        return {
            "schemaVersion": COMMAND_SANDBOX_PROCESS_SCHEMA_VERSION,
            "payloadPolicy": self.payload_policy,
            "status": self.status.value,
            "processStarted": self.process_started,
            "exitCode": self.exit_code,
            "timedOut": self.timed_out,
            "durationMs": self.duration_ms,
            "stdoutByteCount": self.stdout_byte_count,
            "stderrByteCount": self.stderr_byte_count,
            "stdoutTruncated": self.stdout_truncated,
            "stderrTruncated": self.stderr_truncated,
            "artifactReference": self.artifact_reference,
            "issueCodes": list(self.issue_codes),
            "evidenceCodes": list(self.evidence_codes),
            "recommendedActions": list(self.recommended_actions),
        }

    def to_worker_run_request(
        self,
        base_request: ControlledCommandWorkerRunRequest,
    ) -> ControlledCommandWorkerRunRequest:
        """把进程结果转换成 Java receipt runner 可消费的低敏请求。

        转换原则：
        - 成功进程写 `EXECUTION_SUCCEEDED`；
        - 非零退出或超时写 `EXECUTION_FAILED`，因为进程已经启动，不能伪装成 precheck；
        - `BLOCKED` 表示进程未启动，转为失败预检；
        - 不把 stdout/stderr 正文、argv 或 workspace_root 写入 receipt，只写 artifactReference 和低敏说明。
        """

        if self.status == CommandSandboxProcessStatus.SUCCEEDED:
            run_mode = ControlledCommandWorkerRunMode.CONTROLLED_PROCESS_EXECUTION_SUCCESS
            issue_codes = base_request.command_safety_issue_codes
        elif self.status == CommandSandboxProcessStatus.BLOCKED:
            run_mode = ControlledCommandWorkerRunMode.PRECHECK_ONLY
            issue_codes = tuple(dict.fromkeys((*base_request.command_safety_issue_codes, *self.issue_codes)))
        elif self.status == CommandSandboxProcessStatus.TIMED_OUT:
            run_mode = ControlledCommandWorkerRunMode.CONTROLLED_PROCESS_TIMEOUT
            issue_codes = base_request.command_safety_issue_codes
        else:
            run_mode = ControlledCommandWorkerRunMode.CONTROLLED_PROCESS_EXECUTION_FAILURE
            issue_codes = base_request.command_safety_issue_codes
        return replace(
            base_request,
            run_mode=run_mode,
            command_safety_issue_codes=_safe_machine_codes(issue_codes),
            artifact_reference_type="COMMAND_OUTPUT_ARTIFACT" if self.artifact_reference else None,
            artifact_reference=self.artifact_reference,
            artifact_available=bool(self.artifact_reference),
            execution_performed=self.process_started,
            worker_receipt_mode="CONTROLLED_PROCESS_RESULT" if self.process_started else "PRECHECK_ONLY",
            operator_message=_message_for_process_result(self),
            recommended_actions=_safe_recommended_actions(
                tuple(dict.fromkeys((*base_request.recommended_actions, *self.recommended_actions)))
            ),
        )


class CommandSandboxProcessRunner:
    """host-local 最小受控进程外壳。

    生产级容器沙箱还需要 cgroup/namespace/seccomp、网络隔离、文件系统挂载策略和审计采样。本类先实现可测试的最小安全链：
    admission 通过 -> 校验 argv/workspace/env -> 启动 `shell=False` 子进程 -> 按预算读取输出元数据 -> 转 receipt。
    """

    def __init__(self, settings: CommandSandboxProcessRunnerSettings | None = None) -> None:
        self._settings = settings or CommandSandboxProcessRunnerSettings()

    def run(self, request: CommandSandboxProcessRunRequest) -> CommandSandboxProcessRunResult:
        """执行一次受控进程，并返回低敏结果。"""

        blocked = self._precheck(request)
        if blocked:
            return blocked
        workspace_root = Path(request.workspace_root).resolve()
        timeout_seconds = self._timeout_seconds(request)
        output_limit = self._output_byte_limit(request)
        per_stream_limit = max(256, output_limit // 2)
        stdout_capture = _BoundedStreamCapture(per_stream_limit)
        stderr_capture = _BoundedStreamCapture(per_stream_limit)
        started_at = time.monotonic()
        process: subprocess.Popen[bytes] | None = None
        try:
            process = subprocess.Popen(  # noqa: S603 - argv 已通过白名单校验，且 shell=False
                tuple(request.command_argv),
                cwd=str(workspace_root),
                env=self._environment_for(request),
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                stdin=subprocess.DEVNULL,
                shell=False,
            )
            stdout_thread = _drain_stream(process.stdout, stdout_capture)
            stderr_thread = _drain_stream(process.stderr, stderr_capture)
            timed_out = False
            try:
                exit_code = process.wait(timeout=timeout_seconds)
            except subprocess.TimeoutExpired:
                timed_out = True
                process.kill()
                exit_code = process.wait(timeout=5)
            stdout_thread.join(timeout=2)
            stderr_thread.join(timeout=2)
        except Exception:
            return self._blocked("PROCESS_START_FAILED", "进程未能启动，已按 fail-closed 处理。", started_at)
        return self._result_from_process(
            exit_code=exit_code,
            timed_out=timed_out,
            duration_ms=_duration_ms(started_at),
            stdout_capture=stdout_capture,
            stderr_capture=stderr_capture,
            artifact_reference=request.artifact_reference or _default_artifact_reference(request),
        )

    def _precheck(self, request: CommandSandboxProcessRunRequest) -> CommandSandboxProcessRunResult | None:
        """执行所有不会启动进程的前置校验。"""

        if not self._settings.enabled:
            return self._blocked("PROCESS_RUNNER_DISABLED", "受控进程外壳未启用，未启动命令。")
        if not bool(getattr(request.admission_result, "accepted", False)):
            return self._blocked("SANDBOX_ADMISSION_NOT_ACCEPTED", "Java sandbox admission 未准入，未启动命令。")
        self._validate_argv(request.command_argv)
        self._validate_workspace(request.workspace_root, request.workspace_reference)
        self._environment_for(request)
        return None

    def _validate_argv(self, argv: tuple[str, ...]) -> None:
        """校验 argv，不接受 shell 字符串。"""

        if not argv or len(argv) > 32:
            raise ValueError("command_argv 必须包含 1-32 个参数")
        allowed = {item.lower() for item in self._settings.allowed_executable_names}
        executable_name = Path(argv[0]).name.lower()
        if not allowed or executable_name not in allowed:
            raise ValueError("可执行文件不在 allowed_executable_names 白名单中")
        for value in argv:
            text = str(value)
            if not text or "\x00" in text or len(text) > 500 or _looks_sensitive(text):
                raise ValueError("command_argv 包含空值、超长值、NUL 或疑似敏感正文")

    def _validate_workspace(self, workspace_root: str, workspace_reference: str) -> None:
        """校验真实 workspace 根目录与低敏 workspace 引用。"""

        if not _is_safe_workspace_reference(workspace_reference):
            raise ValueError("workspace_reference 必须是低敏受控引用")
        root = Path(workspace_root).resolve()
        if not root.exists() or not root.is_dir():
            raise ValueError("workspace_root 必须是已存在目录")
        allowed_roots = tuple(Path(item).resolve() for item in self._settings.workspace_root_allowlist)
        if not allowed_roots:
            raise ValueError("workspace_root_allowlist 不能为空")
        if not any(root == allowed or allowed in root.parents for allowed in allowed_roots):
            raise ValueError("workspace_root 不在允许的工作区根目录内")

    def _environment_for(self, request: CommandSandboxProcessRunRequest) -> dict[str, str]:
        """构造最小环境变量集合。"""

        env: dict[str, str] = {}
        for key in self._settings.inherited_environment_keys:
            value = os.environ.get(key)
            if value:
                env[key] = value
        for key, value in (*self._settings.extra_environment, *request.environment):
            safe_key = _safe_env_key(key)
            safe_value = _safe_text(value, max_length=240)
            if safe_value is None:
                raise ValueError("环境变量值不能为空或包含敏感片段")
            env[safe_key] = safe_value
        return env

    def _timeout_seconds(self, request: CommandSandboxProcessRunRequest) -> int:
        value = _non_negative_int(request.timeout_seconds or getattr(request.admission_result, "normalized_timeout_seconds", None))
        if value <= 0:
            value = self._settings.default_timeout_seconds
        return min(value, max(1, self._settings.max_timeout_seconds))

    def _output_byte_limit(self, request: CommandSandboxProcessRunRequest) -> int:
        value = _non_negative_int(
            request.output_byte_limit_bytes
            or getattr(request.admission_result, "normalized_output_byte_limit_bytes", None)
        )
        if value <= 0:
            value = self._settings.default_output_byte_limit_bytes
        return min(value, max(512, self._settings.max_output_byte_limit_bytes))

    def _blocked(
        self,
        issue_code: str,
        action: str,
        started_at: float | None = None,
    ) -> CommandSandboxProcessRunResult:
        return CommandSandboxProcessRunResult(
            status=CommandSandboxProcessStatus.BLOCKED,
            process_started=False,
            exit_code=None,
            timed_out=False,
            duration_ms=_duration_ms(started_at) if started_at else 0,
            stdout_byte_count=0,
            stderr_byte_count=0,
            stdout_truncated=False,
            stderr_truncated=False,
            artifact_reference=None,
            issue_codes=(issue_code,),
            evidence_codes=(),
            recommended_actions=(action,),
        )

    def _result_from_process(
        self,
        *,
        exit_code: int,
        timed_out: bool,
        duration_ms: int,
        stdout_capture: "_BoundedStreamCapture",
        stderr_capture: "_BoundedStreamCapture",
        artifact_reference: str,
    ) -> CommandSandboxProcessRunResult:
        status = CommandSandboxProcessStatus.SUCCEEDED if exit_code == 0 and not timed_out else CommandSandboxProcessStatus.FAILED
        if timed_out:
            status = CommandSandboxProcessStatus.TIMED_OUT
        return CommandSandboxProcessRunResult(
            status=status,
            process_started=True,
            exit_code=exit_code,
            timed_out=timed_out,
            duration_ms=duration_ms,
            stdout_byte_count=stdout_capture.total_bytes,
            stderr_byte_count=stderr_capture.total_bytes,
            stdout_truncated=stdout_capture.truncated,
            stderr_truncated=stderr_capture.truncated,
            artifact_reference=artifact_reference,
            issue_codes=(),
            evidence_codes=("PROCESS_STARTED", "OUTPUT_BUDGET_APPLIED"),
            recommended_actions=("将低敏进程结果写回 Java command worker receipt。",),
        )


@dataclass
class _BoundedStreamCapture:
    """有界输出读取器，只统计字节数并保留有限缓冲，不向外暴露正文。"""

    byte_limit: int
    total_bytes: int = 0
    truncated: bool = False

    def add(self, chunk: bytes) -> None:
        self.total_bytes += len(chunk)
        if self.total_bytes > self.byte_limit:
            self.truncated = True


def _drain_stream(stream: Any, capture: _BoundedStreamCapture) -> Thread:
    def target() -> None:
        if stream is None:
            return
        while True:
            chunk = stream.read(4096)
            if not chunk:
                break
            capture.add(chunk)

    thread = Thread(target=target, daemon=True)
    thread.start()
    return thread


def _safe_env_key(key: str) -> str:
    text = str(key).strip().upper()
    if not text or len(text) > 80 or not text.replace("_", "").isalnum() or text[0].isdigit():
        raise ValueError("环境变量名只能使用大写字母、数字和下划线，且不能以数字开头")
    if any(marker in text.lower() for marker in ("token", "secret", "password", "credential", "key")):
        raise ValueError("环境变量名不能疑似承载凭据")
    return text


def _default_artifact_reference(request: CommandSandboxProcessRunRequest) -> str:
    return f"command-output:{request.run_id}/{request.command_id}/process-result"


def _message_for_process_result(result: CommandSandboxProcessRunResult) -> str:
    return {
        CommandSandboxProcessStatus.SUCCEEDED: "Python 受控进程外壳已完成执行，并生成低敏进程结果引用。",
        CommandSandboxProcessStatus.FAILED: "Python 受控进程外壳已启动进程，但进程以失败状态结束。",
        CommandSandboxProcessStatus.TIMED_OUT: "Python 受控进程外壳已按超时策略终止进程。",
        CommandSandboxProcessStatus.BLOCKED: "Python 受控进程外壳在启动前阻断命令。",
    }[result.status]


def _duration_ms(started_at: float) -> int:
    return max(0, int((time.monotonic() - started_at) * 1000))

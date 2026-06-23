"""受控命令 Worker Runner 的低敏回执合同。

本模块是 Python/OpenClaw-style 执行层向 Java Agent Runtime 5.94 command worker receipt 合同靠拢的第一步。
它刻意不执行真实 shell、不读取 payloadReference、不打开文件、不访问网络工具、不采集 stdout/stderr，而是只做两件事：

1. 在 Python 侧提前镜像 Java worker receipt 的关键状态约束，避免非法回执进入跨服务调用；
2. 生成 Java `AgentToolActionCommandWorkerReceiptRequest` 可消费的低敏 payload，并在显式启用时 POST 给 Java 内部接口。

这样做的商业化意义是：在真正引入沙箱进程、worker lease、stdout/stderr 裁剪、artifact 二次鉴权之前，先把
“副作用事实如何被证明、如何被审计、如何避免泄露”这条主合同固定下来。后续真实 runner 只需要替换本模块的
执行来源，不应该改动回执的低敏边界。
"""

from __future__ import annotations

import re
from collections.abc import Mapping
from dataclasses import dataclass, field
from enum import Enum
from typing import Any


COMMAND_WORKER_RECEIPT_SCHEMA_VERSION = "datasmart.python-ai-runtime.controlled-command-worker-runner.v1"
JAVA_COMMAND_WORKER_RECEIPT_ROUTE_TEMPLATE = (
    "/internal/agent-runtime/sessions/{session_id}/runs/{run_id}/tool-executions/command-worker-receipts"
)
COMMAND_WORKER_RECEIPT_PAYLOAD_POLICY = (
    "SUMMARY_ONLY_NO_COMMAND_LINE_NO_STDIO_NO_TOOL_ARGUMENTS_NO_PROMPT_NO_SQL_NO_PAYLOAD_BODY"
)

ALLOW_CONTROLLED_EXECUTION = "ALLOW_CONTROLLED_EXECUTION"
SAFE_ARTIFACT_REFERENCE_PREFIXES = (
    "agent-artifact:",
    "artifact:",
    "minio-object:",
    "agent-output:",
    "command-output:",
    "task-artifact:",
)
SAFE_MACHINE_CODE_PATTERN = re.compile(r"^[A-Z0-9_.:-]{1,120}$")
SAFE_REFERENCE_PATTERN = re.compile(r"^[a-zA-Z0-9_.:/=@+-]{1,220}$")
SENSITIVE_TEXT_MARKERS = (
    "select ",
    "insert ",
    "update ",
    "delete ",
    "authorization:",
    "bearer ",
    "password",
    "secret",
    "credential",
    "api_key",
    "apikey",
    "prompt:",
    "commandline",
    "command line",
    "stdout",
    "stderr",
    "workingdirectory",
    "workspaceroot",
    "http://",
    "https://",
    "jdbc:",
)


class ControlledCommandWorkerRunMode(str, Enum):
    """Python 侧当前允许模拟的 worker 运行模式。

    这里没有 `REAL_EXECUTION`，是有意的产品边界：真实命令执行必须等到 sandbox、lease、输出裁剪、artifact 权限
    和补偿机制齐备后再开启。当前模式只用于把 Java receipt 合同接到 Python 执行层，使后续真实 runner 有一个
    稳定的、可测试的回执外壳。
    """

    PRECHECK_ONLY = "PRECHECK_ONLY"
    SIMULATED_EXECUTION_SUCCESS = "SIMULATED_EXECUTION_SUCCESS"
    SIMULATED_EXECUTION_FAILURE = "SIMULATED_EXECUTION_FAILURE"
    CAPACITY_LIMITED = "CAPACITY_LIMITED"


class CommandWorkerReceiptOutcome(str, Enum):
    """与 Java `AgentToolActionCommandWorkerReceiptService` 对齐的低敏回执结果。"""

    FAILED_PRECHECK = "FAILED_PRECHECK"
    WORKER_PRECHECK_PASSED = "WORKER_PRECHECK_PASSED"
    EXECUTION_SUCCEEDED = "EXECUTION_SUCCEEDED"
    EXECUTION_FAILED = "EXECUTION_FAILED"
    CAPACITY_LIMITED = "CAPACITY_LIMITED"


@dataclass(frozen=True)
class ControlledCommandWorkerRunRequest:
    """受控命令 worker 的低敏运行请求。

    字段说明：
    - `session_id/run_id/command_id` 是串联 Agent 会话、单次运行和 command outbox 指令的最低定位信息；
    - `run_mode` 表示本次 Python runner 是只做预检、模拟成功、模拟失败还是容量受限；
    - `command_safety_decision/policy_version/issue_codes` 必须来自 Java host 或可信控制面，而不是模型自由生成；
    - `normalized_timeout_seconds/normalized_output_byte_limit_bytes` 只记录裁剪后的预算，不记录真实命令参数；
    - `artifact_reference` 只能是受控对象引用，不能是 URL、真实路径、stdout/stderr 正文或业务 payload；
    - `operator_message/recommended_actions` 只允许低敏说明，供 timeline 或运维台展示。
    """

    session_id: str
    run_id: str
    command_id: str
    run_mode: ControlledCommandWorkerRunMode = ControlledCommandWorkerRunMode.PRECHECK_ONLY
    task_id: int | None = None
    task_run_id: int | None = None
    executor_id: str = "python-controlled-command-worker"
    tenant_id: int | None = None
    project_id: int | None = None
    actor_id: int | None = None
    task_status: str = "RUNNING"
    command_safety_decision: str = "UNKNOWN"
    command_safety_policy_version: str | None = None
    command_safety_issue_codes: tuple[str, ...] = field(default_factory=tuple)
    normalized_timeout_seconds: int = 30
    normalized_output_byte_limit_bytes: int = 4096
    artifact_reference_type: str | None = None
    artifact_reference: str | None = None
    artifact_available: bool = False
    audit_id: str | None = None
    tool_code: str = "command.run-program"
    target_service: str = "python-ai-runtime-controlled-worker"
    worker_receipt_mode: str | None = None
    operator_message: str | None = None
    recommended_actions: tuple[str, ...] = field(default_factory=tuple)
    idempotency_key: str | None = None


@dataclass(frozen=True)
class ControlledCommandWorkerReceipt:
    """Java command worker receipt request 的 Python 表达。

    `java_payload` 是唯一允许跨服务传递的正文。它只包含 Java DTO 明确支持的低敏字段，不包含命令行、路径、环境变量、
    stdout/stderr、工具参数、SQL、prompt、模型输出、凭据或内部 endpoint。`execution_performed=False` 用于提醒调用方：
    该 runner 当前仍是受控模拟/预检合同，不是真实 shell runner。
    """

    schema_version: str
    outcome: CommandWorkerReceiptOutcome
    java_payload: dict[str, Any]
    execution_performed: bool = False
    payload_policy: str = COMMAND_WORKER_RECEIPT_PAYLOAD_POLICY

    def to_summary(self) -> dict[str, Any]:
        """输出可用于 API、测试或运行时诊断的低敏摘要。"""

        return {
            "schemaVersion": self.schema_version,
            "payloadPolicy": self.payload_policy,
            "executionPerformed": self.execution_performed,
            "outcome": self.outcome.value,
            "javaPayload": self.java_payload,
        }


@dataclass(frozen=True)
class ControlledCommandWorkerRunResult:
    """受控 runner 的完整低敏执行结果。"""

    receipt: ControlledCommandWorkerReceipt
    post_result: Any | None = None

    def to_summary(self) -> dict[str, Any]:
        """输出统一摘要。"""

        return {
            "schemaVersion": COMMAND_WORKER_RECEIPT_SCHEMA_VERSION,
            "receipt": self.receipt.to_summary(),
            "postResult": self.post_result.to_summary() if self.post_result else None,
        }

class ControlledCommandWorkerRunner:
    """受控命令 worker runner。

    本类未来会成为真实 sandbox runner 的外层适配点。当前版本只根据可信控制面给出的安全决策生成回执，不执行命令。
    规则与 Java 5.94 对齐：
    - 安全决策不是 `ALLOW_CONTROLLED_EXECUTION` 或仍有 issueCode 时，降级为 `FAILED_PRECHECK`；
    - `PRECHECK_ONLY` 只允许输出 `WORKER_PRECHECK_PASSED`，不能声明副作用；
    - 模拟成功才会声明 `sideEffectStarted=true` 和 `sideEffectExecuted=true`；
    - 模拟失败表示进入了受控执行区但未确认副作用完成，因此 `sideEffectExecuted=false`；
    - 容量受限不会通过预检，也不会进入副作用区。
    """

    def build_receipt(self, request: ControlledCommandWorkerRunRequest) -> ControlledCommandWorkerReceipt:
        """根据低敏运行请求生成 Java 可消费的 command worker receipt payload。"""

        self._validate_request_boundary(request)
        safe_issue_codes = _safe_machine_codes(request.command_safety_issue_codes)
        safety_decision = _normalize_machine_code(request.command_safety_decision) or "UNKNOWN"
        safety_allows_execution = safety_decision == ALLOW_CONTROLLED_EXECUTION and not safe_issue_codes

        outcome = self._outcome_for(request.run_mode, safety_allows_execution)
        pre_check_passed = outcome in {
            CommandWorkerReceiptOutcome.WORKER_PRECHECK_PASSED,
            CommandWorkerReceiptOutcome.EXECUTION_SUCCEEDED,
            CommandWorkerReceiptOutcome.EXECUTION_FAILED,
        }
        side_effect_started = outcome in {
            CommandWorkerReceiptOutcome.EXECUTION_SUCCEEDED,
            CommandWorkerReceiptOutcome.EXECUTION_FAILED,
        }
        side_effect_executed = outcome is CommandWorkerReceiptOutcome.EXECUTION_SUCCEEDED
        artifact_reference = self._artifact_reference_for(request, side_effect_executed)
        payload = {
            "commandId": _required_text(request.command_id, "command_id"),
            "taskId": request.task_id,
            "taskRunId": request.task_run_id,
            "executorId": _safe_text(request.executor_id, fallback="python-controlled-command-worker", max_length=160),
            "tenantId": request.tenant_id,
            "projectId": request.project_id,
            "actorId": request.actor_id,
            "taskStatus": self._task_status_for(request, outcome),
            "outcome": outcome.value,
            "preCheckPassed": pre_check_passed,
            "sideEffectStarted": side_effect_started,
            "sideEffectExecuted": side_effect_executed,
            "commandSafetyDecision": safety_decision,
            "commandSafetyPolicyVersion": _safe_text(request.command_safety_policy_version, max_length=160),
            "commandSafetyIssueCodes": list(safe_issue_codes),
            "normalizedTimeoutSeconds": _non_negative_int(request.normalized_timeout_seconds),
            "normalizedOutputByteLimitBytes": _non_negative_int(request.normalized_output_byte_limit_bytes),
            "artifactReferenceType": self._artifact_reference_type_for(request, side_effect_executed),
            "artifactReference": artifact_reference,
            "artifactAvailable": bool(artifact_reference),
            "errorCode": self._error_code_for(outcome),
            "auditId": _safe_text(request.audit_id, max_length=200),
            "toolCode": _safe_text(request.tool_code, fallback="command.run-program", max_length=160),
            "targetService": _safe_text(request.target_service, fallback="python-ai-runtime-controlled-worker", max_length=120),
            "workerReceiptMode": request.worker_receipt_mode or self._worker_receipt_mode_for(outcome),
            "message": _safe_text(request.operator_message, fallback=self._default_message_for(outcome), max_length=500),
            "recommendedActions": list(_safe_recommended_actions(request.recommended_actions)),
            "idempotencyKey": self._idempotency_key_for(request, outcome),
        }
        return ControlledCommandWorkerReceipt(
            schema_version=COMMAND_WORKER_RECEIPT_SCHEMA_VERSION,
            outcome=outcome,
            java_payload=_drop_none(payload),
        )

    def run(
        self,
        request: ControlledCommandWorkerRunRequest,
        *,
        receipt_client: Any | None = None,
        post_to_java: bool = False,
        trace_id: str | None = None,
    ) -> ControlledCommandWorkerRunResult:
        """生成回执，并按需提交给 Java。

        `post_to_java=False` 是默认值，因为当前 runner 主要用于合同闭环和单元测试。真实 worker 上线时，可以在完成
        worker lease、重试和死信策略后显式打开该开关。
        """

        receipt = self.build_receipt(request)
        post_result = None
        if post_to_java:
            from datasmart_ai_runtime.services.tools.command_worker_receipt_client import (
                JavaCommandWorkerReceiptClient,
            )

            client = receipt_client or JavaCommandWorkerReceiptClient()
            post_result = client.post_receipt(
                session_id=request.session_id,
                run_id=request.run_id,
                receipt=receipt,
                trace_id=trace_id,
            )
        return ControlledCommandWorkerRunResult(receipt=receipt, post_result=post_result)

    def _validate_request_boundary(self, request: ControlledCommandWorkerRunRequest) -> None:
        """验证 runner 请求本身没有越过低敏边界。"""

        _required_text(request.session_id, "session_id")
        _required_text(request.run_id, "run_id")
        _required_text(request.command_id, "command_id")
        _safe_machine_codes(request.command_safety_issue_codes)
        _safe_recommended_actions(request.recommended_actions)
        if _looks_sensitive(request.operator_message):
            raise ValueError("operator_message 疑似包含命令、输出、SQL、prompt、凭据或内部地址，不能进入 worker receipt")
        if request.artifact_reference and not _is_safe_artifact_reference(request.artifact_reference):
            raise ValueError("artifact_reference 必须是受控低敏引用，不能是 URL、路径逃逸、对象正文或凭据片段")

    def _outcome_for(
        self,
        run_mode: ControlledCommandWorkerRunMode,
        safety_allows_execution: bool,
    ) -> CommandWorkerReceiptOutcome:
        """把 Python 运行模式映射为 Java receipt outcome。"""

        if not safety_allows_execution:
            return CommandWorkerReceiptOutcome.FAILED_PRECHECK
        if run_mode == ControlledCommandWorkerRunMode.PRECHECK_ONLY:
            return CommandWorkerReceiptOutcome.WORKER_PRECHECK_PASSED
        if run_mode == ControlledCommandWorkerRunMode.SIMULATED_EXECUTION_SUCCESS:
            return CommandWorkerReceiptOutcome.EXECUTION_SUCCEEDED
        if run_mode == ControlledCommandWorkerRunMode.SIMULATED_EXECUTION_FAILURE:
            return CommandWorkerReceiptOutcome.EXECUTION_FAILED
        return CommandWorkerReceiptOutcome.CAPACITY_LIMITED

    def _artifact_reference_for(self, request: ControlledCommandWorkerRunRequest, side_effect_executed: bool) -> str | None:
        """生成或校验 artifactReference。

        模拟成功时，如果调用方没有传入低敏 artifact 引用，runner 会生成一个稳定的 `agent-artifact:` 引用。这不是文件路径，
        也不是对象正文，只是未来 artifact store 可以识别的低敏占位引用。
        """

        if request.artifact_reference:
            return request.artifact_reference.strip()
        if side_effect_executed:
            run_id = _required_text(request.run_id, "run_id")
            command_id = _required_text(request.command_id, "command_id")
            return f"agent-artifact:{run_id}/{command_id}/simulated-receipt"
        return None

    def _artifact_reference_type_for(self, request: ControlledCommandWorkerRunRequest, side_effect_executed: bool) -> str | None:
        if request.artifact_reference_type:
            return _safe_text(request.artifact_reference_type, max_length=80)
        return "AGENT_ARTIFACT" if side_effect_executed else None

    def _task_status_for(
        self,
        request: ControlledCommandWorkerRunRequest,
        outcome: CommandWorkerReceiptOutcome,
    ) -> str:
        if outcome == CommandWorkerReceiptOutcome.EXECUTION_SUCCEEDED:
            return "SUCCEEDED"
        if outcome in {CommandWorkerReceiptOutcome.EXECUTION_FAILED, CommandWorkerReceiptOutcome.FAILED_PRECHECK}:
            return "FAILED"
        if outcome == CommandWorkerReceiptOutcome.CAPACITY_LIMITED:
            return "PENDING"
        return _safe_text(request.task_status, fallback="RUNNING", max_length=80) or "RUNNING"

    def _error_code_for(self, outcome: CommandWorkerReceiptOutcome) -> str:
        return {
            CommandWorkerReceiptOutcome.FAILED_PRECHECK: "AGENT_COMMAND_WORKER_PRECHECK_REJECTED",
            CommandWorkerReceiptOutcome.WORKER_PRECHECK_PASSED: "AGENT_COMMAND_WORKER_PRECHECK_PASSED",
            CommandWorkerReceiptOutcome.EXECUTION_SUCCEEDED: "AGENT_COMMAND_WORKER_EXECUTION_SUCCEEDED",
            CommandWorkerReceiptOutcome.EXECUTION_FAILED: "AGENT_COMMAND_WORKER_EXECUTION_FAILED",
            CommandWorkerReceiptOutcome.CAPACITY_LIMITED: "AGENT_COMMAND_WORKER_CAPACITY_LIMITED",
        }[outcome]

    def _worker_receipt_mode_for(self, outcome: CommandWorkerReceiptOutcome) -> str:
        if outcome in {CommandWorkerReceiptOutcome.FAILED_PRECHECK, CommandWorkerReceiptOutcome.WORKER_PRECHECK_PASSED}:
            return "PRECHECK_ONLY"
        if outcome in {CommandWorkerReceiptOutcome.EXECUTION_SUCCEEDED, CommandWorkerReceiptOutcome.EXECUTION_FAILED}:
            return "EXECUTION_RESULT"
        return "RECEIPT_SUMMARY"

    def _default_message_for(self, outcome: CommandWorkerReceiptOutcome) -> str:
        return {
            CommandWorkerReceiptOutcome.FAILED_PRECHECK: "Python 受控 worker 在执行前复核阶段阻断命令，未产生真实副作用。",
            CommandWorkerReceiptOutcome.WORKER_PRECHECK_PASSED: "Python 受控 worker 复核通过，但当前只进行预检，不进入真实命令执行。",
            CommandWorkerReceiptOutcome.EXECUTION_SUCCEEDED: "Python 受控 worker 生成模拟成功回执，用于验证 Java receipt 合同。",
            CommandWorkerReceiptOutcome.EXECUTION_FAILED: "Python 受控 worker 生成模拟失败回执，用于验证失败治理链路。",
            CommandWorkerReceiptOutcome.CAPACITY_LIMITED: "Python 受控 worker 受容量保护限制，暂不进入执行区。",
        }[outcome]

    def _idempotency_key_for(
        self,
        request: ControlledCommandWorkerRunRequest,
        outcome: CommandWorkerReceiptOutcome,
    ) -> str:
        explicit_key = _safe_text(request.idempotency_key, max_length=220)
        if explicit_key:
            return explicit_key
        return f"command-worker:{request.run_id}:{request.command_id}:{outcome.value}:{request.run_mode.value}"

def _drop_none(payload: Mapping[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in payload.items() if value is not None}


def _required_text(value: Any, field_name: str) -> str:
    text = _safe_text(value, max_length=260)
    if not text:
        raise ValueError(f"{field_name} 不能为空")
    return text


def _safe_text(value: Any, *, fallback: str | None = None, max_length: int) -> str | None:
    if value is None:
        return fallback
    text = str(value).strip()
    if not text:
        return fallback
    if _looks_sensitive(text):
        if fallback is not None:
            return fallback
        raise ValueError("低敏文本字段疑似包含命令、输出、SQL、prompt、凭据或内部地址")
    return text[:max_length]


def _safe_recommended_actions(actions: tuple[str, ...]) -> tuple[str, ...]:
    result: list[str] = []
    for action in actions[:6]:
        result.append(_required_safe_action(action))
    return tuple(result)


def _required_safe_action(action: str) -> str:
    value = _safe_text(action, max_length=240)
    if not value:
        raise ValueError("recommended_actions 中不能包含空动作")
    return value


def _safe_machine_codes(codes: tuple[str, ...]) -> tuple[str, ...]:
    result: list[str] = []
    for code in codes[:12]:
        normalized = _normalize_machine_code(code)
        if not normalized:
            continue
        if not SAFE_MACHINE_CODE_PATTERN.fullmatch(normalized):
            raise ValueError("机器码只能包含大写字母、数字、下划线、点、冒号或短横线")
        if any(marker in normalized.lower() for marker in ("http", "password", "secret", "token", "credential")):
            raise ValueError("机器码不能携带 URL、凭据或敏感片段")
        result.append(normalized)
    return tuple(result)


def _normalize_machine_code(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip().upper()
    return text or None


def _non_negative_int(value: Any) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return 0
    return max(parsed, 0)


def _is_safe_artifact_reference(value: str) -> bool:
    reference = value.strip()
    lowered = reference.lower()
    if not lowered.startswith(SAFE_ARTIFACT_REFERENCE_PREFIXES):
        return False
    if _looks_sensitive(reference):
        return False
    if "://" in lowered or ".." in lowered or "\\" in reference:
        return False
    if "{" in reference or "}" in reference or "\n" in reference or "\r" in reference:
        return False
    return bool(SAFE_REFERENCE_PATTERN.fullmatch(reference))


def _looks_sensitive(value: Any) -> bool:
    if value is None:
        return False
    lowered = str(value).lower()
    return any(marker in lowered for marker in SENSITIVE_TEXT_MARKERS)

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

from dataclasses import dataclass, field, replace
from enum import Enum
from typing import Any

from datasmart_ai_runtime.services.tools.controlled_command_worker_contract import (
    ALLOW_CONTROLLED_EXECUTION,
    COMMAND_WORKER_RECEIPT_PAYLOAD_POLICY,
    COMMAND_WORKER_RECEIPT_SCHEMA_VERSION,
    JAVA_COMMAND_WORKER_RECEIPT_ROUTE_TEMPLATE,
    _drop_none,
    _is_safe_artifact_reference,
    _looks_sensitive,
    _non_negative_int,
    _required_text,
    _safe_fencing_token,
    _safe_fencing_token_version,
    _safe_machine_codes,
    _normalize_machine_code,
    _safe_recommended_actions,
    _safe_text,
    _summary_payload,
)


class ControlledCommandWorkerRunMode(str, Enum):
    """Python 侧当前允许的 worker 运行模式。

    早期只有模拟模式；从 5.107 开始允许“受控进程外壳”把真实进程结果转换成 receipt。即便如此，receipt 仍然只接收
    低敏事实，不接收 argv、工作目录、stdout/stderr 正文或环境变量。
    """

    PRECHECK_ONLY = "PRECHECK_ONLY"
    SIMULATED_EXECUTION_SUCCESS = "SIMULATED_EXECUTION_SUCCESS"
    SIMULATED_EXECUTION_FAILURE = "SIMULATED_EXECUTION_FAILURE"
    CONTROLLED_PROCESS_EXECUTION_SUCCESS = "CONTROLLED_PROCESS_EXECUTION_SUCCESS"
    CONTROLLED_PROCESS_EXECUTION_FAILURE = "CONTROLLED_PROCESS_EXECUTION_FAILURE"
    CONTROLLED_PROCESS_TIMEOUT = "CONTROLLED_PROCESS_TIMEOUT"
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
    - `sandbox_admission_required` 表示调用方要求进入执行区前必须先回到 Java Host 做 admission；
    - `requested_isolation_mode/requested_cpu_millicores/requested_memory_mb/workspace_reference` 是未来真实 sandbox runner
      启动前的控制面输入，当前只作为低敏准入合同提交给 Java，不代表 Python 已经启动进程；
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
    sandbox_admission_required: bool = False
    requested_isolation_mode: str = "NO_NETWORK_PROCESS_SANDBOX"
    requested_cpu_millicores: int = 500
    requested_memory_mb: int = 512
    workspace_reference: str | None = None
    worker_lease_required: bool = False
    fencing_token: str | None = None
    worker_lease_version: int | None = None
    worker_lease_expires_at_ms: int | None = None
    artifact_reference_type: str | None = None
    artifact_reference: str | None = None
    artifact_available: bool = False
    execution_performed: bool = False
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
            "javaPayload": _summary_payload(self.java_payload),
        }


@dataclass(frozen=True)
class ControlledCommandWorkerRunResult:
    """受控 runner 的完整低敏执行结果。"""

    receipt: ControlledCommandWorkerReceipt
    sandbox_admission_result: Any | None = None
    post_result: Any | None = None

    def to_summary(self) -> dict[str, Any]:
        """输出统一摘要。"""

        return {
            "schemaVersion": COMMAND_WORKER_RECEIPT_SCHEMA_VERSION,
            "receipt": self.receipt.to_summary(),
            "sandboxAdmissionResult": (
                self.sandbox_admission_result.to_summary() if self.sandbox_admission_result else None
            ),
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
        worker_lease_required = bool(request.worker_lease_required or side_effect_started or side_effect_executed)
        self._validate_worker_lease_boundary(request, worker_lease_required)
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
            "workerLeaseRequired": worker_lease_required,
            "fencingToken": _safe_fencing_token(request.fencing_token),
            "workerLeaseVersion": request.worker_lease_version,
            "workerLeaseExpiresAtMs": request.worker_lease_expires_at_ms,
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
            execution_performed=bool(request.execution_performed),
        )

    def run(
        self,
        request: ControlledCommandWorkerRunRequest,
        *,
        sandbox_admission_client: Any | None = None,
        require_sandbox_admission: bool = False,
        receipt_client: Any | None = None,
        post_to_java: bool = False,
        trace_id: str | None = None,
    ) -> ControlledCommandWorkerRunResult:
        """生成回执，并按需提交给 Java。

        `post_to_java=False` 是默认值，因为当前 runner 主要用于合同闭环和单元测试。真实 worker 上线时，可以在完成
        worker lease、重试和死信策略后显式打开该开关。

        `require_sandbox_admission=True` 是面向真实/准真实执行区的保护开关。开启后，runner 必须先调用 Java
        sandbox admission：Java 控制面会复核 lease、安全决策、workspace 和资源预算；如果 admission 未启用、调用失败
        或返回拒绝，Python 会 fail-closed 生成 `FAILED_PRECHECK` 回执，而不会继续声明副作用已经发生。
        """

        effective_request = request
        sandbox_admission_result = None
        if self._should_request_sandbox_admission(request, require_sandbox_admission):
            from datasmart_ai_runtime.services.tools.command_sandbox_admission_client import (
                JavaCommandSandboxAdmissionClient,
            )

            client = sandbox_admission_client or JavaCommandSandboxAdmissionClient()
            sandbox_admission_result = client.request_admission(
                session_id=request.session_id,
                run_id=request.run_id,
                request=request,
                trace_id=trace_id,
            )
            effective_request = self._request_after_sandbox_admission(
                request,
                sandbox_admission_result,
                require_sandbox_admission=require_sandbox_admission,
            )

        receipt = self.build_receipt(effective_request)
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
        return ControlledCommandWorkerRunResult(
            receipt=receipt,
            sandbox_admission_result=sandbox_admission_result,
            post_result=post_result,
        )

    def _should_request_sandbox_admission(
        self,
        request: ControlledCommandWorkerRunRequest,
        require_sandbox_admission: bool,
    ) -> bool:
        """判断本次 runner 是否需要先请求 Java sandbox admission。

        这里没有简单地把所有 `SIMULATED_EXECUTION_*` 都强制接 admission，是为了保持历史单元测试、dry-run、离线学习环境
        可运行；但一旦调用方显式设置 `sandbox_admission_required` 或 `require_sandbox_admission`，就说明这条链路正在模拟
        或准备进入真实副作用区域，必须先经过 Java Host 控制面。
        """

        return bool(require_sandbox_admission or request.sandbox_admission_required)

    def _request_after_sandbox_admission(
        self,
        request: ControlledCommandWorkerRunRequest,
        sandbox_admission_result: Any,
        *,
        require_sandbox_admission: bool,
    ) -> ControlledCommandWorkerRunRequest:
        """把 Java sandbox admission 的低敏结果折叠回 runner 请求。

        - 准入成功：采用 Java 裁剪后的 timeout/output 预算，避免 Python 侧继续使用过大的本地预算；
        - 准入失败：不抛弃这次运行事实，而是生成带 issueCode 的失败预检回执，让 Java command outbox 能进入补偿/死信链路；
        - 客户端未启用且调用方要求 admission：同样 fail-closed，防止生产环境因为少配一个开关就绕过 Host admission。
        """

        accepted = bool(getattr(sandbox_admission_result, "accepted", False))
        skipped = bool(getattr(sandbox_admission_result, "skipped", False))
        if accepted:
            return replace(
                request,
                normalized_timeout_seconds=(
                    getattr(sandbox_admission_result, "normalized_timeout_seconds", None)
                    or request.normalized_timeout_seconds
                ),
                normalized_output_byte_limit_bytes=(
                    getattr(sandbox_admission_result, "normalized_output_byte_limit_bytes", None)
                    or request.normalized_output_byte_limit_bytes
                ),
            )

        issue_codes = list(request.command_safety_issue_codes)
        result_issue_codes = getattr(sandbox_admission_result, "issue_codes", ()) or ()
        issue_codes.extend(str(code) for code in result_issue_codes)
        if skipped and require_sandbox_admission:
            issue_codes.append("SANDBOX_ADMISSION_CLIENT_DISABLED")
        elif not issue_codes:
            issue_codes.append("SANDBOX_ADMISSION_DENIED")
        recommended_actions = tuple(request.recommended_actions) + tuple(
            getattr(sandbox_admission_result, "recommended_actions", ()) or ()
        )
        return replace(
            request,
            command_safety_issue_codes=_safe_machine_codes(tuple(dict.fromkeys(issue_codes))),
            operator_message="Java sandbox admission 未准入，Python worker 未启动命令执行。",
            recommended_actions=_safe_recommended_actions(recommended_actions),
        )

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

    def _validate_worker_lease_boundary(
        self,
        request: ControlledCommandWorkerRunRequest,
        worker_lease_required: bool,
    ) -> None:
        """校验进入副作用区前必须具备 worker lease 证据。

        这一步不会连接 Redis 或 Java durable store；它只保证 Python runner 生成的 Java payload 在合同层面已经携带
        fencingToken、leaseVersion 和过期时间。后续真实 sandbox runner 可以在执行前调用 lease manager 领取 token，
        再把 token 透传到本 runner。
        """

        if not worker_lease_required and not request.fencing_token:
            return
        token_version = _safe_fencing_token_version(request.fencing_token)
        if token_version is None:
            raise ValueError("worker lease required 时必须提供 cmd-lease:{version}:{digest} 格式的 fencing_token")
        if request.worker_lease_version is None or int(request.worker_lease_version) < 1:
            raise ValueError("worker lease required 时必须提供大于 0 的 worker_lease_version")
        if token_version != int(request.worker_lease_version):
            raise ValueError("fencing_token 中的版本号必须与 worker_lease_version 一致")
        if request.worker_lease_expires_at_ms is None or int(request.worker_lease_expires_at_ms) <= 0:
            raise ValueError("worker lease required 时必须提供大于 0 的 worker_lease_expires_at_ms")

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
        if run_mode == ControlledCommandWorkerRunMode.CONTROLLED_PROCESS_EXECUTION_SUCCESS:
            return CommandWorkerReceiptOutcome.EXECUTION_SUCCEEDED
        if run_mode in {
            ControlledCommandWorkerRunMode.SIMULATED_EXECUTION_FAILURE,
            ControlledCommandWorkerRunMode.CONTROLLED_PROCESS_EXECUTION_FAILURE,
            ControlledCommandWorkerRunMode.CONTROLLED_PROCESS_TIMEOUT,
        }:
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

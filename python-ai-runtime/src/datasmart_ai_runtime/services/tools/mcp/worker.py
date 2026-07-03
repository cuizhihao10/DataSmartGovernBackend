"""MCP Durable Worker Adapter。

前两个批次已经分别完成：
1. `McpDurableToolExecutionService`：拿到可信 admission 后执行 MCP `tools/call`；
2. `McpToolCallAdmissionBuilder`：把 Java 控制面 facts 转成可信 admission。

本模块把这两段串成“worker 可消费”的最小闭环：
- 输入：Java command proposal/outbox/permission/readiness facts + 本轮短生命周期 arguments；
- 处理：构造 admission，执行 MCP durable bridge；
- 输出：低敏 `ControlledCommandWorkerReceipt`，可选 POST 回 Java agent-runtime。

这里仍然不实现 Java outbox dispatcher，也不轮询数据库。原因是 outbox 的领取、重试、死信、租户公平、
分布式 lease 都属于 Java 控制面或后续 worker runtime 的职责。Python 侧先固定“拿到一条 outbox/facts 后，
如何安全执行并回写 receipt”的合同，避免为了追求闭环速度而把两端状态机耦合成不可维护的一坨。
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from typing import Any

from datasmart_ai_runtime.services.tools.command_worker_receipt_client import JavaCommandWorkerReceiptClient
from datasmart_ai_runtime.services.tools.controlled_command_worker_contract import (
    COMMAND_WORKER_RECEIPT_PAYLOAD_POLICY,
)
from datasmart_ai_runtime.services.tools.controlled_command_worker_runner import (
    COMMAND_WORKER_RECEIPT_SCHEMA_VERSION,
    CommandWorkerReceiptOutcome,
    ControlledCommandWorkerReceipt,
)
from datasmart_ai_runtime.services.tools.mcp.admission import (
    McpAdmissionBuildError,
    McpToolCallAdmissionBuilder,
)
from datasmart_ai_runtime.services.tools.mcp.execution import (
    McpDurableExecutionStatus,
    McpDurableToolExecutionResult,
    McpDurableToolExecutionService,
)


MCP_DURABLE_WORKER_SCHEMA_VERSION = "datasmart.mcp-durable-worker.v1"


@dataclass(frozen=True)
class McpDurableWorkerRunRequest:
    """MCP durable worker 的单次运行请求。

    字段说明：
    - `server_id/internal_tool_name`：定位已配置、已发现的 MCP Server 与内部工具名；
    - `arguments`：短生命周期工具实参，只进入 MCP SDK 调用，不进入 receipt、event 或 checkpoint；
    - `control_facts`：Java command proposal/outbox/permission/readiness/approval 低敏事实；
    - `fallback_context`：由 gateway 或当前 Agent request 可信注入的上下文字段，只能补 tenant/project/workspace 等范围；
    - `post_to_java`：是否把 receipt POST 给 Java。默认关闭，便于本地测试和 dry-run；
    - `session_id`：Java receipt 路由需要 sessionId；缺失时仍生成 receipt，但不会 POST；
    - `trace_id`：低敏链路追踪 ID。
    """

    server_id: str
    internal_tool_name: str
    arguments: Mapping[str, Any]
    control_facts: Mapping[str, Any]
    fallback_context: Mapping[str, Any] | None = None
    execution_node_id: str = "mcp_durable_worker"
    post_to_java: bool = False
    session_id: str | None = None
    trace_id: str | None = None


@dataclass(frozen=True)
class McpDurableWorkerRunResult:
    """MCP durable worker 的低敏运行结果。

    `execution_result` 可能包含运行时正文对象，因此只允许当前进程继续交给模型二轮消费；
    `receipt` 与 `to_summary()` 可进入 Java 控制面、审计、runtime event 和测试断言。
    """

    receipt: ControlledCommandWorkerReceipt
    execution_result: McpDurableToolExecutionResult | None = None
    admission_error: McpAdmissionBuildError | None = None
    post_result: Any | None = None

    def to_summary(self) -> dict[str, Any]:
        """输出低敏摘要，不包含 MCP arguments 或工具结果正文。"""

        return {
            "schemaVersion": MCP_DURABLE_WORKER_SCHEMA_VERSION,
            "receipt": self.receipt.to_summary(),
            "executionResult": self.execution_result.to_summary() if self.execution_result else None,
            "admissionError": self.admission_error.summary if self.admission_error else None,
            "postResult": self.post_result.to_summary() if self.post_result else None,
            "payloadPolicy": "LOW_SENSITIVE_MCP_WORKER_SUMMARY_ONLY",
        }


class McpDurableWorkerAdapter:
    """面向 outbox consumer 的 MCP 执行适配器。

    适配器的主路径是 fail-closed：
    - admission 构造失败：不调用 MCP，生成 `FAILED_PRECHECK` receipt；
    - MCP 调用失败：生成 `EXECUTION_FAILED` receipt，但不回显远端错误正文；
    - MCP 调用成功：生成 `EXECUTION_SUCCEEDED` receipt，结果正文只留在 `execution_result.runtime_result`。
    """

    def __init__(
        self,
        execution_service: McpDurableToolExecutionService,
        *,
        receipt_client: JavaCommandWorkerReceiptClient | None = None,
        admission_builder: McpToolCallAdmissionBuilder | None = None,
    ) -> None:
        self._execution_service = execution_service
        self._receipt_client = receipt_client or JavaCommandWorkerReceiptClient()
        self._admission_builder = admission_builder or McpToolCallAdmissionBuilder()

    async def run(self, request: McpDurableWorkerRunRequest) -> McpDurableWorkerRunResult:
        """运行一次 MCP durable worker。

        该方法适合被未来 Kafka/outbox consumer、LangGraph async 节点或内部 API 调用。它不会因为 Java receipt
        client 未启用而失败；默认会返回 skipped postResult，生产环境可显式启用 client 并设置 fail-open/fail-fast。
        """

        try:
            durable_request = self._execution_service.request_from_control_facts(
                server_id=request.server_id,
                internal_tool_name=request.internal_tool_name,
                arguments=request.arguments,
                control_facts=request.control_facts,
                fallback_context=request.fallback_context,
                execution_node_id=request.execution_node_id,
                trace_id=request.trace_id,
            )
        except McpAdmissionBuildError as exc:
            receipt = _receipt_from_admission_error(request, exc)
            return McpDurableWorkerRunResult(
                receipt=receipt,
                admission_error=exc,
                post_result=self._post_if_requested(request, receipt),
            )

        execution_result = await self._execution_service.execute(durable_request)
        receipt = _receipt_from_execution_result(request, execution_result)
        return McpDurableWorkerRunResult(
            receipt=receipt,
            execution_result=execution_result,
            post_result=self._post_if_requested(request, receipt),
        )

    def _post_if_requested(
        self,
        request: McpDurableWorkerRunRequest,
        receipt: ControlledCommandWorkerReceipt,
    ) -> Any | None:
        """按需把 receipt 写回 Java。

        缺少 sessionId/runId 时不做 POST，因为 Java receipt 路由无法定位 session/run。这里不抛异常，是为了
        让 outbox consumer 可以先把 receipt 摘要返回给上层补偿逻辑；真正生产环境应在 Java facts 中强制携带这些字段。
        """

        if not request.post_to_java:
            return None
        session_id = _optional_text(request.session_id) or _optional_text(
            _first(request.control_facts, "sessionId", "session_id")
        )
        run_id = _optional_text(_first(request.control_facts, "runId", "run_id"))
        if not session_id or not run_id:
            return None
        return self._receipt_client.post_receipt(
            session_id=session_id,
            run_id=run_id,
            receipt=receipt,
            trace_id=request.trace_id or _optional_text(_first(request.control_facts, "traceId", "trace_id")),
        )


def _receipt_from_execution_result(
    request: McpDurableWorkerRunRequest,
    execution_result: McpDurableToolExecutionResult,
) -> ControlledCommandWorkerReceipt:
    """把 MCP durable 执行结果转换为 Java command worker receipt 合同。"""

    status = execution_result.status
    if status == McpDurableExecutionStatus.SUCCEEDED:
        outcome = CommandWorkerReceiptOutcome.EXECUTION_SUCCEEDED
        pre_check_passed = True
        side_effect_started = True
        side_effect_executed = True
        task_status = "SUCCEEDED"
    elif status == McpDurableExecutionStatus.FAILED_PRECHECK:
        outcome = CommandWorkerReceiptOutcome.FAILED_PRECHECK
        pre_check_passed = False
        side_effect_started = False
        side_effect_executed = False
        task_status = "FAILED"
    else:
        outcome = CommandWorkerReceiptOutcome.EXECUTION_FAILED
        pre_check_passed = True
        side_effect_started = True
        side_effect_executed = False
        task_status = "FAILED"
    java_payload = _base_java_payload(
        request,
        outcome=outcome,
        task_status=task_status,
        pre_check_passed=pre_check_passed,
        side_effect_started=side_effect_started,
        side_effect_executed=side_effect_executed,
        error_code=execution_result.error_code or f"MCP_{status.value}",
        result_summary=execution_result.to_summary().get("resultSummary", {}),
    )
    return ControlledCommandWorkerReceipt(
        schema_version=COMMAND_WORKER_RECEIPT_SCHEMA_VERSION,
        outcome=outcome,
        java_payload=java_payload,
        execution_performed=side_effect_started,
        payload_policy=COMMAND_WORKER_RECEIPT_PAYLOAD_POLICY,
    )


def _receipt_from_admission_error(
    request: McpDurableWorkerRunRequest,
    error: McpAdmissionBuildError,
) -> ControlledCommandWorkerReceipt:
    """Admission 构造失败时生成失败 receipt，不调用外部 MCP Server。"""

    java_payload = _base_java_payload(
        request,
        outcome=CommandWorkerReceiptOutcome.FAILED_PRECHECK,
        task_status="FAILED",
        pre_check_passed=False,
        side_effect_started=False,
        side_effect_executed=False,
        error_code="MCP_ADMISSION_BUILD_FAILED",
        result_summary={"issueCodes": error.issue_codes},
    )
    java_payload["commandSafetyIssueCodes"] = list(error.issue_codes)
    return ControlledCommandWorkerReceipt(
        schema_version=COMMAND_WORKER_RECEIPT_SCHEMA_VERSION,
        outcome=CommandWorkerReceiptOutcome.FAILED_PRECHECK,
        java_payload=java_payload,
        execution_performed=False,
        payload_policy=COMMAND_WORKER_RECEIPT_PAYLOAD_POLICY,
    )


def _base_java_payload(
    request: McpDurableWorkerRunRequest,
    *,
    outcome: CommandWorkerReceiptOutcome,
    task_status: str,
    pre_check_passed: bool,
    side_effect_started: bool,
    side_effect_executed: bool,
    error_code: str,
    result_summary: Mapping[str, Any],
) -> dict[str, Any]:
    """生成 Java command worker receipt payload。

    payload 只放低敏字段：commandId、状态、工具名、结果摘要哈希/大小/截断标记和控制面引用。
    它不包含 MCP arguments、工具正文、远端 Server endpoint、token、prompt、SQL 或样本数据。
    """

    command_id = _optional_text(
        _first(request.control_facts, "commandId", "command_id", "callId", "call_id", "auditId", "audit_id")
    ) or request.internal_tool_name
    return _drop_none(
        {
            "commandId": command_id,
            "executorId": "python-mcp-durable-worker",
            "tenantId": _optional_int(_first(request.control_facts, "tenantId", "tenant_id")),
            "projectId": _optional_int(_first(request.control_facts, "projectId", "project_id")),
            "actorId": _optional_int(_first(request.control_facts, "actorId", "actor_id")),
            "taskStatus": task_status,
            "outcome": outcome.value,
            "preCheckPassed": pre_check_passed,
            "sideEffectStarted": side_effect_started,
            "sideEffectExecuted": side_effect_executed,
            "workerLeaseRequired": False,
            "commandSafetyDecision": "ALLOW_CONTROLLED_EXECUTION" if pre_check_passed else "MCP_ADMISSION_REJECTED",
            "commandSafetyPolicyVersion": _optional_text(
                _first(request.control_facts, "policyVersion", "policy_version")
            )
            or "mcp-admission-builder.v1",
            "commandSafetyIssueCodes": [],
            "normalizedTimeoutSeconds": _optional_int(
                _first(request.control_facts, "normalizedTimeoutSeconds", "normalized_timeout_seconds")
            )
            or 60,
            "normalizedOutputByteLimitBytes": _optional_int(
                _first(request.control_facts, "normalizedOutputByteLimitBytes", "normalized_output_byte_limit_bytes")
            )
            or 65536,
            "artifactReferenceType": "MCP_RESULT_SUMMARY",
            "artifactReference": _artifact_reference(request, result_summary),
            "artifactAvailable": bool(result_summary),
            "errorCode": error_code,
            "auditId": _optional_text(_first(request.control_facts, "auditId", "audit_id")),
            "toolCode": request.internal_tool_name,
            "targetService": "python-ai-runtime-mcp-client",
            "workerReceiptMode": "MCP_DURABLE_EXECUTION_RESULT",
            "message": _message_for(outcome),
            "recommendedActions": _recommended_actions_for(outcome),
            "idempotencyKey": _idempotency_key(request, outcome),
            "mcpResultSummary": dict(result_summary),
            "commandProposalId": _optional_text(
                _first(request.control_facts, "commandProposalId", "command_proposal_id", "proposalId")
            ),
            "outboxMessageId": _optional_text(_first(request.control_facts, "outboxMessageId", "outboxId", "outbox_id")),
            "checkpointId": _optional_text(_first(request.control_facts, "checkpointId", "checkpoint_id")),
        }
    )


def _artifact_reference(request: McpDurableWorkerRunRequest, result_summary: Mapping[str, Any]) -> str | None:
    """为 MCP 结果摘要生成低敏 artifact 引用。

    这不是工具结果正文位置，而是一个未来可由 MinIO/artifact store 使用的稳定占位引用。真实大结果落盘
    时应由对象存储写入器生成正式 artifactReference。
    """

    digest = _optional_text(result_summary.get("resultDigest"))
    if not digest:
        return None
    run_id = _optional_text(_first(request.control_facts, "runId", "run_id")) or "run"
    return f"agent-artifact:{run_id}/{request.internal_tool_name}/mcp-result-{digest[:16]}"


def _message_for(outcome: CommandWorkerReceiptOutcome) -> str:
    """生成低敏 operator message。"""

    if outcome == CommandWorkerReceiptOutcome.EXECUTION_SUCCEEDED:
        return "MCP durable worker 已完成受控工具调用，结果正文仅保留在本轮运行时对象中。"
    if outcome == CommandWorkerReceiptOutcome.EXECUTION_FAILED:
        return "MCP durable worker 已通过执行前校验，但远端工具调用失败或返回错误。"
    return "MCP durable worker 在 admission/readiness/permission 阶段被阻断，未调用外部 MCP Server。"


def _recommended_actions_for(outcome: CommandWorkerReceiptOutcome) -> list[str]:
    """给控制面和运维台的低敏下一步建议。"""

    if outcome == CommandWorkerReceiptOutcome.EXECUTION_SUCCEEDED:
        return ["回放 worker receipt", "将安全短结果回填模型第二轮或使用 artifactReference"]
    if outcome == CommandWorkerReceiptOutcome.EXECUTION_FAILED:
        return ["检查 MCP Server 健康状态", "按 outbox 重试策略决定重试或死信"]
    return ["补齐 permission/readiness/approval facts", "不要绕过 admission 直接调用 MCP Server"]


def _idempotency_key(request: McpDurableWorkerRunRequest, outcome: CommandWorkerReceiptOutcome) -> str:
    """生成低敏幂等键。"""

    run_id = _optional_text(_first(request.control_facts, "runId", "run_id")) or "run"
    command_id = _optional_text(
        _first(request.control_facts, "commandId", "command_id", "callId", "call_id")
    ) or request.internal_tool_name
    return f"mcp-worker:{run_id}:{command_id}:{outcome.value}"


def _drop_none(payload: Mapping[str, Any]) -> dict[str, Any]:
    """移除 None 字段，保持 Java DTO payload 简洁。"""

    return {key: value for key, value in payload.items() if value is not None}


def _first(mapping: Mapping[str, Any], *keys: str) -> Any:
    """读取第一个存在的键。"""

    for key in keys:
        if key in mapping:
            return mapping[key]
    return None


def _optional_text(value: Any) -> str | None:
    """读取非空文本。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _optional_int(value: Any) -> int | None:
    """解析整数；无法解析时返回 None。"""

    if value is None or str(value).strip() == "":
        return None
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return None


__all__ = [
    "MCP_DURABLE_WORKER_SCHEMA_VERSION",
    "McpDurableWorkerAdapter",
    "McpDurableWorkerRunRequest",
    "McpDurableWorkerRunResult",
]

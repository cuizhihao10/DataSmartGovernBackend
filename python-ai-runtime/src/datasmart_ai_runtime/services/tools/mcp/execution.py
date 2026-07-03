"""MCP 工具进入 Durable Agent Loop 的受控执行桥。

本模块解决的是“协议已经能连通，但 Agent 执行链路还没有真正消费 MCP 工具”的问题。
上一阶段的 `McpClientRuntime` 已经完成官方 MCP SDK 的 `initialize -> tools/list -> tools/call`，
但它仍然只是一个底层协议运行时；如果上层直接把它暴露给 HTTP 路由、模型输出或临时脚本，
就会绕过 DataSmart 的企业级治理边界。

因此这里新增一个非常明确的中间层：
- 输入必须是已经由 Java/permission/readiness/resume gate 链路生成的可信 admission；
- 工具参数只允许短生命周期进入内存，不写 checkpoint、不写 runtime event；
- 输出分成“本轮 Agent 可使用的运行时结果”和“可持久化的低敏证据摘要”两份视图；
- 当前版本不直接写 Java worker receipt，但会生成 receipt draft，方便下一批接入 outbox/worker receipt。

换句话说，本文件不是一个“快捷 MCP 调用器”，而是 Durable Agent Loop 的工具执行节点雏形。
它把 MCP `tools/call` 从独立协议能力推进为可观测、可替换、可恢复的 Agent 工作单元。
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Mapping

from datasmart_ai_runtime.services.tools.mcp.contracts import (
    MCP_CLIENT_SCHEMA_VERSION,
    McpClientError,
    McpToolCallAdmission,
    McpToolCallRequest,
    McpToolCallResult,
)
from datasmart_ai_runtime.services.tools.mcp.runtime import McpClientRuntime


MCP_DURABLE_EXECUTION_SCHEMA_VERSION = "datasmart.mcp-durable-tool-execution.v1"


class McpDurableExecutionStatus(str, Enum):
    """MCP durable 执行节点的稳定状态枚举。

    这些状态面向 runtime event、worker receipt 和运维诊断，不直接使用异常类名。
    保持低基数枚举可以避免 Prometheus label、审计查询和前端状态筛选随着上游错误文本膨胀。
    """

    SUCCEEDED = "SUCCEEDED"
    FAILED_PRECHECK = "FAILED_PRECHECK"
    FAILED_TOOL_CALL = "FAILED_TOOL_CALL"


@dataclass(frozen=True)
class McpDurableToolExecutionRequest:
    """一次 MCP 工具执行节点的输入合同。

    字段说明：
    - `server_id/internal_tool_name`：定位远端 MCP Server 以及 DataSmart 内部 namespaced 工具名；
    - `arguments`：仅用于本轮 MCP SDK 调用的短生命周期参数，不允许持久化到 checkpoint；
    - `admission`：可信控制面准入事实，必须包含租户、项目、workspace、actor、run、call、权限和审批；
    - `execution_node_id`：LangGraph/Durable Loop 节点 ID，用来把本次调用挂到可观测执行图上；
    - `checkpoint_id`：可选的上游 checkpoint 引用，本服务只回显引用，不读取 checkpoint 正文；
    - `command_proposal_id/outbox_message_id`：后续 Java outbox/worker receipt 接入时的关联锚点；
    - `trace_id`：低敏链路追踪 ID，可来自 gateway 或 Java agent-runtime。

    注意：这里刻意不保存 prompt、SQL、样本数据、工具结果正文或外部 Server endpoint。
    """

    server_id: str
    internal_tool_name: str
    arguments: Mapping[str, Any]
    admission: McpToolCallAdmission
    execution_node_id: str = "mcp_tools_call"
    checkpoint_id: str | None = None
    command_proposal_id: str | None = None
    outbox_message_id: str | None = None
    trace_id: str | None = None


@dataclass(frozen=True)
class McpWorkerReceiptDraft:
    """MCP 工具执行完成后可提交给 Java worker receipt 的低敏草稿。

    当前服务不直接写 Java receipt，是为了保持职责清晰：
    - Python MCP Client 负责真实协议调用和结果归一；
    - Java agent-runtime 负责 durable outbox、worker receipt、审计和任务状态投影。

    这个草稿的意义是先把字段合同固定下来，下一批接入 `JavaCommandWorkerReceiptClient` 时不需要
    重新定义 MCP 结果如何进入控制面。
    """

    schema_version: str
    run_id: str
    call_id: str
    internal_tool_name: str
    status: McpDurableExecutionStatus
    result_summary: Mapping[str, Any] = field(default_factory=dict)
    error_code: str | None = None
    command_proposal_id: str | None = None
    outbox_message_id: str | None = None
    checkpoint_id: str | None = None
    trace_id: str | None = None

    def to_summary(self) -> dict[str, Any]:
        """输出可持久化的 worker receipt 草稿摘要。

        摘要只包含状态、哈希、字节数、引用 ID 等低敏字段；即使工具返回了业务数据、文件内容、
        外部 API 响应或错误正文，也不会通过该方法进入 Java 控制面。
        """

        return {
            "schemaVersion": self.schema_version,
            "runId": self.run_id,
            "callId": self.call_id,
            "internalToolName": self.internal_tool_name,
            "status": self.status.value,
            "resultSummary": dict(self.result_summary),
            "errorCode": self.error_code,
            "commandProposalId": self.command_proposal_id,
            "outboxMessageId": self.outbox_message_id,
            "checkpointId": self.checkpoint_id,
            "traceId": self.trace_id,
            "payloadPolicy": "LOW_SENSITIVE_WORKER_RECEIPT_DRAFT_ONLY",
        }


@dataclass(frozen=True)
class McpDurableToolExecutionResult:
    """MCP durable 执行节点的输出。

    `runtime_result` 是本轮 Agent 可继续推理使用的对象，可能包含工具结果正文；它只能留在内存里。
    `to_summary()` 和 `worker_receipt_draft` 是可进入事件、checkpoint、审计或 Java receipt 的低敏视图。
    这种双视图设计是 Agent 产品很关键的一点：模型需要工具结果继续工作，但治理系统不能无边界地
    保存工具正文。
    """

    status: McpDurableExecutionStatus
    server_id: str
    internal_tool_name: str
    execution_node_id: str
    admission_source: str
    runtime_result: McpToolCallResult | None = None
    error_code: str | None = None
    worker_receipt_draft: McpWorkerReceiptDraft | None = None
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))

    def to_summary(self) -> dict[str, Any]:
        """输出 Durable Loop 可观测摘要。

        摘要中明确标记 `runtimeResultBodyReturned=false`，用于提醒调用方：
        这里不是工具正文读取接口；需要给模型二轮回填时，应使用 `runtime_result` 内存对象或未来的
        MinIO artifactReference，而不是从审计/事件里反取正文。
        """

        result_summary = self.runtime_result.to_summary() if self.runtime_result else {}
        return {
            "schemaVersion": MCP_DURABLE_EXECUTION_SCHEMA_VERSION,
            "mcpClientSchemaVersion": MCP_CLIENT_SCHEMA_VERSION,
            "status": self.status.value,
            "serverId": self.server_id,
            "internalToolName": self.internal_tool_name,
            "executionNodeId": self.execution_node_id,
            "admissionSource": self.admission_source,
            "errorCode": self.error_code,
            "resultSummary": result_summary,
            "workerReceiptDraft": self.worker_receipt_draft.to_summary() if self.worker_receipt_draft else None,
            "runtimeResultBodyReturned": False,
            "createdAt": self.created_at.isoformat(),
            "sideEffectBoundary": {
                "mcpToolCalled": self.status == McpDurableExecutionStatus.SUCCEEDED,
                "javaWorkerReceiptWritten": False,
                "checkpointBodyWritten": False,
                "toolArgumentsPersisted": False,
                "toolResultBodyPersisted": False,
            },
        }


class McpDurableToolExecutionService:
    """把 MCP `tools/call` 包装成 Durable Agent Loop 工具执行节点。

    该服务的核心原则是 fail-closed：
    - admission 不完整时不触发 MCP Runtime；
    - MCP Runtime 抛出的稳定错误码会转换为低敏状态；
    - 真实工具结果只保留在 `runtime_result`，不会进入摘要；
    - 未来接入 Java receipt 时，应消费 `worker_receipt_draft`，而不是重新解析工具正文。
    """

    def __init__(self, runtime: McpClientRuntime) -> None:
        self._runtime = runtime

    async def execute(self, request: McpDurableToolExecutionRequest) -> McpDurableToolExecutionResult:
        """异步执行一次 MCP durable 工具节点。

        该方法适合被未来 LangGraph 节点、worker coroutine 或 FastAPI lifespan 内部任务调用。
        它不会捕获所有未知异常为成功，也不会把未知异常正文回显给调用方。
        """

        try:
            runtime_result = await self._runtime.call_tool(
                McpToolCallRequest(
                    server_id=request.server_id,
                    internal_tool_name=request.internal_tool_name,
                    arguments=request.arguments,
                    admission=request.admission,
                )
            )
        except McpClientError as exc:
            status = _status_from_error(exc.code)
            return self._failed_result(request, status=status, error_code=exc.code)
        except Exception as exc:  # pragma: no cover - 兜底保护，单元测试覆盖稳定 McpClientError 分支即可
            return self._failed_result(
                request,
                status=McpDurableExecutionStatus.FAILED_TOOL_CALL,
                error_code="MCP_DURABLE_EXECUTION_UNEXPECTED_ERROR",
            )
        return McpDurableToolExecutionResult(
            status=McpDurableExecutionStatus.SUCCEEDED,
            server_id=request.server_id,
            internal_tool_name=request.internal_tool_name,
            execution_node_id=request.execution_node_id,
            admission_source=request.admission.source,
            runtime_result=runtime_result,
            worker_receipt_draft=_receipt_draft(
                request,
                status=McpDurableExecutionStatus.SUCCEEDED,
                result_summary=runtime_result.to_summary(),
            ),
        )

    def execute_sync(self, request: McpDurableToolExecutionRequest) -> McpDurableToolExecutionResult:
        """同步执行入口，方便非 async worker 或测试脚本复用。

        如果当前线程已经存在事件循环，不能直接 `asyncio.run`。这种情况通常出现在 ASGI 请求处理、
        LangGraph async runner 或 notebook 环境中；调用方应改用 `await execute(...)`，避免嵌套事件循环。
        """

        try:
            asyncio.get_running_loop()
        except RuntimeError:
            return asyncio.run(self.execute(request))
        raise RuntimeError("当前线程已有事件循环，请在 async 场景使用 await execute(...)。")

    def _failed_result(
        self,
        request: McpDurableToolExecutionRequest,
        *,
        status: McpDurableExecutionStatus,
        error_code: str,
    ) -> McpDurableToolExecutionResult:
        """生成失败结果。

        即使失败发生在 precheck 阶段，也生成 receipt draft，方便 Java 控制面把“为什么没有调用工具”
        记录为可恢复事实，而不是让用户只看到一个临时 Python 异常。
        """

        return McpDurableToolExecutionResult(
            status=status,
            server_id=request.server_id,
            internal_tool_name=request.internal_tool_name,
            execution_node_id=request.execution_node_id,
            admission_source=request.admission.source,
            error_code=error_code,
            worker_receipt_draft=_receipt_draft(
                request,
                status=status,
                result_summary={},
                error_code=error_code,
            ),
        )


def _status_from_error(error_code: str) -> McpDurableExecutionStatus:
    """把 MCP Runtime 稳定错误码映射为 durable 执行状态。

    admission、权限、审批、allowlist、参数与目录类错误属于执行前失败；真实外部调用失败属于工具调用失败。
    这样前端/控制面可以区分“用户或策略还没准备好”和“外部 MCP Server 执行失败”。
    """

    precheck_prefixes = (
        "MCP_ADMISSION_",
        "MCP_PERMISSION_",
        "MCP_APPROVAL_",
        "MCP_TOOL_NOT_ALLOWED",
        "MCP_ARGUMENTS_",
        "MCP_INLINE_SECRET_",
        "MCP_CLIENT_DISABLED",
        "MCP_SERVER_",
        "MCP_TOOL_NOT_DISCOVERED",
    )
    if any(error_code.startswith(prefix) for prefix in precheck_prefixes):
        return McpDurableExecutionStatus.FAILED_PRECHECK
    return McpDurableExecutionStatus.FAILED_TOOL_CALL


def _receipt_draft(
    request: McpDurableToolExecutionRequest,
    *,
    status: McpDurableExecutionStatus,
    result_summary: Mapping[str, Any],
    error_code: str | None = None,
) -> McpWorkerReceiptDraft:
    """构造低敏 worker receipt 草稿。

    草稿只引用 admission 中的 `run_id/call_id`，不把工具参数或结果正文塞进 receipt。
    这为下一步 Java `worker receipt` 接入保留了最小稳定字段集。
    """

    return McpWorkerReceiptDraft(
        schema_version=MCP_DURABLE_EXECUTION_SCHEMA_VERSION,
        run_id=request.admission.run_id,
        call_id=request.admission.call_id,
        internal_tool_name=request.internal_tool_name,
        status=status,
        result_summary=dict(result_summary),
        error_code=error_code,
        command_proposal_id=request.command_proposal_id,
        outbox_message_id=request.outbox_message_id,
        checkpoint_id=request.checkpoint_id,
        trace_id=request.trace_id,
    )


__all__ = [
    "MCP_DURABLE_EXECUTION_SCHEMA_VERSION",
    "McpDurableExecutionStatus",
    "McpDurableToolExecutionRequest",
    "McpDurableToolExecutionResult",
    "McpDurableToolExecutionService",
    "McpWorkerReceiptDraft",
]

"""从工具动作 checkpoint 摘要提取 Java fact bundle 查询提示。

本模块只做一件事：从 `ToolActionExecutionCheckpoint.graph_run_summary` 里提取“Java 恢复事实包 DTO
已经支持的低敏定位字段”。它不是通用 checkpoint 反序列化器，也不会把完整执行图重新还原成工具调用。

为什么要单独拆出这个模块：
- fact bundle client 已经负责 HTTP、Header、安全降级和 Java 响应解析，如果继续把 checkpoint 遍历逻辑塞进去，
  文件会越来越长，也会让“网络调用”和“执行图摘要解析”两个职责耦合；
- checkpoint 摘要未来可能来自 in-memory、Redis、MySQL 或 LangGraph/OpenClaw 风格的持久化后端，但 Java fact
  bundle 查询只需要 commandId、approvalFactId、clarificationFactId、toolCode、policyVersion 等控制面线索；
- 单独 helper 便于给敏感字段边界写测试，确保 payloadReference、graphId、SQL、prompt、arguments 等字段不会被
  当作 Java fact bundle 请求字段扩散出去。
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any


def checkpoint_resume_fact_bundle_hints(checkpoint: Any) -> dict[str, str]:
    """提取 Java fact bundle 查询可直接使用的低敏提示字段。

    返回字段只覆盖 Java `AgentToolActionResumeFactBundleQueryRequest` 已有且明确低敏的定位属性：
    - `commandId`：用于 Java outbox 与 worker receipt 投影查询；
    - `outboxId`：未来 Java proposal/outbox 摘要如果暴露低敏 outbox 编号，可直接用于 outbox 查询；
    - `approvalFactId`：用于 permission-admin 或 agent-runtime 控制面验真，Python 不在 API 摘要中回显；
    - `clarificationFactId`：用于澄清事实验真，Python 不在 API 摘要中回显；
    - `toolCode`：用于策略作用域、审批策略和事实包检索时缩小范围；
    - `requestedPolicyVersion`：用于 Java 控制面确认恢复时仍遵守同一策略版本。

    安全边界：
    - 不返回 `payloadReference`，因为它可以定位真实 payload body，必须由 Java 控制面或受控 payload store 自己校验；
    - 不返回 `graphId/contractId`，因为当前 Java fact bundle DTO 尚未把它们作为查询字段，避免 Python 提前扩散；
    - 不读取 `arguments/sql/prompt/messages/sampleData/modelOutput`，即使调用方把这些字段误塞进 checkpoint 摘要也忽略。
    """

    graph_run = getattr(checkpoint, "graph_run_summary", None)
    if not isinstance(graph_run, Mapping):
        return {}

    hints: dict[str, str] = {}
    for step in _step_mappings(graph_run):
        _put_hint_if_absent(hints, "toolCode", step.get("toolName"))
        proposal = _mapping(step.get("proposalSubmission"))
        request_payload = _mapping(proposal.get("requestPayload"))
        java_proposal = _mapping(proposal.get("javaProposal"))

        # proposal requestPayload 是 Python 发往 Java proposal API 的低敏请求摘要。
        # 这里优先使用 clientRequestId，因为它在 outbox/worker 链路里更接近“幂等命令 ID”语义；
        # requestId 作为次级兜底，避免旧 checkpoint 没有 clientRequestId 时完全失去关联能力。
        _put_hint_if_absent(hints, "commandId", request_payload.get("clientRequestId"))
        _put_hint_if_absent(hints, "commandId", request_payload.get("requestId"))
        _put_hint_if_absent(hints, "outboxId", request_payload.get("outboxId"))
        _put_hint_if_absent(hints, "approvalFactId", request_payload.get("approvalFactId"))
        _put_hint_if_absent(hints, "approvalFactId", request_payload.get("approvalConfirmationId"))
        _put_hint_if_absent(hints, "approvalFactId", request_payload.get("confirmationId"))
        _put_hint_if_absent(hints, "clarificationFactId", request_payload.get("clarificationFactId"))
        _put_hint_if_absent(hints, "requestedPolicyVersion", request_payload.get("policyVersion"))

        # javaProposal 当前主要返回 proposal/tool/graph 状态。这里只读取 DTO 已支持的低敏定位字段，
        # 不读取 payloadReference、targetEndpoint、lastError 或任何可能指向工具正文的字段。
        _put_hint_if_absent(hints, "toolCode", java_proposal.get("toolName"))
        _put_hint_if_absent(hints, "commandId", java_proposal.get("commandId"))
        _put_hint_if_absent(hints, "outboxId", java_proposal.get("outboxId"))

    return hints


def _step_mappings(graph_run: Mapping[str, Any]) -> tuple[Mapping[str, Any], ...]:
    """读取 checkpoint steps，并过滤掉非对象项。

    checkpoint 的 `steps` 在不同测试夹具或序列化后端里可能是 list，也可能是 tuple；helper 不关心具体容器类型，
    只处理 dict-like 对象，避免因为脏数据导致 resume-preview 整体失败。
    """

    steps = graph_run.get("steps")
    if not isinstance(steps, (list, tuple)):
        return ()
    return tuple(item for item in steps if isinstance(item, Mapping))


def _mapping(value: Any) -> Mapping[str, Any]:
    """把未知值安全转换为只读 Mapping 视图。"""

    return value if isinstance(value, Mapping) else {}


def _put_hint_if_absent(hints: dict[str, str], key: str, value: Any) -> None:
    """写入第一个非空提示值。

    “第一个值优先”符合执行图时间线语义：靠前步骤通常代表最早生成的 proposal/command，后续步骤可能只是补充诊断。
    如果未来需要支持多工具并发恢复，可以把这里升级为 `toolCode -> locator bundle` 的列表结构。
    """

    if key in hints:
        return
    text = _text(value)
    if text:
        hints[key] = text


def _text(value: Any) -> str | None:
    """解析非空字符串，并把空白值视为缺失。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None

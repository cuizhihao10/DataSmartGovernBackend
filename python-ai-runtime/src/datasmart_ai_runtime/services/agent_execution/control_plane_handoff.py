"""Agent 执行闭环中的 Java 控制面交接摘要。

`AgentExecutionClosureService` 需要回答“本轮请求下一步如何交给 Java 控制面”，但具体的 command proposal
模板裁剪规则相对独立：它关注路由、模板数量、缺失证据 code 和候选状态，而不参与闭环阶段判断。

把这部分拆出来有两个目的：
- 降低 `agent_execution_closure.py` 的文件长度和职责密度，符合项目“单文件尽量 500 行内”的设计规范；
- 让未来 MCP `tools/call`、A2A task、ChatOps 入口也可以复用同一份 handoff 裁剪规则，避免每个入口
  自己决定哪些字段能暴露、哪些字段必须隐藏。

安全边界：
- 只暴露 method/path、工具名、状态、缺失证据 code、布尔门禁和计数；
- 不暴露 requestBodyTemplate、payloadReference、approvalConfirmationId、clarificationFactId；
- 不暴露 arguments、prompt、SQL、样本数据、模型输出、凭据、内部 endpoint 或 Java 异常正文。
"""

from __future__ import annotations

from typing import Any, Mapping


def build_control_plane_handoff_summary(command_proposal_templates: Mapping[str, Any] | None) -> Mapping[str, Any]:
    """把 command proposal 模板压缩为闭环快照中的控制面交接摘要。

    参数说明：
    - `command_proposal_templates` 来自工具 action proposal 模板构建器；
    - 该对象可能包含 requestBodyTemplate 等“后续提交 Java 时才需要”的结构；
    - 本函数只读取低敏元数据，并显式丢弃所有受控 payload 引用和请求正文模板。

    返回值说明：
    - `available`：是否已经成功构建 handoff 摘要；
    - `previewOnly`：强调当前只是交接导航，不会真正调用 Java；
    - `outboxPreflightCandidateCount`：有多少工具具备进入 Java outbox 预检的候选条件；
    - `missingEvidenceCodes`：进入 Java proposal 前仍缺哪些证据，例如 graphId、payloadReference；
    - `templateSummaries`：每个模板的低敏摘要，最多保留 10 条，避免高频响应过大。
    """

    if not isinstance(command_proposal_templates, Mapping):
        return {
            "schemaVersion": "datasmart.python-ai-runtime.agent-execution-control-plane-handoff.v1",
            "payloadPolicy": "LOW_SENSITIVE_CONTROL_PLANE_HANDOFF_ONLY",
            "available": False,
            "previewOnly": True,
            "reason": "COMMAND_PROPOSAL_TEMPLATE_NOT_BUILT",
            "totalTemplateCount": 0,
            "outboxPreflightCandidateCount": 0,
            "missingEvidenceCodes": (),
            "templateSummaries": (),
            "nextAction": "BUILD_COMMAND_PROPOSAL_TEMPLATE_BEFORE_JAVA_HANDOFF",
        }

    templates = tuple(
        template for template in command_proposal_templates.get("templates", ()) if isinstance(template, Mapping)
    )
    missing_codes = _aggregate_template_missing_codes(templates)
    candidate_count = _int(command_proposal_templates.get("outboxPreflightCandidateCount"))
    return {
        "schemaVersion": "datasmart.python-ai-runtime.agent-execution-control-plane-handoff.v1",
        "payloadPolicy": "LOW_SENSITIVE_CONTROL_PLANE_HANDOFF_ONLY",
        "available": True,
        "previewOnly": bool(command_proposal_templates.get("previewOnly", True)),
        "toolExecutionEnabled": bool(command_proposal_templates.get("toolExecutionEnabled", False)),
        "commandSchemaVersion": _text(command_proposal_templates.get("commandSchemaVersion")),
        "workerReceiptMode": _text(command_proposal_templates.get("workerReceiptMode")),
        "targetControlPlaneRoutes": _safe_routes(command_proposal_templates.get("targetControlPlaneRoutes")),
        "totalTemplateCount": _int(command_proposal_templates.get("totalTemplateCount"), default=len(templates)),
        "outboxPreflightCandidateCount": candidate_count,
        "missingEvidenceCodes": missing_codes,
        "templateSummaries": tuple(_template_summary(template) for template in templates[:10]),
        "truncatedTemplateCount": max(len(templates) - 10, 0),
        "nextAction": (
            "MATERIALIZE_EXECUTION_GRAPH_AND_PAYLOAD_REFERENCE_THEN_CALL_JAVA_PROPOSAL"
            if candidate_count > 0
            else "RESOLVE_READINESS_GATE_BEFORE_JAVA_PROPOSAL"
        ),
        "notIncluded": (
            "requestBodyTemplate",
            "payloadReference",
            "approvalConfirmationId",
            "clarificationFactId",
            "arguments",
            "prompt",
            "sql",
            "sampleData",
            "modelOutput",
            "credential",
            "internalEndpoint",
        ),
    }


def _template_summary(template: Mapping[str, Any]) -> Mapping[str, Any]:
    """裁剪单个 proposal 模板，只保留可展示的治理字段。"""

    return {
        "templateId": _text(template.get("templateId")),
        "source": _text(template.get("source")),
        "protocolFamily": _text(template.get("protocolFamily")),
        "planIndex": template.get("planIndex"),
        "toolName": _text(template.get("toolName")),
        "decision": _text(template.get("decision")),
        "proposalStateHint": _text(template.get("proposalStateHint")),
        "outboxPreflightCandidate": bool(template.get("outboxPreflightCandidate")),
        "graphSelectionRequired": bool(template.get("graphSelectionRequired")),
        "payloadReferenceRequired": bool(template.get("payloadReferenceRequired")),
        "approvalConfirmationRequired": bool(template.get("approvalConfirmationRequired")),
        "clarificationFactRequired": bool(template.get("clarificationFactRequired")),
        "policyVersionRequired": bool(template.get("policyVersionRequired")),
        "missingBeforeJavaProposal": _string_tuple(template.get("missingBeforeJavaProposal")),
        "nextAction": _text(template.get("nextAction")),
    }


def _aggregate_template_missing_codes(templates: tuple[Mapping[str, Any], ...]) -> tuple[str, ...]:
    """聚合模板缺失证据 code，保持稳定顺序并去重。"""

    missing: list[str] = []
    for template in templates:
        for code in _string_tuple(template.get("missingBeforeJavaProposal")):
            if code not in missing:
                missing.append(code)
    return tuple(missing)


def _safe_routes(value: Any) -> tuple[Mapping[str, str], ...]:
    """裁剪 Java 控制面目标路由。

    路由属于低敏契约元数据，可以帮助前端、网关或后续 runner 知道下一跳；但仍然只允许 method/path
    两个字段，避免未来模板里出现 baseUrl、内部 endpoint、认证头或服务发现信息时被闭环快照透传。
    """

    if not isinstance(value, (list, tuple)):
        return ()
    routes: list[Mapping[str, str]] = []
    for item in value[:5]:
        if not isinstance(item, Mapping):
            continue
        method = _text(item.get("method"))
        path = _text(item.get("path"))
        if method and path:
            routes.append({"method": method, "path": path})
    return tuple(routes)


def _string_tuple(value: Any) -> tuple[str, ...]:
    """把模板中的低敏 code 列表规范化为 tuple。"""

    if value is None:
        return ()
    if isinstance(value, str):
        candidates = value.split(",")
    elif isinstance(value, (list, tuple, set, frozenset)):
        candidates = value
    else:
        return ()
    return tuple(text for item in candidates if (text := _text(item)))


def _text(value: Any) -> str | None:
    """读取非空字符串，空值统一返回 None。"""

    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _int(value: Any, *, default: int = 0) -> int:
    """读取非负整数，非法值回退到默认值。"""

    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return default
    return parsed if parsed >= 0 else default

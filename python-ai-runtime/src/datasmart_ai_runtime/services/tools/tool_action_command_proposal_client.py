"""工具动作 command proposal 的 Java 控制面客户端。

本模块承接 `tool_action_command_proposal_template.py` 输出的低敏模板，把“下一步应调用
Java agent-runtime proposal 接口”推进成可测试、可禁用、可 fail-closed 的客户端契约。

设计边界非常重要：
- 本客户端只提交 proposal 预校验请求，不执行工具、不写 outbox、不创建审批、不读取 payloadReference；
- 请求体只允许出现 Java `AgentToolActionCommandProposalRequest` 支持的低敏字段；
- 工具 arguments、SQL、prompt、样本数据、模型输出、凭证、内部 endpoint 等敏感内容即使调用方误传，
  也不会被本客户端接收为合法 payloadReference；
- 默认 `enabled=False`，便于本地学习和单元测试先验证契约，再由生产配置显式打开真实 HTTP 调用。
"""

from __future__ import annotations

import json
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any, Callable
from urllib.request import Request, urlopen

from datasmart_ai_runtime.services.tools.tool_action_command_proposal_contract import (
    ALLOWED_JAVA_REQUEST_FIELDS,
    is_safe_payload_reference,
    json_safe,
    optional_positive_int,
    positive_int,
    proposal_response_summary,
    redact_rejected_request_payload,
    text,
)
from datasmart_ai_runtime.services.tools.tool_action_command_proposal_template import JAVA_PROPOSAL_ROUTE


class ToolActionCommandProposalClientError(RuntimeError):
    """工具动作 command proposal 客户端错误。

    该异常用于区分“业务预检没有通过”和“跨服务调用/响应契约异常”：
    - 预检没有通过时，`propose(...)` 会返回 skipped 结果，方便上层继续展示需要补齐的证据；
    - 远程 Java 返回非 0、响应 data 不是对象、网络不可用等情况会抛出该异常，避免上层误以为 proposal 成功。
    """


@dataclass(frozen=True)
class ToolActionCommandProposalClientSettings:
    """Java proposal 客户端运行配置。

    字段说明：
    - `enabled`：是否真的发起 HTTP 调用。默认关闭，避免 preview 链路被误配置成真实副作用链路；
    - `base_url`：Java agent-runtime 的服务地址。本仓库默认端口是 8091；
    - `proposal_path`：Java proposal 路由，默认对齐 `/agent-runtime/tool-action-commands/proposals`；
    - `timeout_seconds`：HTTP 调用超时。proposal 属于执行前关键路径，超时应短而明确。
    """

    enabled: bool = False
    base_url: str = "http://localhost:8091"
    proposal_path: str = JAVA_PROPOSAL_ROUTE
    timeout_seconds: int = 3


@dataclass(frozen=True)
class ToolActionCommandProposalEvidence:
    """调用 Java proposal 前由可信控制面补齐的证据。

    这些字段不能由模型自由生成，也不应该从 MCP tool arguments 里直接抽取：
    - `graph_id/contract_id` 应来自 Java execution graph projection；
    - `payload_reference` 应来自服务端 payload store 或 artifact store，代表“受控引用”，不是内联参数值；
    - `approval_confirmation_id/clarification_fact_id` 应来自 permission-admin、审批台或澄清事实存储；
    - tenant/project/actor/run/session 等字段仅用于作用域复核和审计定位，不承载工具实参。
    """

    graph_id: str | None = None
    contract_id: str | None = None
    tenant_id: str | None = None
    project_id: str | None = None
    actor_id: str | None = None
    request_id: str | None = None
    run_id: str | None = None
    session_id: str | None = None
    after_sequence: int | None = None
    limit: int | None = None
    payload_reference: str | None = None
    approval_confirmation_id: str | None = None
    clarification_fact_id: str | None = None
    policy_version: str | None = None
    command_schema_version: str | None = None
    worker_receipt_mode: str | None = None
    client_request_id: str | None = None


@dataclass(frozen=True)
class ToolActionCommandProposalClientResult:
    """Python -> Java command proposal 的低敏结果摘要。

    `request_payload` 只包含白名单字段，可用于未来 runtime event 或调试面板展示“提交了哪个 proposal 请求”。
    它仍然不包含工具参数值，因此可以安全进入低敏控制面摘要。
    """

    submitted: bool
    skipped: bool
    submission_state: str
    skip_reason: str | None
    template_id: str
    target_route: str
    payload_policy: str
    request_payload: dict[str, Any]
    response_summary: dict[str, Any]
    missing_evidence: tuple[str, ...] = ()
    rejected_evidence: tuple[str, ...] = ()

    def to_summary(self) -> dict[str, Any]:
        """转换成 API/runtime event 可复用的低敏摘要。"""

        return {
            "schemaVersion": "datasmart.python-ai-runtime.tool-action-command-proposal-client.v1",
            "submitted": self.submitted,
            "skipped": self.skipped,
            "submissionState": self.submission_state,
            "skipReason": self.skip_reason,
            "templateId": self.template_id,
            "targetRoute": self.target_route,
            "payloadPolicy": self.payload_policy,
            "requestPayload": self.request_payload,
            "javaProposal": self.response_summary,
            "missingEvidence": self.missing_evidence,
            "rejectedEvidence": self.rejected_evidence,
            "guardrails": (
                "客户端只提交 Java proposal 预校验，不执行工具、不写 outbox、不调用 worker。",
                "payloadReference 必须是受控引用，不能是 prompt、SQL、arguments 或任意内联 JSON。",
                "Java proposal 成功也不等于工具已执行，后续仍需 outbox writer、worker receipt 和事件投影闭环。",
            ),
        }


class JavaToolActionCommandProposalClient:
    """调用 Java agent-runtime 工具动作 command proposal 接口。

    这个客户端是 Python Runtime 和 Java 控制面之间的“窄门”：
    - Python 负责模型/MCP/A2A 意图归一和 readiness；
    - Java 负责 execution graph、payloadReference 复核、command proposal、outbox 和 receipt；
    - 本客户端只把已补齐的低敏证据提交给 Java，不允许 Python 绕过 Java 直接产生可执行 command。
    """

    def __init__(
        self,
        settings: ToolActionCommandProposalClientSettings | None = None,
        *,
        urlopen_func: Callable[..., Any] = urlopen,
    ) -> None:
        self._settings = settings or ToolActionCommandProposalClientSettings()
        self._urlopen = urlopen_func

    def propose(
        self,
        template: Mapping[str, Any],
        evidence: ToolActionCommandProposalEvidence | None = None,
        *,
        trace_id: str | None = None,
    ) -> ToolActionCommandProposalClientResult:
        """根据 proposal 模板和外部证据，按需调用 Java proposal 接口。

        工作流拆成四步，便于学习和排障：
        1. 从模板构造 Java DTO 请求体，并用 `evidence` 覆盖模板中仍为空的字段；
        2. 做本地 fail-closed 预检，避免明显缺证据或疑似内联 payload 的请求打到 Java；
        3. 若客户端未启用，返回 `CLIENT_DISABLED`，让上层可展示“请求已准备好但未提交”；
        4. 若客户端启用，发起 HTTP POST，并仅解析 Java 响应白名单字段。
        """

        request_payload = self.build_request_payload(template, evidence)
        missing, rejected = self.validate_preconditions(template, request_payload)
        template_id = text(template.get("templateId")) or ""
        payload_policy = text(template.get("payloadPolicy")) or "LOW_SENSITIVE_COMMAND_PROPOSAL_TEMPLATE_ONLY"
        target_route = self._target_url()
        if missing or rejected:
            safe_payload = redact_rejected_request_payload(request_payload, rejected)
            return ToolActionCommandProposalClientResult(
                submitted=False,
                skipped=True,
                submission_state="VALIDATION_FAILED",
                skip_reason="MISSING_OR_UNSAFE_EVIDENCE",
                template_id=template_id,
                target_route=target_route,
                payload_policy=payload_policy,
                request_payload=safe_payload,
                response_summary={},
                missing_evidence=missing,
                rejected_evidence=rejected,
            )
        if not self._settings.enabled:
            return ToolActionCommandProposalClientResult(
                submitted=False,
                skipped=True,
                submission_state="CLIENT_DISABLED",
                skip_reason="CLIENT_DISABLED",
                template_id=template_id,
                target_route=target_route,
                payload_policy=payload_policy,
                request_payload=request_payload,
                response_summary={},
            )
        response_summary = self._post(request_payload, trace_id=trace_id)
        return ToolActionCommandProposalClientResult(
            submitted=True,
            skipped=False,
            submission_state="SUBMITTED_TO_JAVA_PROPOSAL",
            skip_reason=None,
            template_id=template_id,
            target_route=target_route,
            payload_policy=payload_policy,
            request_payload=request_payload,
            response_summary=response_summary,
        )

    @classmethod
    def build_request_payload(
        cls,
        template: Mapping[str, Any],
        evidence: ToolActionCommandProposalEvidence | None = None,
    ) -> dict[str, Any]:
        """把低敏模板转换为 Java `AgentToolActionCommandProposalRequest`。

        模板里的 `toolNameHint/planIndexHint` 只是给人看的低敏提示，不属于 Java DTO；
        这里通过字段白名单主动剔除它们，避免 Python 与 Java 之间形成“靠 Jackson 忽略未知字段”的脆弱契约。
        """

        body_template = template.get("requestBodyTemplate")
        if not isinstance(body_template, Mapping):
            body_template = {}
        payload = {field: body_template.get(field) for field in ALLOWED_JAVA_REQUEST_FIELDS}
        if evidence is not None:
            _apply_evidence(payload, evidence)
        payload["afterSequence"] = optional_positive_int(payload.get("afterSequence"))
        payload["limit"] = positive_int(payload.get("limit"), default=100)
        return {field: json_safe(payload.get(field)) for field in ALLOWED_JAVA_REQUEST_FIELDS}

    @classmethod
    def validate_preconditions(
        cls,
        template: Mapping[str, Any],
        request_payload: Mapping[str, Any],
    ) -> tuple[tuple[str, ...], tuple[str, ...]]:
        """执行本地 fail-closed 预检。

        本地预检不替代 Java proposal 校验，它的作用是提前挡住“明显不应该发出去”的请求：
        - 不是 outbox preflight candidate 的模板，不能提交；
        - 没有 graphId/contractId，Java 无法定位 execution graph；
        - payloadReference 缺失或看起来像 URL/JSON/SQL/凭证，必须阻断；
        - 等待审批或澄清的分支，必须先拿到对应事实 ID。
        """

        missing: list[str] = []
        rejected: list[str] = []
        if not template.get("outboxPreflightCandidate"):
            rejected.append("TEMPLATE_NOT_OUTBOX_PREFLIGHT_CANDIDATE")
        if not text(request_payload.get("graphId")) and not text(request_payload.get("contractId")):
            missing.append("GRAPH_ID_OR_CONTRACT_ID_REQUIRED")
        payload_reference = text(request_payload.get("payloadReference"))
        if not payload_reference:
            missing.append("PAYLOAD_REFERENCE_REQUIRED")
        elif not is_safe_payload_reference(payload_reference):
            rejected.append("PAYLOAD_REFERENCE_UNSAFE_OR_INLINE")
        if not text(request_payload.get("policyVersion")):
            missing.append("POLICY_VERSION_REQUIRED")
        if not text(request_payload.get("commandSchemaVersion")):
            missing.append("COMMAND_SCHEMA_VERSION_REQUIRED")
        if not text(request_payload.get("workerReceiptMode")):
            missing.append("WORKER_RECEIPT_MODE_REQUIRED")
        if template.get("approvalConfirmationRequired") and not text(request_payload.get("approvalConfirmationId")):
            missing.append("APPROVAL_CONFIRMATION_ID_REQUIRED")
        if template.get("clarificationFactRequired") and not text(request_payload.get("clarificationFactId")):
            missing.append("CLARIFICATION_FACT_ID_REQUIRED")
        return tuple(missing), tuple(rejected)

    @classmethod
    def parse_platform_response(cls, payload: Mapping[str, Any]) -> dict[str, Any]:
        """解析 Java `PlatformApiResponse<AgentToolActionCommandProposalResponse>`。

        只返回响应白名单字段。这样即使 Java 未来为了内部审计扩展更多字段，Python 侧也不会自动把未知字段
        扩散到 `/agent/plans`、runtime event 或 WebSocket replay。
        """

        if payload.get("code") != 0:
            reason = payload.get("reason", "UNKNOWN")
            message = payload.get("message", "Java command proposal 接口返回失败")
            raise ToolActionCommandProposalClientError(f"{reason}: {message}")
        data = payload.get("data")
        if not isinstance(data, Mapping):
            raise ToolActionCommandProposalClientError("Java command proposal 响应 data 必须是对象")
        return proposal_response_summary(data)

    def _post(self, request_payload: Mapping[str, Any], *, trace_id: str | None) -> dict[str, Any]:
        """执行 HTTP POST，并把响应交给白名单解析器。

        这里使用标准库 `urllib`，保持 Python Runtime 轻依赖。后续如果要接 mTLS、服务账号 token、重试和熔断，
        可以在本类外部注入更强的 `urlopen_func` 或替换成专用 HTTP adapter，而不用改业务编排层。
        """

        body = json.dumps(request_payload, ensure_ascii=False).encode("utf-8")
        http_request = Request(
            url=self._target_url(),
            data=body,
            headers={
                "Accept": "application/json",
                "Content-Type": "application/json; charset=utf-8",
                "X-DataSmart-Trace-Id": trace_id or text(request_payload.get("requestId")) or "",
                "X-DataSmart-Source-Service": "python-ai-runtime",
            },
            method="POST",
        )
        try:
            with self._urlopen(http_request, timeout=self._settings.timeout_seconds) as response:  # noqa: S310 - URL 来自受控配置
                payload = json.loads(response.read().decode("utf-8"))
        except Exception as exc:  # pragma: no cover - 网络错误由集成环境覆盖
            raise ToolActionCommandProposalClientError(f"调用 Java command proposal 接口失败：{exc}") from exc
        return self.parse_platform_response(payload)

    def _target_url(self) -> str:
        """拼接 Java proposal 目标地址。"""

        return f"{self._settings.base_url.rstrip('/')}{self._settings.proposal_path}"


def _apply_evidence(payload: dict[str, Any], evidence: ToolActionCommandProposalEvidence) -> None:
    """把可信证据覆盖到 Java 请求体。

    使用显式字段映射，而不是 `asdict(evidence)` 自动展开，是为了避免未来 evidence dataclass 新增内部字段后
    被意外透传到 Java 请求体。
    """

    mapping = {
        "graphId": evidence.graph_id,
        "contractId": evidence.contract_id,
        "tenantId": evidence.tenant_id,
        "projectId": evidence.project_id,
        "actorId": evidence.actor_id,
        "requestId": evidence.request_id,
        "runId": evidence.run_id,
        "sessionId": evidence.session_id,
        "afterSequence": evidence.after_sequence,
        "limit": evidence.limit,
        "payloadReference": evidence.payload_reference,
        "approvalConfirmationId": evidence.approval_confirmation_id,
        "clarificationFactId": evidence.clarification_fact_id,
        "policyVersion": evidence.policy_version,
        "commandSchemaVersion": evidence.command_schema_version,
        "workerReceiptMode": evidence.worker_receipt_mode,
        "clientRequestId": evidence.client_request_id,
    }
    for key, value in mapping.items():
        if value is not None:
            payload[key] = value

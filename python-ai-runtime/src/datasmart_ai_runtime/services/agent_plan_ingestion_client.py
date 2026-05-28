"""Java Agent Runtime 的 AgentPlan 接入客户端。

4.05 已经让 Python 可以按 Java session/run/auditId 查询工具结果，但还缺少一个前置环节：
Python 生成 AgentPlan 后，必须先把计划提交给 Java `agent-runtime`，由 Java 创建受控 session、
run 和 toolAudits。只有拿到这些 Java 审计 ID，后续工具结果回填 Provider 才能查询真实执行结果。

本文件提供两个能力：
- 将 Python `AgentRequest + AgentPlan` 转换为 Java `IngestAgentPlanRequest`；
- 解析 Java `IngestedAgentPlanView`，并把 `toolAudits[*].auditId` 回写到 ToolPlan 的治理提示中。

它仍然不执行工具。执行工具、审批、幂等和审计状态推进继续属于 Java 控制面。
"""

from __future__ import annotations

import json
from dataclasses import dataclass, replace
from enum import Enum
from typing import Any
from urllib.request import Request, urlopen

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest, ToolPlan


class AgentPlanIngestionClientError(RuntimeError):
    """AgentPlan 接入客户端错误。

    该错误代表 Python -> Java 控制面接入失败，例如平台响应 code 非 0、响应结构不符合契约、
    tenant/project 不能转换为 Java Long，或网络调用失败。
    """


@dataclass(frozen=True)
class AgentToolAuditReference:
    """Python ToolPlan 与 Java 工具审计之间的引用关系。

    字段说明：
    - `model_tool_call_id`：模型返回的 tool_call_id，用于后续 role=tool 消息与 assistant tool_calls 对齐；
    - `tool_name`：DataSmart 工具编码，例如 `quality.rule.suggest`；
    - `session_id/run_id/audit_id`：Java 控制面的三元组，是查询工具结果和审计回放的核心定位信息；
    - `state`：Java 审计当前状态，例如 PLANNED、WAITING_APPROVAL、SUCCEEDED。
    """

    model_tool_call_id: str
    tool_name: str
    session_id: str
    run_id: str
    audit_id: str
    state: str


@dataclass(frozen=True)
class AgentPlanIngestionResult:
    """AgentPlan 接入结果摘要。

    `raw_response` 保留 Java 原始 data，便于 API 层展示和排障；`tool_audit_references` 是 Python 主链
    真正需要的轻量映射，用于把 Java auditId 注入 ToolPlan。
    """

    session_id: str
    run_id: str
    tool_audit_references: tuple[AgentToolAuditReference, ...]
    raw_response: dict[str, Any]

    def attach_to_plan(self, plan: AgentPlan) -> AgentPlan:
        """把 Java 控制面引用写回 AgentPlan。

        这里返回新的 `AgentPlan`，不修改原对象。原因是领域对象使用 frozen dataclass 风格，避免下游
        在不同阶段随意改写计划，导致审计回放时难以判断哪个版本是真实输出。
        """

        reference_by_call_id = {
            reference.model_tool_call_id: reference
            for reference in self.tool_audit_references
            if reference.model_tool_call_id
        }
        updated_plans: list[ToolPlan] = []
        for tool_plan in plan.tool_plans:
            call_id = str(tool_plan.governance_hints.get("modelToolCallId") or "")
            reference = reference_by_call_id.get(call_id)
            if reference is None:
                updated_plans.append(tool_plan)
                continue
            updated_plans.append(self._attach_reference(tool_plan, reference))
        return replace(plan, tool_plans=tuple(updated_plans))

    def to_summary(self) -> dict[str, Any]:
        """转换为 API 响应可展示摘要。"""

        return {
            "sessionId": self.session_id,
            "runId": self.run_id,
            "toolAuditCount": len(self.tool_audit_references),
            "toolAuditReferences": tuple(
                {
                    "modelToolCallId": reference.model_tool_call_id,
                    "toolName": reference.tool_name,
                    "auditId": reference.audit_id,
                    "state": reference.state,
                }
                for reference in self.tool_audit_references
            ),
        }

    @staticmethod
    def _attach_reference(tool_plan: ToolPlan, reference: AgentToolAuditReference) -> ToolPlan:
        """为单个 ToolPlan 注入 Java 控制面引用。"""

        hints = dict(tool_plan.governance_hints)
        hints.update(
            {
                "agentRuntimeSessionId": reference.session_id,
                "agentRuntimeRunId": reference.run_id,
                "agentRuntimeAuditId": reference.audit_id,
                "agentRuntimeAuditState": reference.state,
            }
        )
        return replace(tool_plan, governance_hints=hints)


class JavaAgentPlanIngestionClient:
    """Java AgentPlan 接入客户端。

    该客户端面向 `/agent-runtime/plan-ingestions`，默认使用标准库 HTTP，避免给 Python Runtime 增加额外
    依赖。生产环境中可以在同一接口后面替换为带 mTLS、服务账号 token、重试和熔断的 HTTP client。
    """

    def __init__(
        self,
        base_url: str,
        timeout_seconds: int = 5,
        ingestion_path: str = "/agent-runtime/plan-ingestions",
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._timeout_seconds = timeout_seconds
        self._ingestion_path = ingestion_path

    def ingest(
        self,
        request_context: AgentRequest,
        plan: AgentPlan,
        trace_id: str | None = None,
    ) -> AgentPlanIngestionResult:
        """提交 Python AgentPlan 到 Java 控制面。"""

        payload = self.build_payload(request_context, plan)
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        request = Request(
            url=f"{self._base_url}{self._ingestion_path}",
            data=body,
            headers={
                "Accept": "application/json",
                "Content-Type": "application/json; charset=utf-8",
                "X-DataSmart-Trace-Id": trace_id or plan.request_id,
                "X-DataSmart-Source-Service": "python-ai-runtime",
            },
            method="POST",
        )
        try:
            with urlopen(request, timeout=self._timeout_seconds) as response:  # noqa: S310 - URL 来自受控配置
                response_payload = json.loads(response.read().decode("utf-8"))
        except Exception as exc:  # pragma: no cover - 网络错误在集成环境覆盖
            raise AgentPlanIngestionClientError(f"提交 Python AgentPlan 到 Java 控制面失败：{exc}") from exc
        return self.parse_platform_response(response_payload)

    @classmethod
    def build_payload(cls, request_context: AgentRequest, plan: AgentPlan) -> dict[str, Any]:
        """把 Python 请求与计划转换为 Java `IngestAgentPlanRequest`。

        这里集中处理 Java/Python 命名和类型差异：
        - Java tenantId/projectId/workspaceId 是 Long，Python 请求当前是字符串；
        - Python 枚举需要转换成 Java 可识别的大写或小写字符串；
        - 参数校验要额外输出 `missingFields`，供 Java 执行守卫阻断参数不完整的工具。
        """

        variables = request_context.variables or {}
        return {
            "sessionId": cls._optional_string(variables.get("agentRuntimeSessionId") or variables.get("sessionId")),
            "tenantId": cls._to_long(request_context.tenant_id, "tenantId"),
            "projectId": cls._to_long(request_context.project_id, "projectId"),
            "workspaceId": cls._optional_long(variables.get("workspaceId") or variables.get("workspace_id"), "workspaceId"),
            "actorId": request_context.actor_id,
            "channel": cls._optional_string(variables.get("channel") or "PYTHON_AI_RUNTIME"),
            "objective": request_context.objective,
            "userInput": request_context.objective,
            "workloadType": cls._enum_value(request_context.preferred_workload),
            "idempotencyKey": cls._optional_string(variables.get("idempotencyKey") or plan.request_id),
            "pythonRequestId": plan.request_id,
            "stateTrace": tuple(plan.state_trace),
            "responseSummary": plan.response_summary,
            "requiresHumanApproval": plan.requires_human_approval,
            "isolationLevel": str(variables.get("isolationLevel") or variables.get("isolation_level") or "PROJECT").upper(),
            "toolPlans": tuple(cls._tool_plan_payload(tool_plan) for tool_plan in plan.tool_plans),
            "modelGatewayGovernance": cls._json_safe(plan.model_gateway_decision),
            "memoryPlan": cls._json_safe(plan.memory_plan),
            "memoryRetrievalReport": cls._json_safe(plan.memory_retrieval_report),
        }

    @classmethod
    def parse_platform_response(cls, payload: dict[str, Any]) -> AgentPlanIngestionResult:
        """解析 Java `PlatformApiResponse<IngestedAgentPlanView>`。"""

        if payload.get("code") != 0:
            reason = payload.get("reason", "UNKNOWN")
            message = payload.get("message", "Java AgentPlan 接口返回失败")
            raise AgentPlanIngestionClientError(f"{reason}: {message}")
        data = payload.get("data")
        if not isinstance(data, dict):
            raise AgentPlanIngestionClientError("Java AgentPlan 接入响应 data 必须是对象")
        session = data.get("session") or {}
        run = data.get("run") or {}
        session_id = str(session.get("sessionId") or "")
        run_id = str(run.get("runId") or "")
        if not session_id or not run_id:
            raise AgentPlanIngestionClientError("Java AgentPlan 接入响应缺少 sessionId 或 runId")
        return AgentPlanIngestionResult(
            session_id=session_id,
            run_id=run_id,
            tool_audit_references=cls._tool_audit_references(data, session_id=session_id, run_id=run_id),
            raw_response=data,
        )

    @classmethod
    def _tool_plan_payload(cls, tool_plan: ToolPlan) -> dict[str, Any]:
        """转换单个 ToolPlan。"""

        return {
            "toolCode": tool_plan.tool_name,
            "reason": tool_plan.reason,
            "targetResourceId": cls._target_resource_id(tool_plan),
            "riskLevel": cls._enum_value(tool_plan.risk_level),
            "executionMode": cls._enum_value(tool_plan.execution_mode),
            "requiresHumanApproval": tool_plan.requires_human_approval,
            "arguments": cls._json_safe(tool_plan.arguments),
            "governanceHints": cls._json_safe(tool_plan.governance_hints),
            "parameterValidation": cls._parameter_validation_payload(tool_plan),
        }

    @classmethod
    def _parameter_validation_payload(cls, tool_plan: ToolPlan) -> dict[str, Any]:
        """转换参数校验结果，并补 Java 执行守卫需要的 missingFields。"""

        issues = tuple(
            {
                "parameterName": issue.parameter_name,
                "message": issue.message,
                "action": cls._enum_value(issue.action),
            }
            for issue in tool_plan.parameter_validation.issues
        )
        return {
            "canExecute": tool_plan.parameter_validation.can_execute,
            "canCreateDraft": tool_plan.parameter_validation.can_create_draft,
            "missingFields": tuple(issue.parameter_name for issue in tool_plan.parameter_validation.issues),
            "issues": issues,
        }

    @classmethod
    def _tool_audit_references(
        cls,
        data: dict[str, Any],
        *,
        session_id: str,
        run_id: str,
    ) -> tuple[AgentToolAuditReference, ...]:
        """从 Java toolAudits 中提取 modelToolCallId -> auditId 引用。"""

        references: list[AgentToolAuditReference] = []
        raw_audits = data.get("toolAudits") or ()
        if not isinstance(raw_audits, list):
            raise AgentPlanIngestionClientError("Java AgentPlan 接入响应 toolAudits 必须是数组")
        for audit in raw_audits:
            if not isinstance(audit, dict):
                continue
            hints = audit.get("governanceHints") or {}
            if not isinstance(hints, dict):
                hints = {}
            model_tool_call_id = str(hints.get("modelToolCallId") or "")
            audit_id = str(audit.get("auditId") or "")
            if not model_tool_call_id or not audit_id:
                continue
            references.append(
                AgentToolAuditReference(
                    model_tool_call_id=model_tool_call_id,
                    tool_name=str(audit.get("toolCode") or ""),
                    session_id=session_id,
                    run_id=run_id,
                    audit_id=audit_id,
                    state=str(audit.get("state") or ""),
                )
            )
        return tuple(references)

    @classmethod
    def _target_resource_id(cls, tool_plan: ToolPlan) -> int | None:
        """从常见参数中提取 Java targetResourceId。"""

        for key in ("targetResourceId", "datasourceId", "taskId", "ruleId", "templateId"):
            value = tool_plan.arguments.get(key)
            if value is not None:
                return cls._optional_long(value, key)
        return None

    @staticmethod
    def _json_safe(value: Any) -> Any:
        """把 dataclass、枚举和 tuple 转成 JSON 友好的基础类型。"""

        if value is None:
            return None
        if isinstance(value, Enum):
            return value.value
        if hasattr(value, "__dataclass_fields__"):
            return {
                field_name: JavaAgentPlanIngestionClient._json_safe(getattr(value, field_name))
                for field_name in value.__dataclass_fields__
            }
        if isinstance(value, dict):
            return {str(key): JavaAgentPlanIngestionClient._json_safe(item) for key, item in value.items()}
        if isinstance(value, (list, tuple, set)):
            return tuple(JavaAgentPlanIngestionClient._json_safe(item) for item in value)
        return value

    @staticmethod
    def _enum_value(value: Any) -> str:
        """读取枚举值，普通对象则转字符串。"""

        if isinstance(value, Enum):
            return str(value.value)
        return str(value) if value is not None else ""

    @staticmethod
    def _optional_string(value: Any) -> str | None:
        """把可选值转换为字符串。"""

        if value is None:
            return None
        text = str(value).strip()
        return text or None

    @staticmethod
    def _to_long(value: Any, field_name: str) -> int:
        """转换 Java Long 必填字段。"""

        try:
            return int(str(value).strip())
        except (TypeError, ValueError) as exc:
            raise AgentPlanIngestionClientError(f"{field_name} 必须是可转换为 Java Long 的数字，当前值={value}") from exc

    @staticmethod
    def _optional_long(value: Any, field_name: str) -> int | None:
        """转换 Java Long 可选字段。"""

        if value is None or str(value).strip() == "":
            return None
        try:
            return int(str(value).strip())
        except (TypeError, ValueError) as exc:
            raise AgentPlanIngestionClientError(f"{field_name} 必须是可转换为 Java Long 的数字，当前值={value}") from exc

"""Autopilot Recovery 的受治理调查工具协作者。

Recovery Specialist 可以自主判断下一步需要只读 preview，但模型建议本身不是执行许可。本模块把一个
平台确认可自治的调查动作接回现有 Java 控制面：先用 ``SpecialistToolPlanBridge`` 创建 diagnosis audit，
取得真实回执后再创建 preview ToolPlan，最后由 Java ``execution-policy`` 与 ``auto-execute-sync`` 决定
是否执行。Python 不直连 data-sync 写接口，也不根据模型自报风险放行工具。
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass, replace
from typing import Any, Mapping, Protocol

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest
from datasmart_ai_runtime.domain.intent import GovernanceDomain, IntentAnalysis
from datasmart_ai_runtime.services.agent_control_plane_feedback import (
    AgentControlPlaneFeedbackItem,
    AgentControlPlaneFeedbackSnapshot,
)
from datasmart_ai_runtime.services.agent_plan_ingestion_client import JavaAgentPlanIngestionClient
from datasmart_ai_runtime.services.model_gateway.model_tool_result_feedback import ToolExecutionFeedbackStatus
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import SpecialistTurnResult
from datasmart_ai_runtime.services.multi_agent.specialist_toolplan_bridge import (
    SpecialistBridgeStatus,
    SpecialistToolPlanBridge,
)


_AUTONOMOUS_INVESTIGATION_TOOLS = {
    "PREVIEW_QUARANTINE": "sync.dirty-record.quarantine.preview",
}
_MODEL_SAFE_PREVIEW_RESULT_FIELDS = frozenset(
    {
        "taskId",
        "executionId",
        "selectedCount",
        "eligibleCount",
        "operationState",
        "confirmationDigest",
        "selectedSampleIds",
        "issueCodes",
    }
)


class _FeedbackCollector(Protocol):
    """协作者使用的最小反馈读取协议，便于测试替换真实 Java Provider。"""

    def collect(self, plan: AgentPlan) -> AgentControlPlaneFeedbackSnapshot:
        """读取一个已接入 Java 的计划及其真实工具回执。"""


class AutopilotRecoveryInvestigationError(RuntimeError):
    """调查链路的可重试技术故障。

    该异常用于 HTTP、Java 合同、执行和回执缺失等“尚未可靠完成”的情况。调用方应让它传播到 Java/Kafka，
    由现有有限重试和 DLT 处理，而不是把暂时故障转换成已经持久化的人工关注业务结论。
    """


@dataclass(frozen=True)
class AutopilotRecoveryInvestigationResult:
    """一次调查动作的低敏结果。

    ``completed=False`` 只表示确定性的治理拒绝，例如动作不在自治目录或 bridge 拒绝扩大权限；技术故障不会
    构造该结果，而会抛出 ``AutopilotRecoveryInvestigationError``。成功结果只保留 Java audit/run/outputRef
    与注册表允许进入模型的 preview 摘要，不含原始日志、坏行正文、SQL 或凭据。
    """

    completed: bool
    reason_code: str
    evidence_summary: Mapping[str, Any]
    evidence_references: tuple[str, ...] = ()


class AutopilotRecoveryInvestigationCollaborator:
    """经由现有 Java ToolPlan 控制面执行一个最小只读 Recovery 调查动作。"""

    def __init__(
        self,
        *,
        bridge: SpecialistToolPlanBridge,
        plan_ingestion_client: JavaAgentPlanIngestionClient,
        feedback_collector: _FeedbackCollector,
    ) -> None:
        """保存启动期共享依赖，确保 Recovery 不创建第二套工具注册表或执行通道。

        三个依赖必须和普通 Agent loop 使用同一 Java 服务身份及工具目录。协作者自身没有 data-sync client，
        因而即使以后代码误选高风险动作，也只能把计划交给 Java 重新做 RBAC、风险、审批和幂等校验。
        """

        self._bridge = bridge
        self._plan_ingestion_client = plan_ingestion_client
        self._feedback_collector = feedback_collector

    def investigate(
        self,
        *,
        request: Any,
        specialist_result: SpecialistTurnResult,
        action_type: str,
    ) -> AutopilotRecoveryInvestigationResult:
        """完成 diagnosis audit、单个安全 preview 与真实 Java receipt 收集。

        输入中的 ``request`` 是 Autopilot coordinator 已验证的范围/循环请求，``specialist_result`` 是同一轮
        Recovery 输出，``action_type`` 则是 coordinator 按平台目录选出的唯一调查动作。本方法按以下顺序执行：

        1. 将 Specialist 结果缩窄为该调查动作，防止同批高风险建议随 preview 一起进入自动执行；
        2. 让 bridge 生成 ``sync.execution.diagnose`` bootstrap，并接入 Java 创建正式 audit；
        3. 读取 diagnosis 成功回执，再让 bridge 生成带 ``diagnosisRef`` 的 preview ToolPlan；
        4. 再次接入 Java，由 ``execution-policy + auto-execute-sync`` 做最终 LOW/readOnly/idempotent 门禁；
        5. 返回 preview 的低敏结构化摘要，供下一轮 Recovery 模型决策。

        确定性治理拒绝返回 ``completed=False``，不会产生副作用。HTTP、Java 合同或真实回执缺失说明交付状态
        未知，会抛出可重试异常；重复 Kafka 投递使用稳定的两个幂等键，Java ingestion/audit 负责回放而不是
        创建无限重复执行。

        English: this collaborator has no data-sync write client.  Its only effect is to submit the two bounded
        ToolPlans through Java and return a filtered receipt for the next model turn.  A later APPLY_QUARANTINE is
        never executed here; the coordinator merely validates this receipt before returning a Java-owned candidate.
        """

        action_code = self._code(action_type)
        expected_tool = _AUTONOMOUS_INVESTIGATION_TOOLS.get(action_code)
        if expected_tool is None:
            return self._blocked("RECOVERY_INVESTIGATION_ACTION_NOT_AUTONOMOUS")

        narrowed = self._narrow_specialist_result(request, specialist_result, action_code)
        if narrowed is None:
            return self._blocked("RECOVERY_INVESTIGATION_ACTION_NOT_FOUND")
        request_context = self._request_context(request, narrowed)
        parent_plan = self._parent_plan(request_context, expected_tool)

        bootstrap = self._bridge.bridge_recovery(
            request=request_context,
            plan=parent_plan,
            specialist_result=narrowed,
            control_plane_feedback=None,
        )
        if bootstrap.status is not SpecialistBridgeStatus.ACCEPTED or bootstrap.plan is None:
            return self._blocked(self._bridge_reason(bootstrap, "RECOVERY_DIAGNOSIS_BOOTSTRAP_REJECTED"))
        if tuple(item.tool_name for item in bootstrap.accepted_tool_plans) != ("sync.execution.diagnose",):
            return self._blocked("RECOVERY_DIAGNOSIS_BOOTSTRAP_NOT_MINIMAL")

        diagnosis_plan = self._ingest(
            request_context,
            bootstrap.plan,
            idempotency_key=f"{request.event_id}:diagnosis",
        )
        diagnosis_feedback = self._feedback_collector.collect(diagnosis_plan)
        self._require_java_success(diagnosis_feedback, "sync.execution.diagnose")

        preview_bridge = self._bridge.bridge_recovery(
            request=request_context,
            plan=diagnosis_plan,
            specialist_result=narrowed,
            control_plane_feedback=diagnosis_feedback,
        )
        if preview_bridge.status is not SpecialistBridgeStatus.ACCEPTED or preview_bridge.plan is None:
            return self._blocked(self._bridge_reason(preview_bridge, "RECOVERY_PREVIEW_BRIDGE_REJECTED"))
        if tuple(item.tool_name for item in preview_bridge.accepted_tool_plans) != (expected_tool,):
            return self._blocked("RECOVERY_PREVIEW_TOOLPLAN_NOT_MINIMAL")

        preview_plan = self._ingest(
            request_context,
            preview_bridge.plan,
            idempotency_key=f"{request.event_id}:preview:{action_code.lower()}",
        )
        preview_feedback = self._feedback_collector.collect(preview_plan)
        receipt = self._require_java_success(preview_feedback, expected_tool)
        safe_result = self._low_sensitive_preview_result(receipt.result)
        auto_execution = preview_feedback.auto_execution_summary or {}
        evidence_summary = {
            "source": "JAVA_AGENT_RUNTIME_TOOL_RECEIPT",
            "actionType": action_code,
            "toolName": expected_tool,
            "auditId": receipt.audit_id,
            "runId": receipt.run_id,
            "outputRef": receipt.output_ref,
            "result": safe_result,
            "autoExecution": {
                "executedCount": int(auto_execution.get("executedCount") or 0),
                "failedCount": int(auto_execution.get("failedCount") or 0),
                "skippedCount": int(auto_execution.get("skippedCount") or 0),
            },
            "payloadPolicy": "LOW_SENSITIVE_AUTOPILOT_INVESTIGATION_RECEIPT_ONLY",
        }
        return AutopilotRecoveryInvestigationResult(
            completed=True,
            reason_code="RECOVERY_INVESTIGATION_COMPLETED",
            evidence_summary=evidence_summary,
            evidence_references=(str(receipt.output_ref),),
        )

    @staticmethod
    def _low_sensitive_preview_result(result: Mapping[str, Any] | Any) -> dict[str, Any]:
        """从 Java preview 输出裁剪下一轮 Recovery 可见的固定字段。

        中文说明：此方法保留 ``operationState`` 仅供 coordinator 判断是否为 ``PREVIEWED``，同时保留任务、
        execution、计数、digest、样本 ID 和 issue codes 作为后续 apply 的候选事实。message、坏行内容、SQL、
        参数和其他未知字段一律不穿过这个边界。最终 API 的 ``quarantinePreview`` 会再移除 operationState，
        只携带 Java 约定的十个字段。

        English: filtering happens before the receipt reaches a model turn.  The helper does not validate or execute
        the preview; malformed eligibility facts are rejected later by the coordinator when and only when a model
        proposes APPLY_QUARANTINE.  This preserves a valid preview-to-retry path without broadening model context.
        """

        if not isinstance(result, Mapping):
            raise AutopilotRecoveryInvestigationError(
                "AUTOPILOT_RECOVERY_INVESTIGATION_PREVIEW_RESULT_INVALID"
            )
        return {
            str(key): value
            for key, value in result.items()
            if str(key) in _MODEL_SAFE_PREVIEW_RESULT_FIELDS
        }

    def _ingest(self, request: AgentRequest, plan: AgentPlan, *, idempotency_key: str) -> AgentPlan:
        """用阶段级稳定幂等键接入 Java，并把 audit 引用附回不可变 ToolPlan。"""

        variables = dict(request.variables)
        variables["idempotencyKey"] = idempotency_key
        stage_request = replace(request, variables=variables)
        try:
            ingestion = self._plan_ingestion_client.ingest(
                stage_request,
                plan,
                trace_id=plan.request_id,
            )
        except Exception as exc:  # noqa: BLE001 - 转成不含远端正文的固定技术异常。
            raise AutopilotRecoveryInvestigationError(
                "AUTOPILOT_RECOVERY_INVESTIGATION_PLAN_INGESTION_FAILED"
            ) from exc
        attached = ingestion.attach_to_plan(plan)
        if not attached.tool_plans or any(
            not item.governance_hints.get("agentRuntimeAuditId") for item in attached.tool_plans
        ):
            raise AutopilotRecoveryInvestigationError(
                "AUTOPILOT_RECOVERY_INVESTIGATION_AUDIT_REFERENCE_MISSING"
            )
        return attached

    @staticmethod
    def _require_java_success(
        feedback: AgentControlPlaneFeedbackSnapshot,
        tool_name: str,
    ) -> AgentControlPlaneFeedbackItem:
        """要求目标工具拥有真实 Java 成功 audit，而不是模拟反馈或仅创建了计划。

        Provider 的普通 Agent 路径允许在 Java 暂时不可用时回退模拟反馈；无人值守恢复不能采用这一降级。
        因此除 ``SUCCEEDED`` 外，还必须有 auditId、runId 和 ``agent-runtime://`` outputRef。缺失任一字段都
        表示真实执行尚未得到证明，调用方应触发 Kafka 技术重试，而不是把模拟结果交给模型继续修复。
        """

        receipt = next(
            (item for item in reversed(feedback.feedback_items) if item.tool_name == tool_name),
            None,
        )
        if (
            receipt is None
            or receipt.status is not ToolExecutionFeedbackStatus.SUCCEEDED
            or not receipt.audit_id
            or not receipt.run_id
            or not str(receipt.output_ref or "").startswith("agent-runtime://")
        ):
            raise AutopilotRecoveryInvestigationError(
                "AUTOPILOT_RECOVERY_INVESTIGATION_REAL_RECEIPT_MISSING"
            )
        return receipt

    @staticmethod
    def _request_context(request: Any, specialist_result: SpecialistTurnResult) -> AgentRequest:
        """从已验证 Autopilot 请求构造现有 Java session 内的系统恢复请求。

        tenant/project/application/actor/session 均来自 Java 触发事实；delegation 优先取 Specialist 的非公开
        control-plane binding，并且不能从模型 ``structured_output`` 读取。``workspaceKey`` 是知识隔离键，
        不是已删除的产品 Workspace 层级，因此不会被转换成 Java ``workspaceId``。
        """

        fact = specialist_result.control_plane_fact_binding
        delegation_id = str(fact.get("delegationId") or request.delegation_id)
        trusted_context = {
            "applicationId": int(request.application_id),
        }
        return AgentRequest(
            tenant_id=request.tenant_id,
            project_id=request.project_id,
            actor_id=request.actor_id,
            objective="在首次授权盒内执行一个受治理的只读恢复调查步骤，并依据真实回执继续决策。",
            request_id=f"{request.event_id}:investigation",
            variables={
                "trustedControlPlane": {
                    "applicationId": request.application_id,
                    "delegationId": delegation_id,
                    "requestContext": trusted_context,
                },
                "agentRuntimeSessionId": request.root_session_id,
                "agentRuntimeRunId": request.root_run_id,
                "interactionOrigin": "SYSTEM_RECOVERY",
            },
        )

    @staticmethod
    def _parent_plan(request: AgentRequest, expected_tool: str) -> AgentPlan:
        """建立只暴露 diagnosis 与当前单个 preview 的最小父计划。"""

        return AgentPlan(
            request_id=request.request_id or "autopilot-recovery-investigation",
            selected_route=None,
            state_trace=("autopilot_recovery", "investigation_bridge"),
            tool_plans=(),
            requires_human_approval=False,
            response_summary="正在把 Recovery 调查建议接入 Java 受治理工具控制面。",
            intent_analysis=IntentAnalysis(
                summary="数据同步失败的只读调查",
                governance_domains=(GovernanceDomain.DATA_SYNC,),
                candidate_tools=("sync.execution.diagnose", expected_tool),
                confidence=1.0,
                reasoning="动作由 Recovery 模型提出，执行资格由平台固定目录和 Java policy 决定。",
            ),
        )

    @staticmethod
    def _narrow_specialist_result(
        request: Any,
        result: SpecialistTurnResult,
        action_type: str,
    ) -> SpecialistTurnResult | None:
        """只保留 coordinator 已选择的调查动作，并为该子步骤生成稳定指纹。

        这不是修改模型结论或丢弃写动作：coordinator 只会在“原始候选全部是调查 preview”时调用本方法；
        混有 retry/apply/replay/schema 写动作的输出会在更早阶段整体阻断。新指纹绑定 event、错误、execution
        与动作代码，供 Java ingestion 幂等审计使用，不会冒充用户批准或最终修复指纹。
        """

        actions = result.structured_output.get("repairActions") or ()
        selected = next(
            (
                dict(item)
                for item in actions
                if isinstance(item, Mapping)
                and AutopilotRecoveryInvestigationCollaborator._code(item.get("actionType")) == action_type
            ),
            None,
        )
        if selected is None:
            return None
        material = "|".join(
            (
                str(request.event_id),
                str(request.error_fingerprint),
                str(request.current_execution_id),
                action_type,
            )
        )
        output = dict(result.structured_output)
        output["repairActions"] = (selected,)
        output["actionFingerprint"] = "sha256:" + hashlib.sha256(material.encode("utf-8")).hexdigest()
        output["executed"] = False
        fact = result.control_plane_fact_binding
        delegated = {
            "tenantId": request.tenant_id,
            "applicationId": request.application_id,
            "projectId": request.project_id,
            "actorId": request.actor_id,
            "userId": request.user_id,
            "sessionId": str(fact.get("sessionId") or request.root_session_id),
            "runId": str(fact.get("runId") or request.root_run_id),
            "delegationId": str(fact.get("delegationId") or request.delegation_id),
        }
        return replace(result, structured_output=output, delegated_scope_binding=delegated)

    @staticmethod
    def _bridge_reason(result: Any, fallback: str) -> str:
        """从 bridge 读取第一个稳定问题码，绝不把参数或异常正文放入 Autopilot 结果。"""

        issues = getattr(result, "issues", ())
        for issue in issues:
            code = str(getattr(issue, "code", "") or "").strip().upper()
            if code:
                return code[:96]
        return fallback

    @staticmethod
    def _blocked(reason_code: str) -> AutopilotRecoveryInvestigationResult:
        """构造无副作用的确定性治理阻断结果。"""

        return AutopilotRecoveryInvestigationResult(
            completed=False,
            reason_code=reason_code,
            evidence_summary={},
        )

    @staticmethod
    def _code(value: Any) -> str:
        """把动作编码规范为平台比较使用的大写下划线形式。"""

        return str(value or "").strip().upper().replace("-", "_")


__all__ = [
    "AutopilotRecoveryInvestigationCollaborator",
    "AutopilotRecoveryInvestigationError",
    "AutopilotRecoveryInvestigationResult",
]

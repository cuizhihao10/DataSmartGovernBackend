"""Autopilot Recovery 的受治理调查工具协作者。

Recovery Specialist 可以自主判断下一步需要只读 preview，但模型建议本身不是执行许可。本模块把一个
平台确认可自治的调查动作接回现有 Java 控制面：先用 ``SpecialistToolPlanBridge`` 创建 diagnosis audit，
取得真实回执后再创建 preview ToolPlan，最后由 Java ``execution-policy`` 与 ``auto-execute-sync`` 决定
是否执行。Python 不直连 data-sync 写接口，也不根据模型自报风险放行工具。
"""

from __future__ import annotations

import hashlib
import json
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
# 模型只能影响这些注册表声明为 model_optional 的预览参数。动作 ID、原因、置信度和证据摘要都属于
# 瞬态规划元数据，不能进入 Java AgentPlan 的重放身份，否则 Kafka 重投会把同一业务动作误判为不同请求。
_AUTONOMOUS_INVESTIGATION_MODEL_ARGUMENTS = {
    "PREVIEW_QUARANTINE": frozenset({"errorSampleIds", "quarantineAllRetryableInExecution"}),
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
        创建无限重复执行。协作者本身没有 data-sync 写客户端，只会把两个有界 ToolPlan 提交给 Java，并把
        裁剪后的回执交给下一轮模型。后续 ``APPLY_QUARANTINE`` 不在这里执行，coordinator 只验证回执并返回
        由 Java 控制面持有的候选动作。
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
        diagnosis_result = self._diagnosis_specialist_result(request, narrowed, action_code)
        preview_idempotency_prefix = self._preview_idempotency_prefix(request, narrowed)

        bootstrap = self._bridge.bridge_recovery(
            request=request_context,
            plan=parent_plan,
            specialist_result=diagnosis_result,
            control_plane_feedback=None,
        )
        if bootstrap.status is not SpecialistBridgeStatus.ACCEPTED or bootstrap.plan is None:
            return self._blocked(self._bridge_reason(bootstrap, "RECOVERY_DIAGNOSIS_BOOTSTRAP_REJECTED"))
        if tuple(item.tool_name for item in bootstrap.accepted_tool_plans) != ("sync.execution.diagnose",):
            return self._blocked("RECOVERY_DIAGNOSIS_BOOTSTRAP_NOT_MINIMAL")

        diagnosis_plan = self._ingest(
            request_context,
            bootstrap.plan,
            idempotency_key=f"{request.event_id}:investigation:v2:diagnosis",
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
            idempotency_key=f"{preview_idempotency_prefix}:preview:{action_code.lower()}",
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

        过滤发生在回执进入模型 turn 之前。本方法既不验证也不执行预览；只有模型后续提出
        ``APPLY_QUARANTINE`` 时，coordinator 才校验资格事实并拒绝畸形数据。这样既保留有效的
        preview-to-retry 路径，也不会扩大模型上下文。
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
        混有 retry/apply/replay/schema 写动作的输出会在更早阶段整体阻断。

        Kafka 重投可能让模型重新生成 actionId、说明、置信度或证据摘要，这些字段不改变预览的执行语义。
        如果直接保留，Python 会用同一阶段幂等键提交不同 Java 请求，触发正确的冲突保护并最终进入 DLT。
        因此这里只保留动作类型和注册表允许的模型参数，再由 event、错误、execution、动作代码及这些参数
        生成稳定动作指纹。真实参数变化会生成新身份；纯文本或瞬态元数据变化仍会回放首次 audit。
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
        model_arguments = AutopilotRecoveryInvestigationCollaborator._canonical_model_arguments(
            action_type,
            selected,
        )
        material = json.dumps(
            {
                "eventId": str(request.event_id),
                "errorFingerprint": str(request.error_fingerprint),
                "executionId": str(request.current_execution_id),
                "actionType": action_type,
                "modelArguments": model_arguments,
            },
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        digest = hashlib.sha256(material.encode("utf-8")).hexdigest()
        canonical_action: dict[str, Any] = {
            "actionId": f"autopilot-investigation-{digest[:24]}",
            "actionType": action_type,
        }
        if model_arguments:
            canonical_action["proposedValues"] = model_arguments
        output = {
            "repairActions": (canonical_action,),
            "actionFingerprint": f"sha256:{digest}",
            "executed": False,
        }
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
    def _diagnosis_specialist_result(
        request: Any,
        result: SpecialistTurnResult,
        action_type: str,
    ) -> SpecialistTurnResult:
        """构造与预览参数无关的诊断建议，确保同一事件只登记一份诊断 audit。

        前置诊断只需要 taskId/executionId 等受信定位，不消费 ``errorSampleIds`` 等预览策略。若把预览参数
        继续放入诊断 ToolPlan 指纹，同一事件内模型调整样本范围时会重复创建完全相同的只读诊断。因此本方法
        为诊断阶段生成独立、稳定的动作和指纹；预览阶段仍使用包含真实参数的原始收敛结果。
        """

        material = "|".join(
            (
                str(request.event_id),
                str(request.error_fingerprint),
                str(request.current_execution_id),
                action_type,
                "diagnosis",
            )
        )
        digest = hashlib.sha256(material.encode("utf-8")).hexdigest()
        output = {
            "repairActions": ({
                "actionId": f"autopilot-diagnosis-{digest[:24]}",
                "actionType": action_type,
            },),
            "actionFingerprint": f"sha256:{digest}",
            "executed": False,
        }
        return replace(result, structured_output=output)

    @staticmethod
    def _canonical_model_arguments(
        action_type: str,
        action: Mapping[str, Any],
    ) -> dict[str, Any]:
        """提取会真实改变预览 ToolPlan 的模型参数，并生成确定性 JSON 结构。

        参数白名单与当前自治调查目录一一对应。``originalValues`` 先读、``proposedValues`` 后读，和
        ``SpecialistToolPlanBridge`` 的覆盖顺序一致；未知字段不会进入指纹或 Java 请求。映射键递归排序，
        使同一对象仅因模型输出字段顺序不同也能命中幂等回放。数组顺序暂时保留，因为某些工具可能把顺序
        视为策略的一部分；数据同步服务仍会在执行边界校验 ID、数量和类型。
        """

        allowed = _AUTONOMOUS_INVESTIGATION_MODEL_ARGUMENTS.get(action_type, frozenset())
        candidates: dict[str, Any] = {}
        for source_name in ("originalValues", "proposedValues"):
            values = action.get(source_name)
            if not isinstance(values, Mapping):
                continue
            for name, value in values.items():
                normalized_name = str(name)
                if normalized_name in allowed:
                    candidates[normalized_name] = value
        error_sample_ids = candidates.get("errorSampleIds")
        if isinstance(error_sample_ids, (list, tuple)):
            # 错误样本是集合语义：模型只改变 ID 顺序不代表新策略。这里先规范为正整数、有序、去重集合，
            # 后续 Bridge 和 data-sync 仍会按工具 schema、数量上限及 execution 归属重新校验。只要存在一个
            # 非法值就保留原数组，绝不能把“无效选择器”静默改成空数组后触发“全部可重试样本”的默认预览。
            normalized_ids: list[int] = []
            all_valid = True
            for item in error_sample_ids:
                if isinstance(item, bool) or not str(item).strip().isdigit() or int(item) <= 0:
                    all_valid = False
                    break
                normalized_ids.append(int(item))
            if all_valid:
                candidates["errorSampleIds"] = sorted(set(normalized_ids))
        return {
            name: AutopilotRecoveryInvestigationCollaborator._canonical_json_value(candidates[name])
            for name in sorted(candidates)
        }

    @staticmethod
    def _canonical_json_value(value: Any) -> Any:
        """把模型参数复制为键顺序稳定的普通 JSON 值，不把对象 repr 混入治理指纹。"""

        if value is None or isinstance(value, (str, int, float, bool)):
            return value
        if isinstance(value, Mapping):
            return {
                str(key): AutopilotRecoveryInvestigationCollaborator._canonical_json_value(value[key])
                for key in sorted(value, key=lambda item: str(item))
            }
        if isinstance(value, (list, tuple)):
            return [
                AutopilotRecoveryInvestigationCollaborator._canonical_json_value(item)
                for item in value
            ]
        raise ValueError("Recovery 调查参数包含非 JSON 类型")

    @staticmethod
    def _preview_idempotency_prefix(
        request: Any,
        result: SpecialistTurnResult,
    ) -> str:
        """生成可迁移、可回放且能区分真实策略变化的预览幂等前缀。

        ``v2`` 用于避开旧实现已经写入数据库的固定键；否则修复部署后重放历史 Kafka 事件时，新的稳定请求
        仍可能撞上旧请求指纹。诊断阶段使用独立的事件级键；这里的 eventId 已包含 recovery cycle、execution
        和错误指纹，动作指纹再绑定当前预览参数，所以同一事件同一策略稳定复用，不同循环或真实策略变化
        则创建新的受治理预览 Run。
        """

        fingerprint = str(result.structured_output.get("actionFingerprint") or "")
        digest = fingerprint.removeprefix("sha256:")
        if len(digest) != 64 or any(character not in "0123456789abcdef" for character in digest):
            raise ValueError("Recovery 调查缺少稳定动作指纹")
        return f"{request.event_id}:investigation:v2:{digest}"

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

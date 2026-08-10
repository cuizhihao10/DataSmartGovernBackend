"""智能网关会话级多 Agent 调度策略。

DataSmart Govern 的 AI 目标不是把所有能力塞进一个“超级 Agent”，而是逐步靠近 Codex、Claude Code
这类真实 Agent Host 的运行方式：主控 Agent 负责理解目标和拆解计划，专门 Agent 负责各自治理域，
工具、Skill、记忆、模型路由和预算由智能网关统一治理。本模块先实现“会话调度策略视图”，用于回答：

- 本轮会话哪些 Agent 应参与；
- 哪个 Agent 是主控，哪些是专家；
- 是否因为模型网关、工具预算、Skill 准入或记忆缺失而降级；
- 是否需要人工审批、运维接管或后续异步任务；
- 调度结果中哪些信息可以安全暴露给前端和 Java 控制面。

它刻意不做真实并发执行和 Agent-to-Agent 网络通信。这样做的原因是商业化 Agent 平台需要先稳定
控制面契约，再把具体执行层替换成 LangGraph、OpenClaw/NemoClaw、A2A、MCP 或内部 runtime。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest, ToolExecutionMode, ToolRiskLevel
from datasmart_ai_runtime.domain.intent import GovernanceDomain
from datasmart_ai_runtime.domain.memory import AgentMemoryType
from datasmart_ai_runtime.services.agent_gateway.a2a_task_scheduling_context import (
    A2aTaskSchedulingContext,
    apply_a2a_task_scheduling_context,
    build_a2a_task_scheduling_context,
    most_restrictive_status,
)
from datasmart_ai_runtime.services.agent_gateway.session_models import (
    AgentParticipationMode,
    AgentSchedulingStatus,
    AgentSessionRole,
    AgentSessionSchedulingPolicyView,
    ScheduledAgentView,
)
from datasmart_ai_runtime.services.agent_gateway.session_scheduler_presentation import (
    display_summary,
    recommended_actions,
)


_PRECHECK_TOOL_NAMES = frozenset(
    {
        "sync.task.precheck",
        "sync.cdc.readiness.check",
    }
)
_RECOVERY_TOOL_NAMES = frozenset(
    {
        "sync.execution.diagnose",
        "sync.execution.failed-objects.retry",
        "sync.execution.rag.lookup",
        "sync.dirty-record.quarantine.preview",
        "sync.dirty-record.quarantine.apply",
        "sync.dirty-record.replay",
        "sync.recovery.case.publish",
        "sync.task.import.repair.apply",
        "datasource.schema.repair.preview",
        "datasource.schema.repair.apply",
        "recovery.failure.diagnose",
        "recovery.failure.diagnostic",
        "recovery.diagnostic.read",
        "recovery.runtime.logs.read",
        "recovery.controlled.execute",
        "recovery.controlled.action",
        "recovery.action.execute",
        "task.recovery.rename",
    }
)
_RECOVERY_TOOL_PREFIXES = (
    "sync.recovery.",
    "sync.dirty-record.",
    "sync.task.import.repair.",
    "datasource.schema.repair.",
    "recovery.",
)
_RECOVERY_EVIDENCE_TOOL_NAMES = frozenset(
    {
        "sync.execution.rag.lookup",
        "sync.task.import.rag.lookup",
        "knowledge.rag.query",
        "knowledge.case.lookup",
        "recovery.case.lookup",
    }
)
_RECOVERY_EVIDENCE_TOOL_PREFIXES = (
    "sync.execution.rag.",
    "sync.task.import.rag.",
    "knowledge.case.",
    "recovery.case.",
)
_MONITOR_TOOL_NAMES = frozenset(
    {
        "task.monitor.read",
        "sync.execution.status",
        "sync.execution.history",
        "sync.execution.logs",
        "sync.task.history",
        "sync.task.logs",
        "task.status.read",
        "task.history.read",
        "task.logs.read",
        "monitor.read",
        "monitor.status",
        "monitor.history",
        "monitor.logs",
        "observability.task.status",
        "observability.task.history",
        "observability.task.logs",
        "observability.status",
        "observability.history",
        "observability.logs",
        "sync.cdc.status",
        "sync.cdc.monitor",
    }
)
_MONITOR_TOOL_PREFIXES = (
    "monitor.read.",
    "monitor.status.",
    "monitor.history.",
    "monitor.logs.",
    "monitor.task.",
    "monitor.execution.",
    "task.monitor.read.",
    "sync.monitor.read.",
    "sync.monitor.status.",
    "sync.monitor.history.",
    "sync.monitor.logs.",
    "observability.read.",
    "observability.status.",
    "observability.history.",
    "observability.logs.",
    "ops.monitor.read.",
)
_MONITORED_SYNC_MODES = frozenset({"SCHEDULED_FULL", "SCHEDULED_BATCH", "CDC_STREAMING"})
_MONITOR_TASK_KINDS = frozenset(
    {
        "LONG_RUNNING",
        "LONG_RUNNING_TASK",
        "PERIODIC",
        "SCHEDULED",
        "SCHEDULED_BATCH",
        "SCHEDULED_FULL",
        "CDC",
        "REALTIME",
        "REAL_TIME",
        "CDC_STREAMING",
        "CDC_REALTIME",
    }
)
_FAILURE_STATUSES = frozenset(
    {
        "FAILED",
        "FAILURE",
        "ERROR",
        "EXECUTION_FAILED",
        "FAILED_PRECHECK",
        "RETRYABLE_FAILURE",
        "RECOVERY_REQUIRED",
        "RETRY_REQUIRED",
        "RETRYING",
        "INTERRUPTED",
        "ABORTED",
    }
)


@dataclass(frozen=True)
class _SpecialistSchedulingFacts:
    """一次调度中供三个新专业角色消费的结构化事实。

    这个内部对象故意只保存布尔判断和工具名白名单，不保存请求正文、工具参数、失败日志或案例正文。
    这样做有两个学习价值：一是把“何时激活角色”与“如何展示角色”分开，二是让后续替换意图分析器时，
    仍然可以沿用同一组低敏控制面事实。
    """

    sync_context: bool
    precheck_required: bool
    recovery_required: bool
    recovery_evidence_required: bool
    monitor_required: bool
    sync_planning_required: bool
    precheck_tool_names: tuple[str, ...] = ()
    recovery_tool_names: tuple[str, ...] = ()
    recovery_evidence_tool_names: tuple[str, ...] = ()
    monitor_tool_names: tuple[str, ...] = ()
    sync_mode: str | None = None


class AgentSessionScheduler:
    """根据计划事实生成会话级多 Agent 调度视图。

    该类的输入全部来自已经完成的治理步骤：意图分析、Skill 选择、工具计划、模型网关、工具预算、
    记忆计划和 workspace。它不重新做权限判断，也不读取外部系统。这样可以避免“API 摘要层又做一遍
    决策”导致控制面事实不一致。
    """

    def schedule(
        self,
        plan: AgentPlan,
        request: AgentRequest,
        *,
        model_gateway: Mapping[str, Any],
        skill_admission: Mapping[str, Any],
        tool_budget: Mapping[str, Any],
        memory: Mapping[str, Any],
        skill_visibility: Mapping[str, Any],
    ) -> AgentSessionSchedulingPolicyView:
        """生成本轮会话的 Agent 调度策略视图。

        参数说明：
        - `plan/request`：提供结构化计划和租户/项目/操作者上下文；
        - `model_gateway`：说明本轮是否有可用模型路由、是否预算不足或 fallback；
        - `skill_admission`：说明 Skill 是否通过准入，哪些能力可见或被拒绝；
        - `tool_budget`：说明模型生成工具调用是否超过预算；
        - `memory`：说明记忆召回目标和结果数量，不包含正文；
        - `skill_visibility`：说明本轮模型可见哪些 Skill，是 Agent 角色选择的重要依据。
        """

        domain_values = self._intent_domain_values(plan)
        selected_skill_codes = self._selected_skill_codes(skill_admission)
        visible_skill_codes = self._visible_skill_codes(skill_visibility)
        planned_tool_names = tuple(tool.tool_name for tool in plan.tool_plans)
        # 角色触发器同时参考最终工具计划、结构化意图候选工具和已准入 Skill 的 required_tools。
        # 候选工具只用于判断“本轮是否需要某类专家”，真正展示给前端的工具仍然只来自最终 plan，
        # 因而不会把模型尚未被控制面接受的工具意图误报成可执行动作。
        routing_tool_names = self._routing_tool_names(plan, planned_tool_names)
        memory_dependencies = self._memory_dependencies(plan)
        specialist_facts = self._specialist_scheduling_facts(
            plan,
            request,
            domain_values=domain_values,
            planned_tool_names=planned_tool_names,
            routing_tool_names=routing_tool_names,
        )
        degraded_reasons = self._global_degradation_reasons(
            model_gateway=model_gateway,
            skill_admission=skill_admission,
            tool_budget=tool_budget,
            memory=memory,
        )
        a2a_context = build_a2a_task_scheduling_context(request)
        # A2A task planning decision 是外部 Agent 委派任务进入 DataSmart 后的控制面事实。
        # 这里不重新解析原始 A2A payload，只消费 5.31/5.32 已经低敏化的 planning decision，并按更保守
        # 的状态合并到会话调度中：例如模型网关 READY，但 A2A task 正在等待授权，则整轮会话仍应显示
        # `APPROVAL_REQUIRED`，避免前端或后续 runtime 误以为可以直接推进 worker。
        status = most_restrictive_status(
            self._overall_status(plan, model_gateway, skill_admission, tool_budget),
            a2a_context.scheduling_status,
        )
        agents = self._build_agents(
            domain_values=domain_values,
            selected_skill_codes=selected_skill_codes,
            visible_skill_codes=visible_skill_codes,
            planned_tool_names=planned_tool_names,
            memory_dependencies=memory_dependencies,
            global_degradation_reasons=degraded_reasons,
            plan=plan,
            request=request,
            routing_tool_names=routing_tool_names,
            specialist_facts=specialist_facts,
        )
        agents = apply_a2a_task_scheduling_context(agents, a2a_context)
        handoff_required = plan.requires_human_approval or status in {
            AgentSchedulingStatus.APPROVAL_REQUIRED,
            AgentSchedulingStatus.BLOCKED,
        } or a2a_context.requires_handoff
        policy_axes = {
            "intentDomains": domain_values,
            "selectedSkillCodes": selected_skill_codes,
            "visibleSkillCodes": visible_skill_codes,
            "plannedToolNames": planned_tool_names,
            "memoryDependencies": memory_dependencies,
            "modelGatewayAvailable": bool(model_gateway.get("available")),
            "skillAdmissionAllowed": bool(skill_admission.get("allowed")),
            "toolBudgetAllowed": bool(tool_budget.get("allowed", True)),
            "approvalRequired": bool(plan.requires_human_approval),
            "tenantScoped": bool(request.tenant_id),
            "projectScoped": bool(request.project_id),
        }
        if a2a_context.available:
            policy_axes["a2aTaskPlanning"] = a2a_context.to_policy_axis()
        return AgentSessionSchedulingPolicyView(
            available=status != AgentSchedulingStatus.BLOCKED,
            status=status,
            primary_agent_role=AgentSessionRole.MASTER_ORCHESTRATOR.value,
            participating_agents=agents,
            policy_axes=policy_axes,
            handoff_required=handoff_required,
            display_summary=display_summary(status, agents),
            recommended_actions=recommended_actions(
                status,
                degraded_reasons + a2a_context.degradation_reasons,
                agents,
                a2a_context,
            ),
        )

    @staticmethod
    def _intent_domain_values(plan: AgentPlan) -> tuple[str, ...]:
        """读取结构化意图域。

        如果编排器未来切换成模型式意图识别，只要继续填充 `plan.intent_analysis.governance_domains`，
        本调度器就不需要变化。
        """

        if plan.intent_analysis is None:
            return ()
        return tuple(domain.value for domain in plan.intent_analysis.governance_domains)

    @staticmethod
    def _selected_skill_codes(skill_admission: Mapping[str, Any]) -> tuple[str, ...]:
        """从 Skill 准入摘要读取已选择 Skill 编码。"""

        return tuple(
            str(item.get("skillCode"))
            for item in skill_admission.get("selectedSkills", ())
            if isinstance(item, Mapping) and item.get("skillCode")
        )

    @staticmethod
    def _routing_tool_names(plan: AgentPlan, planned_tool_names: tuple[str, ...]) -> tuple[str, ...]:
        """合并用于角色路由的结构化工具名，并保持最终计划工具优先。

        `intent_analysis.candidate_tools` 和 Skill 的 `required_tools` 是规划阶段事实，能够在最终工具计划
        为空或被参数门禁裁剪时提醒主控“需要哪类专家继续澄清”。它们只参与角色选择，不会被写入某个
        专业 Agent 的可执行工具白名单；后者仍由最终 `plan.tool_plans` 和控制面另行决定。
        """

        names: list[str] = []
        for name in planned_tool_names:
            normalized = str(name).strip()
            if normalized and normalized not in names:
                names.append(normalized)
        if plan.intent_analysis is not None:
            candidates = plan.intent_analysis.candidate_tools
        else:
            candidates = ()
        for name in candidates:
            normalized = str(name).strip()
            if normalized and normalized not in names:
                names.append(normalized)
        for selection in plan.skill_plan.selected_skills:
            for name in selection.required_tools:
                normalized = str(name).strip()
                if normalized and normalized not in names:
                    names.append(normalized)
        return tuple(names)

    @classmethod
    def _specialist_scheduling_facts(
        cls,
        plan: AgentPlan,
        request: AgentRequest,
        *,
        domain_values: tuple[str, ...],
        planned_tool_names: tuple[str, ...],
        routing_tool_names: tuple[str, ...],
    ) -> _SpecialistSchedulingFacts:
        """从 plan/request 的结构化字段计算三个专业角色的激活事实。

        这里是本次调度规则的核心边界：角色不能因为用户自然语言里出现了“监控”“失败”或“恢复”
        就被直接拉起，而必须由最终工具计划、治理域、结构化请求标志、同步模式或控制面状态证明。
        这样既避免自由文本误触发，也避免把模型的“自我检查”当成真正的 PRECHECK_AGENT。
        """

        request_mappings = cls._structured_request_mappings(request)
        # 触发判断可参考 candidate_tools，但对外的 plannedToolNames 必须严格来自最终 plan，
        # 否则模型候选或被准入裁剪的工具会被误报成已安排工作。
        routing_precheck_tools = tuple(name for name in routing_tool_names if cls._is_precheck_tool(name))
        routing_recovery_tools = tuple(name for name in routing_tool_names if cls._is_recovery_tool(name))
        routing_evidence_tools = tuple(name for name in routing_tool_names if cls._is_recovery_evidence_tool(name))
        routing_monitor_tools = tuple(name for name in routing_tool_names if cls._is_monitor_tool(name))
        precheck_tools = tuple(name for name in planned_tool_names if cls._is_precheck_tool(name))
        recovery_tools = tuple(name for name in planned_tool_names if cls._is_recovery_tool(name))
        evidence_tools = tuple(name for name in planned_tool_names if cls._is_recovery_evidence_tool(name))
        monitor_tools = tuple(name for name in planned_tool_names if cls._is_monitor_tool(name))
        sync_mode = cls._sync_mode_from_request(request_mappings)

        sync_context = bool(
            GovernanceDomain.DATA_SYNC.value in domain_values
            or sync_mode
            or any(name.startswith(("sync.", "data_sync.")) for name in routing_tool_names)
            or cls._has_named_mapping(request, ("dataSyncRequest", "data_sync_request", "syncRequest", "sync_request"))
        )
        # A complete synchronization lifecycle must always pass through the
        # deterministic precheck specialist, even when the model omitted the
        # precheck tool from its first tool list.  We deliberately require a
        # structured sync mode/request or a lifecycle tool here: a failure
        # diagnosis request may also belong to DATA_SYNC, but it should route
        # to KNOWLEDGE/RECOVERY rather than rerun a creation-time precheck.
        structured_sync_request_present = cls._has_named_mapping(
            request,
            ("dataSyncRequest", "data_sync_request", "syncRequest", "sync_request"),
        )
        recovery_context_present = bool(
            cls._has_recovery_context(request)
            or cls._plan_has_recovery_fact(plan)
            or routing_recovery_tools
        )
        # A persisted failed task/execution is a different workflow from task creation.  Continuation
        # payloads may still carry the last reviewed ``dataSyncRequest`` and even stale lifecycle tools;
        # neither is consent to rebuild mappings.  Keep that state in the recovery lane unless the
        # trusted structured request explicitly asks to change/rebuild the synchronization definition.
        existing_failed_execution_present = cls._has_existing_failed_execution_context(request)
        explicit_sync_replanning_requested = cls._has_explicit_sync_replanning_request(request_mappings)
        sync_lifecycle_requested = bool(
            structured_sync_request_present
            or bool(
                not recovery_context_present
                and (
                    sync_mode
                    or any(
                        name in {
                            "sync.task.draft.save",
                            "sync.task.publish",
                            "sync.task.run",
                            "sync.task.execute",
                            "sync.task.create",
                        }
                        for name in planned_tool_names + routing_tool_names
                    )
                )
            )
        )
        if existing_failed_execution_present:
            sync_lifecycle_requested = bool(
                explicit_sync_replanning_requested
                and (
                    structured_sync_request_present
                    or any(
                        name in {
                            "sync.task.draft.save",
                            "sync.task.publish",
                            "sync.task.run",
                            "sync.task.execute",
                            "sync.task.create",
                        }
                        for name in planned_tool_names + routing_tool_names
                    )
                )
            )
        # A recovery continuation may still carry the DATA_SYNC governance domain because the
        # failed execution belongs to data synchronization.  That domain alone must not restart
        # the creation-time planning specialist.  Only a structured sync request or an explicit
        # lifecycle tool, represented by this fact, authorizes DATA_SYNC_AGENT to join this wave.
        sync_planning_required = sync_lifecycle_requested
        precheck_required = bool(routing_precheck_tools) or sync_lifecycle_requested or cls._has_explicit_true(
            request_mappings,
            (
                "precheckRequired",
                "precheck_required",
                "requiresPrecheck",
                "requires_precheck",
                "deterministicPrecheckRequired",
                "deterministic_precheck_required",
            ),
        )
        effective_precheck_tools = (
            precheck_tools
            or routing_precheck_tools
            or (("sync.task.precheck",) if precheck_required and sync_lifecycle_requested else ())
        )
        recovery_required = bool(routing_recovery_tools) or cls._has_recovery_context(request) or cls._plan_has_recovery_fact(plan)
        recovery_evidence_required = recovery_required or bool(routing_evidence_tools) or cls._has_explicit_true(
            request_mappings,
            (
                "recoveryEvidenceRequired",
                "recovery_evidence_required",
                "caseEvidenceRequired",
                "case_evidence_required",
                "caseSearchRequested",
                "case_search_requested",
                "failureCaseLookup",
                "failure_case_lookup",
                "useRecoveryRag",
                "use_recovery_rag",
                "useRag",
                "use_rag",
                "ragRequested",
                "rag_requested",
                "requiresCaseEvidence",
                "requires_case_evidence",
            ),
        ) or cls._has_named_mapping(
            request,
            ("caseEvidence", "case_evidence", "recoveryCaseEvidence", "recovery_case_evidence", "ragEvidence"),
        )
        monitor_required = bool(
            existing_failed_execution_present
            or routing_monitor_tools
            or cls._has_monitoring_context(request, request_mappings)
        )
        if sync_mode in _MONITORED_SYNC_MODES:
            monitor_required = True
        if any(tool.execution_mode == ToolExecutionMode.ASYNC_TASK for tool in plan.tool_plans):
            # ASYNC_TASK 是计划层的长任务事实，不需要从用户目标猜测“任务可能很慢”。
            monitor_required = True

        return _SpecialistSchedulingFacts(
            sync_context=sync_context,
            precheck_required=precheck_required,
            recovery_required=recovery_required,
            recovery_evidence_required=recovery_evidence_required,
            monitor_required=monitor_required,
            sync_planning_required=sync_planning_required,
            precheck_tool_names=effective_precheck_tools,
            recovery_tool_names=recovery_tools,
            recovery_evidence_tool_names=evidence_tools,
            monitor_tool_names=monitor_tools,
            sync_mode=sync_mode,
        )

    @staticmethod
    def _structured_request_mappings(request: AgentRequest) -> tuple[Mapping[str, Any], ...]:
        """提取请求中的结构化对象，排除 objective、latestUserMessage 等自由文本字段。

        这些键是控制面/表单常用的命名空间。只展开一层既能兼容 camelCase 和 snake_case，
        又不会递归扫描任意用户对象，把偶然出现的字符串当成调度证据。
        """

        root = request.variables if isinstance(request.variables, Mapping) else {}
        mappings: list[Mapping[str, Any]] = [root]
        nested_keys = (
            "dataSyncRequest",
            "data_sync_request",
            "syncRequest",
            "sync_request",
            "syncPlan",
            "sync_plan",
            "failureContext",
            "failure_context",
            "failedExecution",
            "failed_execution",
            "executionFailure",
            "execution_failure",
            "recoveryContext",
            "recovery_context",
            "executionContext",
            "execution_context",
            "taskExecution",
            "task_execution",
            "monitoringRequest",
            "monitoring_request",
            "monitorRequest",
            "monitor_request",
            "monitoring",
            "trustedControlPlane",
        )
        for key in nested_keys:
            value = root.get(key)
            if isinstance(value, Mapping):
                mappings.append(value)
        return tuple(mappings)

    @staticmethod
    def _has_named_mapping(request: AgentRequest, names: tuple[str, ...]) -> bool:
        """判断请求是否提供了指定的非空结构化对象。"""

        # 失败上下文、监控请求和同步请求都可能再包一层案例/证据对象；只沿已知控制面命名空间展开，
        # 不递归用户自定义对象，避免误把任意正文当成调度事实。
        return any(
            isinstance(mapping.get(name), Mapping) and bool(mapping.get(name))
            for mapping in AgentSessionScheduler._structured_request_mappings(request)
            for name in names
        )

    @classmethod
    def _has_existing_failed_execution_context(cls, request: AgentRequest) -> bool:
        """Return whether a trusted carrier names one concrete failed task execution.

        Recovery-only scheduling needs a stricter signal than a generic ``failureContext``.  In
        particular, task 76 / execution 1805 must remain a read-only diagnostic/recovery turn even
        when the conversation still contains its previous synchronization form.  Require ``taskId``,
        ``executionId`` and a failure marker in the *same* allow-listed control-plane carrier so IDs
        from unrelated objects cannot be joined into a fictitious execution.
        """

        variables = request.variables if isinstance(request.variables, Mapping) else {}
        candidates: list[Mapping[str, Any]] = []
        for name in (
            "failureContext",
            "failure_context",
            "failedExecution",
            "failed_execution",
            "executionFailure",
            "execution_failure",
            "recoveryContext",
            "recovery_context",
        ):
            value = variables.get(name)
            if isinstance(value, Mapping):
                candidates.append(value)

        trusted = variables.get("trustedControlPlane")
        if isinstance(trusted, Mapping):
            for name in ("failureContext", "failure_context", "failedExecution", "failed_execution"):
                value = trusted.get(name)
                if isinstance(value, Mapping):
                    candidates.append(value)

        for candidate in candidates:
            task_id = candidate.get("taskId", candidate.get("task_id"))
            execution_id = candidate.get("executionId", candidate.get("execution_id"))
            if not cls._is_positive_identifier(task_id) or not cls._is_positive_identifier(execution_id):
                continue
            if cls._has_failure_marker(candidate):
                return True
        return False

    @classmethod
    def _has_explicit_sync_replanning_request(cls, mappings: tuple[Mapping[str, Any], ...]) -> bool:
        """Accept only explicit structured intent before re-entering DATA_SYNC planning.

        A previous draft, a broad ``DATA_SYNC`` governance domain, or a model-proposed lifecycle
        tool must not silently turn diagnosis into a configuration rewrite.  The UI/bridge can set one
        of these boolean facts only after the user has requested mapping/configuration changes.
        """

        return cls._has_explicit_true(
            mappings,
            (
                "modifySyncConfiguration",
                "modify_sync_configuration",
                "rebuildSyncConfiguration",
                "rebuild_sync_configuration",
                "replanSyncConfiguration",
                "replan_sync_configuration",
                "replanObjectMappings",
                "replan_object_mappings",
                "regenerateFieldMappings",
                "regenerate_field_mappings",
                "editSyncConfiguration",
                "edit_sync_configuration",
            ),
        )

    @staticmethod
    def _is_positive_identifier(value: Any) -> bool:
        """Validate database resource IDs without accepting booleans, decimals or free-form text."""

        if isinstance(value, bool):
            return False
        if isinstance(value, int):
            return value > 0
        if isinstance(value, str):
            return value.strip().isdigit() and int(value.strip()) > 0
        return False

    @classmethod
    def _has_failure_marker(cls, value: Mapping[str, Any]) -> bool:
        """Recognize only compact failure metadata, never free-text error messages or objectives."""

        for name in ("failureCode", "failure_code", "failureReference", "failure_reference"):
            marker = value.get(name)
            if isinstance(marker, str) and marker.strip() and len(marker.strip()) <= 240:
                return True
        return any(
            cls._is_failure_status(value.get(name))
            for name in ("status", "state", "executionStatus", "execution_status", "taskStatus", "task_status")
        )

    @staticmethod
    def _has_explicit_true(mappings: tuple[Mapping[str, Any], ...], names: tuple[str, ...]) -> bool:
        """只接受布尔真值作为开关，避免把任意自然语言值误判成调度命令。"""

        return any(mapping.get(name) is True for mapping in mappings for name in names)

    @classmethod
    def _has_recovery_context(cls, request: AgentRequest) -> bool:
        """识别失败/重试/恢复事实，而不是搜索用户文本里的故障词。

        失败上下文可以是 Java 控制面回传的对象，也可以是 API 请求中的明确布尔标志。对象只要出现在
        专用命名空间就表示调用方已经声明了恢复场景；其中的正文不会被返回到调度理由。
        """

        variables = request.variables if isinstance(request.variables, Mapping) else {}
        # Java 确认工具续跑有时不携带完整 failureContext，而是用稳定字段表达上一批工具失败。
        # 先收集已知控制面命名空间，后续只读取这些结构化键，不扫描 objective 或消息正文。
        structured_mappings = AgentSessionScheduler._structured_request_mappings(request)
        context_names = (
            "failureContext",
            "failure_context",
            "failedExecution",
            "failed_execution",
            "executionFailure",
            "execution_failure",
            "recoveryContext",
            "recovery_context",
        )
        if any(
            (isinstance(variables.get(name), Mapping) and bool(variables.get(name)))
            or variables.get(name) is True
            for name in context_names
        ):
            return True

        # durable continuation 的失败事实由平台写入，而不是由模型从自然语言推断。
        # `failedToolNames`、`failureRecoveryKind` 和 `repairProposal` 只用于确认“恢复场景存在”，
        # 不会进入 activation_reasons，因此失败工具参数和修复正文不会泄露到会话摘要。
        if cls._has_explicit_true(
            structured_mappings,
            (
                "failureRecoveryContinuation",
                "failure_recovery_continuation",
                "recoveryRequired",
                "recovery_required",
                "retryRequested",
                "retry_requested",
                "retryRequired",
                "retry_required",
                "resumeRequested",
                "resume_requested",
                "resumeAfterFailure",
                "resume_after_failure",
                "diagnoseSyncExecution",
                "diagnose_sync_execution",
            ),
        ):
            return True
        if any(
            bool(mapping.get(name))
            for mapping in structured_mappings
            for name in (
                "recoveryExecutionId",
                "recovery_execution_id",
                "failedToolNames",
                "failed_tool_names",
                "failureRecoveryKind",
                "failure_recovery_kind",
                "repairProposal",
                "repair_proposal",
            )
        ):
            return True

        recovery_mappings = tuple(
            value
            for mapping in structured_mappings
            for name in context_names + ("executionContext", "execution_context", "taskExecution", "task_execution")
            if isinstance((value := mapping.get(name)), Mapping)
        )
        status_keys = (
            "status",
            "state",
            "executionStatus",
            "execution_status",
            "taskStatus",
            "task_status",
            "outcome",
            "failureStatus",
            "failure_status",
            "recoveryStatus",
            "recovery_status",
        )
        if any(
            cls._is_failure_status(mapping.get(key))
            for mapping in recovery_mappings
            for key in status_keys
        ):
            return True

        # 某些可信控制面版本会直接把 executionStatus 放在 trustedControlPlane 或同步请求对象中。
        # 这些字段仍然是稳定状态码，属于结构化事实；只有根变量的自由文本字段被明确排除在外。
        if any(
            cls._is_failure_status(mapping.get(key))
            for mapping in structured_mappings
            for key in status_keys
        ):
            return True

        # 这些顶层字段是兼容旧 API 的明确控制面投影，不能与普通的 status 文本混用。
        return any(
            cls._is_failure_status(variables.get(key))
            for key in (
                "executionStatus",
                "execution_status",
                "taskStatus",
                "task_status",
                "failureStatus",
                "failure_status",
                "recoveryStatus",
                "recovery_status",
            )
        ) or cls._has_explicit_true(
            (variables,),
            (
                "recoveryRequired",
                "recovery_required",
                "retryRequested",
                "retry_requested",
                "retryRequired",
                "retry_required",
                "resumeRequested",
                "resume_requested",
                "resumeAfterFailure",
                "resume_after_failure",
                "diagnoseSyncExecution",
                "diagnose_sync_execution",
            ),
        )

    @staticmethod
    def _plan_has_recovery_fact(plan: AgentPlan) -> bool:
        """读取计划诊断中的稳定失败状态，不读取诊断正文或模型摘要。"""

        diagnostics = plan.workflow_diagnostics
        if not isinstance(diagnostics, Mapping):
            return False
        if any(diagnostics.get(key) is True for key in ("recoveryRequired", "recovery_required", "retryRequested")):
            return True
        return any(
            str(diagnostics.get(key) or "").strip().upper() in _FAILURE_STATUSES
            for key in ("failureStatus", "failure_status", "executionStatus", "execution_status", "taskStatus")
        )

    @classmethod
    def _has_monitoring_context(
        cls,
        request: AgentRequest,
        mappings: tuple[Mapping[str, Any], ...],
    ) -> bool:
        """识别运行状态、历史、日志和长任务观察事实，并保持观察角色只读。"""

        variables = request.variables if isinstance(request.variables, Mapping) else {}
        monitor_names = (
            "monitoringRequest",
            "monitoring_request",
            "monitorRequest",
            "monitor_request",
            "monitoring",
        )
        if any(isinstance(variables.get(name), Mapping) and bool(variables.get(name)) for name in monitor_names):
            return True
        if cls._has_explicit_true(
            mappings,
            (
                "monitorRequested",
                "monitor_requested",
                "monitoringRequested",
                "monitoring_requested",
                "observeExecution",
                "observe_execution",
                "observeTask",
                "observe_task",
                "includeHistory",
                "include_history",
                "includeLogs",
                "include_logs",
                "longRunning",
                "long_running",
                "scheduledTask",
                "scheduled_task",
                "cdcMonitoring",
                "cdc_monitoring",
            ),
        ):
            return True

        task_kind_keys = ("taskKind", "task_kind", "monitoringType", "monitoring_type", "monitoringMode")
        if any(
            str(mapping.get(key) or "").strip().upper() in _MONITOR_TASK_KINDS
            for mapping in mappings
            for key in task_kind_keys
        ):
            return True

        # 顶层状态/历史/日志字段是已结构化的观察请求，不是对 objective 的关键词扫描。
        return any(
            key in variables
            for key in (
                "executionStatus",
                "execution_status",
                "taskStatus",
                "task_status",
                "historyRequested",
                "history_requested",
                "logsRequested",
                "logs_requested",
            )
        )

    @staticmethod
    def _sync_mode_from_request(mappings: tuple[Mapping[str, Any], ...]) -> str | None:
        """读取同步请求中的模式，用于识别定期与 CDC 的持续观察语义。"""

        for mapping in mappings:
            value = mapping.get("syncMode") or mapping.get("sync_mode")
            if value is None:
                continue
            normalized = str(value).strip().upper()
            # 兼容旧向导和 specialist 入口的别名，先收敛到平台控制面的稳定模式码，
            # 再判断是否需要持续观察；这样 CDC 语义不会依赖某个入口恰好使用哪种写法。
            if normalized in {"REAL_TIME", "REALTIME", "CDC"}:
                return "CDC_STREAMING"
            if normalized in {"SCHEDULED", "PERIODIC"}:
                return "SCHEDULED_BATCH"
            return normalized
        return None

    @staticmethod
    def _is_failure_status(value: object) -> bool:
        """按稳定状态码判断失败，不把失败描述文本当作证据。"""

        normalized = str(getattr(value, "value", value) or "").strip().upper()
        return normalized in _FAILURE_STATUSES

    @staticmethod
    def _is_precheck_tool(name: str) -> bool:
        """判断工具名是否代表同步平台确定性预检。"""

        return str(name).strip() in _PRECHECK_TOOL_NAMES

    @staticmethod
    def _is_recovery_tool(name: str) -> bool:
        """判断工具名是否属于失败诊断、恢复动作或恢复案例链路。"""

        normalized = str(name).strip()
        return normalized in _RECOVERY_TOOL_NAMES or normalized.startswith(_RECOVERY_TOOL_PREFIXES)

    @staticmethod
    def _is_recovery_evidence_tool(name: str) -> bool:
        """判断工具名是否明确要求案例/RAG 证据，而不是泛化地把 RAG 放到每轮首步。"""

        normalized = str(name).strip()
        return normalized in _RECOVERY_EVIDENCE_TOOL_NAMES or normalized.startswith(_RECOVERY_EVIDENCE_TOOL_PREFIXES)

    @staticmethod
    def _is_monitor_tool(name: str) -> bool:
        """判断工具名是否代表只读运行状态、历史、日志或监控查询。"""

        normalized = str(name).strip()
        return normalized in _MONITOR_TOOL_NAMES or normalized.startswith(_MONITOR_TOOL_PREFIXES)

    @staticmethod
    def _visible_skill_codes(skill_visibility: Mapping[str, Any]) -> tuple[str, ...]:
        """读取本轮真正对模型可见的 Skill 编码。"""

        return tuple(
            str(item.get("skillCode"))
            for item in skill_visibility.get("visibleSkills", ())
            if isinstance(item, Mapping) and item.get("skillCode")
        )

    @staticmethod
    def _memory_dependencies(plan: AgentPlan) -> tuple[str, ...]:
        """汇总 Skill 与记忆计划中的记忆依赖类型。"""

        memory_types: set[str] = {target.memory_type.value for target in plan.memory_plan.retrieval_targets}
        for selection in plan.skill_plan.selected_skills:
            for dependency in selection.memory_dependencies:
                if isinstance(dependency, AgentMemoryType):
                    memory_types.add(dependency.value)
                else:
                    memory_types.add(str(dependency))
        return tuple(sorted(memory_types))

    @staticmethod
    def _global_degradation_reasons(
        *,
        model_gateway: Mapping[str, Any],
        skill_admission: Mapping[str, Any],
        tool_budget: Mapping[str, Any],
        memory: Mapping[str, Any],
    ) -> tuple[str, ...]:
        """生成全局降级原因。

        降级原因只使用治理摘要字段，不暴露原始 prompt、工具参数或记忆内容。
        """

        reasons: list[str] = []
        if not model_gateway.get("available"):
            reasons.append("MODEL_GATEWAY_UNAVAILABLE_OR_BUDGET_BLOCKED")
        if not skill_admission.get("allowed"):
            reasons.append("SKILL_ADMISSION_REJECTED")
        if not tool_budget.get("allowed", True):
            reasons.append("MODEL_TOOL_CALL_BUDGET_BLOCKED")
        if memory.get("retrievalTargetCount", 0) > 0 and memory.get("totalRetrieved", 0) == 0:
            reasons.append("MEMORY_TARGETS_WITHOUT_RETRIEVAL_RESULT")
        return tuple(reasons)

    @staticmethod
    def _overall_status(
        plan: AgentPlan,
        model_gateway: Mapping[str, Any],
        skill_admission: Mapping[str, Any],
        tool_budget: Mapping[str, Any],
    ) -> AgentSchedulingStatus:
        """计算本轮会话调度状态。"""

        if not model_gateway.get("available"):
            return AgentSchedulingStatus.BLOCKED
        if not skill_admission.get("allowed") or not tool_budget.get("allowed", True):
            return AgentSchedulingStatus.DEGRADED
        if plan.requires_human_approval:
            return AgentSchedulingStatus.APPROVAL_REQUIRED
        return AgentSchedulingStatus.READY

    def _build_agents(
        self,
        *,
        domain_values: tuple[str, ...],
        selected_skill_codes: tuple[str, ...],
        visible_skill_codes: tuple[str, ...],
        planned_tool_names: tuple[str, ...],
        memory_dependencies: tuple[str, ...],
        global_degradation_reasons: tuple[str, ...],
        plan: AgentPlan,
        request: AgentRequest,
        routing_tool_names: tuple[str, ...],
        specialist_facts: _SpecialistSchedulingFacts,
    ) -> tuple[ScheduledAgentView, ...]:
        """构建参与 Agent 列表。

        主控 Agent 永远参与，因为它承担会话编排和最终摘要职责；专家 Agent 根据治理域、Skill 和工具
        共同激活。新专业角色还要满足各自的结构化触发条件：预检看确定性工具/请求事实，恢复看失败或
        重试事实，监控看只读运行观察事实。这样比只看关键词更稳定，也不会让模型自行声称“已经检查过”。
        """

        agents: list[ScheduledAgentView] = [
            ScheduledAgentView(
                role=AgentSessionRole.MASTER_ORCHESTRATOR,
                display_name="主控编排 Agent",
                participation_mode=AgentParticipationMode.PRIMARY,
                activation_reasons=("所有会话都需要主控 Agent 负责目标理解、计划整合和治理摘要。",),
                governed_domains=domain_values,
                visible_skill_codes=visible_skill_codes,
                planned_tool_names=planned_tool_names,
                memory_dependencies=memory_dependencies,
                status=self._agent_status(global_degradation_reasons, plan),
                degradation_reasons=global_degradation_reasons,
                requires_handoff=plan.requires_human_approval,
            )
        ]
        domain_agent_specs = self._domain_agent_specs()
        for domain, role, name in domain_agent_specs:
            should_join = self._domain_agent_should_join(domain, selected_skill_codes, routing_tool_names, domain_values)
            # 预检/恢复是同步控制面流程的一部分。即使上游意图分析只返回了结构化请求标志，
            # 只要已经证明这是同步上下文，也必须补齐 DATA_SYNC_AGENT，保证 PRECHECK_AGENT 的依赖
            # 不会悬空；普通自由文本不会触发这一分支。
            if role == AgentSessionRole.DATA_SYNC_AGENT:
                # A failed execution can be governed by DATA_SYNC while being a recovery-only
                # continuation.  Do not schedule a fresh planner for that case; otherwise a
                # harmless recovery run produces an unrelated planning-model failure.  A later
                # turn that carries an explicit sync plan/lifecycle request remains eligible.
                should_join = specialist_facts.sync_planning_required
            if role == AgentSessionRole.KNOWLEDGE_AGENT and (
                specialist_facts.recovery_required and specialist_facts.recovery_evidence_required
            ):
                should_join = True
            if should_join:
                activation_reasons = self._domain_activation_reasons(
                    domain,
                    selected_skill_codes,
                    planned_tool_names,
                )
                if role == AgentSessionRole.KNOWLEDGE_AGENT and (
                    specialist_facts.recovery_required and specialist_facts.recovery_evidence_required
                ):
                    activation_reasons = self._knowledge_recovery_activation_reasons(
                        planned_tool_names,
                        specialist_facts,
                    )
                agents.append(
                    ScheduledAgentView(
                        role=role,
                        display_name=name,
                        participation_mode=AgentParticipationMode.SPECIALIST,
                        activation_reasons=activation_reasons,
                        governed_domains=(domain.value,),
                        visible_skill_codes=self._skills_for_domain(domain, selected_skill_codes),
                        planned_tool_names=self._tools_for_domain(domain, planned_tool_names),
                        memory_dependencies=memory_dependencies,
                        status=self._agent_status(global_degradation_reasons, plan),
                        degradation_reasons=global_degradation_reasons,
                        requires_handoff=self._domain_requires_handoff(domain, plan),
                    )
                )
        if specialist_facts.precheck_required and specialist_facts.sync_context:
            agents.append(
                ScheduledAgentView(
                    role=AgentSessionRole.PRECHECK_AGENT,
                    display_name="同步确定性预检查 Agent",
                    participation_mode=AgentParticipationMode.SPECIALIST,
                    activation_reasons=self._specialist_activation_reasons(
                        AgentSessionRole.PRECHECK_AGENT,
                        planned_tool_names,
                        specialist_facts,
                    ),
                    governed_domains=(GovernanceDomain.DATA_SYNC.value,),
                    visible_skill_codes=self._skills_for_specialist_role(
                        AgentSessionRole.PRECHECK_AGENT,
                        selected_skill_codes,
                    ),
                    planned_tool_names=specialist_facts.precheck_tool_names,
                    memory_dependencies=memory_dependencies,
                    status=self._agent_status(global_degradation_reasons, plan),
                    degradation_reasons=global_degradation_reasons,
                    requires_handoff=self._specialist_requires_handoff(
                        AgentSessionRole.PRECHECK_AGENT,
                        plan,
                        specialist_facts,
                    ),
                )
            )
        if specialist_facts.recovery_required:
            agents.append(
                ScheduledAgentView(
                    role=AgentSessionRole.RECOVERY_AGENT,
                    display_name="失败恢复 Agent",
                    participation_mode=AgentParticipationMode.SPECIALIST,
                    activation_reasons=self._specialist_activation_reasons(
                        AgentSessionRole.RECOVERY_AGENT,
                        planned_tool_names,
                        specialist_facts,
                    ),
                    governed_domains=self._specialist_governed_domains(
                        AgentSessionRole.RECOVERY_AGENT,
                        domain_values,
                        specialist_facts,
                    ),
                    visible_skill_codes=self._skills_for_specialist_role(
                        AgentSessionRole.RECOVERY_AGENT,
                        selected_skill_codes,
                    ),
                    planned_tool_names=specialist_facts.recovery_tool_names,
                    memory_dependencies=memory_dependencies,
                    status=self._agent_status(global_degradation_reasons, plan),
                    degradation_reasons=global_degradation_reasons,
                    requires_handoff=self._specialist_requires_handoff(
                        AgentSessionRole.RECOVERY_AGENT,
                        plan,
                        specialist_facts,
                    ),
                )
            )
        if specialist_facts.monitor_required:
            agents.append(
                ScheduledAgentView(
                    role=AgentSessionRole.MONITOR_AGENT,
                    display_name="任务运行监控 Agent",
                    participation_mode=AgentParticipationMode.OBSERVER,
                    activation_reasons=self._specialist_activation_reasons(
                        AgentSessionRole.MONITOR_AGENT,
                        planned_tool_names,
                        specialist_facts,
                    ),
                    governed_domains=self._specialist_governed_domains(
                        AgentSessionRole.MONITOR_AGENT,
                        domain_values,
                        specialist_facts,
                    ),
                    visible_skill_codes=self._skills_for_specialist_role(
                        AgentSessionRole.MONITOR_AGENT,
                        selected_skill_codes,
                    ),
                    planned_tool_names=specialist_facts.monitor_tool_names,
                    memory_dependencies=memory_dependencies,
                    status=self._agent_status(global_degradation_reasons, plan),
                    degradation_reasons=global_degradation_reasons,
                    # 监控角色只读观察，不代表它可以创建审批或执行任何写动作。
                    requires_handoff=False,
                )
            )
        agents.extend(self._guardrail_agents(plan, request, memory_dependencies, global_degradation_reasons))
        return tuple(agents)

    @staticmethod
    def _domain_agent_specs() -> tuple[tuple[GovernanceDomain, AgentSessionRole, str], ...]:
        """治理域与专家 Agent 的映射表。"""

        return (
            (GovernanceDomain.DATASOURCE, AgentSessionRole.DATASOURCE_AGENT, "数据源治理 Agent"),
            (GovernanceDomain.DATA_QUALITY, AgentSessionRole.DATA_QUALITY_AGENT, "数据质量 Agent"),
            (GovernanceDomain.DATA_SYNC, AgentSessionRole.DATA_SYNC_AGENT, "数据同步 Agent"),
            (GovernanceDomain.TASK_MANAGEMENT, AgentSessionRole.TASK_AGENT, "任务编排 Agent"),
            (GovernanceDomain.PERMISSION_ADMIN, AgentSessionRole.PERMISSION_AGENT, "权限治理 Agent"),
            (GovernanceDomain.KNOWLEDGE_QA, AgentSessionRole.KNOWLEDGE_AGENT, "治理知识问答 Agent"),
        )

    @staticmethod
    def _domain_agent_should_join(
        domain: GovernanceDomain,
        selected_skill_codes: tuple[str, ...],
        planned_tool_names: tuple[str, ...],
        domain_values: tuple[str, ...],
    ) -> bool:
        """判断某治理域专家 Agent 是否应该参与。"""

        if domain.value in domain_values:
            return True
        domain_prefixes = {
            GovernanceDomain.DATASOURCE: ("datasource.",),
            GovernanceDomain.DATA_QUALITY: ("quality.",),
            GovernanceDomain.TASK_MANAGEMENT: ("task.", "sync.task."),
            GovernanceDomain.PERMISSION_ADMIN: ("permission.",),
            GovernanceDomain.DATA_SYNC: ("sync.", "data_sync."),
            GovernanceDomain.KNOWLEDGE_QA: (
                "knowledge.",
                "rag.",
                "web.search.",
                "sync.execution.rag.",
                "sync.task.import.rag.",
            ),
        }.get(domain, ())
        return any(code.startswith(domain_prefixes) for code in selected_skill_codes) or any(
            tool.startswith(domain_prefixes) for tool in planned_tool_names
        )

    @staticmethod
    def _domain_activation_reasons(
        domain: GovernanceDomain,
        selected_skill_codes: tuple[str, ...],
        planned_tool_names: tuple[str, ...],
    ) -> tuple[str, ...]:
        """生成专家 Agent 激活原因。"""

        reasons = [f"结构化意图或能力目录命中了 {domain.value} 治理域。"]
        matched_tools = AgentSessionScheduler._tools_for_domain(domain, planned_tool_names)
        matched_skills = AgentSessionScheduler._skills_for_domain(domain, selected_skill_codes)
        if matched_skills:
            reasons.append(f"本轮选择了相关 Skill：{', '.join(matched_skills)}。")
        if matched_tools:
            reasons.append(f"本轮计划了相关工具：{', '.join(matched_tools)}。")
        return tuple(reasons)

    @staticmethod
    def _knowledge_recovery_activation_reasons(
        planned_tool_names: tuple[str, ...],
        specialist_facts: _SpecialistSchedulingFacts,
    ) -> tuple[str, ...]:
        """说明恢复场景为何需要知识 Agent，明确 RAG 不是每轮固定首步。"""

        reasons = [
            "恢复上下文明确要求案例或知识证据，由 KNOWLEDGE_AGENT 提供只读证据摘要。",
            "RAG 仅在恢复证据事实成立时按需参与，不作为每轮会话的固定第一步。",
        ]
        evidence_tools = tuple(
            name for name in planned_tool_names if AgentSessionScheduler._is_recovery_evidence_tool(name)
        )
        if evidence_tools:
            reasons.append(f"本轮计划了恢复证据工具：{', '.join(evidence_tools)}。")
        elif specialist_facts.recovery_evidence_required:
            reasons.append("控制面提供了案例证据需求标志；具体检索内容仍由受控知识工具处理。")
        return tuple(reasons)

    @staticmethod
    def _specialist_activation_reasons(
        role: AgentSessionRole,
        planned_tool_names: tuple[str, ...],
        specialist_facts: _SpecialistSchedulingFacts,
    ) -> tuple[str, ...]:
        """为 PRECHECK/RECOVERY/MONITOR 生成低敏、可审计的激活理由。"""

        if role == AgentSessionRole.PRECHECK_AGENT:
            reasons = [
                "同步结构化计划要求确定性执行前预检查；预检查由平台事实源完成，不由模型自行检查。",
            ]
            matched = tuple(name for name in planned_tool_names if AgentSessionScheduler._is_precheck_tool(name))
            if matched:
                reasons.append(f"本轮计划了确定性预检查工具：{', '.join(matched)}。")
            return tuple(reasons)
        if role == AgentSessionRole.RECOVERY_AGENT:
            reasons = [
                "结构化计划或控制面上下文明确存在失败、重试或恢复事实，才启用失败恢复 Agent。",
                "恢复 Agent 只负责诊断、证据编排和受控交接，不直接绕过控制面执行写操作。",
            ]
            matched = tuple(name for name in planned_tool_names if AgentSessionScheduler._is_recovery_tool(name))
            if matched:
                reasons.append(f"本轮计划了恢复相关工具：{', '.join(matched)}。")
            if specialist_facts.recovery_evidence_required:
                reasons.append("恢复证据需求已由结构化事实确认，案例检索按需交给 KNOWLEDGE_AGENT。")
            return tuple(reasons)
        reasons = [
            "结构化计划或控制面事实要求观察运行状态、历史、日志或持续任务；MONITOR_AGENT 仅只读观察。",
        ]
        matched = tuple(name for name in planned_tool_names if AgentSessionScheduler._is_monitor_tool(name))
        if matched:
            reasons.append(f"本轮计划了运行观察工具：{', '.join(matched)}。")
        if specialist_facts.sync_mode in _MONITORED_SYNC_MODES:
            reasons.append(f"同步模式 {specialist_facts.sync_mode} 具有持续运行或定期调度语义，需要后续观察。")
        return tuple(reasons)

    @staticmethod
    def _skills_for_specialist_role(
        role: AgentSessionRole,
        selected_skill_codes: tuple[str, ...],
    ) -> tuple[str, ...]:
        """按新专业角色过滤 Skill 编码，只返回编码而不返回 Skill 正文。"""

        prefixes = {
            AgentSessionRole.PRECHECK_AGENT: ("sync.", "data_sync."),
            AgentSessionRole.RECOVERY_AGENT: ("sync.", "recovery.", "datasource.schema."),
            AgentSessionRole.MONITOR_AGENT: ("monitor.", "task.", "sync.", "observability."),
        }.get(role, ())
        return tuple(code for code in selected_skill_codes if code.startswith(prefixes))

    @staticmethod
    def _specialist_governed_domains(
        role: AgentSessionRole,
        domain_values: tuple[str, ...],
        specialist_facts: _SpecialistSchedulingFacts,
    ) -> tuple[str, ...]:
        """给新专业角色绑定治理域，保持 tenant/application/project 之外不引入 workspace 层级。"""

        if role in {AgentSessionRole.PRECHECK_AGENT, AgentSessionRole.RECOVERY_AGENT} and specialist_facts.sync_context:
            return (GovernanceDomain.DATA_SYNC.value,)
        if role == AgentSessionRole.MONITOR_AGENT:
            monitor_domains = tuple(
                value
                for value in domain_values
                if value in {
                    GovernanceDomain.DATA_SYNC.value,
                    GovernanceDomain.TASK_MANAGEMENT.value,
                    GovernanceDomain.DATASOURCE.value,
                }
            )
            return monitor_domains or (GovernanceDomain.TASK_MANAGEMENT.value,)
        return domain_values or (GovernanceDomain.GENERAL_GOVERNANCE.value,)

    @staticmethod
    def _specialist_requires_handoff(
        role: AgentSessionRole,
        plan: AgentPlan,
        specialist_facts: _SpecialistSchedulingFacts,
    ) -> bool:
        """只根据该角色的工具风险判断 handoff，不把恢复正文或参数写入理由。"""

        if role == AgentSessionRole.PRECHECK_AGENT:
            names = set(specialist_facts.precheck_tool_names)
        elif role == AgentSessionRole.RECOVERY_AGENT:
            names = set(specialist_facts.recovery_tool_names)
        else:
            names = set(specialist_facts.monitor_tool_names)
        return any(
            tool.tool_name in names
            and (
                tool.execution_mode == ToolExecutionMode.APPROVAL_REQUIRED
                or tool.risk_level in {ToolRiskLevel.HIGH, ToolRiskLevel.CRITICAL}
            )
            for tool in plan.tool_plans
        )

    @staticmethod
    def _skills_for_domain(domain: GovernanceDomain, skill_codes: tuple[str, ...]) -> tuple[str, ...]:
        """按治理域过滤 Skill 编码。"""

        prefixes = {
            GovernanceDomain.DATASOURCE: ("datasource.",),
            GovernanceDomain.DATA_QUALITY: ("quality.",),
            GovernanceDomain.TASK_MANAGEMENT: ("governed.task.", "task.", "sync.task."),
            GovernanceDomain.PERMISSION_ADMIN: ("permission.",),
            GovernanceDomain.DATA_SYNC: ("sync.", "data_sync."),
            GovernanceDomain.KNOWLEDGE_QA: (
                "knowledge.",
                "rag.",
                "sync.execution.rag.",
                "sync.task.import.rag.",
            ),
        }.get(domain, ())
        return tuple(code for code in skill_codes if code.startswith(prefixes))

    @staticmethod
    def _tools_for_domain(domain: GovernanceDomain, tool_names: tuple[str, ...]) -> tuple[str, ...]:
        """按治理域过滤工具名称。"""

        prefixes = {
            GovernanceDomain.DATASOURCE: ("datasource.",),
            GovernanceDomain.DATA_QUALITY: ("quality.",),
            GovernanceDomain.TASK_MANAGEMENT: ("task.", "sync.task."),
            GovernanceDomain.PERMISSION_ADMIN: ("permission.",),
            GovernanceDomain.DATA_SYNC: ("sync.", "data_sync."),
            GovernanceDomain.KNOWLEDGE_QA: (
                "knowledge.",
                "rag.",
                "web.search.",
                "sync.execution.rag.",
                "sync.task.import.rag.",
            ),
        }.get(domain, ())
        return tuple(name for name in tool_names if name.startswith(prefixes))

    @staticmethod
    def _domain_requires_handoff(domain: GovernanceDomain, plan: AgentPlan) -> bool:
        """判断某治理域是否因为工具风险需要 handoff。"""

        if domain == GovernanceDomain.TASK_MANAGEMENT and plan.requires_human_approval:
            return True
        return any(
            tool.execution_mode == ToolExecutionMode.APPROVAL_REQUIRED
            or tool.risk_level in {ToolRiskLevel.HIGH, ToolRiskLevel.CRITICAL}
            for tool in plan.tool_plans
            if tool.tool_name in AgentSessionScheduler._tools_for_domain(domain, (tool.tool_name,))
        )

    def _guardrail_agents(
        self,
        plan: AgentPlan,
        request: AgentRequest,
        memory_dependencies: tuple[str, ...],
        global_degradation_reasons: tuple[str, ...],
    ) -> tuple[ScheduledAgentView, ...]:
        """构建防护型 Agent。

        防护型 Agent 不一定执行工具，但它们在商业化 Agent host 中非常关键：权限 Agent 负责解释准入和
        审批边界，记忆 Agent 负责避免跨租户/跨项目召回，运维 Agent 负责模型、预算和执行降级。
        """

        agents: list[ScheduledAgentView] = []
        if plan.requires_human_approval or "SKILL_ADMISSION_REJECTED" in global_degradation_reasons:
            agents.append(
                ScheduledAgentView(
                    role=AgentSessionRole.PERMISSION_AGENT,
                    display_name="权限与审批 Agent",
                    participation_mode=AgentParticipationMode.GUARDRAIL,
                    activation_reasons=("本轮存在审批、权限准入或高风险工具边界，需要权限治理 Agent 解释和守护。",),
                    status=self._agent_status(global_degradation_reasons, plan),
                    degradation_reasons=tuple(
                        reason for reason in global_degradation_reasons if "SKILL" in reason
                    ),
                    requires_handoff=plan.requires_human_approval,
                )
            )
        if memory_dependencies:
            agents.append(
                ScheduledAgentView(
                    role=AgentSessionRole.MEMORY_AGENT,
                    display_name="长期记忆 Agent",
                    participation_mode=AgentParticipationMode.GUARDRAIL,
                    activation_reasons=(
                        "本轮计划依赖长期记忆或 Skill 声明了记忆依赖，需要记忆 Agent 维护租户、项目和会话边界。",
                    ),
                    memory_dependencies=memory_dependencies,
                    status=(
                        AgentSchedulingStatus.DEGRADED
                        if "MEMORY_TARGETS_WITHOUT_RETRIEVAL_RESULT" in global_degradation_reasons
                        else AgentSchedulingStatus.READY
                    ),
                    degradation_reasons=tuple(
                        reason for reason in global_degradation_reasons if reason.startswith("MEMORY_")
                    ),
                )
            )
        if self._ops_agent_should_join(global_degradation_reasons, request):
            agents.append(
                ScheduledAgentView(
                    role=AgentSessionRole.OPS_AGENT,
                    display_name="运行治理 Agent",
                    participation_mode=AgentParticipationMode.OBSERVER,
                    activation_reasons=("本轮存在模型、预算、工具批次或生产运维相关降级，需要运行治理 Agent 观察。",),
                    status=self._agent_status(global_degradation_reasons, plan),
                    degradation_reasons=global_degradation_reasons,
                    requires_handoff=AgentSchedulingStatus.BLOCKED == self._agent_status(global_degradation_reasons, plan),
                )
            )
        return tuple(agents)

    @staticmethod
    def _ops_agent_should_join(global_degradation_reasons: tuple[str, ...], request: AgentRequest) -> bool:
        """判断是否需要运行治理 Agent 参与。"""

        if global_degradation_reasons:
            return True
        return str(request.variables.get("runtimeProfile") or "").lower() in {"prod", "production", "high_concurrency"}

    @staticmethod
    def _agent_status(
        global_degradation_reasons: tuple[str, ...],
        plan: AgentPlan,
    ) -> AgentSchedulingStatus:
        """把全局降级事实转换成单 Agent 状态。"""

        if "MODEL_GATEWAY_UNAVAILABLE_OR_BUDGET_BLOCKED" in global_degradation_reasons:
            return AgentSchedulingStatus.BLOCKED
        if global_degradation_reasons:
            return AgentSchedulingStatus.DEGRADED
        if plan.requires_human_approval:
            return AgentSchedulingStatus.APPROVAL_REQUIRED
        return AgentSchedulingStatus.READY


def build_agent_session_scheduling_policy_view(
    plan: AgentPlan,
    request: AgentRequest,
    *,
    model_gateway: Mapping[str, Any],
    skill_admission: Mapping[str, Any],
    tool_budget: Mapping[str, Any],
    memory: Mapping[str, Any],
    skill_visibility: Mapping[str, Any],
) -> dict[str, Any]:
    """便捷函数：构建并返回智能网关会话调度摘要。"""

    return AgentSessionScheduler().schedule(
        plan,
        request,
        model_gateway=model_gateway,
        skill_admission=skill_admission,
        tool_budget=tool_budget,
        memory=memory,
        skill_visibility=skill_visibility,
    ).to_summary()

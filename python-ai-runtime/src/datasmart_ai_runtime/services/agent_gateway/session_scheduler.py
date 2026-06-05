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

from typing import Any, Mapping

from datasmart_ai_runtime.domain.contracts import AgentPlan, AgentRequest, ToolExecutionMode, ToolRiskLevel
from datasmart_ai_runtime.domain.intent import GovernanceDomain
from datasmart_ai_runtime.domain.memory import AgentMemoryType
from datasmart_ai_runtime.services.agent_gateway.session_models import (
    AgentParticipationMode,
    AgentSchedulingStatus,
    AgentSessionRole,
    AgentSessionSchedulingPolicyView,
    ScheduledAgentView,
)


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
        memory_dependencies = self._memory_dependencies(plan)
        degraded_reasons = self._global_degradation_reasons(
            model_gateway=model_gateway,
            skill_admission=skill_admission,
            tool_budget=tool_budget,
            memory=memory,
        )
        status = self._overall_status(plan, model_gateway, skill_admission, tool_budget)
        agents = self._build_agents(
            domain_values=domain_values,
            selected_skill_codes=selected_skill_codes,
            visible_skill_codes=visible_skill_codes,
            planned_tool_names=planned_tool_names,
            memory_dependencies=memory_dependencies,
            global_degradation_reasons=degraded_reasons,
            plan=plan,
            request=request,
        )
        handoff_required = plan.requires_human_approval or status in {
            AgentSchedulingStatus.APPROVAL_REQUIRED,
            AgentSchedulingStatus.BLOCKED,
        }
        return AgentSessionSchedulingPolicyView(
            available=status != AgentSchedulingStatus.BLOCKED,
            status=status,
            primary_agent_role=AgentSessionRole.MASTER_ORCHESTRATOR.value,
            participating_agents=agents,
            policy_axes={
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
            },
            handoff_required=handoff_required,
            display_summary=self._display_summary(status, agents),
            recommended_actions=self._recommended_actions(status, degraded_reasons, agents),
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
    ) -> tuple[ScheduledAgentView, ...]:
        """构建参与 Agent 列表。

        主控 Agent 永远参与，因为它承担会话编排和最终摘要职责；专家 Agent 根据治理域、Skill 和工具
        共同激活。这样比只看关键词更稳定：例如质量规则请求通常同时需要数据源元数据与质量规则生成，
        因此会同时激活 DataSourceAgent 和 DataQualityAgent。
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
            if self._domain_agent_should_join(domain, selected_skill_codes, planned_tool_names, domain_values):
                agents.append(
                    ScheduledAgentView(
                        role=role,
                        display_name=name,
                        participation_mode=AgentParticipationMode.SPECIALIST,
                        activation_reasons=self._domain_activation_reasons(domain, selected_skill_codes, planned_tool_names),
                        governed_domains=(domain.value,),
                        visible_skill_codes=self._skills_for_domain(domain, selected_skill_codes),
                        planned_tool_names=self._tools_for_domain(domain, planned_tool_names),
                        memory_dependencies=memory_dependencies,
                        status=self._agent_status(global_degradation_reasons, plan),
                        degradation_reasons=global_degradation_reasons,
                        requires_handoff=self._domain_requires_handoff(domain, plan),
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
            GovernanceDomain.TASK_MANAGEMENT: ("task.",),
            GovernanceDomain.PERMISSION_ADMIN: ("permission.",),
            GovernanceDomain.DATA_SYNC: ("sync.", "data_sync."),
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
    def _skills_for_domain(domain: GovernanceDomain, skill_codes: tuple[str, ...]) -> tuple[str, ...]:
        """按治理域过滤 Skill 编码。"""

        prefixes = {
            GovernanceDomain.DATASOURCE: ("datasource.",),
            GovernanceDomain.DATA_QUALITY: ("quality.",),
            GovernanceDomain.TASK_MANAGEMENT: ("governed.task.", "task."),
            GovernanceDomain.PERMISSION_ADMIN: ("permission.",),
            GovernanceDomain.DATA_SYNC: ("sync.", "data_sync."),
        }.get(domain, ())
        return tuple(code for code in skill_codes if code.startswith(prefixes))

    @staticmethod
    def _tools_for_domain(domain: GovernanceDomain, tool_names: tuple[str, ...]) -> tuple[str, ...]:
        """按治理域过滤工具名称。"""

        prefixes = {
            GovernanceDomain.DATASOURCE: ("datasource.",),
            GovernanceDomain.DATA_QUALITY: ("quality.",),
            GovernanceDomain.TASK_MANAGEMENT: ("task.",),
            GovernanceDomain.PERMISSION_ADMIN: ("permission.",),
            GovernanceDomain.DATA_SYNC: ("sync.", "data_sync."),
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

    @staticmethod
    def _display_summary(status: AgentSchedulingStatus, agents: tuple[ScheduledAgentView, ...]) -> str:
        """生成面向前端治理卡片的一句话摘要。"""

        if status == AgentSchedulingStatus.BLOCKED:
            return "智能网关已生成多 Agent 调度视图，但关键模型网关或预算能力不可用，当前不应自动推进。"
        if status == AgentSchedulingStatus.DEGRADED:
            return f"智能网关已调度 {len(agents)} 个 Agent，但存在权限、预算、记忆或工具治理降级。"
        if status == AgentSchedulingStatus.APPROVAL_REQUIRED:
            return f"智能网关已调度 {len(agents)} 个 Agent，其中部分动作需要人工审批后才能执行。"
        return f"智能网关已调度 {len(agents)} 个 Agent，当前可进入控制面执行或继续会话。"

    @staticmethod
    def _recommended_actions(
        status: AgentSchedulingStatus,
        degraded_reasons: tuple[str, ...],
        agents: tuple[ScheduledAgentView, ...],
    ) -> tuple[str, ...]:
        """根据调度状态生成下一步建议。"""

        actions: list[str] = []
        if status == AgentSchedulingStatus.BLOCKED:
            actions.append("优先恢复模型网关路由、Provider 健康或租户预算，再允许主控 Agent 推进下一步。")
        if "SKILL_ADMISSION_REJECTED" in degraded_reasons:
            actions.append("将被拒绝 Skill 的权限包、租户开关和审批模板同步到 permission-admin 控制面。")
        if "MODEL_TOOL_CALL_BUDGET_BLOCKED" in degraded_reasons:
            actions.append("把本轮工具调用拆成多轮计划，或提高高并发/批处理场景下的工具预算策略。")
        if "MEMORY_TARGETS_WITHOUT_RETRIEVAL_RESULT" in degraded_reasons:
            actions.append("检查长期记忆写入、二级索引同步和 workspace 过滤条件，避免专家 Agent 缺少历史经验。")
        if any(agent.requires_handoff for agent in agents):
            actions.append("把需要 handoff 的 Agent 决策写入 Java 控制面审批单，等待项目负责人或管理员确认。")
        if not actions:
            actions.append("下一阶段可接入真实多 Agent runtime，把当前策略视图升级为可执行 handoff 图。")
        return tuple(actions)


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

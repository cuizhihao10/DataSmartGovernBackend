"""工具规划服务。

当前版本先采用规则式规划，而不是直接接入大模型。这个选择有两个原因：
1. 规则式规划可解释、可测试，适合作为商业系统的安全基线。
2. 后续接入 LLM 规划器时，可以把 LLM 输出约束成同样的 `ToolPlan` 契约，避免接口重写。
"""

from __future__ import annotations

from datasmart_ai_runtime.domain.context import ContextBlock, ContextSourceType
from datasmart_ai_runtime.domain.contracts import (
    AgentRequest,
    ToolDefinition,
    ToolExecutionMode,
    ToolPlan,
    ToolRiskLevel,
)
from datasmart_ai_runtime.domain.intent import IntentAnalysis, IntentRiskTag
from datasmart_ai_runtime.domain.resource_reference import AgentResourceReference
from datasmart_ai_runtime.domain.skills import AgentSkillPlan
from datasmart_ai_runtime.services.quality_remediation_tool_plan_builder import (
    QualityRemediationToolPlanArgumentBuilder,
)
from datasmart_ai_runtime.services.tools.workspace_file_plan_builder import WorkspaceFileToolPlanBuilder
from datasmart_ai_runtime.services.tool_plan_dag import ToolPlanDagAnnotator
from datasmart_ai_runtime.services.tool_parameter_validator import ToolParameterValidator


class ToolPlanner:
    """根据用户目标和工具注册表生成工具计划。

    这个类只负责“计划”，不负责“执行”。执行仍应交给 Java `agent-runtime` 或对应业务微服务，
    因为执行会涉及权限、审计、幂等、审批、事务和状态机，这些属于控制面职责。
    """

    def __init__(
        self,
        tools: tuple[ToolDefinition, ...],
        parameter_validator: ToolParameterValidator | None = None,
    ) -> None:
        """初始化工具规划器。

        `parameter_validator` 作为可注入依赖，而不是在每次规划时临时创建，是为了给后续商业化能力
        预留扩展空间：不同租户可以使用不同 schema 策略，生产环境也可以替换成读取 Java 工具注册表
        JSON Schema 的校验器，而不需要改动规划规则本身。
        """

        self._tools = {tool.name: tool for tool in tools}
        self._parameter_validator = parameter_validator or ToolParameterValidator()
        self._dag_annotator = ToolPlanDagAnnotator()
        self._quality_remediation_arguments = QualityRemediationToolPlanArgumentBuilder()
        self._workspace_file_plans = WorkspaceFileToolPlanBuilder()

    def plan(
        self,
        request: AgentRequest,
        intent_analysis: IntentAnalysis | None = None,
        context_blocks: tuple[ContextBlock, ...] = (),
    ) -> tuple[ToolPlan, ...]:
        """为一次 Agent 请求生成工具调用计划。

        规划逻辑现在分三层：
        1. `IntentAnalysis`：优先使用结构化意图里的候选工具和风险标签；
        2. `ContextBlock`：从上下文 metadata 中补齐 datasourceId、businessGoal 等参数；
        3. 关键词规则：作为兼容旧调用方和模型失败降级的安全兜底。

        这样做能避免工具规划只靠自然语言关键词。例如真实 LLM 或规则意图分析器已经判断需要
        `quality.rule.suggest`，即使用户没有明确说“质量/规则”，规划器也可以生成对应草案工具。
        """

        objective = request.objective.lower()
        plans: list[ToolPlan] = []

        candidate_tools = set(intent_analysis.candidate_tools if intent_analysis else ())
        datasource_id = self._resolve_datasource_id(request, context_blocks)
        business_goal = self._resolve_business_goal(request, context_blocks)
        planned_tool_names: set[str] = set()
        wants_quality_remediation = self._wants_quality_remediation(request, objective, candidate_tools)

        wants_datasource_metadata = (
            "datasource.metadata.read" in candidate_tools
            or datasource_id is not None
        )
        if wants_datasource_metadata and datasource_id and "datasource.metadata.read" in self._tools:
            tool = self._tools["datasource.metadata.read"]
            plans.append(
                self._build_plan(
                    tool=tool,
                    reason="意图或上下文显示请求涉及具体数据源，先读取元数据以避免在缺少表结构上下文时生成错误规则。",
                    arguments={"datasourceId": datasource_id},
                )
            )
            planned_tool_names.add("datasource.metadata.read")

        workspace_file_plans = self._workspace_file_plans.build(
            request=request,
            objective=objective,
            candidate_tools=candidate_tools,
            tools=self._tools,
            plan_factory=lambda tool, reason, arguments: self._build_plan(
                tool=tool,
                reason=reason,
                arguments=arguments,
            ),
        )
        plans.extend(workspace_file_plans)
        planned_tool_names.update(plan.tool_name for plan in workspace_file_plans)

        quality_keywords = ("quality", "rule", "校验", "质量", "规则", "异常", "清洗")
        wants_quality_rule = (
            "quality.rule.suggest" in candidate_tools
            or (self._contains_any(objective, quality_keywords) and not wants_quality_remediation)
        )
        if wants_quality_rule and "quality.rule.suggest" in self._tools:
            tool = self._tools["quality.rule.suggest"]
            plans.append(
                self._build_plan(
                    tool=tool,
                    reason="结构化意图或用户目标包含质量治理需求，生成规则草案比直接执行更安全，便于业务人员复核。",
                    arguments={
                        "datasourceId": datasource_id,
                        "businessGoal": business_goal,
                        **self._reference_argument(
                            argument_name="metadataRef",
                            from_tool="datasource.metadata.read",
                            path="metadata",
                            enabled="datasource.metadata.read" in planned_tool_names,
                        ),
                    },
                )
            )
            planned_tool_names.add("quality.rule.suggest")

        if wants_quality_remediation and "quality.remediation.task.draft" in self._tools:
            tool = self._tools["quality.remediation.task.draft"]
            plans.append(
                self._build_plan(
                    tool=tool,
                    reason=(
                        "用户目标指向质量异常复核、整改或派单。该工具只生成低敏治理任务草案和 dry-run 预览，"
                        "不直接提交 task-management，也不执行清洗脚本，适合作为人工确认前的 Agent 建议。"
                    ),
                    arguments={
                        **self._quality_remediation_arguments.build(request),
                        **self._reference_argument(
                            argument_name="suggestionRef",
                            from_tool="quality.rule.suggest",
                            path="suggestion",
                            enabled="quality.rule.suggest" in planned_tool_names,
                        ),
                    },
                )
            )
            planned_tool_names.add("quality.remediation.task.draft")

        task_keywords = ("create task", "schedule", "run", "创建任务", "调度", "执行", "同步任务")
        create_task_requested = bool(request.variables.get("createTask") or request.variables.get("create_task"))
        wants_task_draft = (
            not wants_quality_remediation
            and (
                "task.create.draft" in candidate_tools
                or create_task_requested
                or self._contains_any(objective, task_keywords)
            )
        )
        if wants_task_draft and "task.create.draft" in self._tools:
            tool = self._tools["task.create.draft"]
            risk_tags = tuple(tag.value for tag in intent_analysis.risk_tags) if intent_analysis else ()
            plans.append(
                self._build_plan(
                    tool=tool,
                    reason="意图分析显示可能创建或调度任务，该动作会改变平台业务状态，必须先生成草案并进入审批/确认链路。",
                    arguments={
                        "taskType": self._resolve_task_type(request, "quality.rule.suggest" in planned_tool_names),
                        "objective": request.objective,
                        "priority": request.variables.get("priority", "MEDIUM"),
                        "payload": {
                            "objective": request.objective,
                            "variables": request.variables,
                            "intentRiskTags": risk_tags,
                            "missingParameters": intent_analysis.missing_parameters if intent_analysis else (),
                        },
                        **self._reference_argument(
                            argument_name="suggestionRef",
                            from_tool="quality.rule.suggest",
                            path="suggestion",
                            enabled="quality.rule.suggest" in planned_tool_names,
                        ),
                    },
                )
            )
            planned_tool_names.add("task.create.draft")

        wants_task_draft_persist = (
            not wants_quality_remediation
            and (
                "task.draft.persist" in candidate_tools
                or create_task_requested
                or bool(request.variables.get("persistTaskDraft") or request.variables.get("persist_task_draft"))
            )
        )
        if (
            wants_task_draft_persist
            and "task.create.draft" in planned_tool_names
            and "task.draft.persist" in self._tools
        ):
            tool = self._tools["task.draft.persist"]
            plans.append(
                self._build_plan(
                    tool=tool,
                    reason=(
                        "用户目标要求进入任务创建链路。保存草稿是受控写操作，只写入 task_draft，"
                        "不会提交审批或转换真实任务，因此仍需人工确认。"
                    ),
                    arguments={
                        "taskDraftRef": self._tool_output_reference("task.create.draft", "taskDraft"),
                    },
                )
            )
            planned_tool_names.add("task.draft.persist")

        return self._dag_annotator.annotate(tuple(plans))

    def model_visible_tools(
        self,
        request: AgentRequest,
        intent_analysis: IntentAnalysis | None = None,
        context_blocks: tuple[ContextBlock, ...] = (),
        skill_plan: AgentSkillPlan | None = None,
    ) -> tuple[ToolDefinition, ...]:
        """选择本轮允许暴露给模型的候选工具定义。

        这个方法服务于 OpenAI-compatible `tools` 请求体，不等同于最终工具执行计划。它的设计原则是：
        - 先用结构化意图中的 `candidate_tools`，避免只靠关键词猜测；
        - 再合并已选 Skill 的 `required_tools`，让能力包能显式声明模型需要知道哪些工具；
        - 最后用当前规则式规划结果兜底，确保本地 dry-run 和 LLM 调用看到的工具集合尽量一致。

        这里返回的仍是“候选工具”，真正暴露给模型前还会经过 `OpenAICompatibleToolSchemaBuilder` 的
        风险过滤，例如默认隐藏 CRITICAL 工具、限制单次工具数量。执行前还要再经过 Java 控制面的权限、
        审批、参数校验和审计，因此这里不会直接产生任何副作用。
        """

        candidate_names: list[str] = []
        if intent_analysis is not None:
            candidate_names.extend(intent_analysis.candidate_tools)
        if skill_plan is not None:
            for selection in skill_plan.selected_skills:
                candidate_names.extend(selection.required_tools)
        # 规则式计划作为兜底来源：如果 Skill 注册表缺失，或 intent 只识别到领域但没有工具，
        # 仍可以让模型看到与后续 plan_tools 大致一致的工具集合。
        candidate_names.extend(
            plan.tool_name
            for plan in self.plan(
                request=request,
                intent_analysis=intent_analysis,
                context_blocks=context_blocks,
            )
        )

        visible_tools: list[ToolDefinition] = []
        seen: set[str] = set()
        for name in candidate_names:
            if name in seen:
                continue
            tool = self._tools.get(name)
            if tool is None:
                continue
            visible_tools.append(tool)
            seen.add(name)
        return tuple(visible_tools)

    def registered_tools(self) -> tuple[ToolDefinition, ...]:
        """返回当前规划器持有的完整工具注册表快照。

        模型工具调用治理需要同时知道两类工具集合：
        - `registered_tools`：平台真正认识的全部工具，用来判断模型是否幻觉了不存在的工具；
        - `visible_tools`：本轮暴露给模型的工具，用来判断模型是否越过了最小权限候选集。

        这里返回 tuple 快照，而不是暴露内部 dict，是为了避免调用方意外修改规划器状态。后续当工具
        注册表迁移到 Java agent-runtime 动态同步时，这个方法仍可以保持同样契约。
        """

        return tuple(self._tools.values())

    def _build_plan(self, tool: ToolDefinition, reason: str, arguments: dict[str, object]) -> ToolPlan:
        """把工具定义转换为工具计划。

        审批判断集中在这里，是为了避免每个规则分支都重复实现风险逻辑。后续如果增加租户级策略，
        例如“某些租户禁止 Agent 自动执行任何写操作”，也可以在这里统一扩展。

        参数校验也在这里统一执行：规划分支只负责填入它能确定的参数，至于哪些字段缺失、缺失后
        应该追问用户还是允许先生成草案，交给 `ToolParameterValidator` 处理。这样可以避免未来每个
        工具分支都重复写 `datasourceId is None`、`businessGoal is None` 之类的局部判断。
        """

        requires_approval = tool.requires_approval or tool.execution_mode == ToolExecutionMode.APPROVAL_REQUIRED or tool.risk_level in {
            ToolRiskLevel.HIGH,
            ToolRiskLevel.CRITICAL,
        }
        normalized_arguments = dict(arguments)
        parameter_validation = self._parameter_validator.validate(tool, normalized_arguments)
        return ToolPlan(
            tool_name=tool.name,
            reason=reason,
            arguments=normalized_arguments,
            risk_level=tool.risk_level,
            execution_mode=tool.execution_mode,
            requires_human_approval=requires_approval,
            parameter_validation=parameter_validation,
            governance_hints={
                "protocolHint": tool.protocol_hint,
                "targetService": tool.target_service,
                "targetEndpoint": tool.target_endpoint,
                "tenantScoped": tool.tenant_scoped,
                "projectScoped": tool.project_scoped,
                "sensitiveFields": tool.sensitive_fields,
                "memoryWritePolicy": tool.memory_write_policy,
                "cachePolicy": tool.cache_policy,
            },
        )

    @staticmethod
    def _contains_any(text: str, keywords: tuple[str, ...]) -> bool:
        """判断文本是否命中任一关键词。

        这是临时的轻量语义识别实现。后续可以替换为意图分类模型、Embedding 相似度检索，
        或由 LLM 输出结构化意图，但对外仍保持 `plan()` 方法契约不变。
        """

        return any(keyword in text for keyword in keywords)

    def _wants_quality_remediation(
        self,
        request: AgentRequest,
        objective: str,
        candidate_tools: set[str],
    ) -> bool:
        """判断是否需要规划质量异常治理任务草案工具。

        这里和 `RuleBasedIntentAnalyzer` 保持同一套触发边界：普通“质量异常识别/清洗规则”仍属于规则设计，
        只有“治理任务、复核、派单、整改、修复”等动作语义才进入治理任务草案。
        这样可以把产品能力收敛成两条清晰链路：
        - 质量规则链路：metadata -> rule suggestion -> generic task draft；
        - 异常治理链路：quality report/anomaly scope -> remediation task draft -> Java 控制面确认。
        """

        if "quality.remediation.task.draft" in candidate_tools:
            return True
        if request.variables.get("createRemediationTask") or request.variables.get("create_remediation_task"):
            return True
        if request.variables.get("remediationTask") or request.variables.get("remediation_task"):
            return True
        return self._contains_any(
            objective,
            (
                "remediation",
                "remediate",
                "治理任务",
                "异常复核",
                "质量复核",
                "派单",
                "整改",
                "修复任务",
                "处理任务",
                "创建治理",
            ),
        )

    @staticmethod
    def _reference_argument(argument_name: str, from_tool: str, path: str, enabled: bool) -> dict[str, object]:
        """按需生成工具输出引用参数。

        Python 规划阶段通常还不知道 Java 控制面创建的工具审计 ID，因此这里不伪造 `fromAuditId`。
        但仍显式写出 `fromTool` 和 `path`，让 Java `AgentToolOutputReferenceResolver` 可以在同一
        session/run 内读取前序工具最新成功输出。后续如果 Java 计划落库后把 auditId 回传给 Python，
        可以在不改变字段名的情况下补上 `fromAuditId`，升级为精确引用。
        """

        if not enabled:
            return {}
        return {argument_name: ToolPlanner._tool_output_reference(from_tool, path)}

    @staticmethod
    def _tool_output_reference(from_tool: str, path: str) -> dict[str, object]:
        """构造 Java Agent Runtime 可识别的轻量输出引用对象。

        `referenceMode` 不是 Java 当前必需字段，但它能帮助前端、日志和后续 LLM 规划器理解：
        这个引用不是复制大 JSON，而是指向同一 Run 内前序工具的结构化输出。

        同时这里追加统一 `resourceReference` 结构。这样做是兼容式演进：
        - Java 当前解析器继续读取 `fromTool/path/referenceMode`；
        - 新的 Python/前端/未来 Skill Runtime 可以读取 `resourceReference.kind/uri/contextPolicy`；
        - 后续一旦 Java 解析器升级，也可以直接消费统一资源引用，而不需要再猜测字段含义。
        """

        return {
            "fromTool": from_tool,
            "path": path,
            "referenceMode": "LATEST_SUCCESS_IN_RUN",
            "resourceReference": AgentResourceReference.tool_output(
                tool_code=from_tool,
                json_path=path,
            ).to_payload(),
        }

    @staticmethod
    def _resolve_task_type(request: AgentRequest, has_quality_suggestion: bool) -> str:
        """解析任务草稿类型。

        用户显式传入 `taskType/type` 时优先尊重；如果当前链路已经规划质量规则建议，
        默认生成 `DATA_QUALITY_SCAN` 草稿；否则退回 `MANUAL_REVIEW`，避免把不明确目标误判成可执行任务。
        """

        explicit = request.variables.get("taskType") or request.variables.get("type")
        if explicit:
            return str(explicit).upper()
        if has_quality_suggestion:
            return "DATA_QUALITY_SCAN"
        return "MANUAL_REVIEW"

    @staticmethod
    def _resolve_datasource_id(request: AgentRequest, context_blocks: tuple[ContextBlock, ...]) -> str | None:
        """从请求变量或上下文块中解析 datasourceId。

        后续真实 GraphRAG/Java 控制面上下文接入后，datasourceId 可能来自元数据检索结果，而不是
        用户表单。集中解析可以避免每个工具分支都重复写参数补齐逻辑。
        """

        value = request.variables.get("datasourceId") or request.variables.get("datasource_id")
        if value:
            return str(value)
        for block in context_blocks:
            if block.source_type == ContextSourceType.DATASOURCE_METADATA:
                metadata_value = block.metadata.get("datasourceId") or block.metadata.get("datasource_id")
                if metadata_value:
                    return str(metadata_value)
        return None

    @staticmethod
    def _resolve_business_goal(request: AgentRequest, context_blocks: tuple[ContextBlock, ...]) -> str:
        """解析质量规则或任务规划的业务目标。

        优先使用结构化变量，其次使用质量规则案例上下文中的业务目标，最后退回用户原始目标。
        """

        value = request.variables.get("businessGoal") or request.variables.get("business_goal")
        if value:
            return str(value)
        for block in context_blocks:
            if block.source_type == ContextSourceType.QUALITY_RULE_CASE:
                metadata_value = block.metadata.get("businessGoal") or block.metadata.get("business_goal")
                if metadata_value:
                    return str(metadata_value)
        return request.objective

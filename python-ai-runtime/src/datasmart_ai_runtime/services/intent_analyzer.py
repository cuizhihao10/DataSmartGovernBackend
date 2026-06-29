"""Agent 意图分析器。

当前实现仍采用规则式分析。它不是最终智能能力，而是生产系统需要的安全基线：
即使真实 LLM 暂时不可用，平台也能识别常见治理域、风险标签和缺失参数。后续可增加
`LlmIntentAnalyzer`，让模型输出同样的 `IntentAnalysis` 结构，再由规则层做校验和兜底。
"""

from __future__ import annotations

from typing import Protocol

from datasmart_ai_runtime.domain.context import ContextBlock, ContextSourceType
from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.intent import GovernanceDomain, IntentAnalysis, IntentRiskTag


class IntentAnalyzer(Protocol):
    """意图分析器协议。

    通过协议隔离实现后，编排器只依赖 `analyze(...)` 契约，不关心底层是关键词规则、
    轻量分类模型、LLM 结构化输出，还是“LLM + 规则校验”的组合。
    """

    def analyze(self, request: AgentRequest, context_blocks: tuple[ContextBlock, ...]) -> IntentAnalysis:
        """根据用户请求和上下文块生成结构化意图分析。"""


class RuleBasedIntentAnalyzer:
    """规则式意图分析器。

    该分析器会同时参考用户目标、结构化变量和上下文块。相比只看自然语言，这种方式更稳：
    例如用户没有说“数据源”，但变量里有 `datasourceId`，仍然应判断为数据源相关场景。
    """

    def analyze(self, request: AgentRequest, context_blocks: tuple[ContextBlock, ...]) -> IntentAnalysis:
        """生成结构化意图分析。

        当前规则覆盖数据源、质量、任务/同步、权限和知识问答几类高频场景。它会输出候选工具和
        缺失参数，但不会直接执行工具；执行边界仍由 Java 控制面和工具审计链路负责。
        """

        objective = request.objective.lower()
        domains: list[GovernanceDomain] = []
        candidate_tools: list[str] = []
        risk_tags: list[IntentRiskTag] = []
        missing_parameters: list[str] = []

        datasource_id = request.variables.get("datasourceId") or request.variables.get("datasource_id")
        if datasource_id or self._has_context(context_blocks, ContextSourceType.DATASOURCE_METADATA):
            self._append_unique(domains, GovernanceDomain.DATASOURCE)
            self._append_unique(candidate_tools, "datasource.metadata.read")
            self._append_unique(risk_tags, IntentRiskTag.READ_ONLY)

        remediation_requested = self._wants_quality_remediation(request, objective)
        quality_rule_requested = self._contains_any(
            objective,
            ("quality", "rule", "校验", "质量", "规则", "异常", "清洗", "完整性"),
        )

        if quality_rule_requested and not remediation_requested:
            self._append_unique(domains, GovernanceDomain.DATA_QUALITY)
            self._append_unique(candidate_tools, "quality.rule.suggest")
            self._append_unique(risk_tags, IntentRiskTag.DRAFT_GENERATION)
            if not datasource_id:
                self._append_unique(missing_parameters, "datasourceId")

        if remediation_requested:
            self._append_unique(domains, GovernanceDomain.DATA_QUALITY)
            self._append_unique(domains, GovernanceDomain.TASK_MANAGEMENT)
            self._append_unique(candidate_tools, "quality.remediation.task.draft")
            self._append_unique(risk_tags, IntentRiskTag.DRAFT_GENERATION)
            self._append_unique(risk_tags, IntentRiskTag.STATE_CHANGE)
            self._append_unique(risk_tags, IntentRiskTag.APPROVAL_REQUIRED)
            if not self._has_remediation_scope(request):
                self._append_unique(missing_parameters, "remediationScope")

        if self._contains_any(objective, ("sync", "同步", "补数", "回放", "增量", "cdc")):
            self._append_unique(domains, GovernanceDomain.DATA_SYNC)
            self._append_unique(candidate_tools, "task.create.draft")
            self._append_unique(risk_tags, IntentRiskTag.STATE_CHANGE)
            self._append_unique(risk_tags, IntentRiskTag.APPROVAL_REQUIRED)

        create_task_requested = bool(request.variables.get("createTask") or request.variables.get("create_task"))
        if not remediation_requested and (
            create_task_requested
            or self._contains_any(objective, ("create task", "schedule", "run", "创建任务", "调度", "执行"))
        ):
            self._append_unique(domains, GovernanceDomain.TASK_MANAGEMENT)
            self._append_unique(candidate_tools, "task.create.draft")
            self._append_unique(risk_tags, IntentRiskTag.STATE_CHANGE)
            self._append_unique(risk_tags, IntentRiskTag.APPROVAL_REQUIRED)

        if self._contains_any(objective, ("permission", "role", "权限", "角色", "授权", "成员", "越权")):
            self._append_unique(domains, GovernanceDomain.PERMISSION_ADMIN)
            self._append_unique(risk_tags, IntentRiskTag.CROSS_SCOPE)

        workspace_file_operation = self._workspace_file_operation(request, objective)
        if workspace_file_operation == "READ":
            self._append_unique(domains, GovernanceDomain.GENERAL_GOVERNANCE)
            self._append_unique(candidate_tools, "workspace.file.read")
            self._append_unique(risk_tags, IntentRiskTag.READ_ONLY)
            if not self._has_workspace_file_path(request):
                self._append_unique(missing_parameters, "workspaceFilePath")
        elif workspace_file_operation == "WRITE":
            self._append_unique(domains, GovernanceDomain.GENERAL_GOVERNANCE)
            self._append_unique(candidate_tools, "workspace.file.write")
            self._append_unique(risk_tags, IntentRiskTag.STATE_CHANGE)
            self._append_unique(risk_tags, IntentRiskTag.APPROVAL_REQUIRED)
            if not self._has_workspace_file_path(request):
                self._append_unique(missing_parameters, "workspaceFilePath")
            if not self._has_workspace_file_content_reference(request):
                self._append_unique(missing_parameters, "workspaceFileContentRef")

        self._append_high_risk_tags(objective, request, risk_tags, missing_parameters)

        if not domains:
            self._append_unique(domains, GovernanceDomain.KNOWLEDGE_QA)
            self._append_unique(risk_tags, IntentRiskTag.READ_ONLY)

        confidence = self._estimate_confidence(domains, candidate_tools, missing_parameters)
        summary = self._build_summary(domains, candidate_tools, risk_tags, missing_parameters)
        return IntentAnalysis(
            summary=summary,
            governance_domains=tuple(domains),
            candidate_tools=tuple(candidate_tools),
            risk_tags=tuple(risk_tags),
            missing_parameters=tuple(missing_parameters),
            confidence=confidence,
            reasoning="基于用户目标关键词、结构化变量和上下文块类型进行规则式意图识别。",
        )

    @staticmethod
    def _contains_any(text: str, keywords: tuple[str, ...]) -> bool:
        """判断文本是否命中任一关键词。"""

        return any(keyword in text for keyword in keywords)

    def _wants_quality_remediation(self, request: AgentRequest, objective: str) -> bool:
        """判断用户是否在请求“质量异常治理任务”而不是普通质量规则草案。

        质量域里“异常”这个词出现频率很高：生成规则时会说异常识别，查看报告时会说异常统计，
        真正需要创建治理任务时才会出现“复核、派单、整改、修复、治理任务”等动作词。
        因此这里刻意不让单独的“异常”触发治理任务草案，避免 Agent 在普通规则设计场景中多规划一个派单工具。
        """

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
    def _has_remediation_scope(request: AgentRequest) -> bool:
        """判断治理任务是否至少具备一个低敏定位条件。

        Java data-quality 的治理任务接口可以从 reportId、ruleId、异常类型、严重级别、字段或目标对象等维度定位异常。
        Python Runtime 不应该要求用户一次性给出全部条件，否则会破坏“先生成草案再人工确认”的体验；
        但如果完全没有定位范围，后续 task-management 也无法解释这张治理任务到底要处理哪一批异常。
        """

        scope_fields = (
            "reportId",
            "report_id",
            "ruleId",
            "rule_id",
            "anomalyType",
            "anomaly_type",
            "severity",
            "fieldName",
            "field_name",
            "targetObject",
            "target_object",
            "projectId",
            "project_id",
        )
        return any(request.variables.get(field) not in (None, "", [], {}) for field in scope_fields)

    def _workspace_file_operation(self, request: AgentRequest, objective: str) -> str | None:
        """识别 workspace 文件读写意图。

        这里把读和写分开，是为了让风险标签和后续审批策略更清楚：
        - 读文件属于只读能力，但仍必须受 workspace 与路径白名单约束；
        - 写文件会改变 workspace 资源，必须进入审批/确认链路。
        """

        operation = str(request.variables.get("fileOperation") or request.variables.get("file_operation") or "").upper()
        if operation in {"READ", "READ_TEXT"}:
            return "READ"
        if operation in {"WRITE", "WRITE_TEXT", "CREATE", "OVERWRITE"}:
            return "WRITE"
        if request.variables.get("workspaceFileContent") is not None or request.variables.get("workspace_file_content") is not None:
            return "WRITE"
        if self._contains_any(
            objective,
            ("write file", "create file", "update file", "写文件", "创建文件", "更新文件", "保存到文件", "写入 workspace"),
        ):
            return "WRITE"
        if self._contains_any(
            objective,
            ("read file", "读取文件", "查看文件", "打开文件", "读取 workspace", "查看 workspace", "readme", "文档文件"),
        ):
            return "READ"
        return None

    @staticmethod
    def _has_workspace_file_path(request: AgentRequest) -> bool:
        """判断请求是否提供了 workspace 相对路径。"""

        fields = ("workspaceFilePath", "workspace_file_path", "relativePath", "relative_path", "filePath", "file_path")
        return any(request.variables.get(field) not in (None, "", [], {}) for field in fields)

    @staticmethod
    def _has_workspace_file_content_reference(request: AgentRequest) -> bool:
        """判断请求是否提供了写入内容或内容引用。

        规划响应不会输出正文；这个判断只用于缺失参数提示。
        """

        fields = (
            "workspaceFileContentRef",
            "workspace_file_content_ref",
            "workspaceFileContent",
            "workspace_file_content",
        )
        return any(request.variables.get(field) not in (None, "", [], {}) for field in fields)

    def _append_high_risk_tags(
        self,
        objective: str,
        request: AgentRequest,
        risk_tags: list[IntentRiskTag],
        missing_parameters: list[str],
    ) -> None:
        """补充跨域、敏感数据、导出和写 SQL 等高风险标签。

        这些规则是商业化 Agent 的安全底线。即使后续 LLM 没有识别出风险，规则层也应该拦住
        明显高风险意图，并把风险标签传递给工具规划、审批和前端确认链路。
        """

        if self._contains_any(objective, ("export", "download", "导出", "下载", "excel", "csv", "pdf")):
            self._append_unique(risk_tags, IntentRiskTag.DATA_EXPORT)
            self._append_unique(risk_tags, IntentRiskTag.APPROVAL_REQUIRED)

        if self._contains_any(objective, ("insert", "update", "delete", "drop", "truncate", "写入", "删除", "更新", "清空", "执行sql", "写sql")):
            self._append_unique(risk_tags, IntentRiskTag.WRITE_SQL)
            self._append_unique(risk_tags, IntentRiskTag.STATE_CHANGE)
            self._append_unique(risk_tags, IntentRiskTag.APPROVAL_REQUIRED)

        if self._contains_any(objective, ("身份证", "手机号", "手机号码", "银行卡", "姓名", "地址", "邮箱", "email", "phone", "id card", "敏感")):
            self._append_unique(risk_tags, IntentRiskTag.SENSITIVE_DATA)
            # 敏感数据并不总是禁止读取，但至少应进入审批或人工确认语义，避免模型自动扩大访问范围。
            self._append_unique(risk_tags, IntentRiskTag.APPROVAL_REQUIRED)

        if self._contains_any(objective, ("cross project", "跨项目", "其他项目", "所有项目", "全项目")):
            self._append_unique(risk_tags, IntentRiskTag.CROSS_SCOPE)
            self._append_unique(risk_tags, IntentRiskTag.APPROVAL_REQUIRED)

        if self._contains_any(objective, ("cross tenant", "跨租户", "其他租户", "所有租户", "全租户")):
            self._append_unique(risk_tags, IntentRiskTag.CROSS_TENANT)
            self._append_unique(risk_tags, IntentRiskTag.CROSS_SCOPE)
            self._append_unique(risk_tags, IntentRiskTag.APPROVAL_REQUIRED)

        if IntentRiskTag.DATA_EXPORT in risk_tags and not (request.variables.get("exportFormat") or request.variables.get("export_format")):
            self._append_unique(missing_parameters, "exportFormat")

    @staticmethod
    def _has_context(context_blocks: tuple[ContextBlock, ...], source_type: ContextSourceType) -> bool:
        """判断上下文块中是否存在指定来源类型。"""

        return any(block.source_type == source_type for block in context_blocks)

    @staticmethod
    def _append_unique(items: list, value) -> None:
        """保持插入顺序的去重追加。

        保留顺序有助于调试和前端展示，例如先识别到数据源，再识别到质量治理。
        """

        if value not in items:
            items.append(value)

    @staticmethod
    def _estimate_confidence(
        domains: list[GovernanceDomain],
        candidate_tools: list[str],
        missing_parameters: list[str],
    ) -> float:
        """估算规则式意图置信度。

        这是启发式分数，不代表模型概率。它的用途是给前端和控制面一个粗略信号：
        识别到多个明确域和工具时置信度更高；缺少关键参数时置信度降低。
        """

        score = 0.55
        if candidate_tools:
            score += 0.25
        if len(domains) > 1:
            score += 0.1
        if missing_parameters:
            score -= 0.15
        return max(0.1, min(0.95, round(score, 2)))

    @staticmethod
    def _build_summary(
        domains: list[GovernanceDomain],
        candidate_tools: list[str],
        risk_tags: list[IntentRiskTag],
        missing_parameters: list[str],
    ) -> str:
        """构建人读摘要。"""

        domain_text = "、".join(domain.value for domain in domains)
        tool_text = "、".join(candidate_tools) if candidate_tools else "暂无明确候选工具"
        risk_text = "、".join(tag.value for tag in risk_tags) if risk_tags else "暂无明显风险标签"
        missing_text = "、".join(missing_parameters) if missing_parameters else "暂无明显缺失参数"
        return f"识别到治理域：{domain_text}；候选工具：{tool_text}；风险标签：{risk_text}；缺失参数：{missing_text}。"

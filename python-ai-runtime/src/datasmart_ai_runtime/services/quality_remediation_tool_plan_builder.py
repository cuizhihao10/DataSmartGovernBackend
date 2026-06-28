"""质量异常治理任务工具参数构造器。

这个模块专门服务 `quality.remediation.task.draft` 工具计划。把它从 `ToolPlanner`
中拆出来有两个目的：
1. 让通用工具规划器只关注“是否需要规划该工具”，避免单个文件继续膨胀；
2. 把质量治理任务的低敏字段边界集中在一个类里，后续接 Java data-quality DTO、
   readiness projection 或审批页时，可以更容易检查是否误带了样本、SQL、prompt 等敏感内容。
"""

from __future__ import annotations

from datasmart_ai_runtime.domain.contracts import AgentRequest


class QualityRemediationToolPlanArgumentBuilder:
    """构造质量异常治理任务草案的低敏工具参数。

    这个 builder 只负责“参数形状”和“低敏范围”，不负责权限、审批或真实提交。真实执行仍应由
    Java `agent-runtime` 与 `data-quality` 控制面完成，因为那里才能统一处理租户隔离、服务账号、
    outbox、审计、失败重试和 task-management 写入。
    """

    def build(self, request: AgentRequest) -> dict[str, object]:
        """构造 Java `QualityRemediationTaskRequest` 可理解的 dry-run 参数。

        输入：
        - `request.variables`：来自 API、前端、Java 控制面或未来 MCP tool call 的结构化变量；
        - 字段名允许 camelCase 与 snake_case 并存，便于多端逐步迁移。

        输出：
        - `remediationScope`：只表示“处理哪一批异常”的低敏定位范围；
        - `remediationType/reason/recommendation`：用于解释治理任务草案为什么被创建；
        - `dryRun=true`：明确当前计划只生成预览，不提交 task-management，也不执行清洗。

        安全边界：
        - 不写入用户原始 objective，避免把自由文本中的业务敏感信息带入工具参数；
        - 不写入异常样本、observedValue、SQL、prompt、模型输出、完整工具参数、凭据或内部 endpoint；
        - 无 scope 时仍返回空 `remediationScope`，交给 `ToolParameterValidator` 标记为需要上下文补齐。
        """

        scope = self._remediation_scope(request)
        return {
            "remediationScope": scope,
            "remediationType": self._first_variable(
                request,
                ("remediationType", "remediation_type"),
                default="MANUAL_REVIEW",
            ),
            "reason": "基于低敏质量异常聚合生成治理任务草案，需人工复核后再提交。",
            "recommendation": self._first_variable(
                request,
                ("remediationRecommendationCode", "recommendationCode", "recommendation_code"),
                default="MANUAL_REVIEW_AND_ASSIGN_OWNER",
            ),
            "dryRun": True,
            "payloadPolicy": "LOW_SENSITIVE_AGGREGATION_ONLY",
            "priority": self._first_variable(request, ("priority",), default="MEDIUM"),
            "aggregationLimit": self._first_variable(
                request,
                ("aggregationLimit", "aggregation_limit"),
                default=10,
            ),
        }

    def _remediation_scope(self, request: AgentRequest) -> dict[str, object]:
        """提取治理任务的低敏定位范围。

        scope 的业务含义是“对哪一个报告、规则、异常类型、严重级别、字段或目标对象创建治理草案”。
        它不承载样本正文，也不承载真实异常值。即使 `fieldName/targetObject` 在某些行业也可能被视为
        敏感元数据，我们仍把它们统一收口到 `remediationScope`，方便后续事件投影只展示字段集合、
        数量和截断标记，而不是在多个顶层字段中分散暴露。
        """

        field_mapping = {
            "tenantId": ("tenantId", "tenant_id"),
            "projectId": ("projectId", "project_id"),
            "workspaceId": ("workspaceId", "workspace_id"),
            "reportId": ("reportId", "report_id"),
            "ruleId": ("ruleId", "rule_id"),
            "anomalyType": ("anomalyType", "anomaly_type"),
            "fieldName": ("fieldName", "field_name"),
            "severity": ("severity",),
            "targetObject": ("targetObject", "target_object"),
            "startTime": ("startTime", "start_time"),
            "endTime": ("endTime", "end_time"),
            "assigneeActorId": ("assigneeActorId", "assignee_actor_id"),
        }
        scope: dict[str, object] = {}
        for target_name, source_names in field_mapping.items():
            value = self._first_variable(request, source_names)
            if value is not None:
                scope[target_name] = value
        return scope

    @staticmethod
    def _first_variable(
        request: AgentRequest,
        names: tuple[str, ...],
        default: object | None = None,
    ) -> object | None:
        """按多个兼容字段名读取请求变量。

        Python API、Java DTO、前端表单和未来 MCP 工具调用可能使用不同命名风格。集中做兼容读取，
        可以让业务逻辑只讨论“治理范围”而不是反复写 `xxx or yyy`，也能减少字段迁移时的漏改风险。
        """

        for name in names:
            value = request.variables.get(name)
            if value not in (None, "", [], {}):
                return value
        return default

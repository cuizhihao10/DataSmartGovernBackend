"""Agent 记忆规划服务。

当前服务不负责真正检索或写入记忆，而是根据意图、上下文和工具计划生成“应该读什么、允许写什么”
的计划。这个拆分很关键：检索实现会随技术栈变化，例如 Chroma、Neo4j、Redis、MySQL、MinIO 都
可能参与，但“数据质量场景应该优先看语义规则和历史异常”这种业务原则不应该散落在存储适配器里。
"""

from __future__ import annotations

from datasmart_ai_runtime.domain.contracts import AgentRequest, ToolPlan
from datasmart_ai_runtime.domain.context import ContextBlock
from datasmart_ai_runtime.domain.intent import GovernanceDomain, IntentAnalysis, IntentRiskTag
from datasmart_ai_runtime.domain.memory import (
    AgentMemoryPlan,
    AgentMemoryRetrievalTarget,
    AgentMemoryScope,
    AgentMemoryType,
)


class AgentMemoryPlanner:
    """根据治理意图生成分层记忆计划。

    这一步是 DataSmart 从“单次问答 Agent”走向“可持续学习的治理 Agent”的关键过渡：
    - 读取计划让 Agent 知道本次请求应该参考哪些历史知识；
    - 写入计划让平台知道哪些工具结果可以沉淀为长期资产；
    - 范围与审批策略让记忆能力不会突破租户、项目和合规边界。
    """

    def plan(
        self,
        request: AgentRequest,
        intent_analysis: IntentAnalysis | None,
        context_blocks: tuple[ContextBlock, ...],
        tool_plans: tuple[ToolPlan, ...],
    ) -> AgentMemoryPlan:
        """生成当前请求的记忆计划。

        参数说明：
        - `request`：提供租户、项目、操作者和原始目标，是决定记忆范围的基础。
        - `intent_analysis`：提供治理域和风险标签，用于选择语义/情节/程序等记忆。
        - `context_blocks`：当前已经选入模型的上下文，可用于判断是否还需要补充资源记忆。
        - `tool_plans`：工具计划中包含 Java descriptor 透传的 `memoryWritePolicy/cachePolicy`。
        """

        domains = set(intent_analysis.governance_domains if intent_analysis else ())
        risk_tags = set(intent_analysis.risk_tags if intent_analysis else ())
        retrieval_targets: list[AgentMemoryRetrievalTarget] = []

        if GovernanceDomain.DATASOURCE in domains:
            retrieval_targets.append(
                self._target(
                    AgentMemoryType.SEMANTIC,
                    "数据源元数据、字段画像、主键索引和业务术语",
                    "数据源分析需要稳定的结构知识，避免 Agent 仅凭自然语言猜测表结构。",
                )
            )

        if GovernanceDomain.DATA_QUALITY in domains:
            retrieval_targets.append(
                self._target(
                    AgentMemoryType.SEMANTIC,
                    "质量规则模板、指标定义、字段级校验经验",
                    "质量规则生成需要复用已验证的规则知识，减少重复设计和幻觉规则。",
                )
            )
            retrieval_targets.append(
                self._target(
                    AgentMemoryType.EPISODIC,
                    "历史质量异常、检测失败案例、整改记录",
                    "质量治理不仅依赖静态规则，也需要参考真实异常处理经验。",
                )
            )

        if GovernanceDomain.DATA_SYNC in domains or GovernanceDomain.TASK_MANAGEMENT in domains:
            retrieval_targets.append(
                self._target(
                    AgentMemoryType.PROCEDURAL,
                    "同步任务创建、调度、回放、失败恢复的标准流程",
                    "涉及任务或同步时，程序记忆可以帮助 Agent 复用稳定操作步骤。",
                )
            )
            retrieval_targets.append(
                self._target(
                    AgentMemoryType.EPISODIC,
                    "历史同步事故、checkpoint、错误样本和人工处理记录",
                    "同步执行问题常常需要参考过去事故和恢复边界。",
                )
            )

        if GovernanceDomain.PERMISSION_ADMIN in domains:
            retrieval_targets.append(
                self._target(
                    AgentMemoryType.EPISODIC,
                    "权限变更、审批历史、越权拦截和审计说明",
                    "权限相关建议必须参考历史审批与审计事实，不能只看当前用户描述。",
                )
            )

        if context_blocks:
            retrieval_targets.append(
                self._target(
                    AgentMemoryType.SHORT_TERM,
                    "当前会话上下文、已选数据源、已生成工具参数",
                    "已有上下文需要在本次 run 内保持一致，避免多轮对话中参数漂移。",
                    scope=AgentMemoryScope.SESSION,
                    max_items=8,
                )
            )

        writable_memory_types = self._resolve_writable_types(tool_plans)
        sensitive = bool(
            risk_tags
            & {
                IntentRiskTag.SENSITIVE_DATA,
                IntentRiskTag.CROSS_SCOPE,
                IntentRiskTag.CROSS_TENANT,
                IntentRiskTag.DATA_EXPORT,
            }
        )
        privacy_notes = self._privacy_notes(request, sensitive)
        return AgentMemoryPlan(
            retrieval_targets=self._deduplicate_targets(retrieval_targets),
            writable_memory_types=writable_memory_types,
            default_scope=AgentMemoryScope.SESSION if sensitive else AgentMemoryScope.PROJECT,
            retention_days=7 if sensitive else 30,
            approval_required_for_write=sensitive or any(plan.requires_human_approval for plan in tool_plans),
            audit_required=True,
            privacy_notes=privacy_notes,
            rationale=self._build_rationale(domains, writable_memory_types, sensitive),
            attributes={
                "tenantId": request.tenant_id,
                "projectId": request.project_id,
                "contextBlockCount": len(context_blocks),
                "toolPlanCount": len(tool_plans),
            },
        )

    @staticmethod
    def _target(
        memory_type: AgentMemoryType,
        query_hint: str,
        reason: str,
        scope: AgentMemoryScope = AgentMemoryScope.PROJECT,
        max_items: int = 5,
    ) -> AgentMemoryRetrievalTarget:
        """创建记忆检索目标，集中默认 scope 与 max_items。"""

        return AgentMemoryRetrievalTarget(
            memory_type=memory_type,
            scope=scope,
            query_hint=query_hint,
            reason=reason,
            max_items=max_items,
        )

    @staticmethod
    def _resolve_writable_types(tool_plans: tuple[ToolPlan, ...]) -> tuple[AgentMemoryType, ...]:
        """从工具治理 hint 中解析允许写入的记忆类型。

        Java descriptor 中的 `memoryWritePolicy` 是工具级治理结果。Python Runtime 不能随意把工具结果
        写进长期记忆，只能把该策略转换成计划，交给后续控制面执行。
        """

        resolved: list[AgentMemoryType] = []
        mapping = {
            "semantic": AgentMemoryType.SEMANTIC,
            "episodic": AgentMemoryType.EPISODIC,
            "procedural": AgentMemoryType.PROCEDURAL,
            "resource": AgentMemoryType.RESOURCE,
            "short_term": AgentMemoryType.SHORT_TERM,
        }
        for plan in tool_plans:
            policy = str(plan.governance_hints.get("memoryWritePolicy", "none")).lower()
            memory_type = mapping.get(policy)
            if memory_type and memory_type not in resolved:
                resolved.append(memory_type)
        return tuple(resolved)

    @staticmethod
    def _deduplicate_targets(
        targets: list[AgentMemoryRetrievalTarget],
    ) -> tuple[AgentMemoryRetrievalTarget, ...]:
        """按类型、范围和查询提示去重，避免同一类记忆被重复检索。"""

        deduplicated: list[AgentMemoryRetrievalTarget] = []
        seen: set[tuple[str, str, str]] = set()
        for target in targets:
            key = (target.memory_type.value, target.scope.value, target.query_hint)
            if key in seen:
                continue
            seen.add(key)
            deduplicated.append(target)
        return tuple(deduplicated)

    @staticmethod
    def _privacy_notes(request: AgentRequest, sensitive: bool) -> tuple[str, ...]:
        """生成记忆计划的隐私边界说明。"""

        notes = (
            f"记忆检索和写入必须限定在 tenant={request.tenant_id}, project={request.project_id} 的授权范围内。",
            "写入长期记忆前需要保留审计记录，记录来源请求、操作者、工具计划和脱敏摘要。",
        )
        if sensitive:
            return notes + (
                "当前意图涉及敏感、跨范围或导出风险，默认只允许会话级范围并缩短保留期。",
            )
        return notes

    @staticmethod
    def _build_rationale(
        domains: set[GovernanceDomain],
        writable_memory_types: tuple[AgentMemoryType, ...],
        sensitive: bool,
    ) -> str:
        """构建人读解释，方便调试面板和学习阅读。"""

        domain_text = "、".join(domain.value for domain in domains) if domains else "general"
        write_text = "、".join(item.value for item in writable_memory_types) or "none"
        risk_text = "敏感收口" if sensitive else "项目级默认收口"
        return f"根据治理域 {domain_text} 规划记忆检索；工具结果允许写入 {write_text}；当前采用 {risk_text}。"

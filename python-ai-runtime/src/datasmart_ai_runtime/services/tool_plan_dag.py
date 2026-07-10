"""ToolPlan DAG 治理提示生成器。

该模块把 Python Runtime 生成的线性 `ToolPlan` 列表，补充为 Java `agent-runtime` 可解释的 DAG hint。
它仍然不执行工具，也不决定最终调度；它只把“计划阶段已经知道的依赖关系”显式写入
`ToolPlan.governance_hints`，让 Java 4.57 的 `dag-plan` 预检不必长期依赖兼容推断。
"""

from __future__ import annotations

import re
from dataclasses import replace
from typing import Any

from datasmart_ai_runtime.domain.contracts import ToolExecutionMode, ToolPlan, ToolRiskLevel


class ToolPlanDagAnnotator:
    """为 ToolPlan 写入 DAG 编排提示。

    真实商业化 Agent 不应只输出“工具数组”，因为工具之间经常存在前后置关系：
    - 质量规则生成通常依赖数据源元数据；
    - 任务草稿通常依赖规则建议或同步方案；
    - 草稿保存通常依赖前一步生成的草稿对象；
    - 异步执行工具后续还需要任务中心回写状态。

    本类的职责是把这些关系转成 `planNodeId/dependsOn/parallelGroup/failurePolicy/resultAlias`。
    Java 控制面后续仍会重新做权限、审批、幂等、限流和状态校验，因此这里的 hint 不是执行授权。
    """

    DAG_HINT_VERSION = "2026-05-31.v1"

    _IMPLICIT_TOOL_DEPENDENCIES: dict[str, tuple[str, ...]] = {
        "quality.rule.suggest": ("datasource.metadata.read",),
        "quality.remediation.task.draft": ("quality.rule.suggest",),
        "task.create.draft": ("quality.rule.suggest",),
        "task.draft.persist": ("task.create.draft",),
        "datasource.source.metadata.read": ("datasource.source.connection.test",),
        "datasource.target.metadata.read": ("datasource.target.connection.test",),
        "sync.task.draft.save": ("datasource.source.metadata.read", "datasource.target.metadata.read"),
        "sync.task.precheck": ("sync.task.draft.save",),
        "sync.task.publish": ("sync.task.precheck",),
        "sync.task.run": ("sync.task.publish",),
        "sync.execution.status": ("sync.task.run",),
    }

    _RESULT_ALIAS_BY_TOOL: dict[str, str] = {
        "datasource.metadata.read": "metadata",
        "quality.rule.suggest": "suggestion",
        "quality.remediation.task.draft": "remediationTaskDraft",
        "task.create.draft": "taskDraft",
        "task.draft.persist": "persistedTaskDraft",
        "data-sync.execute": "dataSyncExecution",
        "datasource.source.connection.test": "sourceConnectionTest",
        "datasource.target.connection.test": "targetConnectionTest",
        "datasource.source.metadata.read": "sourceMetadata",
        "datasource.target.metadata.read": "targetMetadata",
        "sync.task.draft.save": "syncTaskDraft",
        "sync.task.precheck": "syncTaskPrecheck",
        "sync.task.publish": "publishedSyncTask",
        "sync.task.run": "syncTaskExecution",
        "sync.execution.status": "syncExecutionStatus",
        "knowledge.rag.query": "ragEvidence",
        "web.search.query": "webSearchResults",
    }

    def annotate(self, tool_plans: tuple[ToolPlan, ...]) -> tuple[ToolPlan, ...]:
        """返回写入 DAG hint 后的新 ToolPlan 列表。

        方法保持不可变风格：不会修改原始 `ToolPlan`，而是通过 `dataclasses.replace(...)` 生成新对象。
        这样 API 响应、事件记录、Java ingestion 和后续测试都能清楚地区分“原始计划”和“已治理计划”。
        """

        if not tool_plans:
            return ()
        total_by_tool = self._count_by_tool(tool_plans)
        occurrence_by_tool: dict[str, int] = {}
        latest_node_by_tool: dict[str, str] = {}
        annotated: list[ToolPlan] = []
        for sequence, plan in enumerate(tool_plans, start=1):
            occurrence_by_tool[plan.tool_name] = occurrence_by_tool.get(plan.tool_name, 0) + 1
            node_id = self._node_id(
                tool_name=plan.tool_name,
                sequence=sequence,
                occurrence=occurrence_by_tool[plan.tool_name],
                total_occurrences=total_by_tool[plan.tool_name],
            )
            dependency_tools = self._dependency_tools(plan, latest_node_by_tool)
            depends_on, unresolved_tools = self._resolve_dependency_nodes(dependency_tools, latest_node_by_tool)
            hints = {
                **plan.governance_hints,
                "dagHintVersion": self.DAG_HINT_VERSION,
                "planNodeId": node_id,
                "planSequence": sequence,
                "dependsOn": depends_on,
                "dependsOnTools": dependency_tools,
                "unresolvedDependsOnTools": unresolved_tools,
                "parallelGroup": self._parallel_group(plan, depends_on),
                "failurePolicy": self._failure_policy(plan),
                "resultAlias": self._result_alias(plan),
            }
            annotated_plan = replace(plan, governance_hints=hints)
            annotated.append(annotated_plan)
            latest_node_by_tool[plan.tool_name] = node_id
        return tuple(annotated)

    def _dependency_tools(self, plan: ToolPlan, latest_node_by_tool: dict[str, str]) -> tuple[str, ...]:
        """解析某个工具计划依赖的前序工具编码。

        第一来源是工具参数中的输出引用，例如 `metadataRef.fromTool=datasource.metadata.read`。
        第二来源是平台内置的最小依赖规则，用于保护模型生成计划：如果模型覆盖了规则式质量工具参数，
        但同一轮前面已经规划了元数据读取节点，质量规则节点仍应显式依赖该元数据节点。
        """

        dependencies: list[str] = []
        dependencies.extend(self._reference_dependency_tools(plan.arguments))
        for tool_name in self._IMPLICIT_TOOL_DEPENDENCIES.get(plan.tool_name, ()):
            if tool_name in latest_node_by_tool:
                dependencies.append(tool_name)
        return self._deduplicate(dependencies)

    def _reference_dependency_tools(self, value: Any) -> list[str]:
        """递归扫描工具参数中的 Tool output reference。

        当前项目已经使用两种兼容引用形态：
        - 旧轻量字段：`{"fromTool": "...", "path": "..."}`
        - 新资源引用：`{"resourceReference": {"kind": "tool_output", "toolCode": "..."}}`

        递归扫描可以覆盖 payload 内嵌引用、列表引用和未来更复杂的参数结构。
        """

        dependencies: list[str] = []
        if isinstance(value, dict):
            from_tool = value.get("fromTool")
            if from_tool:
                dependencies.append(str(from_tool))
            resource_reference = value.get("resourceReference")
            if isinstance(resource_reference, dict) and resource_reference.get("kind") == "tool_output":
                tool_code = resource_reference.get("toolCode")
                if tool_code:
                    dependencies.append(str(tool_code))
            for item in value.values():
                dependencies.extend(self._reference_dependency_tools(item))
        elif isinstance(value, (list, tuple, set)):
            for item in value:
                dependencies.extend(self._reference_dependency_tools(item))
        return dependencies

    @staticmethod
    def _resolve_dependency_nodes(
        dependency_tools: tuple[str, ...],
        latest_node_by_tool: dict[str, str],
    ) -> tuple[tuple[str, ...], tuple[str, ...]]:
        """把依赖工具编码转换为前序 DAG 节点 ID。"""

        depends_on: list[str] = []
        unresolved_tools: list[str] = []
        for tool_name in dependency_tools:
            node_id = latest_node_by_tool.get(tool_name)
            if node_id:
                depends_on.append(node_id)
            else:
                unresolved_tools.append(tool_name)
        return tuple(depends_on), tuple(unresolved_tools)

    @staticmethod
    def _parallel_group(plan: ToolPlan, depends_on: tuple[str, ...]) -> str:
        """生成轻量并行组提示。

        并行组不是强制调度指令，只是给 Java worker 和前端一个可解释分类：
        无依赖只读/低风险节点通常可以作为首批探测节点；有依赖节点则按首个依赖形成 join 组。
        """

        if depends_on:
            return f"after-{depends_on[0]}"
        if plan.execution_mode == ToolExecutionMode.ASYNC_TASK:
            return "async-task"
        if plan.execution_mode in (ToolExecutionMode.DRAFT_ONLY, ToolExecutionMode.APPROVAL_REQUIRED):
            return "draft-or-approval"
        if plan.risk_level == ToolRiskLevel.LOW:
            return "read-only-probe"
        return "controlled-tool"

    @staticmethod
    def _failure_policy(plan: ToolPlan) -> str:
        """根据工具风险和执行模式给出保守失败策略。

        当前默认偏安全：除异步任务和审批类节点外，大多数节点失败都会阻断依赖它的后续节点。
        后续如果工具注册表支持 `optional=true` 或补偿动作，再把可选分支升级为 `CONTINUE_ON_FAILURE`。
        """

        if plan.execution_mode == ToolExecutionMode.ASYNC_TASK:
            return "RETRY_THEN_BLOCK"
        if plan.requires_human_approval or plan.execution_mode == ToolExecutionMode.APPROVAL_REQUIRED:
            return "MANUAL_REVIEW"
        return "BLOCK_RUN"

    def _result_alias(self, plan: ToolPlan) -> str:
        """生成下游节点可读的结果别名。"""

        return self._RESULT_ALIAS_BY_TOOL.get(plan.tool_name, f"{self._slug(plan.tool_name)}Result")

    @classmethod
    def _node_id(
        cls,
        *,
        tool_name: str,
        sequence: int,
        occurrence: int,
        total_occurrences: int,
    ) -> str:
        """生成稳定且可读的 DAG 节点 ID。"""

        base = cls._slug(tool_name)
        if total_occurrences <= 1:
            return base
        return f"{base}-{occurrence or sequence}"

    @staticmethod
    def _count_by_tool(tool_plans: tuple[ToolPlan, ...]) -> dict[str, int]:
        """统计同名工具出现次数，用于避免 planNodeId 冲突。"""

        counts: dict[str, int] = {}
        for plan in tool_plans:
            counts[plan.tool_name] = counts.get(plan.tool_name, 0) + 1
        return counts

    @staticmethod
    def _deduplicate(values: list[str]) -> tuple[str, ...]:
        """保持原始顺序去重。"""

        seen: set[str] = set()
        result: list[str] = []
        for value in values:
            if value in seen:
                continue
            seen.add(value)
            result.append(value)
        return tuple(result)

    @staticmethod
    def _slug(value: str) -> str:
        """把工具编码转换为适合 DAG 展示和 JSON 引用的节点 ID。"""

        slug = re.sub(r"[^a-zA-Z0-9]+", "-", value).strip("-").lower()
        return slug or "tool-node"

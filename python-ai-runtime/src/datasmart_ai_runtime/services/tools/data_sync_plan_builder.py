"""数据同步 Agent 工具计划构建器。

本模块只组织可复用 ToolPlan 节点，不执行任何 Java 业务动作。数据源凭据不属于 Agent 上下文；
调用方只能提供已经通过 datasource-management 安全创建的数据源 ID。
"""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

from datasmart_ai_runtime.domain.contracts import AgentRequest, ToolDefinition, ToolPlan


class DataSyncToolPlanBuilder:
    """构建连接验证 -> 元数据 -> 草稿 -> 预检查 -> 发布 -> 运行 -> 状态查询 DAG。"""

    _TOOL_NAMES = (
        "datasource.source.connection.test",
        "datasource.target.connection.test",
        "datasource.source.metadata.read",
        "datasource.target.metadata.read",
        "sync.task.draft.save",
        "sync.task.precheck",
        "sync.task.publish",
        "sync.task.run",
        "sync.execution.status",
    )

    def build(
        self,
        *,
        request: AgentRequest,
        objective: str,
        candidate_tools: set[str],
        tools: dict[str, ToolDefinition],
        plan_factory: Callable[[ToolDefinition, str, dict[str, object]], ToolPlan],
    ) -> tuple[ToolPlan, ...]:
        payload = self._payload(request)
        requested = bool(payload) or bool(candidate_tools.intersection(self._TOOL_NAMES))
        requested = requested or (
            self._contains_any(objective, ("数据同步", "数据迁移", "全量传输", "同步任务", "sync", "migrate"))
            and bool(request.variables.get("sourceDatasourceId"))
            and bool(request.variables.get("targetDatasourceId"))
        )
        if not requested:
            return ()

        source_id = payload.get("sourceDatasourceId") or request.variables.get("sourceDatasourceId")
        target_id = payload.get("targetDatasourceId") or request.variables.get("targetDatasourceId")
        object_mappings = payload.get("objectMappings") or request.variables.get("objectMappings") or []
        common = {
            "sourceDatasourceId": source_id,
            "targetDatasourceId": target_id,
            "objectMappings": object_mappings,
            "taskName": payload.get("taskName") or request.variables.get("taskName") or "Agent 创建的全量同步任务",
            "taskDescription": payload.get("taskDescription") or "由智能助手根据用户确认的计划创建。",
            "groupCode": payload.get("groupCode") or "DEFAULT",
            "groupName": payload.get("groupName") or "默认分组",
            "priority": payload.get("priority") or "MEDIUM",
            "syncMode": payload.get("syncMode") or "FULL",
            "writeStrategy": payload.get("writeStrategy") or "INSERT",
        }
        plans: list[ToolPlan] = []

        self._append(
            plans,
            tools,
            plan_factory,
            "datasource.source.connection.test",
            "先验证用户已安全登记的源端数据源连接，避免在无效连接上继续创建任务。",
            {"datasourceId": source_id},
        )
        self._append(
            plans,
            tools,
            plan_factory,
            "datasource.target.connection.test",
            "并行验证用户已安全登记的目标端数据源连接，避免任务发布后才发现目标不可写。",
            {"datasourceId": target_id},
        )
        self._append(
            plans,
            tools,
            plan_factory,
            "datasource.source.metadata.read",
            "源端连接通过后读取真实表和字段结构，为对象与字段映射提供依据。",
            {
                "datasourceId": source_id,
                "connectionTestRef": self._ref("datasource.source.connection.test", "success"),
            },
        )
        self._append(
            plans,
            tools,
            plan_factory,
            "datasource.target.metadata.read",
            "目标端连接通过后读取真实表、字段和约束，为映射与预检查提供依据。",
            {
                "datasourceId": target_id,
                "connectionTestRef": self._ref("datasource.target.connection.test", "success"),
            },
        )
        self._append(
            plans,
            tools,
            plan_factory,
            "sync.task.draft.save",
            "基于两端真实元数据生成同名字段默认映射，并保存为可继续编辑的同步任务草稿。",
            {
                **common,
                "sourceMetadataRef": self._ref("datasource.source.metadata.read", "metadata"),
                "targetMetadataRef": self._ref("datasource.target.metadata.read", "metadata"),
            },
        )
        self._append(
            plans,
            tools,
            plan_factory,
            "sync.task.precheck",
            "草稿保存后调用真实预检查，验证对象、字段、目标约束和 runner 准入。",
            {"draftRef": self._ref("sync.task.draft.save", "templateId")},
        )
        self._append(
            plans,
            tools,
            plan_factory,
            "sync.task.publish",
            "只有预检查通过后才发布任务定义；该写操作必须由发起用户确认。",
            {
                "draftRef": self._ref("sync.task.draft.save", "taskId"),
                "precheckRef": self._ref("sync.task.precheck", "canStartExecution"),
            },
        )
        self._append(
            plans,
            tools,
            plan_factory,
            "sync.task.run",
            "发布成功后创建真实 execution 并提交 worker 队列；该动作必须由发起用户确认。",
            {"taskRef": self._ref("sync.task.publish", "taskId")},
        )
        self._append(
            plans,
            tools,
            plan_factory,
            "sync.execution.status",
            "提交运行后读取最新 execution 状态和低敏进度，让用户能继续进入任务详情追踪。",
            {"taskRef": self._ref("sync.task.run", "taskId")},
        )
        return tuple(plans)

    @staticmethod
    def _payload(request: AgentRequest) -> dict[str, Any]:
        raw = request.variables.get("dataSyncRequest") or request.variables.get("data_sync_request")
        return dict(raw) if isinstance(raw, dict) else {}

    @staticmethod
    def _append(
        plans: list[ToolPlan],
        tools: dict[str, ToolDefinition],
        plan_factory: Callable[[ToolDefinition, str, dict[str, object]], ToolPlan],
        tool_name: str,
        reason: str,
        arguments: dict[str, object],
    ) -> None:
        tool = tools.get(tool_name)
        if tool is not None:
            plans.append(plan_factory(tool, reason, arguments))

    @staticmethod
    def _ref(from_tool: str, path: str) -> dict[str, str]:
        return {"fromTool": from_tool, "path": path}

    @staticmethod
    def _contains_any(value: str, keywords: tuple[str, ...]) -> bool:
        return any(keyword in value for keyword in keywords)


__all__ = ["DataSyncToolPlanBuilder"]

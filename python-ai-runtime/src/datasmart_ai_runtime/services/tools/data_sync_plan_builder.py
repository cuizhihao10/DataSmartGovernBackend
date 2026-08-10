"""数据同步 Agent 工具计划构建器。

本模块只组织可复用 ToolPlan 节点，不执行任何 Java 业务动作。数据源凭据不属于 Agent 上下文；
调用方只能提供已经通过 datasource-management 安全创建的数据源 ID。
"""

from __future__ import annotations

import re
from collections.abc import Callable
from typing import Any

from datasmart_ai_runtime.domain.contracts import AgentRequest, ToolDefinition, ToolPlan
from datasmart_ai_runtime.services.sync_configuration_corrections import (
    apply_explicit_sync_corrections,
)


class DataSyncToolPlanBuilder:
    """按人工创建向导的五种产品模式构建数据同步工具 DAG。

    FULL 和 CUSTOM_SQL_QUERY 发布后立即创建 execution；SCHEDULED_FULL 与
    SCHEDULED_BATCH 发布后进入等待调度；CDC_STREAMING 发布后由实时通道接管，
    不得误用离线 ``sync.task.run``。对象、字段、WHERE、SQL 与调度配置只在用户
    确认后进入草稿工具，规划器不猜测或交换源端/目标端。
    """

    _SCHEDULED_MODES = frozenset({"SCHEDULED_FULL", "SCHEDULED_BATCH"})
    _IMMEDIATE_OFFLINE_MODES = frozenset({"FULL", "CUSTOM_SQL_QUERY"})

    _TOOL_NAMES = (
        "datasource.source.connection.test",
        "datasource.target.connection.test",
        "datasource.source.metadata.read",
        "datasource.target.metadata.read",
        "sync.cdc.readiness.check",
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
        """Build the governed synchronization lifecycle after identities are known.

        ``dataSyncRequest`` is also used as a progressive form payload, so its mere
        presence does not prove that a source and target resource have been selected.
        This method first normalizes both datasource IDs and returns no executable
        tools when either side is absent. The conversation layer can then ask for the
        exact missing selector instead of allowing a connection/metadata tool with a
        null ID to fail inside Java control-plane execution.

        Once both identities are valid, the method emits the same ordered lifecycle
        used by manual task creation: connection checks, metadata reads, draft save,
        precheck, publish/run and execution observation. Every node still passes
        through the registry, parameter validation, permission and approval policies;
        this builder only constructs candidates and never executes a side effect.
        """

        payload = self._payload(request)
        source_id = self._positive_datasource_id(
            payload.get("sourceDatasourceId") or request.variables.get("sourceDatasourceId")
        )
        target_id = self._positive_datasource_id(
            payload.get("targetDatasourceId") or request.variables.get("targetDatasourceId")
        )
        # The frontend deliberately sends a partial dataSyncRequest while the user is
        # still clarifying the task.  Descriptive fields such as taskDescription or
        # groupName do not make that shell an executable contract.  Connection and
        # metadata nodes may only be built after both resource identities are known;
        # otherwise ``None`` would cross the Java control-plane boundary and turn a
        # normal datasource question into a misleading tool failure.
        if source_id is None or target_id is None:
            return ()
        requested = bool(payload) or bool(candidate_tools.intersection(self._TOOL_NAMES))
        if not requested:
            return ()

        object_mappings = payload.get("objectMappings") or request.variables.get("objectMappings") or []
        metadata_filters = self._metadata_filters(
            object_mappings=object_mappings,
            objective=str(request.objective or ""),
        )
        sync_mode = self._normalize_sync_mode(payload.get("syncMode"))
        write_strategy = self._normalize_write_strategy(payload.get("writeStrategy"), sync_mode)
        common = {
            "sourceDatasourceId": source_id,
            "targetDatasourceId": target_id,
            "objectMappings": object_mappings,
            "taskName": payload.get("taskName") or request.variables.get("taskName") or "Agent 创建的全量同步任务",
            "taskDescription": payload.get("taskDescription") or "由智能助手根据用户确认的计划创建。",
            "groupCode": payload.get("groupCode") or "DEFAULT",
            "groupName": payload.get("groupName") or "默认分组",
            "priority": payload.get("priority") or "MEDIUM",
            "syncMode": sync_mode,
            "writeStrategy": write_strategy,
            "scheduleConfig": payload.get("scheduleConfig") if sync_mode in self._SCHEDULED_MODES else None,
            "customSqlText": payload.get("customSqlText") if sync_mode == "CUSTOM_SQL_QUERY" else None,
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
                "connectionTestRef": self._ref("datasource.source.connection.test", "datasourceId"),
                **metadata_filters["source"],
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
                "connectionTestRef": self._ref("datasource.target.connection.test", "datasourceId"),
                **metadata_filters["target"],
            },
        )
        if sync_mode == "CDC_STREAMING":
            self._append(
                plans,
                tools,
                plan_factory,
                "sync.cdc.readiness.check",
                "保存实时任务前检查主键、binlog/WAL、Kafka、Debezium 和 CDC 运行时是否全部具备。",
                {
                    "sourceMetadataRef": self._ref("datasource.source.metadata.read", "metadata"),
                    "targetMetadataRef": self._ref("datasource.target.metadata.read", "metadata"),
                    "objectMappings": object_mappings,
                },
            )
        self._append(
            plans,
            tools,
            plan_factory,
            "sync.task.draft.save",
            "按用户确认的源表到目标表、字段与 WHERE 映射保存草稿；真实元数据只用于存在性和兼容性校验。",
            {
                **common,
                **({
                    "cdcReadinessRef": self._ref("sync.cdc.readiness.check", "ready")
                } if sync_mode == "CDC_STREAMING" else {}),
                "sourceMetadataRef": self._ref("datasource.source.metadata.read", "metadata"),
                "targetMetadataRef": self._ref("datasource.target.metadata.read", "metadata"),
                # A structured form submission normally reads all requested
                # objects in one metadata batch. Keep the grouped contract too,
                # so the same draft tool also supports natural-language loops
                # that discover multiple tables through separate narrow calls.
                "sourceMetadataRefs": [
                    self._ref("datasource.source.metadata.read", "metadata")
                ],
                "targetMetadataRefs": [
                    self._ref("datasource.target.metadata.read", "metadata")
                ],
            },
        )
        self._append(
            plans,
            tools,
            plan_factory,
            "sync.task.precheck",
            "草稿保存后调用真实预检查，验证对象、字段、目标约束和 runner 准入。",
            {"draftRef": self._ref("sync.task.draft.save", "taskId")},
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
                "syncMode": sync_mode,
                "enableSchedule": sync_mode in self._SCHEDULED_MODES,
            },
        )
        if sync_mode in self._IMMEDIATE_OFFLINE_MODES:
            self._append(
                plans,
                tools,
                plan_factory,
                "sync.task.run",
                "即时离线模式发布成功后创建真实 execution 并提交 worker 队列；该动作必须由发起用户确认。",
                {"taskRef": self._ref("sync.task.publish", "taskId"), "syncMode": sync_mode},
            )
            self._append(
                plans,
                tools,
                plan_factory,
                "sync.execution.status",
                "提交运行后只读取一次最新 execution 状态和低敏进度，不等待长任务终态；用户可进入任务详情持续追踪。",
                {"taskRef": self._ref("sync.task.run", "taskId")},
            )
        return tuple(plans)

    @classmethod
    def _metadata_filters(
        cls,
        *,
        object_mappings: object,
        objective: str,
    ) -> dict[str, dict[str, object]]:
        """为两端控制面元数据读取生成确定性的精确过滤参数。

        元数据读取工具的默认上限适合对象选择器，却不能证明上限之外的表不存在。只要用户
        已经在结构化映射里给出表名，就应把两端名称分别传给 Java Runtime，由 Java 逐表查询、
        合并并生成同一个审计事实。自然语言场景只在用户明确说“同名表”且出现带下划线或
        引号包裹的安全标识符时复用同一组名称，避免把 MySQL/PostgreSQL 当作实例或表名。

        该方法只缩小只读查询范围，不创建映射、不修改 schema，也不绕过后续字段和预检查。
        """

        source_names: list[str] = []
        target_names: list[str] = []
        source_schemas: list[str] = []
        target_schemas: list[str] = []

        def append_identifier(values: list[str], value: object) -> None:
            normalized = str(value or "").strip()
            if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_$]{0,127}", normalized):
                return
            if normalized.casefold() not in {item.casefold() for item in values}:
                values.append(normalized)

        if isinstance(object_mappings, (list, tuple)):
            for mapping in object_mappings[:100]:
                if not isinstance(mapping, dict):
                    continue
                append_identifier(
                    source_names,
                    mapping.get("sourceObjectName") or mapping.get("sourceTableName"),
                )
                append_identifier(
                    target_names,
                    mapping.get("targetObjectName") or mapping.get("targetTableName"),
                )
                append_identifier(source_schemas, mapping.get("sourceSchemaName"))
                append_identifier(target_schemas, mapping.get("targetSchemaName"))

        normalized_objective = str(objective or "")
        same_name_requested = any(
            marker in normalized_objective.casefold()
            for marker in ("同名", "same-name", "same name")
        )
        if not source_names and not target_names and same_name_requested:
            quoted = re.findall(
                r"[`\"']([A-Za-z_][A-Za-z0-9_$]{0,127})[`\"']",
                normalized_objective,
            )
            underscored = re.findall(
                r"(?<![A-Za-z0-9_$])([A-Za-z_][A-Za-z0-9_$]*_[A-Za-z0-9_$]*)(?![A-Za-z0-9_$])",
                normalized_objective,
            )
            for candidate in (*quoted, *underscored):
                append_identifier(source_names, candidate)
                append_identifier(target_names, candidate)

        if not target_schemas:
            schema_patterns = (
                r"(?<![A-Za-z0-9_$])(?P<schema>[A-Za-z_][A-Za-z0-9_$]{0,127})\s+schema(?![A-Za-z0-9_$])",
                r"\bschema\s*(?:is|=|:|为|是|叫做|中的)?\s*(?P<schema>[A-Za-z_][A-Za-z0-9_$]{0,127})",
            )
            for pattern in schema_patterns:
                matches = tuple(re.finditer(pattern, normalized_objective, re.IGNORECASE))
                if matches:
                    append_identifier(target_schemas, matches[-1].group("schema"))
                    break

        source: dict[str, object] = {}
        target: dict[str, object] = {}
        if source_names:
            source["tableNames"] = source_names
        if target_names:
            target["tableNames"] = target_names
        if len(source_schemas) == 1:
            source["schemaPattern"] = source_schemas[0]
        if len(target_schemas) == 1:
            target["schemaPattern"] = target_schemas[0]
        return {"source": source, "target": target}

    def build_confirmed_lifecycle_from_draft(
        self,
        *,
        draft_plan: ToolPlan,
        tools: dict[str, ToolDefinition],
        plan_factory: Callable[[ToolDefinition, str, dict[str, object]], ToolPlan],
    ) -> tuple[ToolPlan, ...]:
        """Expand one validated model draft into the lifecycle covered by one confirmation.

        The model remains responsible for interpreting user choices and producing the
        complete draft arguments.  Once that draft has passed metadata/state guards,
        precheck, publish, run and status are deterministic platform lifecycle steps;
        asking the model to rediscover them one at a time would require several user
        approvals for one business intent and could leave orphaned drafts.

        All downstream identifiers are output references, not model-supplied IDs.  Java
        resolves them inside the same Run and still applies permissions, approval,
        idempotency and downstream state validation before every side effect.
        """

        if draft_plan.tool_name != "sync.task.draft.save":
            return (draft_plan,)

        sync_mode = self._normalize_sync_mode(draft_plan.arguments.get("syncMode"))
        plans = [draft_plan]
        self._append(
            plans,
            tools,
            plan_factory,
            "sync.task.precheck",
            "Validate the saved draft against real objects, fields, target constraints and runner admission.",
            {"draftRef": self._ref("sync.task.draft.save", "taskId")},
        )
        self._append(
            plans,
            tools,
            plan_factory,
            "sync.task.publish",
            "Publish only after the real precheck succeeds; this mutation remains inside the user's confirmation scope.",
            {
                "draftRef": self._ref("sync.task.draft.save", "taskId"),
                "precheckRef": self._ref("sync.task.precheck", "canStartExecution"),
                "syncMode": sync_mode,
                "enableSchedule": sync_mode in self._SCHEDULED_MODES,
            },
        )
        if sync_mode in self._IMMEDIATE_OFFLINE_MODES:
            self._append(
                plans,
                tools,
                plan_factory,
                "sync.task.run",
                "Submit the published immediate offline task to the real worker queue under the same confirmation.",
                {"taskRef": self._ref("sync.task.publish", "taskId"), "syncMode": sync_mode},
            )
            self._append(
                plans,
                tools,
                plan_factory,
                "sync.execution.status",
                "Read one low-sensitive execution snapshot after submission; the task remains observable in the sync task list and is not blocked on terminal completion.",
                {"taskRef": self._ref("sync.task.run", "taskId")},
            )
        return tuple(plans)

    @staticmethod
    def _payload(request: AgentRequest) -> dict[str, Any]:
        raw = request.variables.get("dataSyncRequest") or request.variables.get("data_sync_request")
        payload = dict(raw) if isinstance(raw, dict) else {}
        return apply_explicit_sync_corrections(
            payload,
            str(request.variables.get("latestUserMessage") or ""),
        )

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

    @staticmethod
    def _normalize_sync_mode(value: object) -> str:
        normalized = str(value or "FULL").strip().upper()
        if normalized == "REAL_TIME":
            return "CDC_STREAMING"
        allowed = {"FULL", "SCHEDULED_FULL", "SCHEDULED_BATCH", "CUSTOM_SQL_QUERY", "CDC_STREAMING"}
        return normalized if normalized in allowed else "FULL"

    @staticmethod
    def _normalize_write_strategy(value: object, sync_mode: str) -> str:
        if sync_mode == "CDC_STREAMING":
            return "UPDATE"
        normalized = str(value or "INSERT").strip().upper()
        if normalized in {"MERGE", "UPSERT"}:
            return "UPDATE"
        return normalized if normalized in {"INSERT", "UPDATE"} else "INSERT"

    @staticmethod
    def _positive_datasource_id(value: object) -> int | None:
        """Normalize a trusted datasource identity before building executable tools.

        Browser forms, model JSON and restored history can represent the same ID as
        an integer or a numeric string.  Zero, negative values, booleans and arbitrary
        text are never valid datasource identities and must stop at clarification.
        """

        if isinstance(value, bool):
            return None
        try:
            normalized = int(str(value).strip())
        except (TypeError, ValueError):
            return None
        return normalized if normalized > 0 else None


__all__ = ["DataSyncToolPlanBuilder"]

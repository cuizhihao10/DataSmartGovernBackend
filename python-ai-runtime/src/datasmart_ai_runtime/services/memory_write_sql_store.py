"""Agent 记忆写入候选的关系型数据库 store。

这个实现面向“可持久化候选”和“操作审计”两个生产化目标：
1. 候选必须跨 Python Runtime 重启、多实例切换和审批台刷新继续存在；
2. 批准/拒绝不是一次简单字段更新，而是需要留下操作者、原因、前后状态、版本号和时间的审计事实。

当前类只依赖 Python 标准 DB-API 连接对象，测试里用 sqlite3 验证语义；生产环境可以用 MySQL 驱动连接，
并使用 `docker/mysql/migrations` 下的 MySQL 表结构。这样做的好处是先把领域持久化契约固定下来，
不急着把 Runtime 绑定到某个具体第三方包，后续再按部署形态选择 pymysql、mysqlclient 或 Java memory-service。
"""

from __future__ import annotations

import json
from dataclasses import replace
from datetime import datetime, timezone
from typing import Any

from datasmart_ai_runtime.domain.memory import (
    AgentMemoryScope,
    AgentMemoryType,
    AgentMemoryWriteCandidate,
    AgentMemoryWriteCandidateStatus,
)


class SqlAgentMemoryWriteCandidateStore:
    """基于关系型数据库的长期记忆候选 store。

    设计边界：
    - 本类负责候选 `save/get/list/update` 和 update 时的操作审计写入；
    - 本类不负责创建数据库连接池，连接生命周期由应用启动配置或测试夹具管理；
    - 本类不直接做权限判断，权限应由 gateway/permission-admin 和服务层数据范围共同完成；
    - 本类不执行 APPROVED 候选到 Chroma/Neo4j/MinIO/MySQL 记忆层的写入，后续应由异步 worker 消费。

    为什么 update 里要写审计表：
    候选状态从 DRAFT/PENDING_APPROVAL 变成 APPROVED/REJECTED 时，真实产品必须回答“谁在什么时候因为什么原因做了决策”。
    如果只更新候选主表，事故复盘时无法区分自动策略、人工审批、重复提交或越权调用造成的状态变化。
    """

    def __init__(
        self,
        connection: Any,
        *,
        placeholder: str = "?",
        auto_commit: bool = True,
    ) -> None:
        self._connection = connection
        self._placeholder = placeholder
        self._auto_commit = auto_commit

    def save(self, candidate: AgentMemoryWriteCandidate) -> AgentMemoryWriteCandidate:
        """插入或幂等覆盖候选。

        这里采用“先查后写”的方式，而不是直接依赖某个数据库方言的 upsert 语法。
        这样 sqlite 测试、MySQL 生产和未来 PostgreSQL 迁移都能复用同一套 Python 语义。
        如果候选已存在，保留已有版本号；如果是新候选，则以 candidate_version=1 写入。
        """

        existing = self.get(candidate.candidate_id)
        stored = replace(candidate, candidate_version=existing.candidate_version if existing else 1)
        if existing:
            self._execute(self._update_candidate_sql(), self._candidate_update_params(stored))
        else:
            self._execute(self._insert_candidate_sql(), self._candidate_insert_params(stored))
        self._commit()
        return stored

    def get(self, candidate_id: str) -> AgentMemoryWriteCandidate | None:
        """按候选 ID 查询单条记录。"""

        cursor = self._execute(
            f"SELECT {self._candidate_columns()} FROM agent_memory_write_candidate WHERE candidate_id = {self._placeholder}",
            (candidate_id,),
        )
        row = cursor.fetchone()
        return self._candidate_from_row(row) if row else None

    def list(
        self,
        *,
        tenant_id: str | None = None,
        project_id: str | None = None,
        status: AgentMemoryWriteCandidateStatus | None = None,
        limit: int = 100,
    ) -> tuple[AgentMemoryWriteCandidate, ...]:
        """按审批台常用条件查询候选列表。

        查询条件仍然保持“先租户、再项目、再状态”的顺序，方便未来把 gateway 下发的数据范围条件继续收紧到 SQL 层。
        limit 被限制在 1-500，避免审批台或错误调用一次性拉取过多候选导致 Runtime 内存和数据库压力突增。
        """

        sql = [f"SELECT {self._candidate_columns()} FROM agent_memory_write_candidate WHERE 1 = 1"]
        params: list[Any] = []
        if tenant_id:
            sql.append(f"AND tenant_id = {self._placeholder}")
            params.append(tenant_id)
        if project_id:
            sql.append(f"AND project_id = {self._placeholder}")
            params.append(project_id)
        if status:
            sql.append(f"AND status = {self._placeholder}")
            params.append(status.value)
        sql.append(f"ORDER BY created_at DESC LIMIT {self._placeholder}")
        params.append(max(1, min(limit, 500)))
        cursor = self._execute(" ".join(sql), tuple(params))
        return tuple(self._candidate_from_row(row) for row in cursor.fetchall())

    def update(self, candidate: AgentMemoryWriteCandidate) -> AgentMemoryWriteCandidate:
        """更新候选并写入操作审计。

        本方法实现轻量乐观锁：
        - 先读取当前候选，拿到旧状态和旧版本号；
        - 更新时要求 `candidate_version` 仍等于旧版本；
        - 成功后版本号 +1；
        - 同一事务内插入操作审计，记录前后状态和决策原因。

        如果未来多个审批请求同时到达，旧版本请求会因为版本不匹配而失败，从而避免“先批准后拒绝”这类覆盖事故。
        """

        current = self.get(candidate.candidate_id)
        if current is None:
            raise KeyError(f"记忆写入候选不存在: {candidate.candidate_id}")
        next_candidate = replace(candidate, candidate_version=current.candidate_version + 1)
        update_sql = self._update_candidate_sql(
            where=f"candidate_id = {self._placeholder} AND candidate_version = {self._placeholder}"
        )
        params = self._candidate_update_params(next_candidate, current.candidate_version)
        cursor = self._execute(update_sql, params)
        if cursor.rowcount == 0:
            raise RuntimeError(f"记忆写入候选版本冲突，candidateId={candidate.candidate_id}")
        self._insert_audit(current, next_candidate)
        self._commit()
        return next_candidate

    def list_decision_audits(self, candidate_id: str) -> tuple[dict[str, Any], ...]:
        """查询某条候选的决策审计记录。

        该方法不是 store 协议的必需项，但对测试和后续审批台非常有用。
        审批台详情页未来可以展示完整决策时间线，而不是只显示主表上的最后一次状态。
        """

        cursor = self._execute(
            "SELECT candidate_id, operator_id, action, previous_status, next_status, reason, "
            f"candidate_version, decided_at FROM agent_memory_write_candidate_audit WHERE candidate_id = {self._placeholder} "
            "ORDER BY id ASC",
            (candidate_id,),
        )
        return tuple(dict(row) if hasattr(row, "keys") else self._audit_tuple_to_dict(row) for row in cursor.fetchall())

    def _insert_audit(self, before: AgentMemoryWriteCandidate, after: AgentMemoryWriteCandidate) -> None:
        action = "approve" if after.status == AgentMemoryWriteCandidateStatus.APPROVED else "reject"
        self._execute(
            "INSERT INTO agent_memory_write_candidate_audit "
            "(candidate_id, tenant_id, project_id, operator_id, action, previous_status, next_status, reason, "
            "candidate_version, decided_at, create_time) VALUES "
            f"({','.join([self._placeholder] * 11)})",
            (
                after.candidate_id,
                after.tenant_id,
                after.project_id,
                after.decided_by or "",
                action,
                before.status.value,
                after.status.value,
                after.decision_reason or "",
                after.candidate_version,
                self._format_datetime(after.decided_at),
                self._format_datetime(datetime.now(timezone.utc)),
            ),
        )

    def _execute(self, sql: str, params: tuple[Any, ...] = ()):
        cursor = self._connection.cursor()
        cursor.execute(sql, params)
        return cursor

    def _commit(self) -> None:
        if self._auto_commit:
            self._connection.commit()

    def _insert_candidate_sql(self) -> str:
        columns = self._candidate_columns()
        placeholders = ",".join([self._placeholder] * len(columns.split(",")))
        return f"INSERT INTO agent_memory_write_candidate ({columns}) VALUES ({placeholders})"

    def _update_candidate_sql(self, where: str | None = None) -> str:
        columns = [column.strip() for column in self._candidate_columns().split(",") if column.strip() != "candidate_id"]
        assignments = ", ".join(f"{column} = {self._placeholder}" for column in columns)
        return f"UPDATE agent_memory_write_candidate SET {assignments} WHERE {where or f'candidate_id = {self._placeholder}'}"

    def _candidate_insert_params(self, candidate: AgentMemoryWriteCandidate) -> tuple[Any, ...]:
        return tuple(self._candidate_values(candidate))

    def _candidate_update_params(
        self,
        candidate: AgentMemoryWriteCandidate,
        expected_version: int | None = None,
    ) -> tuple[Any, ...]:
        values = self._candidate_values(candidate)
        params = tuple(values[1:] + [candidate.candidate_id])
        if expected_version is not None:
            params = params + (expected_version,)
        return params

    @staticmethod
    def _candidate_columns() -> str:
        return (
            "candidate_id,tenant_id,project_id,actor_id,memory_type,scope,status,title,content_summary,source,"
            "workspace_key,memory_namespace,source_tool_name,source_status,source_audit_id,source_run_id,output_ref,"
            "approval_required,retention_days,"
            "sensitivity_level,privacy_notes_json,candidate_version,idempotency_key,created_at,decided_at,decided_by,"
            "decision_reason,attributes_json"
        )

    def _candidate_values(self, candidate: AgentMemoryWriteCandidate) -> list[Any]:
        return [
            candidate.candidate_id,
            candidate.tenant_id,
            candidate.project_id,
            candidate.actor_id,
            candidate.memory_type.value,
            candidate.scope.value,
            candidate.status.value,
            candidate.title,
            candidate.content_summary,
            candidate.source,
            candidate.workspace_key,
            candidate.memory_namespace,
            candidate.source_tool_name,
            candidate.source_status,
            candidate.source_audit_id,
            candidate.source_run_id,
            candidate.output_ref,
            1 if candidate.approval_required else 0,
            candidate.retention_days,
            candidate.sensitivity_level,
            self._json(candidate.privacy_notes),
            candidate.candidate_version,
            candidate.idempotency_key,
            self._format_datetime(candidate.created_at),
            self._format_datetime(candidate.decided_at),
            candidate.decided_by,
            candidate.decision_reason,
            self._json(candidate.attributes),
        ]

    @staticmethod
    def _candidate_from_row(row: Any) -> AgentMemoryWriteCandidate:
        values = dict(row) if hasattr(row, "keys") else dict(zip(SqlAgentMemoryWriteCandidateStore._candidate_columns().split(","), row))
        return AgentMemoryWriteCandidate(
            candidate_id=values["candidate_id"],
            tenant_id=values["tenant_id"],
            project_id=values["project_id"],
            actor_id=values["actor_id"],
            memory_type=AgentMemoryType(values["memory_type"]),
            scope=AgentMemoryScope(values["scope"]),
            status=AgentMemoryWriteCandidateStatus(values["status"]),
            title=values["title"],
            content_summary=values["content_summary"],
            source=values["source"],
            workspace_key=values["workspace_key"],
            memory_namespace=values["memory_namespace"],
            source_tool_name=values["source_tool_name"] or "",
            source_status=values["source_status"] or "",
            source_audit_id=values["source_audit_id"],
            source_run_id=values["source_run_id"],
            output_ref=values["output_ref"],
            approval_required=bool(values["approval_required"]),
            retention_days=int(values["retention_days"]),
            sensitivity_level=values["sensitivity_level"],
            privacy_notes=tuple(SqlAgentMemoryWriteCandidateStore._loads(values["privacy_notes_json"]) or ()),
            candidate_version=int(values["candidate_version"]),
            idempotency_key=values["idempotency_key"],
            created_at=SqlAgentMemoryWriteCandidateStore._parse_datetime(values["created_at"]),
            decided_at=SqlAgentMemoryWriteCandidateStore._parse_datetime(values["decided_at"]),
            decided_by=values["decided_by"],
            decision_reason=values["decision_reason"],
            attributes=SqlAgentMemoryWriteCandidateStore._loads(values["attributes_json"]) or {},
        )

    @staticmethod
    def _audit_tuple_to_dict(row: tuple[Any, ...]) -> dict[str, Any]:
        keys = ("candidate_id", "operator_id", "action", "previous_status", "next_status", "reason", "candidate_version", "decided_at")
        return dict(zip(keys, row))

    @staticmethod
    def _json(value: Any) -> str:
        return json.dumps(value, ensure_ascii=False, sort_keys=True)

    @staticmethod
    def _loads(value: str | None) -> Any:
        if not value:
            return None
        return SqlAgentMemoryWriteCandidateStore._restore_json_value(json.loads(value))

    @staticmethod
    def _restore_json_value(value: Any) -> Any:
        if isinstance(value, list):
            return tuple(SqlAgentMemoryWriteCandidateStore._restore_json_value(item) for item in value)
        if isinstance(value, dict):
            return {key: SqlAgentMemoryWriteCandidateStore._restore_json_value(item) for key, item in value.items()}
        return value

    @staticmethod
    def _format_datetime(value: datetime | None) -> str | None:
        return value.astimezone(timezone.utc).isoformat() if value else None

    @staticmethod
    def _parse_datetime(value: str | None) -> datetime | None:
        return datetime.fromisoformat(value) if value else None

"""长期记忆物化审计 outbox 的 SQL Store。

审计 outbox 与 receipt/lease 的职责不同：

- receipt 记录“候选是否已经尝试落成正式记忆”；
- lease 记录“哪个 worker 暂时拥有处理权”；
- audit outbox 记录“worker 批次或管理员补偿动作是否已经留下可交付给审计系统的事实”。

本 SQL store 只负责 append 与 recent query，不负责把 outbox 投递到 Java 审计中心。后续可以在此基础上增加
claim/dispatch/ack/retry，从而形成真正的审计事件派发 worker。
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Any

from datasmart_ai_runtime.services.memory.memory_materialization_audit_outbox import (
    AgentMemoryMaterializationAuditDeliveryStatus,
    AgentMemoryMaterializationAuditOutboxRecord,
)


class SqlAgentMemoryMaterializationAuditOutboxStore:
    """基于 DB-API 的长期记忆物化审计 outbox store。

    设计边界：
    - 不创建连接池，连接生命周期由 runtime builder 或测试 fixture 管理；
    - 不自动建表，schema 由 migration/init SQL 管理，避免 Runtime 悄悄掩盖表结构漂移；
    - 只保存低敏审计 payload，不保存候选正文、正式记忆正文、SQL、样本数据、工具原始输出或完整异常堆栈；
    - 当前 append 默认 auto-commit。未来如果要与 lease/requeue 放在同一事务里，可以关闭 auto_commit 并由外层统一提交。
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

    def append(self, record: AgentMemoryMaterializationAuditOutboxRecord) -> AgentMemoryMaterializationAuditOutboxRecord:
        """写入一条审计 outbox 记录。"""

        self._execute(self._insert_sql(), self._insert_params(record))
        self._commit()
        return record

    def list_recent(self, *, limit: int = 100) -> tuple[AgentMemoryMaterializationAuditOutboxRecord, ...]:
        """按创建时间倒序读取最近记录。"""

        safe_limit = max(1, min(int(limit), 500))
        cursor = self._execute(
            f"SELECT {self._columns()} FROM agent_memory_materialization_audit_outbox "
            "ORDER BY created_at DESC LIMIT "
            f"{self._placeholder}",
            (safe_limit,),
        )
        return tuple(self._record_from_row(row) for row in cursor.fetchall())

    def _execute(self, sql: str, params: tuple[Any, ...] = ()):
        """执行参数化 SQL，避免 outbox payload 或 ID 破坏 SQL 语句。"""

        cursor = self._connection.cursor()
        cursor.execute(sql, params)
        return cursor

    def _commit(self) -> None:
        """按配置提交事务。"""

        if self._auto_commit:
            self._connection.commit()

    def _insert_sql(self) -> str:
        """构造插入 SQL。"""

        placeholders = ",".join([self._placeholder] * len(self._columns().split(",")))
        return f"INSERT INTO agent_memory_materialization_audit_outbox ({self._columns()}) VALUES ({placeholders})"

    @staticmethod
    def _columns() -> str:
        """返回字段顺序。

        `updated_at` 由领域对象维护，SQL 表中对应 `updated_at` 字段；后续 dispatcher 更新投递状态时也应更新它。
        """

        return (
            "outbox_id,event_type,event_purpose,aggregate_id,tenant_id,project_id,actor_id,request_id,run_id,"
            "session_id,severity,action,dry_run,payload_json,delivery_status,attempt_count,next_delivery_attempt_at,"
            "created_at,updated_at"
        )

    def _insert_params(self, record: AgentMemoryMaterializationAuditOutboxRecord) -> tuple[Any, ...]:
        """把领域对象转换为 SQL 参数。"""

        return (
            record.outbox_id,
            record.event_type,
            record.event_purpose,
            record.aggregate_id,
            record.tenant_id,
            record.project_id,
            record.actor_id,
            record.request_id,
            record.run_id,
            record.session_id,
            record.severity,
            record.action,
            # PostgreSQL 目标表使用 BOOLEAN 类型，因此这里传 Python bool。
            # SQLite 会把 bool 当作 0/1 保存，MySQL 也兼容 tinyint 映射；避免继续把目标架构锁在 MySQL 习惯上。
            bool(record.dry_run),
            self._json(record.payload),
            record.delivery_status.value,
            record.attempt_count,
            self._format_datetime(record.next_delivery_attempt_at),
            self._format_datetime(record.created_at),
            self._format_datetime(record.updated_at),
        )

    @staticmethod
    def _record_from_row(row: Any) -> AgentMemoryMaterializationAuditOutboxRecord:
        """把数据库行转换为领域对象。"""

        values = dict(row) if hasattr(row, "keys") else dict(
            zip(SqlAgentMemoryMaterializationAuditOutboxStore._columns().split(","), row)
        )
        return AgentMemoryMaterializationAuditOutboxRecord(
            outbox_id=values["outbox_id"],
            event_type=values["event_type"],
            event_purpose=values["event_purpose"],
            aggregate_id=values["aggregate_id"],
            tenant_id=values["tenant_id"],
            project_id=values["project_id"],
            actor_id=values["actor_id"],
            request_id=values["request_id"],
            run_id=values["run_id"],
            session_id=values["session_id"],
            severity=values["severity"],
            action=values["action"],
            dry_run=bool(values["dry_run"]),
            payload=SqlAgentMemoryMaterializationAuditOutboxStore._loads(values["payload_json"]) or {},
            delivery_status=AgentMemoryMaterializationAuditDeliveryStatus(values["delivery_status"]),
            attempt_count=int(values["attempt_count"] or 0),
            next_delivery_attempt_at=SqlAgentMemoryMaterializationAuditOutboxStore._parse_datetime(
                values["next_delivery_attempt_at"]
            ),
            created_at=SqlAgentMemoryMaterializationAuditOutboxStore._parse_datetime(values["created_at"])
            or datetime.now(timezone.utc),
            updated_at=SqlAgentMemoryMaterializationAuditOutboxStore._parse_datetime(values["updated_at"])
            or datetime.now(timezone.utc),
        )

    @staticmethod
    def _json(value: Any) -> str:
        """序列化 JSON，保留中文并稳定 key 顺序。"""

        return json.dumps(value, ensure_ascii=False, sort_keys=True)

    @staticmethod
    def _loads(value: Any) -> Any:
        """读取 JSON 字段，兼容 sqlite 字符串和部分 MySQL 驱动返回的 dict/list。"""

        if not value:
            return None
        if isinstance(value, (dict, list, tuple)):
            return value
        return json.loads(value)

    @staticmethod
    def _format_datetime(value: datetime | None) -> str | None:
        """把时间转换成 MySQL DATETIME(3) 友好的 UTC 字符串。"""

        if value is None:
            return None
        utc_value = value.astimezone(timezone.utc)
        return utc_value.strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]

    @staticmethod
    def _parse_datetime(value: str | datetime | None) -> datetime | None:
        """解析数据库时间，并统一为 UTC aware datetime。"""

        if value is None:
            return None
        parsed = value if isinstance(value, datetime) else datetime.fromisoformat(value)
        return parsed.replace(tzinfo=timezone.utc) if parsed.tzinfo is None else parsed.astimezone(timezone.utc)

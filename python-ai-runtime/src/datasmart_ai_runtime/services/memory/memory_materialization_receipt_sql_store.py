"""长期记忆正式落成 receipt 的 SQL Store。

`InMemoryAgentMemoryMaterializationReceiptStore` 已经固定了 receipt 的领域语义：begin/succeed/fail 三个动作
分别代表“开始处理”“正式记忆写入成功或幂等复用”“本次尝试失败”。但内存实现无法满足生产环境：
- Python Runtime 重启后，后台写入尝试证据会消失；
- 多 worker/多实例部署时，不同实例看到的 attempt_count 不一致；
- 管理员无法从数据库查询“哪些候选反复失败、哪些已经成功、哪个 worker 处理过”。

本文件提供 DB-API 风格 SQL 实现。它不负责领取任务、重试退避或 DLQ，这些属于下一阶段 outbox worker；
它只负责把 receipt 执行证据可靠保存下来，让 worker、补偿台、审计导出和指标系统可以围绕同一张表协作。
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Any
from uuid import NAMESPACE_URL, uuid5

from datasmart_ai_runtime.services.memory.memory_materialization_receipt_store import (
    AgentMemoryMaterializationReceipt,
    AgentMemoryMaterializationReceiptStatus,
)


class SqlAgentMemoryMaterializationReceiptStore:
    """基于关系型数据库的长期记忆落成 receipt store。

    设计边界：
    - 本类负责 begin/succeed/fail/get_by_candidate_id 的持久化语义；
    - 本类不主动扫描 APPROVED 候选，不实现 worker 租约，也不做失败重试；
    - 本类不保存 prompt、工具原始输出、SQL 样本或大段错误堆栈；
    - 本类不创建连接池，连接生命周期由 API runtime、worker 或测试夹具管理。

    为什么 receipt 要独立于候选表：
    候选状态表达“是否允许写入长期记忆”，receipt 状态表达“写入尝试是否执行成功”。如果把二者混在一个
    status 字段里，审批台、补偿台和监控台会读到同一个字段却表达不同业务含义，后续很难扩展重试、DLQ、
    管理员补偿和 worker 指标。
    """

    def __init__(
        self,
        connection: Any,
        *,
        placeholder: str = "?",
        auto_commit: bool = True,
    ) -> None:
        """创建 SQL receipt store。

        参数说明：
        - `connection`：符合 Python DB-API 的连接对象；
        - `placeholder`：参数占位符，sqlite 使用 `?`，MySQL 驱动通常使用 `%s`；
        - `auto_commit`：是否在每次状态变更后提交。未来 worker 如果需要把 receipt、正式记忆、outbox ack
          放进同一个事务，可以关闭该开关并由外层统一提交。
        """

        self._connection = connection
        self._placeholder = placeholder
        self._auto_commit = auto_commit

    def begin(
        self,
        *,
        candidate_id: str,
        tenant_id: str,
        project_id: str,
        workspace_key: str,
        memory_namespace: str,
        worker_id: str,
    ) -> AgentMemoryMaterializationReceipt:
        """记录一次落成尝试开始。

        重复 begin 不报错，而是递增 `attempt_count` 并把状态重新置为 STARTED。这符合至少一次投递语义：
        同一候选可能因为进程重启、网络超时、Kafka rebalance 或管理员补偿被再次处理。
        """

        now = datetime.now(timezone.utc)
        receipt_id = self._receipt_id(candidate_id)
        current = self._get_by_receipt_id(receipt_id)
        if current is None:
            receipt = AgentMemoryMaterializationReceipt(
                receipt_id=receipt_id,
                candidate_id=candidate_id,
                tenant_id=tenant_id,
                project_id=project_id,
                workspace_key=workspace_key,
                memory_namespace=memory_namespace,
                status=AgentMemoryMaterializationReceiptStatus.STARTED,
                worker_id=worker_id,
                started_at=now,
                updated_at=now,
            )
            self._execute(self._insert_sql(), self._insert_params(receipt))
            self._commit()
            return receipt

        self._execute(
            "UPDATE agent_memory_materialization_receipt SET "
            f"tenant_id = {self._placeholder}, project_id = {self._placeholder}, "
            f"workspace_key = {self._placeholder}, memory_namespace = {self._placeholder}, "
            f"status = {self._placeholder}, attempt_count = {self._placeholder}, "
            f"worker_id = {self._placeholder}, error_message = {self._placeholder}, "
            f"started_at = {self._placeholder}, finished_at = {self._placeholder}, "
            f"update_time = {self._placeholder} WHERE receipt_id = {self._placeholder}",
            (
                tenant_id,
                project_id,
                workspace_key,
                memory_namespace,
                AgentMemoryMaterializationReceiptStatus.STARTED.value,
                current.attempt_count + 1,
                worker_id,
                None,
                self._format_datetime(now),
                None,
                self._format_datetime(now),
                receipt_id,
            ),
        )
        self._commit()
        return self._required_by_receipt_id(receipt_id)

    def succeed(
        self,
        *,
        receipt_id: str,
        memory_id: str,
        namespace: tuple[str, ...],
        outcome: str,
        message: str,
    ) -> AgentMemoryMaterializationReceipt:
        """把 receipt 标记为成功或幂等复用成功。"""

        current = self._required_by_receipt_id(receipt_id)
        now = datetime.now(timezone.utc)
        self._execute(
            "UPDATE agent_memory_materialization_receipt SET "
            f"status = {self._placeholder}, memory_id = {self._placeholder}, "
            f"namespace_json = {self._placeholder}, outcome = {self._placeholder}, "
            f"message = {self._placeholder}, error_message = {self._placeholder}, "
            f"finished_at = {self._placeholder}, update_time = {self._placeholder} "
            f"WHERE receipt_id = {self._placeholder}",
            (
                AgentMemoryMaterializationReceiptStatus.SUCCEEDED.value,
                memory_id,
                self._json(namespace),
                outcome,
                message[:1024],
                None,
                self._format_datetime(now),
                self._format_datetime(now),
                current.receipt_id,
            ),
        )
        self._commit()
        return self._required_by_receipt_id(receipt_id)

    def fail(self, *, receipt_id: str, error_message: str) -> AgentMemoryMaterializationReceipt:
        """把 receipt 标记为失败，并保存低敏错误摘要。"""

        current = self._required_by_receipt_id(receipt_id)
        now = datetime.now(timezone.utc)
        self._execute(
            "UPDATE agent_memory_materialization_receipt SET "
            f"status = {self._placeholder}, error_message = {self._placeholder}, "
            f"finished_at = {self._placeholder}, update_time = {self._placeholder} "
            f"WHERE receipt_id = {self._placeholder}",
            (
                AgentMemoryMaterializationReceiptStatus.FAILED.value,
                error_message[:1000],
                self._format_datetime(now),
                self._format_datetime(now),
                current.receipt_id,
            ),
        )
        self._commit()
        return self._required_by_receipt_id(receipt_id)

    def get_by_candidate_id(self, candidate_id: str) -> AgentMemoryMaterializationReceipt | None:
        """按候选 ID 查询 receipt，供补偿台、审计台和测试使用。"""

        cursor = self._execute(
            f"SELECT {self._columns()} FROM agent_memory_materialization_receipt "
            f"WHERE candidate_id = {self._placeholder}",
            (candidate_id,),
        )
        row = cursor.fetchone()
        return self._receipt_from_row(row) if row else None

    def _get_by_receipt_id(self, receipt_id: str) -> AgentMemoryMaterializationReceipt | None:
        """按 receipt ID 查询记录。"""

        cursor = self._execute(
            f"SELECT {self._columns()} FROM agent_memory_materialization_receipt "
            f"WHERE receipt_id = {self._placeholder}",
            (receipt_id,),
        )
        row = cursor.fetchone()
        return self._receipt_from_row(row) if row else None

    def _required_by_receipt_id(self, receipt_id: str) -> AgentMemoryMaterializationReceipt:
        """读取必然存在的 receipt，避免 succeed/fail 对未知 ID 静默成功。"""

        receipt = self._get_by_receipt_id(receipt_id)
        if receipt is None:
            raise KeyError(f"长期记忆落成 receipt 不存在: {receipt_id}")
        return receipt

    def _execute(self, sql: str, params: tuple[Any, ...] = ()):
        """执行参数化 SQL，避免候选 ID、workerId 或错误摘要破坏 SQL 语句。"""

        cursor = self._connection.cursor()
        cursor.execute(sql, params)
        return cursor

    def _commit(self) -> None:
        """按配置提交事务。"""

        if self._auto_commit:
            self._connection.commit()

    def _insert_sql(self) -> str:
        """构造插入 SQL。

        当前不用数据库方言 upsert，是为了让 sqlite 单测和 MySQL 生产共用同一套领域语义。
        """

        placeholders = ",".join([self._placeholder] * len(self._columns().split(",")))
        return f"INSERT INTO agent_memory_materialization_receipt ({self._columns()}) VALUES ({placeholders})"

    @staticmethod
    def _columns() -> str:
        """返回 receipt 表字段顺序。

        `update_time` 映射到领域对象的 `updated_at`。MySQL migration 中该字段带自动更新时间；这里仍显式写入，
        是为了让 sqlite 测试和 MySQL 行为保持一致。
        """

        return (
            "receipt_id,candidate_id,tenant_id,project_id,workspace_key,memory_namespace,status,attempt_count,"
            "worker_id,memory_id,namespace_json,outcome,message,error_message,started_at,finished_at,update_time"
        )

    def _insert_params(self, receipt: AgentMemoryMaterializationReceipt) -> tuple[Any, ...]:
        """把领域对象转换为数据库参数。"""

        return (
            receipt.receipt_id,
            receipt.candidate_id,
            receipt.tenant_id,
            receipt.project_id,
            receipt.workspace_key,
            receipt.memory_namespace,
            receipt.status.value,
            receipt.attempt_count,
            receipt.worker_id,
            receipt.memory_id,
            self._json(receipt.namespace),
            receipt.outcome,
            receipt.message,
            receipt.error_message,
            self._format_datetime(receipt.started_at),
            self._format_datetime(receipt.finished_at),
            self._format_datetime(receipt.updated_at),
        )

    @staticmethod
    def _receipt_from_row(row: Any) -> AgentMemoryMaterializationReceipt:
        """把数据库行转换为 receipt 领域对象。"""

        values = dict(row) if hasattr(row, "keys") else dict(
            zip(SqlAgentMemoryMaterializationReceiptStore._columns().split(","), row)
        )
        return AgentMemoryMaterializationReceipt(
            receipt_id=values["receipt_id"],
            candidate_id=values["candidate_id"],
            tenant_id=values["tenant_id"],
            project_id=values["project_id"],
            workspace_key=values["workspace_key"],
            memory_namespace=values["memory_namespace"],
            status=AgentMemoryMaterializationReceiptStatus(values["status"]),
            attempt_count=int(values["attempt_count"]),
            worker_id=values["worker_id"] or "python-ai-runtime-inline",
            memory_id=values["memory_id"],
            namespace=tuple(SqlAgentMemoryMaterializationReceiptStore._loads(values["namespace_json"]) or ()),
            outcome=values["outcome"],
            message=values["message"],
            error_message=values["error_message"],
            started_at=SqlAgentMemoryMaterializationReceiptStore._parse_datetime(values["started_at"])
            or datetime.now(timezone.utc),
            finished_at=SqlAgentMemoryMaterializationReceiptStore._parse_datetime(values["finished_at"]),
            updated_at=SqlAgentMemoryMaterializationReceiptStore._parse_datetime(values["update_time"])
            or datetime.now(timezone.utc),
        )

    @staticmethod
    def _json(value: Any) -> str:
        """序列化 JSON 字段，保留中文并稳定 key 顺序。"""

        return json.dumps(value, ensure_ascii=False, sort_keys=True)

    @staticmethod
    def _loads(value: Any) -> Any:
        """读取 JSON 字段。

        某些 MySQL 驱动可能直接返回 list/dict，sqlite 通常返回字符串，因此这里同时兼容两类形态。
        """

        if not value:
            return None
        if isinstance(value, (list, tuple, dict)):
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
        """解析数据库时间，并统一转换为 UTC aware datetime。"""

        if value is None:
            return None
        parsed = value if isinstance(value, datetime) else datetime.fromisoformat(value)
        return parsed.replace(tzinfo=timezone.utc) if parsed.tzinfo is None else parsed.astimezone(timezone.utc)

    @staticmethod
    def _receipt_id(candidate_id: str) -> str:
        """生成与内存实现一致的稳定 receipt ID。"""

        return f"memory-materialization-{uuid5(NAMESPACE_URL, candidate_id)}"

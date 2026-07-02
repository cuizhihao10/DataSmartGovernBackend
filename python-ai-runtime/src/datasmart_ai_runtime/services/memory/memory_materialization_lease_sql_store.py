"""长期记忆落成租约的 SQL Store。

该实现把 `AgentMemoryMaterializationLeaseStore` 协议落到关系型数据库，服务于 Python Runtime 多实例部署。
核心目标不是“记录一行状态”，而是让多个 worker 在竞争同一 APPROVED 候选时只有一个实例取得有效 token。

当前实现使用可移植的条件 UPDATE + INSERT 冲突恢复：
- SQLite 单测和 MySQL 生产可以共享同一套领域语义；
- 条件 UPDATE 只允许成功终态以外、且没有有效租约的记录被重新领取；
- INSERT 遇到并发唯一键冲突时会回滚并重新尝试条件 UPDATE；
- 完成与失败必须携带 lease token，过期 worker 无法覆盖新 worker 的结果。

高吞吐优化方向：
MySQL 8 可以进一步使用 `SELECT ... FOR UPDATE SKIP LOCKED` 批量领取 queue-like 表记录，降低高并发竞争；
但该语法不适用于 SQLite。当前阶段先固定跨数据库可验证语义，再按压测结果增加 MySQL 专用批量 claim。
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Any
from uuid import uuid4

from datasmart_ai_runtime.services.memory.memory_materialization_lease_store import (
    AgentMemoryMaterializationLease,
    AgentMemoryMaterializationLeaseStatus,
    _admin_requeue_message,
    _default_compensation_statuses,
    _require_requeueable_status,
    _validate_compensation_operator,
    decide_materialization_retry,
    _lease_id,
    _utc,
)
from datasmart_ai_runtime.services.memory.memory_materialization_lease_sql_mapping import (
    LEASE_COLUMNS,
    lease_from_row,
)


class SqlAgentMemoryMaterializationLeaseStore:
    """基于 DB-API 的长期记忆落成租约 store。

    连接生命周期由 runtime builder 或测试夹具管理。该类不自动建表，避免应用启动时偷偷修改生产 schema。
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

    def try_acquire(
        self,
        *,
        candidate_id: str,
        tenant_id: str,
        project_id: str,
        workspace_key: str,
        memory_namespace: str,
        worker_id: str,
        lease_seconds: int,
        now: datetime | None = None,
    ) -> AgentMemoryMaterializationLease | None:
        """尝试领取一条候选。

        算法步骤：
        1. 先用条件 UPDATE 尝试接管已到重试时间的失败租约或过期租约；
        2. 如果候选还没有 lease 行，则 INSERT 新租约；
        3. 如果多个实例同时 INSERT，唯一键冲突的一方回滚并再次尝试条件 UPDATE；
        4. 已成功或仍在有效期内的租约返回 `None`。

        这是一种乐观 claim。它比“先查再写”更可靠，因为最终竞争结果由数据库行级条件和唯一约束裁决。
        """

        current_time = _utc(now)
        safe_lease_seconds = max(1, lease_seconds)
        lease_token = str(uuid4())
        leased_until = current_time + timedelta(seconds=safe_lease_seconds)
        updated = self._try_reacquire(
            candidate_id=candidate_id,
            tenant_id=tenant_id,
            project_id=project_id,
            workspace_key=workspace_key,
            memory_namespace=memory_namespace,
            worker_id=worker_id,
            lease_token=lease_token,
            leased_until=leased_until,
            now=current_time,
        )
        if updated:
            return self.get_by_candidate_id(candidate_id)

        current = self.get_by_candidate_id(candidate_id)
        if current is not None:
            return None

        lease = AgentMemoryMaterializationLease(
            lease_id=_lease_id(candidate_id),
            candidate_id=candidate_id,
            tenant_id=tenant_id,
            project_id=project_id,
            workspace_key=workspace_key,
            memory_namespace=memory_namespace,
            status=AgentMemoryMaterializationLeaseStatus.LEASED,
            attempt_count=1,
            worker_id=worker_id,
            lease_token=lease_token,
            leased_until=leased_until,
            next_retry_at=None,
            started_at=current_time,
            updated_at=current_time,
        )
        try:
            self._execute(self._insert_sql(), self._insert_params(lease))
            self._commit()
            return lease
        except Exception:
            # 两个 worker 首次争抢同一候选时，只有一个 INSERT 能通过 candidate_id 唯一约束。
            # 失败方必须先回滚当前事务，再尝试接管“已经过期”的行；如果获胜方租约仍有效，则返回 None。
            self._rollback()
            retry_token = str(uuid4())
            updated = self._try_reacquire(
                candidate_id=candidate_id,
                tenant_id=tenant_id,
                project_id=project_id,
                workspace_key=workspace_key,
                memory_namespace=memory_namespace,
                worker_id=worker_id,
                lease_token=retry_token,
                leased_until=leased_until,
                now=current_time,
            )
            return self.get_by_candidate_id(candidate_id) if updated else None

    def succeed(
        self,
        *,
        candidate_id: str,
        lease_token: str,
        memory_id: str,
        outcome: str,
        message: str,
        now: datetime | None = None,
    ) -> AgentMemoryMaterializationLease:
        """使用 fencing token 把租约推进到成功终态。

        如果 UPDATE 没有命中，说明租约已经过期并被其他 worker 接管，或调用方 token 错误。此时必须失败，
        不能让旧 worker 覆盖新 worker。正式 memory store 自身仍要保持幂等，处理“写入成功但 lease 回写超时”的情况。
        """

        current_time = _utc(now)
        cursor = self._execute(
            "UPDATE agent_memory_materialization_lease SET "
            f"status = {self._placeholder}, memory_id = {self._placeholder}, "
            f"outcome = {self._placeholder}, message = {self._placeholder}, "
            f"error_message = {self._placeholder}, next_retry_at = {self._placeholder}, "
            f"finished_at = {self._placeholder}, "
            f"update_time = {self._placeholder} "
            f"WHERE candidate_id = {self._placeholder} AND lease_token = {self._placeholder} "
            f"AND status = {self._placeholder}",
            (
                AgentMemoryMaterializationLeaseStatus.SUCCEEDED.value,
                memory_id,
                outcome,
                message[:1024],
                None,
                None,
                self._format_datetime(current_time),
                self._format_datetime(current_time),
                candidate_id,
                lease_token,
                AgentMemoryMaterializationLeaseStatus.LEASED.value,
            ),
        )
        self._require_fenced_update(cursor.rowcount, candidate_id)
        self._commit()
        return self._required(candidate_id)

    def fail(
        self,
        *,
        candidate_id: str,
        lease_token: str,
        error_message: str,
        max_attempts: int = 5,
        retry_base_seconds: int = 30,
        retry_max_seconds: int = 3600,
        now: datetime | None = None,
    ) -> AgentMemoryMaterializationLease:
        """记录失败并释放当前领取权。

        这里会先读取当前租约计算重试策略，再用 token 条件 UPDATE 写回。即使读取后租约被其他 worker 接管，
        最终 UPDATE 仍会因为 token 不匹配失败，从而保护新 worker 的处理结果。
        """

        current_time = _utc(now)
        current = self._required(candidate_id)
        if (
            current.status != AgentMemoryMaterializationLeaseStatus.LEASED
            or current.lease_token != lease_token
        ):
            self._require_fenced_update(0, candidate_id)
        decision = decide_materialization_retry(
            attempt_count=current.attempt_count,
            now=current_time,
            max_attempts=max_attempts,
            retry_base_seconds=retry_base_seconds,
            retry_max_seconds=retry_max_seconds,
        )
        cursor = self._execute(
            "UPDATE agent_memory_materialization_lease SET "
            f"status = {self._placeholder}, error_message = {self._placeholder}, "
            f"next_retry_at = {self._placeholder}, finished_at = {self._placeholder}, "
            f"update_time = {self._placeholder} "
            f"WHERE candidate_id = {self._placeholder} AND lease_token = {self._placeholder} "
            f"AND status = {self._placeholder}",
            (
                decision.status.value,
                error_message[:1000],
                self._format_datetime(decision.next_retry_at),
                self._format_datetime(current_time),
                self._format_datetime(current_time),
                candidate_id,
                lease_token,
                AgentMemoryMaterializationLeaseStatus.LEASED.value,
            ),
        )
        self._require_fenced_update(cursor.rowcount, candidate_id)
        self._commit()
        return self._required(candidate_id)

    def get_by_candidate_id(self, candidate_id: str) -> AgentMemoryMaterializationLease | None:
        """按候选 ID 查询租约快照。"""

        cursor = self._execute(
            f"SELECT {self._columns()} FROM agent_memory_materialization_lease "
            f"WHERE candidate_id = {self._placeholder}",
            (candidate_id,),
        )
        row = cursor.fetchone()
        return self._lease_from_row(row) if row else None

    def list_for_compensation(
        self,
        *,
        tenant_id: str | None = None,
        project_id: str | None = None,
        workspace_key: str | None = None,
        statuses: tuple[AgentMemoryMaterializationLeaseStatus, ...] | None = None,
        limit: int = 100,
    ) -> tuple[AgentMemoryMaterializationLease, ...]:
        """查询管理员补偿台需要展示的失败/DLQ lease。

        SQL store 是生产多实例部署的主要形态，因此这里把范围过滤下沉到数据库：
        - `status IN (...)` 默认只查 failed/dead_letter，减少不必要扫描；
        - tenant/project/workspace 条件会成为后续权限数据范围的承接点；
        - 结果按 `update_time DESC` 排序，方便运维优先处理最近失败；
        - `LIMIT` 使用经过裁剪的整数拼接，而不是直接使用用户输入，避免 SQL 注入风险。
        """

        selected_statuses = tuple(statuses or _default_compensation_statuses())
        safe_limit = max(1, min(int(limit), 500))
        status_placeholders = ",".join([self._placeholder] * len(selected_statuses))
        where_parts = [f"status IN ({status_placeholders})"]
        params: list[Any] = [status.value for status in selected_statuses]
        if tenant_id and str(tenant_id).strip():
            where_parts.append(f"tenant_id = {self._placeholder}")
            params.append(str(tenant_id).strip())
        if project_id and str(project_id).strip():
            where_parts.append(f"project_id = {self._placeholder}")
            params.append(str(project_id).strip())
        if workspace_key and str(workspace_key).strip():
            where_parts.append(f"workspace_key = {self._placeholder}")
            params.append(str(workspace_key).strip())
        cursor = self._execute(
            f"SELECT {self._columns()} FROM agent_memory_materialization_lease "
            f"WHERE {' AND '.join(where_parts)} "
            f"ORDER BY update_time DESC LIMIT {safe_limit}",
            tuple(params),
        )
        return tuple(self._lease_from_row(row) for row in cursor.fetchall())

    def schedule_retry(
        self,
        *,
        candidate_id: str,
        operator_id: str,
        reason: str,
        next_retry_at: datetime,
        now: datetime | None = None,
    ) -> AgentMemoryMaterializationLease:
        """把 failed/dead_letter lease 重新安排到下一次可领取时间。

        这里先读取当前状态再做条件 UPDATE，是为了同时兼顾清晰错误提示与并发安全：
        - 当前状态不是 failed/dead_letter 时，直接给出业务冲突；
        - 真正写回时仍带 `status IN (failed, dead_letter)` 条件，如果状态在读写之间被其他管理员或 worker 改变，
          UPDATE 将不会命中，从而避免覆盖并发结果；
        - 不修改 `attempt_count` 和 `lease_token`。下一轮 Runner 领取时会生成新 token 并增加尝试次数。
        """

        current_time = _utc(now)
        scheduled_time = _utc(next_retry_at)
        _validate_compensation_operator(operator_id=operator_id, reason=reason)
        current = self._required(candidate_id)
        _require_requeueable_status(current)
        cursor = self._execute(
            "UPDATE agent_memory_materialization_lease SET "
            f"status = {self._placeholder}, next_retry_at = {self._placeholder}, "
            f"message = {self._placeholder}, update_time = {self._placeholder} "
            f"WHERE candidate_id = {self._placeholder} "
            f"AND status IN ({self._placeholder},{self._placeholder})",
            (
                AgentMemoryMaterializationLeaseStatus.FAILED.value,
                self._format_datetime(scheduled_time),
                _admin_requeue_message(operator_id=operator_id, reason=reason),
                self._format_datetime(current_time),
                candidate_id,
                AgentMemoryMaterializationLeaseStatus.FAILED.value,
                AgentMemoryMaterializationLeaseStatus.DEAD_LETTER.value,
            ),
        )
        if cursor.rowcount == 0:
            raise RuntimeError(f"长期记忆物化补偿重排失败，lease 状态可能已变化: {candidate_id}")
        self._commit()
        return self._required(candidate_id)

    def _try_reacquire(
        self,
        *,
        candidate_id: str,
        tenant_id: str,
        project_id: str,
        workspace_key: str,
        memory_namespace: str,
        worker_id: str,
        lease_token: str,
        leased_until: datetime,
        now: datetime,
    ) -> bool:
        """尝试接管失败租约或过期租约。

        条件表达式是并发安全的关键：
        - `succeeded` 和 `dead_letter` 永不自动重新领取；
        - `leased` 只有在 `leased_until <= now` 时才能接管；
        - `failed` 只有在 `next_retry_at <= now` 时才能接管。
        """

        cursor = self._execute(
            "UPDATE agent_memory_materialization_lease SET "
            f"tenant_id = {self._placeholder}, project_id = {self._placeholder}, "
            f"workspace_key = {self._placeholder}, memory_namespace = {self._placeholder}, "
            f"status = {self._placeholder}, attempt_count = attempt_count + 1, "
            f"worker_id = {self._placeholder}, lease_token = {self._placeholder}, "
            f"leased_until = {self._placeholder}, memory_id = {self._placeholder}, "
            f"outcome = {self._placeholder}, message = {self._placeholder}, "
            f"error_message = {self._placeholder}, next_retry_at = {self._placeholder}, "
            f"started_at = {self._placeholder}, "
            f"finished_at = {self._placeholder}, update_time = {self._placeholder} "
            f"WHERE candidate_id = {self._placeholder} AND status <> {self._placeholder} "
            f"AND status <> {self._placeholder} "
            f"AND (status <> {self._placeholder} OR leased_until IS NULL OR leased_until <= {self._placeholder}) "
            f"AND (status <> {self._placeholder} OR next_retry_at IS NULL OR next_retry_at <= {self._placeholder})",
            (
                tenant_id,
                project_id,
                workspace_key,
                memory_namespace,
                AgentMemoryMaterializationLeaseStatus.LEASED.value,
                worker_id,
                lease_token,
                self._format_datetime(leased_until),
                None,
                None,
                None,
                None,
                None,
                self._format_datetime(now),
                None,
                self._format_datetime(now),
                candidate_id,
                AgentMemoryMaterializationLeaseStatus.SUCCEEDED.value,
                AgentMemoryMaterializationLeaseStatus.DEAD_LETTER.value,
                AgentMemoryMaterializationLeaseStatus.LEASED.value,
                self._format_datetime(now),
                AgentMemoryMaterializationLeaseStatus.FAILED.value,
                self._format_datetime(now),
            ),
        )
        if cursor.rowcount:
            self._commit()
            return True
        return False

    def _execute(self, sql: str, params: tuple[Any, ...] = ()):
        """执行参数化 SQL，避免 candidateId、workerId 或错误摘要破坏语句。"""

        cursor = self._connection.cursor()
        cursor.execute(sql, params)
        return cursor

    def _commit(self) -> None:
        """按配置提交事务。"""

        if self._auto_commit:
            self._connection.commit()

    def _rollback(self) -> None:
        """发生并发 INSERT 冲突时回滚当前事务。"""

        self._connection.rollback()

    @staticmethod
    def _require_fenced_update(rowcount: int, candidate_id: str) -> None:
        """要求 token 条件 UPDATE 必须命中一行。"""

        if rowcount == 0:
            raise RuntimeError(f"长期记忆落成租约 fencing 校验失败，candidateId={candidate_id}")

    def _required(self, candidate_id: str) -> AgentMemoryMaterializationLease:
        """读取必然存在的租约。"""

        lease = self.get_by_candidate_id(candidate_id)
        if lease is None:
            raise KeyError(f"长期记忆落成租约不存在: {candidate_id}")
        return lease

    def _insert_sql(self) -> str:
        """构造首次领取 INSERT。"""

        placeholders = ",".join([self._placeholder] * len(self._columns().split(",")))
        return f"INSERT INTO agent_memory_materialization_lease ({self._columns()}) VALUES ({placeholders})"

    @staticmethod
    def _columns() -> str:
        """返回 SQL 字段顺序。"""

        return LEASE_COLUMNS

    def _insert_params(self, lease: AgentMemoryMaterializationLease) -> tuple[Any, ...]:
        """把领域对象转换为 SQL 参数。"""

        return (
            lease.lease_id,
            lease.candidate_id,
            lease.tenant_id,
            lease.project_id,
            lease.workspace_key,
            lease.memory_namespace,
            lease.status.value,
            lease.attempt_count,
            lease.worker_id,
            lease.lease_token,
            self._format_datetime(lease.leased_until),
            self._format_datetime(lease.next_retry_at),
            lease.memory_id,
            lease.outcome,
            lease.message,
            lease.error_message,
            self._format_datetime(lease.started_at),
            self._format_datetime(lease.finished_at),
            self._format_datetime(lease.updated_at),
        )

    @staticmethod
    def _lease_from_row(row: Any) -> AgentMemoryMaterializationLease:
        """把 SQL 行还原为租约对象。"""

        return lease_from_row(row)

    @staticmethod
    def _format_datetime(value: datetime | None) -> str | None:
        """转换为 MySQL `DATETIME(3)` 友好的 UTC 字符串。"""

        if value is None:
            return None
        return _utc(value).strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]

    @staticmethod
    def _parse_datetime(value: str | datetime | None) -> datetime | None:
        """解析 SQLite/MySQL 返回的时间并统一为 UTC aware datetime。"""

        if value is None:
            return None
        parsed = value if isinstance(value, datetime) else datetime.fromisoformat(value)
        return parsed.replace(tzinfo=timezone.utc) if parsed.tzinfo is None else parsed.astimezone(timezone.utc)

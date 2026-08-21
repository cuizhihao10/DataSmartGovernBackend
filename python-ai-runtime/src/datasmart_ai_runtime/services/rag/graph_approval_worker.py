"""Kafka 图事实审批摄取 worker。

本模块把 ``permission-admin -> outbox -> Kafka -> Python -> Neo4j`` 串成真正可运行的异步边界。
它不把 Kafka 消息当成授权凭证，而是逐条执行：解析事件、回查 permission-admin、加载 MinIO 事实包、
校验指纹与范围、调用受控 Neo4j 摄取器、落 PostgreSQL receipt，最后才提交 Kafka offset。

设计约束：
* 默认不启动；只有显式设置 ``DATASMART_GRAPH_FACT_WORKER_ENABLED=true`` 才会创建线程；
* receipt 的 ``event_id`` 是唯一幂等键，重复投递只读取已成功结果，不重复写图；
* 失败只保存稳定错误码和截断摘要，不保存实体正文、SQL、日志正文、凭据或完整堆栈；
* MinIO/S3 只通过 ``s3://bucket/key`` 受控 URI 读取，禁止把临时文件 URI当成生产事实包。
"""

from __future__ import annotations

import json
import os
import threading
import time
from dataclasses import dataclass
from typing import Any, Callable, Mapping, Protocol
from urllib.request import Request, urlopen

from datasmart_ai_runtime.services.rag.graph_approval_consumer import (
    GraphFactApprovalConsumer,
    GraphFactApprovalConsumerError,
)
from datasmart_ai_runtime.services.rag.graph_ingestion import load_graph_fact_documents_bytes


GRAPH_FACT_APPROVAL_TOPIC = "datasmart.graph.facts.approved.v1"
GRAPH_FACT_APPROVAL_DLT_SUFFIX = ".dlt"


class GraphFactWorkerError(RuntimeError):
    """worker 无法安全处理消息时抛出的稳定异常。"""


class GraphFactApprovalReceiptStore(Protocol):
    """图事实摄取 receipt 的最小持久化协议。"""

    def get(self, event_id: str) -> Mapping[str, Any] | None:
        """读取同一事件已有 receipt。"""

    def begin(self, event: Mapping[str, Any], *, topic: str, partition: int, offset: int) -> None:
        """记录 PROCESSING 状态。"""

    def succeed(self, event_id: str, result: Mapping[str, Any]) -> None:
        """记录成功结果。"""

    def fail(self, event_id: str, *, status: str, error_code: str, error_summary: str) -> None:
        """记录失败、重试或 DEAD 状态。"""


class GraphFactBundleBytesLoader(Protocol):
    """从稳定事实包 URI 加载字节的协议。"""

    def __call__(self, uri: str) -> bytes:
        """读取对象正文；实现必须拒绝 file/http 等非受控 scheme。"""


class PermissionAdminGraphFactEvaluator(Protocol):
    """调用 permission-admin evaluate API 的协议。"""

    def __call__(self, event: Mapping[str, Any]) -> Mapping[str, Any]:
        """按双主体、范围、委托、命令和策略版本回查审批事实。"""


class KafkaConsumerLike(Protocol):
    """kafka-python consumer 的最小可替换接口。"""

    def __iter__(self):
        """迭代消息。"""

    def commit(self) -> None:
        """提交当前 offset。"""

    def close(self) -> None:
        """关闭 consumer。"""


class KafkaProducerLike(Protocol):
    """kafka-python producer 的最小可替换接口。"""

    def send(self, topic: str, *, key: bytes | None, value: bytes) -> Any:
        """发送 DLQ 消息。"""

    def flush(self, timeout: float | None = None) -> None:
        """等待发送完成。"""

    def close(self) -> None:
        """关闭 producer。"""


@dataclass(frozen=True)
class GraphFactApprovalWorkerSettings:
    """worker 配置；所有值都来自部署环境，不把密钥写入代码。"""

    enabled: bool = False
    bootstrap_servers: str = "kafka:29092"
    topic: str = GRAPH_FACT_APPROVAL_TOPIC
    group_id: str = "datasmart-graph-fact-ingestion"
    dlq_topic: str = "datasmart.graph.facts.approved.v1.dlt"
    poll_timeout_ms: int = 1000
    max_attempts: int = 3
    retry_backoff_seconds: float = 1.0
    worker_id: str = "python-ai-runtime-graph-fact-worker"


def graph_fact_approval_worker_settings_from_env(
    environ: Mapping[str, str] | None = None,
) -> GraphFactApprovalWorkerSettings:
    """读取 worker 环境变量并限制重试、轮询和退避边界。"""

    source = environ or os.environ
    return GraphFactApprovalWorkerSettings(
        enabled=_truthy(source.get("DATASMART_GRAPH_FACT_WORKER_ENABLED")),
        bootstrap_servers=str(source.get("DATASMART_GRAPH_FACT_WORKER_BOOTSTRAP_SERVERS") or "kafka:29092"),
        topic=str(source.get("DATASMART_GRAPH_FACT_WORKER_TOPIC") or GRAPH_FACT_APPROVAL_TOPIC),
        group_id=str(source.get("DATASMART_GRAPH_FACT_WORKER_GROUP_ID") or "datasmart-graph-fact-ingestion"),
        dlq_topic=str(source.get("DATASMART_GRAPH_FACT_WORKER_DLT_TOPIC") or "")
        or str(source.get("DATASMART_GRAPH_FACT_WORKER_TOPIC") or GRAPH_FACT_APPROVAL_TOPIC) + GRAPH_FACT_APPROVAL_DLT_SUFFIX,
        poll_timeout_ms=_positive_int(source.get("DATASMART_GRAPH_FACT_WORKER_POLL_TIMEOUT_MS"), 1000, 100, 30000),
        max_attempts=_positive_int(source.get("DATASMART_GRAPH_FACT_WORKER_MAX_ATTEMPTS"), 3, 1, 10),
        retry_backoff_seconds=_positive_float(source.get("DATASMART_GRAPH_FACT_WORKER_RETRY_BACKOFF_SECONDS"), 1.0, 0.0, 60.0),
        worker_id=str(source.get("DATASMART_GRAPH_FACT_WORKER_ID") or "python-ai-runtime-graph-fact-worker"),
    )


class InMemoryGraphFactApprovalReceiptStore:
    """测试用 receipt store；生产必须替换成 PostgreSQL 实现。"""

    def __init__(self) -> None:
        self.records: dict[str, dict[str, Any]] = {}

    def get(self, event_id: str) -> Mapping[str, Any] | None:
        return self.records.get(event_id)

    def begin(self, event: Mapping[str, Any], *, topic: str, partition: int, offset: int) -> None:
        event_id = str(event["eventId"])
        current = self.records.get(event_id)
        if current and current.get("status") == "SUCCEEDED":
            return
        self.records[event_id] = {
            "eventId": event_id,
            "approvalFactId": str(event.get("approvalFactId") or ""),
            "fingerprint": str(event.get("factFingerprint") or ""),
            "status": "PROCESSING",
            "attemptCount": int(current.get("attemptCount", 0) if current else 0) + 1,
            "topic": topic,
            "partition": partition,
            "offset": offset,
        }

    def succeed(self, event_id: str, result: Mapping[str, Any]) -> None:
        # receipt 状态与领域摄取结果状态含义不同：前者表示 durable 消费已完成，后者可能是
        # ``INGESTED``。先复制领域结果，再强制写 receipt 状态，避免重复消息返回错误的状态语义。
        self.records.setdefault(event_id, {}).update({**dict(result), "status": "SUCCEEDED"})

    def fail(self, event_id: str, *, status: str, error_code: str, error_summary: str) -> None:
        self.records.setdefault(event_id, {}).update(
            {"status": status, "errorCode": error_code, "errorSummary": error_summary[:500]}
        )


class SqlGraphFactApprovalReceiptStore:
    """PostgreSQL/DB-API receipt store。

    连接由应用装配层创建，避免本领域类隐藏数据库连接池或凭据。SQL 只写控制面字段，事实正文
    永远留在 MinIO，图实体和关系也不进入 receipt 表。
    """

    def __init__(self, connection: Any, *, placeholder: str = "%s", auto_commit: bool = True) -> None:
        self._connection = connection
        self._placeholder = placeholder
        self._auto_commit = auto_commit

    def get(self, event_id: str) -> Mapping[str, Any] | None:
        cursor = self._execute(
            f"SELECT event_id, status, attempt_count, error_code, error_summary, entity_count, edge_count "
            f"FROM graph_fact_ingestion_receipt WHERE event_id = {self._placeholder}",
            (event_id,),
        )
        row = cursor.fetchone()
        if not row:
            return None
        values = dict(row) if hasattr(row, "keys") else dict(zip(
            ("event_id", "status", "attempt_count", "error_code", "error_summary", "entity_count", "edge_count"), row
        ))
        return {
            "eventId": values["event_id"],
            "status": values["status"],
            "attemptCount": int(values["attempt_count"] or 0),
            "errorCode": values.get("error_code"),
            "errorSummary": values.get("error_summary"),
            "entityCount": int(values.get("entity_count") or 0),
            "edgeCount": int(values.get("edge_count") or 0),
        }

    def begin(self, event: Mapping[str, Any], *, topic: str, partition: int, offset: int) -> None:
        """用 PostgreSQL upsert 递增 attempt_count，兼容 Kafka 至少一次重投。"""

        sql = f"""
            INSERT INTO graph_fact_ingestion_receipt
                (event_id, approval_fact_id, tenant_id, application_id, project_id, fingerprint,
                 status, attempt_count, topic, partition_no, offset_no, created_at, updated_at)
            VALUES ({self._placeholder}, {self._placeholder}, {self._placeholder}, {self._placeholder}, {self._placeholder},
                    {self._placeholder}, 'PROCESSING', 1, {self._placeholder}, {self._placeholder}, {self._placeholder}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (event_id) DO UPDATE SET
                status = CASE WHEN graph_fact_ingestion_receipt.status = 'SUCCEEDED'
                              THEN graph_fact_ingestion_receipt.status ELSE 'PROCESSING' END,
                attempt_count = graph_fact_ingestion_receipt.attempt_count + 1,
                topic = EXCLUDED.topic, partition_no = EXCLUDED.partition_no, offset_no = EXCLUDED.offset_no,
                updated_at = CURRENT_TIMESTAMP
        """
        self._execute(sql, (
            str(event["eventId"]), str(event.get("approvalFactId") or ""), str(event.get("tenantId") or ""),
            str(event.get("applicationId") or ""), str(event.get("projectId") or ""), str(event.get("factFingerprint") or ""),
            topic, partition, offset,
        ))
        self._commit()

    def succeed(self, event_id: str, result: Mapping[str, Any]) -> None:
        self._execute(
            f"UPDATE graph_fact_ingestion_receipt SET status='SUCCEEDED', entity_count={self._placeholder}, "
            f"edge_count={self._placeholder}, error_code=NULL, error_summary=NULL, finished_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP "
            f"WHERE event_id={self._placeholder}",
            (int(result.get("entityCount") or 0), int(result.get("edgeCount") or 0), event_id),
        )
        self._commit()

    def fail(self, event_id: str, *, status: str, error_code: str, error_summary: str) -> None:
        if status not in {"FAILED", "DEAD"}:
            raise ValueError("图事实 receipt status 只能是 FAILED 或 DEAD")
        self._execute(
            f"UPDATE graph_fact_ingestion_receipt SET status={self._placeholder}, error_code={self._placeholder}, "
            f"error_summary={self._placeholder}, finished_at={self._placeholder}, updated_at=CURRENT_TIMESTAMP WHERE event_id={self._placeholder}",
            (status, error_code[:96], error_summary[:500], None if status == "FAILED" else _now_sql(), event_id),
        )
        self._commit()

    def _execute(self, sql: str, params: tuple[Any, ...] = ()) -> Any:
        cursor = self._connection.cursor()
        cursor.execute(sql, params)
        return cursor

    def _commit(self) -> None:
        if self._auto_commit:
            self._connection.commit()


def build_graph_fact_approval_receipt_store_from_env() -> GraphFactApprovalReceiptStore:
    """根据 worker 配置构建 durable receipt store。

    worker 显式启用时默认使用 PostgreSQL；若未提供 DSN 或 psycopg 依赖，直接 fail-closed，不能偷偷
    回退到内存 receipt。只有 worker 未启用的本地单测才使用 in-memory 实现。
    """

    settings = graph_fact_approval_worker_settings_from_env()
    if not settings.enabled:
        return InMemoryGraphFactApprovalReceiptStore()
    store_type = str(os.getenv("DATASMART_GRAPH_FACT_RECEIPT_STORE") or "postgresql").strip().lower()
    if store_type in {"memory", "in-memory"}:
        if _truthy(os.getenv("DATASMART_GRAPH_FACT_RECEIPT_ALLOW_MEMORY")):
            return InMemoryGraphFactApprovalReceiptStore()
        raise GraphFactWorkerError("GRAPH_FACT_RECEIPT_MEMORY_FORBIDDEN")
    dsn = str(
        os.getenv("DATASMART_GRAPH_FACT_RECEIPT_POSTGRESQL_DSN")
        or os.getenv("DATASMART_AI_MEMORY_POSTGRESQL_DSN")
        or ""
    ).strip()
    if not dsn:
        raise GraphFactWorkerError("GRAPH_FACT_RECEIPT_POSTGRESQL_DSN_MISSING")
    try:
        import psycopg  # type: ignore[import-not-found]
        from psycopg.rows import dict_row  # type: ignore[import-not-found]
        connection = psycopg.connect(dsn, connect_timeout=3, row_factory=dict_row)
    except Exception as exc:
        raise GraphFactWorkerError("GRAPH_FACT_RECEIPT_DATABASE_UNAVAILABLE") from exc
    if _truthy(os.getenv("DATASMART_GRAPH_FACT_RECEIPT_SCHEMA_BOOTSTRAP")):
        _ensure_receipt_schema(connection)
    return SqlGraphFactApprovalReceiptStore(connection)


def _ensure_receipt_schema(connection: Any) -> None:
    """执行幂等的最小 receipt 表 bootstrap。

    正式部署仍应将同样 DDL 纳入版本化数据库迁移；该开关仅用于已有 PostgreSQL 数据卷的本地升级，
    解决 init 目录只在首次创建 volume 执行、代码新增表却无法自动出现的问题。
    """

    connection.execute(
        """
        CREATE TABLE IF NOT EXISTS graph_fact_ingestion_receipt (
            id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
            event_id VARCHAR(256) NOT NULL UNIQUE,
            approval_fact_id VARCHAR(160) NOT NULL,
            tenant_id VARCHAR(128) NOT NULL,
            application_id VARCHAR(128) NOT NULL,
            project_id VARCHAR(128) NOT NULL,
            fingerprint VARCHAR(128) NOT NULL,
            status VARCHAR(32) NOT NULL,
            attempt_count INTEGER NOT NULL DEFAULT 0,
            topic VARCHAR(256), partition_no INTEGER, offset_no BIGINT,
            entity_count INTEGER NOT NULL DEFAULT 0, edge_count INTEGER NOT NULL DEFAULT 0,
            error_code VARCHAR(96), error_summary VARCHAR(500),
            created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
            finished_at TIMESTAMPTZ
        )
        """
    )
    connection.commit()


def _now_sql() -> str:
    """返回 UTC 时间字符串，供兼容 DB-API 驱动写入 finished_at。"""

    from datetime import datetime, timezone

    return datetime.now(timezone.utc).isoformat()


class MinioGraphFactBundleLoader:
    """MinIO/S3-compatible事实包读取器。

    只接受 ``s3://bucket/key``，并要求 bucket 在启动配置中固定。这样 approval URI 不能借机访问
    任意本地文件、任意 HTTP 地址或其他租户 bucket；对象正文只短暂留在 Python 内存中。
    """

    def __init__(self, *, configured_bucket: str, s3_client: Any | None = None) -> None:
        self._configured_bucket = configured_bucket.strip()
        self._s3_client = s3_client

    def __call__(self, uri: str) -> bytes:
        from urllib.parse import urlsplit

        parsed = urlsplit(str(uri).strip())
        if parsed.scheme != "s3" or parsed.netloc != self._configured_bucket or not parsed.path.strip("/"):
            raise GraphFactWorkerError("FACT_BUNDLE_URI_NOT_ALLOWED")
        client = self._s3_client or self._build_client()
        try:
            response = client.get_object(Bucket=self._configured_bucket, Key=parsed.path.lstrip("/"))
            body = response["Body"]
            return bytes(body.read())
        except Exception as exc:  # provider error must become a low-sensitive worker code
            raise GraphFactWorkerError("FACT_BUNDLE_LOAD_FAILED") from exc

    @staticmethod
    def _build_client() -> Any:
        try:
            import boto3  # type: ignore[import-not-found]
        except ImportError as exc:
            raise GraphFactWorkerError("OBJECT_STORE_DEPENDENCY_MISSING") from exc
        return boto3.client(
            "s3",
            endpoint_url=os.getenv("DATASMART_GRAPH_FACT_MINIO_ENDPOINT") or os.getenv("DATASMART_RAG_ARTIFACT_STORE_ENDPOINT"),
            aws_access_key_id=os.getenv("DATASMART_GRAPH_FACT_MINIO_ACCESS_KEY") or os.getenv("DATASMART_MINIO_ACCESS_KEY"),
            aws_secret_access_key=os.getenv("DATASMART_GRAPH_FACT_MINIO_SECRET_KEY") or os.getenv("DATASMART_MINIO_SECRET_KEY"),
            region_name=os.getenv("DATASMART_GRAPH_FACT_MINIO_REGION") or "us-east-1",
        )


class MinioGraphFactDocumentLoader:
    """把 MinIO 字节加载器适配成审批 consumer 所需的 ``Iterable[RagDocument]``。"""

    def __init__(self, bytes_loader: MinioGraphFactBundleLoader) -> None:
        self._bytes_loader = bytes_loader

    def __call__(self, uri: str):
        """只在内存中解析事实包，不创建临时文件。"""

        return load_graph_fact_documents_bytes(self._bytes_loader(uri))


class HttpPermissionAdminGraphFactEvaluator:
    """permission-admin evaluate HTTP adapter。

    每次事件都完整发送双主体和运行绑定字段。服务 token 只存在于请求头，不会进入错误摘要、receipt
    或诊断。HTTP envelope 兼容项目统一 ``PlatformApiResponse``，并拒绝非 200/非对象响应。
    """

    def __init__(self, *, base_url: str, service_token: str, timeout_seconds: float = 5.0) -> None:
        self._url = base_url.rstrip("/") + "/permissions/agent/graph-facts/evaluate"
        self._service_token = service_token.strip()
        self._timeout_seconds = timeout_seconds

    def __call__(self, event: Mapping[str, Any]) -> Mapping[str, Any]:
        body = {
            "approvalFactId": event.get("approvalFactId"),
            "tenantId": _long_or_text(event.get("tenantId")),
            "applicationId": _long_or_text(event.get("applicationId")),
            "projectId": _long_or_text(event.get("projectId")),
            "userId": event.get("userId"),
            "actorId": event.get("actorId"),
            "agentId": event.get("agentId"),
            "sessionId": event.get("sessionId"),
            "runId": event.get("runId"),
            "delegationId": event.get("delegationId"),
            "commandId": event.get("commandId"),
            "requestedPolicyVersion": event.get("policyVersion"),
        }
        request = Request(
            self._url,
            data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json",
                "X-DataSmart-Source-Service": "python-ai-runtime",
                "X-DataSmart-Internal-Service-Token": self._service_token,
                "X-DataSmart-Trace-Id": str(event.get("runId") or event.get("eventId") or "graph-fact-worker"),
            },
            method="POST",
        )
        try:
            with urlopen(request, timeout=self._timeout_seconds) as response:  # noqa: S310 - URL 来自受控部署配置
                payload = json.loads(response.read().decode("utf-8"))
        except Exception as exc:
            raise GraphFactWorkerError("PERMISSION_ADMIN_EVALUATE_UNAVAILABLE") from exc
        if not isinstance(payload, Mapping):
            raise GraphFactWorkerError("PERMISSION_ADMIN_EVALUATE_INVALID_RESPONSE")
        data = payload.get("data", payload)
        return data if isinstance(data, Mapping) else {"approved": False, "decision": "INVALID_RESPONSE"}


def _long_or_text(value: Any) -> Any:
    """将事件范围转换为 Java Long 可接受的 JSON 数字；非数字历史租户 ID保持文本兼容。"""

    try:
        return int(str(value))
    except (TypeError, ValueError):
        return value


class GraphFactApprovalWorker:
    """执行 Kafka 图事实审批事件的手动 offset consumer。"""

    def __init__(
        self,
        *,
        consumer: KafkaConsumerLike,
        producer: KafkaProducerLike | None,
        approval_consumer: GraphFactApprovalConsumer,
        provider: Any,
        receipt_store: GraphFactApprovalReceiptStore,
        settings: GraphFactApprovalWorkerSettings,
    ) -> None:
        self._consumer = consumer
        self._producer = producer
        self._approval_consumer = approval_consumer
        self._provider = provider
        self._receipt_store = receipt_store
        self._settings = settings
        self._stop_event = threading.Event()
        self._thread: threading.Thread | None = None
        self._processed = 0
        self._failed = 0

    def start(self) -> None:
        """显式启动后台线程；重复 start 幂等。"""

        if not self._settings.enabled or (self._thread and self._thread.is_alive()):
            return
        self._stop_event.clear()
        self._thread = threading.Thread(target=self.run_forever, name="graph-fact-approval-worker", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        """请求停止并关闭 Kafka consumer/producer。"""

        self._stop_event.set()
        if self._thread:
            self._thread.join(timeout=5)
        self._safe_close(self._consumer)
        self._safe_close(self._producer)

    def run_forever(self) -> None:
        """持续消费消息；只有 Kafka 客户端级故障才结束线程。

        单条合法事件的审批、MinIO、Neo4j 或 receipt 异常由 ``process_message`` 在同一
        Kafka offset 上完成有界重试，不能抛回这里后期待 consumer 自动重新投递。无法解析的
        毒消息则由 ``process_message`` 写入不含原正文的 DLT 后提交 offset，避免永久阻塞分区。
        """

        try:
            for message in self._consumer:
                if self._stop_event.is_set():
                    break
                self.process_message(message)
        except Exception:
            # Kafka client/network fatal error 不能伪装成健康；线程退出后诊断接口仍会显示 stopped。
            self._failed += 1

    def process_message(self, message: Any) -> Mapping[str, Any]:
        """在同一 Kafka 消息上执行有界重试，并且只在成功或 DEAD 后提交 offset。

        ``kafka-python`` 的 iterator 读过消息后会继续推进本地 position；仅仅“不 commit 并抛异常”
        不会让当前进程自动再次处理同一消息，随后提交更高 offset 还可能把失败事件一起跳过。因此本方法
        必须在返回前完成全部尝试：每轮先把 attempt 写入 PostgreSQL receipt，再重新回查 permission-admin、
        读取 MinIO 并摄取 Neo4j；短暂故障在退避后重试，耗尽后写低敏 DLT 并提交当前 offset。

        对连 JSON 对象或 eventId 都无法提供的毒消息，不能创建缺少租户/应用/项目的伪 receipt。本方法使用
        topic/partition/offset 生成低敏定位符，只把稳定错误码写入 DLT，然后提交该毒消息以保护分区可用性。
        """

        topic = str(getattr(message, "topic", self._settings.topic))
        partition = int(getattr(message, "partition", -1))
        offset = int(getattr(message, "offset", -1))
        try:
            event = _decode_message_value(message)
            event_id = str(event.get("eventId") or "").strip()
            if not event_id:
                raise GraphFactWorkerError("EVENT_ID_MISSING")
        except Exception as exc:
            self._failed += 1
            error_code = _stable_error_code(exc)
            invalid_event = {"eventId": f"graph-fact-invalid:{topic}:{partition}:{offset}"}
            self._send_dlq(invalid_event, error_code)
            self._consumer.commit()
            return {"status": "DEAD", "eventId": invalid_event["eventId"], "errorCode": error_code}

        previous = self._receipt_store.get(event_id)
        if previous and previous.get("status") == "SUCCEEDED":
            self._consumer.commit()
            return dict(previous)
        attempts = int(previous.get("attemptCount", 0) if previous else 0)
        while attempts < self._settings.max_attempts:
            self._receipt_store.begin(event, topic=topic, partition=partition, offset=offset)
            attempts += 1
            try:
                result = self._approval_consumer.handle(event, self._provider)
                self._receipt_store.succeed(event_id, result.to_dict())
                self._consumer.commit()
                self._processed += 1
                return result.to_dict()
            except Exception as exc:
                self._failed += 1
                error_code = _stable_error_code(exc)
                if attempts >= self._settings.max_attempts:
                    self._receipt_store.fail(event_id, status="DEAD", error_code=error_code, error_summary=str(exc))
                    self._send_dlq(event, error_code)
                    self._consumer.commit()
                    return {"status": "DEAD", "eventId": event_id, "errorCode": error_code}
                self._receipt_store.fail(event_id, status="FAILED", error_code=error_code, error_summary=str(exc))
                # Event.wait 同时承担退避和可中断停止；关闭期间不提交 offset，重启后仍能重新消费。
                if self._stop_event.wait(self._settings.retry_backoff_seconds):
                    raise GraphFactWorkerError("GRAPH_FACT_WORKER_STOPPING") from exc

        raise GraphFactWorkerError("GRAPH_FACT_RETRY_STATE_INVALID")

    def diagnostics(self) -> dict[str, Any]:
        """返回不含正文、URI、凭据和图实体名称的运行诊断。"""

        return {
            "enabled": self._settings.enabled,
            "topic": self._settings.topic,
            "groupId": self._settings.group_id,
            "processedCount": self._processed,
            "failedCount": self._failed,
            "running": bool(self._thread and self._thread.is_alive()),
        }

    def _send_dlq(self, event: Mapping[str, Any], error_code: str) -> None:
        """向 DLT 发送低敏错误 envelope；DLT 不携带事实正文。"""

        if self._producer is None:
            return
        payload = json.dumps(
            {
                "schemaVersion": "datasmart.graph-facts-approved-dlt.v1",
                "eventId": str(event.get("eventId") or ""),
                "approvalFactId": str(event.get("approvalFactId") or ""),
                "factFingerprint": str(event.get("factFingerprint") or ""),
                "errorCode": error_code,
                "payloadPolicy": "GRAPH_FACT_DLT_NO_FACT_CONTENT_OR_SECRET",
            },
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")
        self._producer.send(
            self._settings.dlq_topic,
            key=str(event.get("eventId") or "").encode("utf-8"),
            value=payload,
        )
        self._producer.flush(timeout=5)

    @staticmethod
    def _safe_close(client: Any | None) -> None:
        close = getattr(client, "close", None)
        if callable(close):
            close()


def build_kafka_graph_fact_approval_worker(
    *,
    approval_consumer: GraphFactApprovalConsumer,
    provider: Any,
    receipt_store: GraphFactApprovalReceiptStore,
    settings: GraphFactApprovalWorkerSettings | None = None,
) -> GraphFactApprovalWorker:
    """按环境配置构建真实 kafka-python worker；依赖缺失时显式失败。"""

    resolved = settings or graph_fact_approval_worker_settings_from_env()
    try:
        from kafka import KafkaConsumer, KafkaProducer  # type: ignore[import-not-found]
    except ImportError as exc:
        raise GraphFactWorkerError("KAFKA_DEPENDENCY_MISSING") from exc
    consumer = KafkaConsumer(
        resolved.topic,
        bootstrap_servers=resolved.bootstrap_servers.split(","),
        group_id=resolved.group_id,
        enable_auto_commit=False,
        auto_offset_reset="earliest",
        value_deserializer=lambda value: value,
        # kafka-python 中 0 不是“无限等待”，而是让 iterator 在第一次 poll 后立即结束。
        # 后台线程会因此安静退出并显示 running=false、failedCount=0，造成最危险的“看起来健康但
        # 从未消费”的假闭环。-1 才表示持续等待，直到 stop() 关闭 consumer 或客户端发生致命异常。
        consumer_timeout_ms=-1,
    )
    producer = KafkaProducer(bootstrap_servers=resolved.bootstrap_servers.split(","))
    return GraphFactApprovalWorker(
        consumer=consumer,
        producer=producer,
        approval_consumer=approval_consumer,
        provider=provider,
        receipt_store=receipt_store,
        settings=resolved,
    )


def _decode_message_value(message: Any) -> dict[str, Any]:
    """解析 Kafka value 并拒绝非对象 JSON。"""

    value = getattr(message, "value", message)
    if isinstance(value, Mapping):
        return dict(value)
    if isinstance(value, bytes):
        value = value.decode("utf-8")
    try:
        parsed = json.loads(str(value))
    except (TypeError, ValueError, json.JSONDecodeError) as exc:
        raise GraphFactWorkerError("EVENT_JSON_INVALID") from exc
    if not isinstance(parsed, dict):
        raise GraphFactWorkerError("EVENT_JSON_OBJECT_REQUIRED")
    return parsed


def _stable_error_code(error: Exception) -> str:
    """把内部异常映射为低敏稳定错误码。"""

    if isinstance(error, GraphFactApprovalConsumerError):
        return "GRAPH_FACT_VALIDATION_FAILED"
    message = str(error)
    if message.startswith("EVENT_"):
        return message.split(":", 1)[0]
    if message.startswith("FACT_BUNDLE_") or message.startswith("OBJECT_STORE_"):
        return message.split(":", 1)[0]
    if message.startswith("PERMISSION_ADMIN_") or message.startswith("GRAPH_FACT_RECEIPT_"):
        return message.split(":", 1)[0]
    return "GRAPH_FACT_PROCESSING_FAILED"


def _truthy(value: Any) -> bool:
    return str(value or "").strip().lower() in {"1", "true", "yes", "on"}


def _positive_int(value: Any, default: int, minimum: int, maximum: int) -> int:
    try:
        return max(minimum, min(maximum, int(value))) if value is not None else default
    except (TypeError, ValueError):
        return default


def _positive_float(value: Any, default: float, minimum: float, maximum: float) -> float:
    try:
        return max(minimum, min(maximum, float(value))) if value is not None else default
    except (TypeError, ValueError):
        return default


__all__ = [
    "GRAPH_FACT_APPROVAL_TOPIC",
    "GraphFactApprovalWorker",
    "GraphFactApprovalWorkerSettings",
    "GraphFactWorkerError",
    "InMemoryGraphFactApprovalReceiptStore",
    "SqlGraphFactApprovalReceiptStore",
    "build_graph_fact_approval_receipt_store_from_env",
    "MinioGraphFactBundleLoader",
    "MinioGraphFactDocumentLoader",
    "HttpPermissionAdminGraphFactEvaluator",
    "build_kafka_graph_fact_approval_worker",
    "graph_fact_approval_worker_settings_from_env",
]

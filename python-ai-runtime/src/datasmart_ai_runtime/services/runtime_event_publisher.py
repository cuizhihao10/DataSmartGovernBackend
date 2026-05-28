"""Agent Runtime 事件发布器抽象。

本模块解决的是“Python AI Runtime 产生的结构化运行时事件，如何进入平台级异步事件总线”的问题。
前面阶段已经让事件具备了三类本地/短期能力：
- `RuntimeEventStore`：服务端短期 replay；
- `RuntimeEventLivePushHub`：面向 WebSocket 订阅者的实时推送；
- `RuntimeEventOutboxStore`：多实例场景下的待发送 live frame 缓冲。

这些能力主要服务“当前用户能不能看到事件”。但商业化平台还需要另一条链路：
- Java `agent-runtime` 控制面需要接收 Python 侧执行进度，更新 run/session 状态；
- `observability` 需要消费事件，生成指标、告警、链路排障视图；
- 审计/合规模块需要保存关键 AI 决策、工具规划、审批等待等节点；
- 后续多 Agent 协作、异步补偿、失败重试也需要可订阅的事件总线。

因此这里把“发布到 Kafka”做成一个小而稳定的端口。默认实现是 no-op，保证本地学习和单元测试不需要 Kafka；
生产环境显式配置后，再由 Kafka 适配器把事件写入 topic。这样既符合项目“Kafka 作为 Java/Python 异步解耦骨干”的约束，
也避免把 Python Runtime 绑定死在某一个 Kafka 客户端库上。
"""

from __future__ import annotations

import json
from dataclasses import asdict
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Protocol

from datasmart_ai_runtime.domain.events import AgentRuntimeEvent


class RuntimeEventPublisher(Protocol):
    """运行时事件发布协议。

    这个协议刻意只暴露 `publish(...)` 一个动作，原因是 API 层只关心“把这一批事件交给异步总线”，
    不应该关心底层是 kafka-python、confluent-kafka、Redpanda SDK，还是未来的平台消息网关。

    返回值表示本次成功交给底层 producer 的事件数量。这里的“成功”不是 Kafka broker 已经完成持久化确认，
    而是当前适配器已经调用 producer 的发送方法。真正的 broker ack、重试、dead-letter、事务消息等能力，
    后续可以在适配器内部或独立 worker 中继续增强，而不改变 API 层调用方式。
    """

    def publish(self, events: tuple[AgentRuntimeEvent, ...]) -> int:
        """发布一批 Agent runtime events。"""


class NoopRuntimeEventPublisher:
    """空事件发布器。

    本地开发、单元测试、离线演示默认使用该实现。它不会丢掉业务主流程，因为 runtime event publisher
    在当前阶段是“异步旁路能力”：即使不启用 Kafka，HTTP 响应、WebSocket live push、短期 replay 仍然可用。

    生产环境如果要求审计和跨服务状态同步，就不能长期停留在 no-op，应显式配置 Kafka publisher。
    """

    def publish(self, events: tuple[AgentRuntimeEvent, ...]) -> int:
        """忽略事件并返回 0。

        返回 0 能让诊断或测试清晰地区分“没有启用发布器”和“启用了发布器并处理了 N 条事件”。
        """

        return 0


class KafkaRuntimeEventPublisher:
    """Kafka 运行时事件发布器。

    设计要点：
    - 不直接要求某一个 Kafka 客户端类型，只要求 producer 具备 `send(...)` 或 `produce(...)` 方法；
    - 发送 payload 使用稳定 JSON 字符串，便于 Java/Spring Kafka、日志系统、审计服务跨语言消费；
    - partition key 优先选择 runId/sessionId/requestId/tenantId，尽量让同一运行链路落到同一分区，降低乱序风险；
    - 默认不逐条 flush，避免同步 HTTP 请求被 Kafka broker 延迟放大；需要强确认时后续应改为异步 outbox/worker。
    """

    def __init__(
        self,
        producer: Any,
        topic: str = "datasmart.agent-runtime.events",
        source: str = "python-ai-runtime",
        flush_on_publish: bool = False,
    ) -> None:
        """初始化 Kafka publisher。

        参数说明：
        - `producer`：真实 Kafka producer 或测试 fake，只要兼容 `send`/`produce` 方法即可；
        - `topic`：运行时事件 topic，建议按环境和租户规模规划分区数、保留时间与压缩策略；
        - `source`：事件来源服务名，方便 Java 消费端区分 Python Runtime、Java agent-runtime 或未来 worker；
        - `flush_on_publish`：是否每批事件后立即 flush。默认关闭是为了保护 HTTP 规划接口延迟。
        """

        self._producer = producer
        self._topic = topic
        self._source = source
        self._flush_on_publish = flush_on_publish

    @property
    def topic(self) -> str:
        """当前发布目标 topic，诊断接口会读取该字段展示部署配置。"""

        return self._topic

    def publish(self, events: tuple[AgentRuntimeEvent, ...]) -> int:
        """把事件批量发布到 Kafka。

        当前实现是一条 AgentRuntimeEvent 对应一条 Kafka message。这样做的优点是消费端可以按事件粒度重试、
        过滤、审计和告警；代价是事件很多时消息数量更高。后续如果出现高频 token streaming 事件，需要额外
        增加采样、批量压缩或分 topic 策略，而不是把所有细粒度流式 token 都塞进同一个审计 topic。
        """

        published = 0
        for event in events:
            key = self._partition_key(event)
            payload = self._serialize_event(event)
            self._send(key=key, value=payload)
            published += 1

        if published > 0 and self._flush_on_publish and hasattr(self._producer, "flush"):
            # 默认不 flush；只有显式配置时才同步等待，适合测试或少量高价值审计事件。
            self._producer.flush()
        return published

    def _send(self, key: str, value: str) -> None:
        """兼容常见 Python Kafka producer 发送方法。

        kafka-python 常用 `send(topic, key=..., value=...)`；
        confluent-kafka 常用 `produce(topic, key=..., value=...)`。
        这里支持两种形态，可以降低后续替换客户端库的成本。
        """

        if hasattr(self._producer, "send"):
            self._producer.send(self._topic, key=key, value=value)
            return
        if hasattr(self._producer, "produce"):
            self._producer.produce(self._topic, key=key, value=value)
            return
        raise TypeError("Kafka producer 必须提供 send(...) 或 produce(...) 方法")

    def _serialize_event(self, event: AgentRuntimeEvent) -> str:
        """序列化 AgentRuntimeEvent 为跨语言 JSON payload。

        字段命名使用 Java/Spring 服务更常见的 lowerCamelCase，避免 Java 消费端再做一次 Python snake_case 映射。
        `schemaVersion` 用于后续兼容演进：新增字段时消费端可以按版本做兼容处理，而不是隐式依赖当前 Python dataclass。
        """

        payload = {
            "schemaVersion": "agent-runtime-event.v1",
            "source": self._source,
            "publishedAt": datetime.now(timezone.utc).isoformat(),
            "eventType": event.event_type.value,
            "stage": event.stage,
            "message": event.message,
            "severity": event.severity.value,
            "tenantId": event.tenant_id,
            "projectId": event.project_id,
            "actorId": event.actor_id,
            "requestId": event.request_id,
            "runId": event.run_id,
            "sessionId": event.session_id,
            "sequence": event.sequence,
            "attributes": _json_safe(event.attributes),
            "createdAt": event.created_at.isoformat(),
        }
        return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))

    @staticmethod
    def _partition_key(event: AgentRuntimeEvent) -> str:
        """选择 Kafka partition key。

        顺序优先级按“最能代表一次 Agent 运行链路”的字段排列：
        - runId：最适合长任务和多步骤 Agent 执行；
        - sessionId：适合同一会话内多次请求；
        - requestId：适合同步 HTTP 规划请求；
        - tenantId：至少保证同一租户事件有一定局部性；
        - unknown：兜底值，避免 key 为 None 导致不同客户端行为不一致。
        """

        return event.run_id or event.session_id or event.request_id or event.tenant_id or "unknown"


def build_default_kafka_producer(
    bootstrap_servers: str,
    client_id: str = "datasmart-python-ai-runtime",
) -> Any:
    """创建默认 kafka-python producer。

    项目没有把 Kafka 客户端放进默认依赖，是为了保持 Python Runtime 核心能力轻量可测。
    只有当部署方显式配置 `DATASMART_AI_RUNTIME_EVENT_PUBLISHER=kafka` 时才会尝试导入 `kafka.KafkaProducer`。
    如果生产镜像缺少该依赖，会抛出清晰错误，提醒安装 `kafka-python` 或注入兼容 producer factory。
    """

    try:
        from kafka import KafkaProducer  # type: ignore
    except ImportError as exc:  # pragma: no cover - 仅在显式启用 Kafka 且缺少依赖时触发
        raise RuntimeError(
            "已配置 Python AI Runtime 使用 Kafka runtime event publisher，但当前环境未安装 kafka-python。"
            "请安装 kafka-python，或在测试/企业 SDK 场景下注入 kafka_producer_factory。"
        ) from exc

    servers = tuple(item.strip() for item in bootstrap_servers.split(",") if item.strip())
    return KafkaProducer(
        bootstrap_servers=servers or ("localhost:9092",),
        client_id=client_id,
        key_serializer=lambda value: value.encode("utf-8") if isinstance(value, str) else value,
        value_serializer=lambda value: value.encode("utf-8") if isinstance(value, str) else value,
    )


def _json_safe(value: Any) -> Any:
    """递归转换为 JSON 友好结构。

    attributes 往往会携带枚举、datetime、tuple 或 dataclass。Kafka payload 必须是跨语言可读 JSON，
    不能把 Python 对象 repr 写进消息体，否则 Java 消费端无法稳定反序列化。
    """

    if hasattr(value, "__dataclass_fields__"):
        return _json_safe(asdict(value))
    if isinstance(value, Enum):
        return value.value
    if isinstance(value, datetime):
        return value.isoformat()
    if isinstance(value, dict):
        return {str(key): _json_safe(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_json_safe(item) for item in value]
    return value

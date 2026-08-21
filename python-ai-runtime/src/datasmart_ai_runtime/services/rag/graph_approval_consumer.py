"""GraphRAG 审批事件的受控消费核心。

Java permission-admin 只发布“某个已经登记并批准的事实包可以被摄取”的低敏事件；
本模块负责在 Python/Neo4j 一侧再次校验服务端审批事实、范围和事实包指纹，然后调用
``ControlledGraphRagIngestor``。它不接受调用方直接传来的 ``APPROVED`` 字符串作为授权证明。

Kafka、HTTP internal worker 或本地回放脚本都可以复用 ``GraphFactApprovalConsumer.handle``，
因此传输方式变化时，审批和图写入的安全语义不会分叉。
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from typing import Any, Callable, Iterable, Mapping, Protocol

from datasmart_ai_runtime.services.rag.graph_ingestion import ControlledGraphRagIngestor
from datasmart_ai_runtime.services.rag.models import RagDocument


GRAPH_FACT_APPROVAL_EVENT_SCHEMA_VERSION = "datasmart.graph-facts-approved.v1"


class GraphFactApprovalConsumerError(ValueError):
    """审批事件不完整、审批事实不通过或摄取失败时的稳定异常。"""


class GraphFactApprovalEvaluator(Protocol):
    """permission-admin 服务端审批事实评估器。"""

    def __call__(self, event: Mapping[str, Any]) -> Mapping[str, Any]:
        """根据事件绑定字段回查服务端审批事实。"""


class GraphFactBundleLoader(Protocol):
    """受控事实包加载器，实际实现可读取对象存储或 PostgreSQL。"""

    def __call__(self, uri: str) -> Iterable[RagDocument]:
        """按事件中的稳定 URI 读取已经持久化的事实包。"""


@dataclass(frozen=True)
class GraphFactApprovalEvent:
    """Java outbox 发布的低敏图事实审批事件。"""

    event_id: str
    approval_fact_id: str
    fact_bundle_uri: str
    fact_fingerprint: str
    tenant_id: str
    application_id: str
    project_id: str
    user_id: str
    actor_id: str
    agent_id: str
    session_id: str
    run_id: str
    delegation_id: str
    command_id: str
    entity_count: int
    edge_count: int
    policy_version: str | None = None
    schema_version: str = GRAPH_FACT_APPROVAL_EVENT_SCHEMA_VERSION

    @classmethod
    def from_mapping(cls, value: Mapping[str, Any]) -> "GraphFactApprovalEvent":
        """从 Kafka JSON 解析事件，并拒绝缺少稳定绑定字段的消息。"""

        if not isinstance(value, Mapping):
            raise GraphFactApprovalConsumerError("图事实审批事件必须是 JSON 对象。")
        schema = str(value.get("schemaVersion") or value.get("schema_version") or "").strip()
        if schema != GRAPH_FACT_APPROVAL_EVENT_SCHEMA_VERSION:
            raise GraphFactApprovalConsumerError("图事实审批事件 schemaVersion 不受支持。")
        return cls(
            event_id=_required(value, "eventId", "event_id"),
            approval_fact_id=_required(value, "approvalFactId", "approval_fact_id"),
            fact_bundle_uri=_required(value, "factBundleUri", "fact_bundle_uri"),
            fact_fingerprint=_required(value, "factFingerprint", "fact_fingerprint"),
            tenant_id=_required(value, "tenantId", "tenant_id"),
            application_id=_required(value, "applicationId", "application_id"),
            project_id=_required(value, "projectId", "project_id"),
            user_id=_required(value, "userId", "user_id"),
            actor_id=_required(value, "actorId", "actor_id"),
            agent_id=_required(value, "agentId", "agent_id"),
            session_id=_required(value, "sessionId", "session_id"),
            run_id=_required(value, "runId", "run_id"),
            delegation_id=_required(value, "delegationId", "delegation_id"),
            command_id=_required(value, "commandId", "command_id"),
            entity_count=_non_negative_int(value.get("entityCount", value.get("entity_count"))),
            edge_count=_non_negative_int(value.get("edgeCount", value.get("edge_count"))),
            policy_version=_optional(value, "policyVersion", "policy_version"),
            schema_version=schema,
        )

    def to_dict(self) -> dict[str, Any]:
        """输出可写入低敏运行事件的摘要。"""

        return {
            "schemaVersion": self.schema_version,
            "eventId": self.event_id,
            "approvalFactId": self.approval_fact_id,
            "factBundleUri": self.fact_bundle_uri,
            "factFingerprint": self.fact_fingerprint,
            "tenantId": self.tenant_id,
            "applicationId": self.application_id,
            "projectId": self.project_id,
            "userId": self.user_id,
            "actorId": self.actor_id,
            "agentId": self.agent_id,
            "sessionId": self.session_id,
            "runId": self.run_id,
            "delegationId": self.delegation_id,
            "commandId": self.command_id,
            "entityCount": self.entity_count,
            "edgeCount": self.edge_count,
            "policyVersion": self.policy_version,
        }


@dataclass(frozen=True)
class GraphFactApprovalConsumeResult:
    """一次审批事件消费结果，不包含图实体名称或事实正文。"""

    status: str
    event_id: str
    approval_fact_id: str
    fingerprint: str
    entity_count: int
    edge_count: int

    def to_dict(self) -> dict[str, Any]:
        """输出 Kafka receipt 和审计事件使用的低敏结果。"""

        return {
            "status": self.status,
            "eventId": self.event_id,
            "approvalFactId": self.approval_fact_id,
            "fingerprint": self.fingerprint,
            "entityCount": self.entity_count,
            "edgeCount": self.edge_count,
            "payloadPolicy": "GRAPH_APPROVAL_RECEIPT_NO_DOCUMENT_CONTENT_OR_SECRET",
        }


class GraphFactApprovalConsumer:
    """执行“回查审批 -> 加载事实 -> 指纹校验 -> 幂等写图”的消费流程。"""

    def __init__(
        self,
        *,
        approval_evaluator: GraphFactApprovalEvaluator,
        bundle_loader: GraphFactBundleLoader,
        ingestor: ControlledGraphRagIngestor | None = None,
    ) -> None:
        self._approval_evaluator = approval_evaluator
        self._bundle_loader = bundle_loader
        self._ingestor = ingestor or ControlledGraphRagIngestor()

    def handle(self, event: Mapping[str, Any], provider: Any) -> GraphFactApprovalConsumeResult:
        """消费一条事件；任何审批或指纹异常都会 fail-closed。"""

        parsed = GraphFactApprovalEvent.from_mapping(event)
        evaluation = self._approval_evaluator(parsed.to_dict())
        if not isinstance(evaluation, Mapping):
            raise GraphFactApprovalConsumerError("permission-admin 审批评估结果不是对象。")
        if evaluation.get("approved") is not True:
            raise GraphFactApprovalConsumerError("permission-admin 未批准当前图事实摄取。")
        evaluated_fact_id = str(evaluation.get("approvalFactId") or evaluation.get("approval_fact_id") or "").strip()
        if evaluated_fact_id != parsed.approval_fact_id:
            raise GraphFactApprovalConsumerError("审批评估返回的 approvalFactId 与事件不一致。")

        documents = tuple(self._bundle_loader(parsed.fact_bundle_uri))
        if not documents:
            raise GraphFactApprovalConsumerError("审批事件引用的事实包为空。")
        # 事件的租户、应用和项目范围必须与加载出的每份来源文档一致，避免把一个已批准事件
        # 重放到另一个业务范围。Workspace 不参与新业务事实范围，旧字段只能保持通配兼容值。
        for document in documents:
            if (
                document.tenant_id != parsed.tenant_id
                or document.application_id != parsed.application_id
                or document.project_id != parsed.project_id
            ):
                raise GraphFactApprovalConsumerError("事实包范围与审批事件范围不一致。")

        # MinIO 中保存的是审批前不可变的 PROPOSED 事实包。Kafka payload 中即使出现 APPROVED 也不能
        # 被直接信任；只有上面的服务端 evaluate 返回 approved=true 后，consumer 才在进程内复制文档，
        # 覆盖授权元数据并绑定权威 approvalFactId。审批字段不参与事实指纹，因此这一步不会把审批后
        # 的内容替换伪装成原候选，同时也避免为了改一个状态而覆盖对象存储中的原始审计证据。
        authorized_documents = tuple(_bind_authoritative_approval(document, parsed.approval_fact_id) for document in documents)
        result = self._ingestor.ingest(
            authorized_documents,
            provider,
            expected_fingerprint=parsed.fact_fingerprint,
            authoritative_approval_fact_id=parsed.approval_fact_id,
        )
        if result.entity_count != parsed.entity_count or result.edge_count != parsed.edge_count:
            raise GraphFactApprovalConsumerError("事实包数量与审批事件数量不一致。")
        return GraphFactApprovalConsumeResult(
            status="INGESTED",
            event_id=parsed.event_id,
            approval_fact_id=parsed.approval_fact_id,
            fingerprint=result.fingerprint,
            entity_count=result.entity_count,
            edge_count=result.edge_count,
        )


def _bind_authoritative_approval(document: RagDocument, approval_fact_id: str) -> RagDocument:
    """把服务端已通过的审批事实绑定到一份不可变来源文档副本。"""

    return replace(
        document,
        metadata={
            **dict(document.metadata),
            "graphIngestionApproval": {
                "status": "APPROVED",
                "approvalId": approval_fact_id,
                "authority": "permission-admin-evaluate",
            },
        },
    )


def _required(value: Mapping[str, Any], *keys: str) -> str:
    for key in keys:
        candidate = value.get(key)
        if candidate is not None and str(candidate).strip():
            return str(candidate).strip()
    raise GraphFactApprovalConsumerError(f"审批事件缺少 {keys[0]}。")


def _optional(value: Mapping[str, Any], *keys: str) -> str | None:
    for key in keys:
        candidate = value.get(key)
        if candidate is not None and str(candidate).strip():
            return str(candidate).strip()
    return None


def _non_negative_int(value: Any) -> int:
    try:
        result = int(value)
    except (TypeError, ValueError) as exc:
        raise GraphFactApprovalConsumerError("审批事件数量字段必须是非负整数。") from exc
    if result < 0:
        raise GraphFactApprovalConsumerError("审批事件数量字段必须是非负整数。")
    return result


__all__ = [
    "GRAPH_FACT_APPROVAL_EVENT_SCHEMA_VERSION",
    "GraphFactApprovalConsumer",
    "GraphFactApprovalConsumerError",
    "GraphFactApprovalConsumeResult",
    "GraphFactApprovalEvent",
]

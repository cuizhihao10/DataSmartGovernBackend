"""RAG 默认组件装配。

这里放“怎么把 RAG 管线组装起来”，而不是把装配逻辑塞进 `api/app.py`。这样 API 入口只负责注册路由，
而 RAG 的知识库、检索器、模型网关和配置仍属于服务层。
"""

from __future__ import annotations

from datasmart_ai_runtime.services.memory.memory_embedding_provider import AgentMemoryEmbeddingProvider
from datasmart_ai_runtime.services.model_gateway import ModelGatewayGovernanceService
from datasmart_ai_runtime.services.model_gateway.model_provider import ModelProviderRegistry
from datasmart_ai_runtime.services.model_gateway.model_router import ModelRouteRegistry
from datasmart_ai_runtime.services.rag.knowledge_base import InMemoryRagKnowledgeBase, RagHybridRetriever
from datasmart_ai_runtime.services.rag.models import RagChunkSourceType, RagDocument
from datasmart_ai_runtime.services.rag.pipeline import RagPipeline


def build_default_governance_rag_pipeline(
    *,
    model_routes: ModelRouteRegistry,
    model_gateway: ModelGatewayGovernanceService,
    model_providers: ModelProviderRegistry,
    embedding_provider: AgentMemoryEmbeddingProvider | None = None,
) -> RagPipeline:
    """构建默认治理 RAG 管线。

    默认文档不是为了替代客户知识库，而是用于本地 smoke、学习和 API 合同验证。真实部署时应把文档来源
    改为 PostgreSQL/MinIO/Neo4j 或企业文档服务，并继续复用 RagPipeline。
    """

    knowledge_base = InMemoryRagKnowledgeBase(default_governance_rag_documents())
    retriever = RagHybridRetriever(
        knowledge_base,
        embedding_provider=embedding_provider,
    )
    return RagPipeline(
        retriever=retriever,
        model_routes=model_routes,
        model_gateway=model_gateway,
        model_providers=model_providers,
    )


def default_governance_rag_documents() -> tuple[RagDocument, ...]:
    """返回 DataSmart 默认治理知识文档。

    这些文档尽量覆盖项目当前闭环阶段最常被问到的主题：RAG 原理、数据质量、权限、Agent 执行和可观测。
    文档范围使用 `*`，表示公共产品知识；客户项目知识后续必须使用具体 tenant/project/workspace 写入。
    """

    return (
        RagDocument(
            document_id="datasmart-rag-principle",
            title="DataSmart RAG 管线原理",
            source_uri="builtin://datasmart/rag/principle",
            source_type=RagChunkSourceType.DOCUMENT,
            tags=("rag", "retrieval", "rerank", "context"),
            content=(
                "DataSmart 的 RAG 管线采用可解释的多阶段结构。首先按租户、项目和 workspace 过滤知识，"
                "再对文档 chunk 进行关键词召回和可选向量召回。随后使用 Reciprocal Rank Fusion 融合"
                "多路召回结果，并用 MMR 降低重复证据。最终上下文压缩器只保留与问题相关的句子，"
                "并要求模型回答时引用 [C1]、[C2] 证据编号。"
            ),
        ),
        RagDocument(
            document_id="datasmart-quality-rule",
            title="数据质量规则生成说明",
            source_uri="builtin://datasmart/data-quality/rule-generation",
            source_type=RagChunkSourceType.RULE,
            tags=("data-quality", "rule", "cleaning"),
            content=(
                "数据质量规则应覆盖完整性、唯一性、有效性、一致性、及时性和准确性。"
                "规则生成不能只依赖模型自由发挥，应结合数据源元数据、字段口径、历史异常、业务阈值和审批策略。"
                "高风险清洗方案应先生成草案，经过人工确认后再进入任务管理和执行链路。"
            ),
        ),
        RagDocument(
            document_id="datasmart-permission-boundary",
            title="权限与 Agent 工具调用边界",
            source_uri="builtin://datasmart/permission/agent-boundary",
            source_type=RagChunkSourceType.RULE,
            tags=("permission", "rbac", "agent", "tool"),
            content=(
                "Agent 工具调用必须区分规划、审批、执行和回执。模型不能自称已经获得授权；"
                "permission-admin 或 Java 控制面需要提供可信 permission、approval、readiness facts。"
                "高风险工具必须经过人工审批，工具参数和结果正文不应直接写入 checkpoint 或日志。"
            ),
        ),
        RagDocument(
            document_id="datasmart-agent-durable-state",
            title="LangGraph Durable Agent 状态机",
            source_uri="builtin://datasmart/agent/langgraph-durable-state",
            source_type=RagChunkSourceType.RUNBOOK,
            tags=("langgraph", "agent", "checkpoint", "multi-agent"),
            content=(
                "LangGraph 节点应是可复用、可观测、可替换的工作单元；边用于表达分支、循环、暂停和恢复；"
                "状态用于保存可恢复执行现场。DataSmart 的 checkpoint 只保存低敏状态摘要，"
                "例如节点名、下一候选节点、角色状态、错误码和恢复条件，不保存 prompt、工具参数或模型输出正文。"
            ),
        ),
        RagDocument(
            document_id="datasmart-observability",
            title="可观测与故障演练要求",
            source_uri="builtin://datasmart/observability/failure-drill",
            source_type=RagChunkSourceType.RUNBOOK,
            tags=("observability", "metrics", "failure-drill"),
            content=(
                "生产级数据治理平台需要为模型调用、RAG 检索、工具执行、Kafka outbox、任务状态和中间件健康"
                "提供低基数指标、日志和诊断接口。故障演练应覆盖 PostgreSQL、Kafka、Redis、Keycloak、"
                "Python Runtime、Java 服务和模型 Provider 不可用时的降级与恢复。"
            ),
        ),
    )


__all__ = [
    "build_default_governance_rag_pipeline",
    "default_governance_rag_documents",
]

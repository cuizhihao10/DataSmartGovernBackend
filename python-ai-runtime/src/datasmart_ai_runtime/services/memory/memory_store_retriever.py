"""正式长期记忆 store 的检索适配器。

`InMemoryAgentMemoryRetriever` 已经验证了范围隔离、关键词排序和目标级报告语义，但它接收的是启动时静态 records。
本适配器把同一套检索规则连接到正式记忆 store，使审批后 materialize 的记录可以在后续请求中被真正召回。
"""

from __future__ import annotations

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.memory import (
    AgentMemoryPlan,
    AgentMemoryRetrievalReport,
)
from datasmart_ai_runtime.services.memory.memory_retriever import InMemoryAgentMemoryRetriever, _resolve_session_id
from datasmart_ai_runtime.services.memory.memory_store import AgentMemoryStore
from datasmart_ai_runtime.services.memory.memory_secondary_index import (
    AgentMemorySecondaryIndex,
    AgentMemorySecondaryIndexKind,
    AgentMemorySecondaryIndexQuery,
    AgentMemorySecondaryIndexRouter,
    default_store_backed_secondary_indexes,
)
from datasmart_ai_runtime.services.memory.memory_write_workspace import AgentMemoryWorkspaceSupport


class StoreBackedAgentMemoryRetriever:
    """从正式记忆 store 读取有界窗口，再复用现有相关性排序的检索器。

    第一阶段这样设计有两个好处：
    - store 负责 tenant/project/session 与 workspace namespace 强隔离，避免先读取全量记忆再在 Python 内存过滤；
    - 现有内存 retriever 再做一次范围过滤和关键词排序，形成防御纵深。

    未来 Chroma/Embedding、Neo4j 或 MySQL 全文索引接入后，相关性搜索可以下沉到 store；
    但检索报告、范围隔离和有界窗口仍应保留。
    """

    def __init__(
        self,
        store: AgentMemoryStore,
        *,
        secondary_indexes: dict[AgentMemorySecondaryIndexKind, AgentMemorySecondaryIndex] | None = None,
        secondary_index_router: AgentMemorySecondaryIndexRouter | None = None,
    ) -> None:
        self._store = store
        self._secondary_indexes = secondary_indexes or default_store_backed_secondary_indexes(store)
        self._secondary_index_router = secondary_index_router or AgentMemorySecondaryIndexRouter()

    def retrieve(self, request: AgentRequest, memory_plan: AgentMemoryPlan) -> AgentMemoryRetrievalReport:
        """按 AgentMemoryPlan 检索正式记忆。

        每个 target 最多向 store 请求 100 条候选，避免第一阶段内存实现和后续关系型实现无界扫描。
        target.max_items 决定最终进入报告的数量，候选窗口适度放大是为了给关键词排序留出空间。
        """

        session_id = _resolve_session_id(request)
        workspace = AgentMemoryWorkspaceSupport.from_request(request)
        results = []
        total_retrieved = 0
        for target in memory_plan.retrieval_targets:
            route = self._secondary_index_router.route(
                target,
                available_indexes=set(self._secondary_indexes.keys()),
            )
            index = self._secondary_indexes[route.actual_index_kind]
            index_result = index.search(
                AgentMemorySecondaryIndexQuery(
                    target=target,
                    tenant_id=request.tenant_id,
                    project_id=request.project_id,
                    session_id=session_id,
                    workspace_key=workspace.workspace_key,
                    memory_namespace=workspace.memory_namespace,
                    objective=request.objective,
                    index_kind=route.actual_index_kind,
                    candidate_limit=route.candidate_limit,
                )
            )
            target_report = InMemoryAgentMemoryRetriever(
                records=tuple(entry.memory for entry in index_result.entries)
            ).retrieve(
                request,
                AgentMemoryPlan(retrieval_targets=(target,)),
            )
            target_result = target_report.results[0]
            target_result.attributes.update(
                {
                    "secondaryIndexKind": route.actual_index_kind.value,
                    "preferredSecondaryIndexKind": route.preferred_index_kind.value,
                    "secondaryIndexFallbackUsed": route.fallback_used,
                    "secondaryIndexFallbackReason": route.fallback_reason,
                    "secondaryIndexCandidateLimit": route.candidate_limit,
                    "secondaryIndexCandidateCount": len(index_result.entries),
                    "secondaryIndexNotes": route.notes,
                }
            )
            target_result.attributes.update(index_result.attributes)
            total_retrieved += len(target_result.memories)
            results.append(target_result)

        return AgentMemoryRetrievalReport(
            results=tuple(results),
            total_retrieved=total_retrieved,
            retrieval_notes=(
                "当前使用正式记忆 store + 轻量关键词排序适配器。",
                "正式 store 已先执行 tenant/project/session 隔离；向量库接入后仍必须保留同等范围过滤。",
            ),
            attributes={
                "retriever": "store_backed_keyword",
                "secondaryIndexRouter": "AgentMemorySecondaryIndexRouter",
                "availableSecondaryIndexes": tuple(
                    kind.value for kind in sorted(self._secondary_indexes.keys(), key=lambda item: item.value)
                ),
                "targetCount": len(memory_plan.retrieval_targets),
                "workspaceKey": workspace.workspace_key,
                "memoryNamespace": workspace.memory_namespace,
            },
        )

    def secondary_indexes(self) -> dict[AgentMemorySecondaryIndexKind, AgentMemorySecondaryIndex]:
        """返回当前 retriever 可用的二级索引集合。

        诊断接口使用该方法展示运行时到底启用了哪些索引通道。返回浅拷贝可以避免外部调用方直接修改
        retriever 内部字典，保持主链路配置稳定。
        """

        return dict(self._secondary_indexes)

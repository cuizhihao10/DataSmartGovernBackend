"""Chroma-compatible 语义记忆二级索引适配器。

本模块提供的是“可注入 Chroma collection 的语义索引适配器”，而不是在仓库中强制安装某个
Chroma Python SDK 版本。这样做有三个原因：

1. DataSmart 当前本地开发环境仍然需要零依赖运行，不能因为没有启动 Chroma 就阻断 Python Runtime；
2. Chroma Open Source、Chroma Cloud、企业内部向量服务和 pgvector 未来都可能承载 `VECTOR` 通道；
3. 对 Agent 平台来说，真正必须先固定的是 metadata filter、安全字段和同步语义，而不是某个 SDK 调用细节。

适配器的职责：
- 从正式记忆 store 读取已批准、已落成的语义记忆；
- 调用可注入的 embedding provider 生成向量；
- 调用可注入的 Chroma collection port 执行 upsert；
- metadata 中强制写入 tenant/project/session/workspace/memoryNamespace 等过滤字段。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol, Sequence

from datasmart_ai_runtime.domain.memory import AgentMemoryType
from datasmart_ai_runtime.services.memory.memory_embedding_provider import (
    AgentMemoryEmbeddingProvider,
    DeterministicHashEmbeddingProvider,
)
from datasmart_ai_runtime.services.memory.memory_secondary_index import AgentMemorySecondaryIndexKind
from datasmart_ai_runtime.services.memory.memory_secondary_index_sync import (
    AgentMemorySecondaryIndexSyncAdapterResult,
    AgentMemorySecondaryIndexSyncTask,
)
from datasmart_ai_runtime.services.memory.memory_store import AgentMemoryStore, AgentMemoryStoreEntry


class ChromaCollectionPort(Protocol):
    """Chroma collection 最小端口。

    Chroma Python SDK 的 collection 通常支持 `upsert(ids=..., embeddings=..., documents=..., metadatas=...)`。
    这里用协议表达最小形状，测试可以注入 fake collection，生产可以注入真实 collection 或兼容包装器。
    """

    def upsert(
        self,
        *,
        ids: Sequence[str],
        embeddings: Sequence[Sequence[float]],
        documents: Sequence[str],
        metadatas: Sequence[dict[str, str | int | float | bool | None]],
    ) -> object:
        """向 Chroma-compatible collection 幂等写入记录。"""


@dataclass(frozen=True)
class ChromaSemanticMemoryAdapterSettings:
    """Chroma 语义记忆适配器设置。

    `document_max_chars` 用于限制进入向量索引的正文长度。正式记忆本身已经只保存低敏摘要，但仍然需要
    控制长度，避免异常候选把超长文本推入 embedding 模型或向量库。
    """

    document_max_chars: int = 4000
    payload_policy: str = "SUMMARY_ONLY_NO_RAW_TOOL_RESULT_NO_SQL_NO_SAMPLE_DATA"


class ChromaSemanticMemorySyncAdapter:
    """把 semantic memory 同步到 Chroma-compatible collection。

    该适配器只处理 `indexKind=vector` 且 `memoryType=semantic` 的任务。其他任务类型交给 graph/resource/keyword
    adapter 处理，避免一个 adapter 变成全能实现。
    """

    def __init__(
        self,
        *,
        memory_store: AgentMemoryStore,
        collection: ChromaCollectionPort,
        embedding_provider: AgentMemoryEmbeddingProvider,
        settings: ChromaSemanticMemoryAdapterSettings | None = None,
    ) -> None:
        self._memory_store = memory_store
        self._collection = collection
        self._embedding_provider = embedding_provider
        self._settings = settings or ChromaSemanticMemoryAdapterSettings()

    def sync(self, task: AgentMemorySecondaryIndexSyncTask) -> AgentMemorySecondaryIndexSyncAdapterResult:
        """同步一条 semantic memory vector upsert 任务。"""

        if task.index_kind != AgentMemorySecondaryIndexKind.VECTOR:
            return AgentMemorySecondaryIndexSyncAdapterResult(
                synced=False,
                message=f"Chroma semantic adapter 只处理 vector 任务，当前为 {task.index_kind.value}。",
            )
        if task.memory_type != AgentMemoryType.SEMANTIC:
            return AgentMemorySecondaryIndexSyncAdapterResult(
                synced=False,
                message=f"Chroma semantic adapter 只处理 semantic memory，当前为 {task.memory_type.value}。",
            )
        entry = self._memory_store.get_by_candidate_id(task.source_candidate_id)
        if entry is None:
            return AgentMemorySecondaryIndexSyncAdapterResult(
                synced=False,
                message=f"正式记忆不存在，无法同步向量索引: candidateId={task.source_candidate_id}",
            )
        self._validate_task_matches_entry(task, entry)
        document = self._document(entry)
        embedding = self._embedding_provider.embed_text(document)
        if not embedding:
            return AgentMemorySecondaryIndexSyncAdapterResult(synced=False, message="embedding provider 返回空向量。")
        self._collection.upsert(
            ids=(entry.memory.memory_id,),
            embeddings=(embedding,),
            documents=(document,),
            metadatas=(self._metadata(entry),),
        )
        return AgentMemorySecondaryIndexSyncAdapterResult(
            synced=True,
            message="semantic memory 已同步到 Chroma-compatible vector index。",
        )

    def _validate_task_matches_entry(
        self,
        task: AgentMemorySecondaryIndexSyncTask,
        entry: AgentMemoryStoreEntry,
    ) -> None:
        """校验任务控制面事实与正式记忆一致。"""

        if task.memory_id != entry.memory.memory_id:
            raise ValueError("同步任务 memoryId 与正式记忆不一致。")
        if task.memory_namespace != entry.memory_namespace:
            raise ValueError("同步任务 memoryNamespace 与正式记忆不一致。")
        if task.workspace_key != entry.workspace_key:
            raise ValueError("同步任务 workspaceKey 与正式记忆不一致。")

    def _document(self, entry: AgentMemoryStoreEntry) -> str:
        """构造进入向量库的低敏文档。"""

        text = f"{entry.memory.title}\n\n{entry.memory.content}".strip()
        limit = max(100, self._settings.document_max_chars)
        return text[:limit]

    def _metadata(self, entry: AgentMemoryStoreEntry) -> dict[str, str | int | float | bool | None]:
        """构造 Chroma metadata。

        这些字段是未来查询时必须使用的 metadata filter 基础。尤其 `memoryNamespace` 不能省略，否则向量库
        召回容易跨 workspace 误命中。
        """

        return {
            "tenantId": entry.memory.tenant_id,
            "projectId": entry.memory.project_id,
            "sessionId": entry.memory.session_id,
            "workspaceKey": entry.workspace_key,
            "memoryNamespace": entry.memory_namespace,
            "memoryType": entry.memory.memory_type.value,
            "scope": entry.memory.scope.value,
            "sourceCandidateId": entry.source_candidate_id,
            "sensitivityLevel": entry.memory.sensitivity_level,
            "payloadPolicy": str(entry.memory.attributes.get("payloadPolicy") or self._settings.payload_policy),
            "expiresAt": entry.expires_at.isoformat(),
        }

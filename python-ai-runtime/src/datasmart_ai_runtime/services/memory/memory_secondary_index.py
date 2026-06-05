"""长期记忆二级索引路由与适配器。

长期记忆检索不应永远只有“从正式 store 拉一批记录再关键词排序”这一条路。真实商业化 Agent
需要同时支持：

- Chroma / 向量库：召回语义记忆、业务术语、字段含义和质量规则；
- Neo4j / 图数据库：召回资产血缘、流程依赖、故障因果链和治理步骤；
- MySQL / 事件表：召回审批、执行、异常和人工处理经验；
- MinIO / 资源索引：召回报告、SQL、日志、截图和配置文件引用。

本模块先固定“二级索引如何被选择、如何诊断、如何回退”的服务层契约。当前默认实现仍然委托
正式记忆 store 读取候选窗口，目的是在不强依赖 Chroma/Neo4j 的情况下，把可替换边界先落进主链路。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Mapping, Protocol

from datasmart_ai_runtime.domain.memory import (
    AgentMemoryRetrievalTarget,
    AgentMemoryScope,
    AgentMemoryType,
)
from datasmart_ai_runtime.services.memory.memory_store import AgentMemoryStore, AgentMemoryStoreEntry


class AgentMemorySecondaryIndexKind(str, Enum):
    """长期记忆二级索引类型。

    枚举表达的是“优先用哪类索引召回候选”，不是具体技术选型。后续同一个 `VECTOR` 可以接 Chroma、
    Milvus、pgvector 或企业内部向量平台；`GRAPH` 可以接 Neo4j、NebulaGraph 或治理资产图服务。
    """

    KEYWORD = "keyword"
    VECTOR = "vector"
    GRAPH = "graph"
    RESOURCE = "resource"


@dataclass(frozen=True)
class AgentMemorySecondaryIndexQuery:
    """一次二级索引候选召回查询。

    字段说明：
    - `target`：原始记忆检索目标，保留 memoryType、scope、queryHint 和 maxItems；
    - `tenant_id/project_id/session_id`：范围隔离字段，必须在索引层参与过滤；
    - `memory_namespace`：workspace 级命名空间，是同项目内不同 Agent 工作区的硬边界；
    - `objective`：用户目标，只用于相关性提示，不应被索引实现写入持久日志；
    - `index_kind`：本次实际选择的索引类型；
    - `candidate_limit`：候选窗口上限，防止索引层无界扫描。
    """

    target: AgentMemoryRetrievalTarget
    tenant_id: str
    project_id: str
    session_id: str | None
    memory_namespace: str
    objective: str
    index_kind: AgentMemorySecondaryIndexKind
    candidate_limit: int


@dataclass(frozen=True)
class AgentMemorySecondaryIndexRoute:
    """二级索引路由结果。

    `preferred_index_kind` 表示按记忆类型和产品语义最希望使用的索引；`actual_index_kind` 表示当前运行时
    实际可用索引。两者不同意味着发生了可控 fallback，诊断面板应展示出来，避免生产环境误以为已经
    使用向量或图索引。
    """

    preferred_index_kind: AgentMemorySecondaryIndexKind
    actual_index_kind: AgentMemorySecondaryIndexKind
    fallback_used: bool
    fallback_reason: str = ""
    candidate_limit: int = 100
    notes: tuple[str, ...] = ()


@dataclass(frozen=True)
class AgentMemorySecondaryIndexResult:
    """二级索引候选召回结果。"""

    entries: tuple[AgentMemoryStoreEntry, ...] = ()
    route: AgentMemorySecondaryIndexRoute | None = None
    skipped_reason: str = ""
    attributes: dict[str, object] = field(default_factory=dict)


class AgentMemorySecondaryIndex(Protocol):
    """长期记忆二级索引协议。

    不同索引实现必须返回正式记忆 store entry，而不是直接返回 prompt 文本。这样上层 retriever 仍然可以
    复用统一的记忆领域对象、范围过滤、脱敏摘要和检索报告结构。
    """

    kind: AgentMemorySecondaryIndexKind

    def search(self, query: AgentMemorySecondaryIndexQuery) -> AgentMemorySecondaryIndexResult:
        """按二级索引查询候选正式记忆。"""


class AgentMemorySecondaryIndexRouter:
    """根据记忆类型选择二级索引。

    路由规则体现当前 DataSmart 的产品判断：
    - 语义记忆优先向量索引，因为业务术语、字段含义和规则描述往往需要语义相似；
    - 情节记忆优先关键词/事件索引，因为失败记录、审批记录和异常码通常结构化程度较高；
    - 程序记忆优先图索引，因为流程步骤和工具依赖天然是关系结构；
    - 资源记忆优先资源索引，因为它们通常是对象存储引用、报告或日志片段；
    - 短期记忆优先关键词/会话索引，避免把临时状态放入跨会话向量库。
    """

    DEFAULT_ROUTE: Mapping[AgentMemoryType, tuple[AgentMemorySecondaryIndexKind, ...]] = {
        AgentMemoryType.SEMANTIC: (
            AgentMemorySecondaryIndexKind.VECTOR,
            AgentMemorySecondaryIndexKind.KEYWORD,
        ),
        AgentMemoryType.EPISODIC: (
            AgentMemorySecondaryIndexKind.KEYWORD,
            AgentMemorySecondaryIndexKind.VECTOR,
        ),
        AgentMemoryType.PROCEDURAL: (
            AgentMemorySecondaryIndexKind.GRAPH,
            AgentMemorySecondaryIndexKind.KEYWORD,
        ),
        AgentMemoryType.RESOURCE: (
            AgentMemorySecondaryIndexKind.RESOURCE,
            AgentMemorySecondaryIndexKind.KEYWORD,
        ),
        AgentMemoryType.SHORT_TERM: (
            AgentMemorySecondaryIndexKind.KEYWORD,
        ),
    }

    def __init__(
        self,
        *,
        route_table: Mapping[AgentMemoryType, tuple[AgentMemorySecondaryIndexKind, ...]] | None = None,
    ) -> None:
        self._route_table = route_table or self.DEFAULT_ROUTE

    def route(
        self,
        target: AgentMemoryRetrievalTarget,
        *,
        available_indexes: set[AgentMemorySecondaryIndexKind],
    ) -> AgentMemorySecondaryIndexRoute:
        """选择当前 target 应使用的索引。

        如果首选索引不可用，会按路由表顺序选择可用 fallback。若所有声明索引都不可用，则退回 KEYWORD；
        这让本地学习环境仍可运行，但会在 route 中显式标记 fallback，提醒生产需要补齐真实索引能力。
        """

        preferred_order = self._route_table.get(target.memory_type, (AgentMemorySecondaryIndexKind.KEYWORD,))
        preferred = preferred_order[0]
        for candidate in preferred_order:
            if candidate in available_indexes:
                return AgentMemorySecondaryIndexRoute(
                    preferred_index_kind=preferred,
                    actual_index_kind=candidate,
                    fallback_used=candidate != preferred,
                    fallback_reason="" if candidate == preferred else f"{preferred.value} 索引不可用，已回退到 {candidate.value}。",
                    candidate_limit=_candidate_limit(target),
                    notes=_route_notes(target.memory_type, candidate),
                )
        fallback = AgentMemorySecondaryIndexKind.KEYWORD
        return AgentMemorySecondaryIndexRoute(
            preferred_index_kind=preferred,
            actual_index_kind=fallback,
            fallback_used=True,
            fallback_reason=f"{preferred.value} 索引不可用，且声明 fallback 均不可用，已使用 keyword 安全兜底。",
            candidate_limit=_candidate_limit(target),
            notes=_route_notes(target.memory_type, fallback),
        )


class StoreBackedAgentMemorySecondaryIndex:
    """基于正式记忆 store 的二级索引适配器。

    当前实现不做真正向量或图谱搜索，而是把“索引类型选择”接到已有 store 的范围过滤能力上。
    这样做的价值是先稳定主流程和报告字段：后续把 `VECTOR` 替换成 Chroma、把 `GRAPH` 替换成 Neo4j 时，
    `StoreBackedAgentMemoryRetriever` 不需要知道底层实现细节。
    """

    def __init__(self, *, kind: AgentMemorySecondaryIndexKind, store: AgentMemoryStore) -> None:
        self.kind = kind
        self._store = store

    def search(self, query: AgentMemorySecondaryIndexQuery) -> AgentMemorySecondaryIndexResult:
        """从正式 store 读取一个有界候选窗口。"""

        entries = self._store.search(
            memory_type=query.target.memory_type,
            scope=query.target.scope,
            tenant_id=query.tenant_id,
            project_id=query.project_id,
            session_id=query.session_id,
            memory_namespace=query.memory_namespace,
            limit=query.candidate_limit,
        )
        return AgentMemorySecondaryIndexResult(
            entries=entries,
            attributes={
                "indexKind": self.kind.value,
                "candidateLimit": query.candidate_limit,
                "candidateCount": len(entries),
                "implementation": "StoreBackedAgentMemorySecondaryIndex",
            },
        )


def default_store_backed_secondary_indexes(store: AgentMemoryStore) -> dict[AgentMemorySecondaryIndexKind, AgentMemorySecondaryIndex]:
    """为本地运行时创建默认二级索引适配器集合。

    这里为四类索引都创建 store-backed 适配器，是为了让主链路能先展示“应该走哪个索引通道”。
    它并不宣称已经具备真实向量/图谱能力；诊断文档会把这类实现标记为过渡适配器。
    """

    return {
        kind: StoreBackedAgentMemorySecondaryIndex(kind=kind, store=store)
        for kind in AgentMemorySecondaryIndexKind
    }


def secondary_index_runtime_diagnostics(
    indexes: Mapping[AgentMemorySecondaryIndexKind, AgentMemorySecondaryIndex],
) -> dict[str, object]:
    """生成二级索引运行时诊断。

    诊断只展示索引类型和实现类，不输出记忆内容、queryHint、objective 或 namespace 明细。
    """

    return {
        "availableIndexes": tuple(kind.value for kind in sorted(indexes.keys(), key=lambda item: item.value)),
        "implementations": {
            kind.value: type(index).__name__
            for kind, index in sorted(indexes.items(), key=lambda item: item[0].value)
        },
        "notes": (
            "当前默认二级索引仍委托正式记忆 store 读取候选窗口，用于稳定路由、诊断和测试契约。",
            "生产环境应逐步把 vector 接到 Chroma/pgvector，graph 接到 Neo4j，resource 接到 MinIO 对象索引。",
        ),
    }


def _candidate_limit(target: AgentMemoryRetrievalTarget) -> int:
    """计算二级索引候选窗口大小。"""

    return max(10, min(100, target.max_items * 10))


def _route_notes(
    memory_type: AgentMemoryType,
    index_kind: AgentMemorySecondaryIndexKind,
) -> tuple[str, ...]:
    """生成面向诊断和学习的路由说明。"""

    return (
        f"{memory_type.value} 记忆本次使用 {index_kind.value} 二级索引通道召回候选。",
        "索引层必须先执行 tenant/project/session/memoryNamespace 过滤，再允许相关性排序结果进入模型上下文。",
    )

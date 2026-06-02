"""Agent 长期记忆能力包。

这个子包承载 Python Runtime 中与“记忆”相关的服务层能力。之所以从
`datasmart_ai_runtime.services` 的平铺目录中拆出来，是因为长期记忆已经不再是一个简单工具：

- `memory_planner` 负责判断当前 Agent 请求应该检索哪些类型、哪些范围的记忆；
- `memory_retriever` 与 `memory_store_retriever` 负责把正式记忆重新召回到上下文；
- `memory_store` 负责正式长期记忆的持久化抽象；
- `memory_write_*` 负责工具结果是否可以沉淀为长期记忆的候选生成、审批治理、SQL 候选仓储和 workspace 隔离；
- `memory_materialization_lease_store`、`memory_materialization_receipt_store` 与 `memory_write_materializer`
  负责 APPROVED 候选的安全领取、正式落成和幂等证据。

目录分层原则：
本包当前仍保留“文件名带 memory_ 前缀”的过渡形态，原因是仓库内已有较多测试和文档引用这些名字。
第一阶段先把能力域从大 `services` 目录中分离出来；后续再按成熟开源项目常见方式进一步拆成
`planning/`、`retrieval/`、`write_governance/`、`materialization/`、`stores/` 等更细子包。

这样做的好处是渐进、可验证、低风险：目录层次开始体现产品能力边界，同时不会一次性改动所有类名和
公共导出，避免为了“看起来整齐”而牺牲当前已经稳定的 267 个 Python Runtime 测试。
"""

from datasmart_ai_runtime.services.memory.memory_materialization_receipt_store import (
    AgentMemoryMaterializationReceipt,
    AgentMemoryMaterializationReceiptStatus,
    AgentMemoryMaterializationReceiptStore,
    InMemoryAgentMemoryMaterializationReceiptStore,
)
from datasmart_ai_runtime.services.memory.memory_materialization_lease_store import (
    AgentMemoryMaterializationLease,
    AgentMemoryMaterializationLeaseStatus,
    AgentMemoryMaterializationLeaseStore,
    InMemoryAgentMemoryMaterializationLeaseStore,
)
from datasmart_ai_runtime.services.memory.memory_materialization_lease_components import (
    AgentMemoryMaterializationLeaseStoreRuntime,
    AgentMemoryMaterializationLeaseStoreSettings,
    build_memory_materialization_lease_store_runtime,
    memory_materialization_lease_store_diagnostics,
    memory_materialization_lease_store_settings_from_env,
)
from datasmart_ai_runtime.services.memory.memory_materialization_lease_sql_store import (
    SqlAgentMemoryMaterializationLeaseStore,
)
from datasmart_ai_runtime.services.memory.memory_materialization_receipt_components import (
    AgentMemoryMaterializationReceiptStoreRuntime,
    AgentMemoryMaterializationReceiptStoreSettings,
    build_memory_materialization_receipt_store_runtime,
    memory_materialization_receipt_store_diagnostics,
    memory_materialization_receipt_store_settings_from_env,
)
from datasmart_ai_runtime.services.memory.memory_materialization_receipt_sql_store import (
    SqlAgentMemoryMaterializationReceiptStore,
)
from datasmart_ai_runtime.services.memory.memory_materialization_runner import (
    AgentMemoryMaterializationRunner,
    AgentMemoryMaterializationRunnerItem,
    AgentMemoryMaterializationRunnerItemStatus,
    AgentMemoryMaterializationRunnerReport,
)
from datasmart_ai_runtime.services.memory.memory_planner import AgentMemoryPlanner
from datasmart_ai_runtime.services.memory.memory_retriever import AgentMemoryRetriever, InMemoryAgentMemoryRetriever
from datasmart_ai_runtime.services.memory.memory_store import (
    AgentMemoryStore,
    AgentMemoryStoreEntry,
    AgentMemoryStoreWriteResult,
    InMemoryAgentMemoryStore,
)
from datasmart_ai_runtime.services.memory.memory_store_components import (
    AgentMemoryStoreRuntime,
    AgentMemoryStoreSettings,
    build_memory_store_runtime,
    memory_store_diagnostics,
    memory_store_settings_from_env,
)
from datasmart_ai_runtime.services.memory.memory_store_retriever import StoreBackedAgentMemoryRetriever
from datasmart_ai_runtime.services.memory.memory_sql_store import SqlAgentMemoryStore
from datasmart_ai_runtime.services.memory.memory_write_candidate_factory import AgentMemoryWriteCandidateFactory
from datasmart_ai_runtime.services.memory.memory_write_candidate_store import (
    AgentMemoryWriteCandidateStore,
    InMemoryAgentMemoryWriteCandidateStore,
)
from datasmart_ai_runtime.services.memory.memory_write_components import (
    AgentMemoryWriteStoreRuntime,
    AgentMemoryWriteStoreSettings,
    build_memory_write_store_runtime,
    memory_write_store_diagnostics,
    memory_write_store_settings_from_env,
)
from datasmart_ai_runtime.services.memory.memory_write_governance import (
    AgentMemoryWriteGovernanceService,
    approve_memory_write_candidate,
    reject_memory_write_candidate,
)
from datasmart_ai_runtime.services.memory.memory_write_materializer import (
    AgentApprovedMemoryWriteMaterializer,
    AgentMemoryMaterializationOutcome,
    AgentMemoryMaterializationResult,
)
from datasmart_ai_runtime.services.memory.memory_write_sql_store import SqlAgentMemoryWriteCandidateStore
from datasmart_ai_runtime.services.memory.memory_write_workspace import (
    AgentMemoryWorkspaceBinding,
    AgentMemoryWorkspaceSupport,
)

__all__ = [
    "AgentApprovedMemoryWriteMaterializer",
    "AgentMemoryMaterializationOutcome",
    "AgentMemoryMaterializationLease",
    "AgentMemoryMaterializationLeaseStatus",
    "AgentMemoryMaterializationLeaseStore",
    "AgentMemoryMaterializationLeaseStoreRuntime",
    "AgentMemoryMaterializationLeaseStoreSettings",
    "AgentMemoryMaterializationReceipt",
    "AgentMemoryMaterializationReceiptStatus",
    "AgentMemoryMaterializationReceiptStore",
    "AgentMemoryMaterializationReceiptStoreRuntime",
    "AgentMemoryMaterializationReceiptStoreSettings",
    "AgentMemoryMaterializationRunner",
    "AgentMemoryMaterializationRunnerItem",
    "AgentMemoryMaterializationRunnerItemStatus",
    "AgentMemoryMaterializationRunnerReport",
    "AgentMemoryMaterializationResult",
    "AgentMemoryPlanner",
    "AgentMemoryRetriever",
    "AgentMemoryStore",
    "AgentMemoryStoreEntry",
    "AgentMemoryStoreRuntime",
    "AgentMemoryStoreSettings",
    "AgentMemoryStoreWriteResult",
    "AgentMemoryWorkspaceBinding",
    "AgentMemoryWorkspaceSupport",
    "AgentMemoryWriteCandidateFactory",
    "AgentMemoryWriteCandidateStore",
    "AgentMemoryWriteGovernanceService",
    "AgentMemoryWriteStoreRuntime",
    "AgentMemoryWriteStoreSettings",
    "InMemoryAgentMemoryMaterializationReceiptStore",
    "InMemoryAgentMemoryMaterializationLeaseStore",
    "InMemoryAgentMemoryRetriever",
    "InMemoryAgentMemoryStore",
    "InMemoryAgentMemoryWriteCandidateStore",
    "SqlAgentMemoryWriteCandidateStore",
    "SqlAgentMemoryStore",
    "SqlAgentMemoryMaterializationReceiptStore",
    "SqlAgentMemoryMaterializationLeaseStore",
    "StoreBackedAgentMemoryRetriever",
    "approve_memory_write_candidate",
    "build_memory_materialization_receipt_store_runtime",
    "build_memory_materialization_lease_store_runtime",
    "build_memory_store_runtime",
    "build_memory_write_store_runtime",
    "memory_materialization_receipt_store_diagnostics",
    "memory_materialization_receipt_store_settings_from_env",
    "memory_materialization_lease_store_diagnostics",
    "memory_materialization_lease_store_settings_from_env",
    "memory_store_diagnostics",
    "memory_store_settings_from_env",
    "memory_write_store_diagnostics",
    "memory_write_store_settings_from_env",
    "reject_memory_write_candidate",
]

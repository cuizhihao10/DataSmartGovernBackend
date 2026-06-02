"""Python API 的长期记忆运行时装配。

`api.py` 已经承担 FastAPI 创建、路由注册、网关签名、事件组件、工具目录、Skill 准入等很多职责。
如果继续把候选 store、正式 store、materializer、retriever 和诊断逻辑全部写在 `api.py`，启动入口会变成
一个难以维护的巨型文件，也不利于后台 worker 或 CLI 复用同一套长期记忆装配。

本文件把“API 启动时需要的长期记忆组件”聚合起来：
- 记忆写入候选 store：保存工具结果是否允许写入长期记忆的候选事实；
- 正式长期记忆 store：保存 APPROVED 候选落成后的可召回低敏摘要；
- receipt store：保存正式记忆落成尝试、成功、失败和重试次数；
- lease store：保存 worker 短时领取权和 fencing token，避免多实例重复处理；
- 治理服务：负责候选生成、审批、拒绝；
- materializer：负责把 APPROVED 候选幂等写入正式 store；
- materialization runner：负责有界扫描 APPROVED 候选，并把单条失败隔离在批次报告中；
- retriever：负责让 Agent 规划请求能从正式 store 召回记忆。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from datasmart_ai_runtime.services.memory.memory_materialization_receipt_components import (
    AgentMemoryMaterializationReceiptStoreRuntime,
    build_memory_materialization_receipt_store_runtime,
    memory_materialization_receipt_store_diagnostics,
)
from datasmart_ai_runtime.services.memory.memory_materialization_lease_components import (
    AgentMemoryMaterializationLeaseStoreRuntime,
    build_memory_materialization_lease_store_runtime,
    memory_materialization_lease_store_diagnostics,
)
from datasmart_ai_runtime.services.memory.memory_store_components import (
    AgentMemoryStoreRuntime,
    build_memory_store_runtime,
    memory_store_diagnostics,
)
from datasmart_ai_runtime.services.memory.memory_store_retriever import StoreBackedAgentMemoryRetriever
from datasmart_ai_runtime.services.memory.memory_materialization_runner import AgentMemoryMaterializationRunner
from datasmart_ai_runtime.services.memory.memory_write_components import (
    AgentMemoryWriteStoreRuntime,
    build_memory_write_store_runtime,
    memory_write_store_diagnostics,
)
from datasmart_ai_runtime.services.memory.memory_write_governance import AgentMemoryWriteGovernanceService
from datasmart_ai_runtime.services.memory.memory_write_materializer import AgentApprovedMemoryWriteMaterializer


@dataclass(frozen=True)
class ApiMemoryRuntimeComponents:
    """API 启动后的长期记忆组件集合。

    字段说明：
    - `write_store_runtime`：候选 store 运行时，影响 `/agent/memory/write-candidates` 列表、审批和拒绝；
    - `formal_store_runtime`：正式记忆 store 运行时，影响 `/agent/plans` 的长期记忆召回；
    - `receipt_store_runtime`：落成 receipt store 运行时，影响 materializer 的执行证据持久化；
    - `lease_store_runtime`：落成 lease store 运行时，影响多 worker 是否可以安全竞争同一候选；
    - `memory_write_governance`：候选治理服务，接入 API 路由和 plan response 的候选生成；
    - `memory_materializer`：APPROVED 候选落成服务，当前主要供未来 worker/补偿入口复用；
    - `memory_materialization_runner`：APPROVED 候选有界批处理入口，负责失败隔离和低敏批次报告；
    - `memory_retriever`：store-backed retriever，被注入默认 orchestrator，使正式记忆真正参与 Agent 规划。

    把这些对象集中到一个 dataclass 中，是为了避免 `create_app()` 中散落多个临时变量；同时也方便测试直接
    构建并断言诊断结果。
    """

    write_store_runtime: AgentMemoryWriteStoreRuntime
    formal_store_runtime: AgentMemoryStoreRuntime
    receipt_store_runtime: AgentMemoryMaterializationReceiptStoreRuntime
    lease_store_runtime: AgentMemoryMaterializationLeaseStoreRuntime
    memory_write_governance: AgentMemoryWriteGovernanceService
    memory_materializer: AgentApprovedMemoryWriteMaterializer
    memory_materialization_runner: AgentMemoryMaterializationRunner
    memory_retriever: StoreBackedAgentMemoryRetriever


def build_api_memory_runtime() -> ApiMemoryRuntimeComponents:
    """构建 API 默认长期记忆运行时组件。

    当前函数读取环境变量完成装配：
    - 候选 store 由 `DATASMART_AI_MEMORY_WRITE_*` 控制；
    - 正式 store 由 `DATASMART_AI_FORMAL_MEMORY_*` 控制；
    - receipt store 由 `DATASMART_AI_MEMORY_RECEIPT_*` 控制；
    - lease store 由 `DATASMART_AI_MEMORY_LEASE_*` 控制。

    这里没有自动启动后台 worker。当前已具备 lease token fencing，但正式 worker 还需要失败退避、DLQ、
    审计事件和指标。当前先把 store、materializer 和 runner 装配好，后续 worker 可以复用这里的组件边界。
    """

    write_store_runtime = build_memory_write_store_runtime()
    formal_store_runtime = build_memory_store_runtime()
    receipt_store_runtime = build_memory_materialization_receipt_store_runtime()
    lease_store_runtime = build_memory_materialization_lease_store_runtime()
    governance = AgentMemoryWriteGovernanceService(store=write_store_runtime.store)
    materializer = AgentApprovedMemoryWriteMaterializer(
        candidate_store=write_store_runtime.store,
        memory_store=formal_store_runtime.store,
        receipt_store=receipt_store_runtime.store,
    )
    runner = AgentMemoryMaterializationRunner(
        candidate_store=write_store_runtime.store,
        materializer=materializer,
        lease_store=lease_store_runtime.store,
        lease_seconds=lease_store_runtime.settings.default_lease_seconds,
    )
    retriever = StoreBackedAgentMemoryRetriever(formal_store_runtime.store)
    return ApiMemoryRuntimeComponents(
        write_store_runtime=write_store_runtime,
        formal_store_runtime=formal_store_runtime,
        receipt_store_runtime=receipt_store_runtime,
        lease_store_runtime=lease_store_runtime,
        memory_write_governance=governance,
        memory_materializer=materializer,
        memory_materialization_runner=runner,
        memory_retriever=retriever,
    )


def api_memory_runtime_diagnostics(components: ApiMemoryRuntimeComponents) -> dict[str, Any]:
    """生成 API 长期记忆运行时诊断信息。

    诊断输出只展示 store 类型、持久化状态和脱敏连接目标，不展示候选内容、正式记忆正文、标签、namespace
    明细或审批原因。商业化部署中，该接口仍应由 gateway/permission-admin 做管理员权限保护。
    """

    return {
        "component": "python-ai-memory-runtime",
        "candidateStore": memory_write_store_diagnostics(components.write_store_runtime),
        "formalStore": memory_store_diagnostics(components.formal_store_runtime),
        "receiptStore": memory_materialization_receipt_store_diagnostics(components.receipt_store_runtime),
        "leaseStore": memory_materialization_lease_store_diagnostics(components.lease_store_runtime),
        "retriever": {
            "implementation": "StoreBackedAgentMemoryRetriever",
            "usesFormalStore": True,
            "notes": (
                "Agent 规划请求会从正式长期记忆 store 读取候选窗口，再执行轻量关键词排序。"
                "未来接入 Chroma/Neo4j 时仍必须保留 memoryNamespace 范围过滤。"
            ),
        },
        "materializer": {
            "implementation": "AgentApprovedMemoryWriteMaterializer",
            "runnerAvailable": True,
            "workerEnabled": False,
            "notes": (
                "当前 API 已装配 materializer 和最小 runner。runner 可以被管理接口、CLI 或未来后台 worker 显式触发，"
                "但 API 启动时尚不会自动消费 APPROVED 候选。生产化 worker 仍需要补失败退避、DLQ、审计事件和指标。"
            ),
        },
        "materializationRunner": {
            "implementation": "AgentMemoryMaterializationRunner",
            "workerEnabled": False,
            "defaultPolicy": "BOUNDED_AT_LEAST_ONCE_WITH_LEASE_TOKEN_FENCING",
            "notes": (
                "runner 负责有界扫描 APPROVED 候选、领取 lease 并逐条调用 materializer。单条失败会进入批次报告，"
                "不会阻塞同批其他候选；token fencing 会阻止过期 worker 覆盖新 worker。候选审批状态不被 runner 修改。"
            ),
        },
    }

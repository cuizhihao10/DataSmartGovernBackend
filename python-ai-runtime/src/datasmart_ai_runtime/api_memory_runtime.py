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
- materialization worker：由 API 启动层按配置可选启动，周期性调用 runner，并把批次报告写入事件与指标；
- materialization audit outbox：把 worker 批次和管理员补偿记录成可持久化审计事实；
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
from datasmart_ai_runtime.services.memory.memory_materialization_audit_outbox_components import (
    AgentMemoryMaterializationAuditOutboxRuntime,
    build_memory_materialization_audit_outbox_runtime,
    memory_materialization_audit_outbox_diagnostics,
)
from datasmart_ai_runtime.services.memory.memory_store_components import (
    AgentMemoryStoreRuntime,
    build_memory_store_runtime,
    memory_store_diagnostics,
)
from datasmart_ai_runtime.services.memory.memory_store_retriever import StoreBackedAgentMemoryRetriever
from datasmart_ai_runtime.services.memory.memory_materialization_runner import AgentMemoryMaterializationRunner
from datasmart_ai_runtime.services.memory.memory_materialization_admin import AgentMemoryMaterializationAdminService
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
    - `audit_outbox_runtime`：审计 outbox 运行时，影响 worker 批次和管理员补偿是否形成持久审计事实；
    - `memory_write_governance`：候选治理服务，接入 API 路由和 plan response 的候选生成；
    - `memory_materializer`：APPROVED 候选落成服务，当前主要供未来 worker/补偿入口复用；
    - `memory_materialization_runner`：APPROVED 候选有界批处理入口，负责失败隔离和低敏批次报告；
    - `memory_materialization_admin`：失败/DLQ 补偿服务，负责 dry-run、重排和低敏补偿摘要；
    - `memory_retriever`：store-backed retriever，被注入默认 orchestrator，使正式记忆真正参与 Agent 规划。

    把这些对象集中到一个 dataclass 中，是为了避免 `create_app()` 中散落多个临时变量；同时也方便测试直接
    构建并断言诊断结果。
    """

    write_store_runtime: AgentMemoryWriteStoreRuntime
    formal_store_runtime: AgentMemoryStoreRuntime
    receipt_store_runtime: AgentMemoryMaterializationReceiptStoreRuntime
    lease_store_runtime: AgentMemoryMaterializationLeaseStoreRuntime
    audit_outbox_runtime: AgentMemoryMaterializationAuditOutboxRuntime
    memory_write_governance: AgentMemoryWriteGovernanceService
    memory_materializer: AgentApprovedMemoryWriteMaterializer
    memory_materialization_runner: AgentMemoryMaterializationRunner
    memory_materialization_admin: AgentMemoryMaterializationAdminService
    memory_retriever: StoreBackedAgentMemoryRetriever


def build_api_memory_runtime() -> ApiMemoryRuntimeComponents:
    """构建 API 默认长期记忆运行时组件。

    当前函数读取环境变量完成装配：
    - 候选 store 由 `DATASMART_AI_MEMORY_WRITE_*` 控制；
    - 正式 store 由 `DATASMART_AI_FORMAL_MEMORY_*` 控制；
    - receipt store 由 `DATASMART_AI_MEMORY_RECEIPT_*` 控制；
    - lease store 由 `DATASMART_AI_MEMORY_LEASE_*` 控制。
    - 审计 outbox 由 `DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_*` 控制。

    这里仍不直接启动后台 worker，而只装配 runner、store 与治理组件。是否启动后台循环由 `api.py` 在 FastAPI
    生命周期中根据 `DATASMART_AI_MEMORY_MATERIALIZATION_WORKER_*` 配置决定。这样可以避免组件装配函数产生隐藏线程，
    也方便 CLI、测试或未来 Java task-management 调度器复用同一套长期记忆运行时。
    """

    write_store_runtime = build_memory_write_store_runtime()
    formal_store_runtime = build_memory_store_runtime()
    receipt_store_runtime = build_memory_materialization_receipt_store_runtime()
    lease_store_runtime = build_memory_materialization_lease_store_runtime()
    audit_outbox_runtime = build_memory_materialization_audit_outbox_runtime()
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
        max_attempts=lease_store_runtime.settings.max_attempts,
        retry_base_seconds=lease_store_runtime.settings.retry_base_seconds,
        retry_max_seconds=lease_store_runtime.settings.retry_max_seconds,
    )
    materialization_admin = AgentMemoryMaterializationAdminService(lease_store_runtime.store)
    retriever = StoreBackedAgentMemoryRetriever(formal_store_runtime.store)
    return ApiMemoryRuntimeComponents(
        write_store_runtime=write_store_runtime,
        formal_store_runtime=formal_store_runtime,
        receipt_store_runtime=receipt_store_runtime,
        lease_store_runtime=lease_store_runtime,
        audit_outbox_runtime=audit_outbox_runtime,
        memory_write_governance=governance,
        memory_materializer=materializer,
        memory_materialization_runner=runner,
        memory_materialization_admin=materialization_admin,
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
        "materializationAuditOutbox": memory_materialization_audit_outbox_diagnostics(components.audit_outbox_runtime),
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
                "当前 API 已装配 materializer、runner、管理员补偿、runtime event 和低基数指标。后台 worker 默认关闭，"
                "只有显式配置 DATASMART_AI_MEMORY_MATERIALIZATION_WORKER_ENABLED=true 后才会在 FastAPI 生命周期中启动。"
                "审计 outbox 默认关闭；强合规部署可显式启用 DATASMART_AI_MEMORY_MATERIALIZATION_AUDIT_OUTBOX_ENABLED。"
            ),
        },
        "materializationRunner": {
            "implementation": "AgentMemoryMaterializationRunner",
            "workerEnabled": False,
            "defaultPolicy": "BOUNDED_AT_LEAST_ONCE_WITH_LEASE_TOKEN_FENCING_AND_BACKOFF_DLQ",
            "notes": (
                "runner 负责有界扫描 APPROVED 候选、领取 lease 并逐条调用 materializer。单条失败会进入批次报告，"
                "不会阻塞同批其他候选；token fencing 会阻止过期 worker 覆盖新 worker。失败候选会先退避，达到最大尝试次数后进入 DLQ。"
                "候选审批状态不被 runner 修改。后台循环由 AgentMemoryMaterializationWorker 可选驱动。"
            ),
        },
        "materializationAdmin": {
            "implementation": "AgentMemoryMaterializationAdminService",
            "available": True,
            "defaultQueryStatuses": ("failed", "dead_letter"),
            "notes": (
                "管理员补偿入口只调整 lease 的 nextRetryAt 与重排说明，不会绕过候选审批，也不会直接写正式记忆。"
                "生产环境必须由 gateway/permission-admin 对该入口做管理员权限、租户范围和审计保护。"
                "如果启用 materializationAuditOutbox，真实 requeue 会同步写入审计 outbox 摘要。"
            ),
        },
    }

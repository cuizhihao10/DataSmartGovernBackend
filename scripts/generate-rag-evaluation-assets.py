#!/usr/bin/env python3
"""生成 DataSmart Govern 中文 RAG 离线评测资产。

这个脚本及其异构文档提取器只依赖 Python 标准库：输入是本文件内经过人工审阅的 Markdown
模板，以及 ``generate-rag-multiformat-assets.mjs`` 生成的合成办公文档和结构化文件。流程不读取
网络、环境变量、密钥、数据库或客户文件，避免把生产知识意外混入基准语料。

生成流程采用“先暂存、后校验、再替换”的顺序：
1. 在 ``python-ai-runtime/evaluation/rag/.staging`` 生成完整候选资产；
2. 对候选资产检查数量、哈希、引用、范围与拒答合同；
3. 仅当所有检查通过后，使用 ``os.replace`` 原子替换每个最终文件。

目标目录下的文件是提交物，本脚本只是可审计的再生成来源。运行方式：

    python scripts/generate-rag-evaluation-assets.py
    python scripts/generate-rag-evaluation-assets.py --check
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
PYTHON_RUNTIME_SOURCE = REPOSITORY_ROOT / "python-ai-runtime" / "src"
ASSET_ROOT = REPOSITORY_ROOT / "python-ai-runtime" / "evaluation" / "rag"
STAGING_ROOT = ASSET_ROOT / ".staging"
MULTIFORMAT_CATALOG_PATH = ASSET_ROOT / "multiformat_catalog.json"
SCHEMA_VERSION = "datasmart.rag-evaluation-assets.v2"
EXPECTED_DOCUMENT_COUNT = 188
EXPECTED_GOLDEN_CASE_COUNT = 308
EXPECTED_MULTIFORMAT_DOCUMENT_COUNT = 92
if str(PYTHON_RUNTIME_SOURCE) not in sys.path:
    sys.path.insert(0, str(PYTHON_RUNTIME_SOURCE))

from datasmart_ai_runtime.services.rag.document_extractor import (  # noqa: E402
    RAG_DOCUMENT_EXTRACTION_VERSION,
    RagDocumentExtractionError,
    extract_rag_document_bytes,
)

# 运行时 ``RagChunkSourceType`` 已定义的来源类型。这里保持字符串，避免生成器在离线
# 资产构建时导入 Python Runtime，从而不会触发任何可选依赖或应用启动副作用。
VALID_SOURCE_TYPES = {
    "document",
    "rule",
    "metadata",
    "runbook",
    "incident",
    "task_case",
    "dataset",
    "memory_export",
    "wiki",
    "git_history",
    "exact_search",
}


@dataclass(frozen=True)
class ScopeSpec:
    """一个评测范围及其可公开写入的合成差异。

    ``tenant_id``、``project_id``、``workspace_key`` 与 ``RagDocument`` 一一对应。
    四个范围使用相同的文档主题和错误码，但是给出不同的合成运行参数；因此检索器若在
    过滤前排序，极易把相似的异范围文档误选为证据。这里的参数仅为评测设定，不代表任何
    客户、项目或生产环境。
    """

    key: str
    tenant_id: str
    project_id: str
    workspace_key: str
    label: str
    lag_budget: str
    retry_window: str
    retention_days: str


@dataclass(frozen=True)
class DocumentSpec:
    """定义一个跨范围重复的知识主题。

    每个主题都带有稳定精确码和可读的中文结论。错误码在不同范围中保持一致，这是有意
    制造的近重复干扰；``retrieval_anchor`` 才是每份文档独有的检索锚点。``current`` 用于
    标明可作为现行操作依据的证据，``supersedes_slug`` 用于建立已过期历史记录的替代关系。
    """

    slug: str
    category: str
    title: str
    source_type: str
    tags: tuple[str, ...]
    code: str
    summary: str
    actions: tuple[str, ...]
    exact_question: str
    semantic_question: str | None = None
    current: bool = True
    supersedes_slug: str | None = None


SCOPES: tuple[ScopeSpec, ...] = (
    ScopeSpec(
        key="global",
        tenant_id="*",
        project_id="*",
        workspace_key="*",
        label="全局产品基线",
        lag_budget="10 分钟",
        retry_window="3 次",
        retention_days="180 天",
    ),
    ScopeSpec(
        key="tenant-10-project-101",
        tenant_id="10",
        project_id="101",
        workspace_key="tenant-10-project-101",
        label="租户 10 项目 101 合成演示空间",
        lag_budget="6 分钟",
        retry_window="4 次",
        retention_days="120 天",
    ),
    ScopeSpec(
        key="tenant-10-project-102",
        tenant_id="10",
        project_id="102",
        workspace_key="tenant-10-project-102",
        label="租户 10 项目 102 合成演示空间",
        lag_budget="8 分钟",
        retry_window="2 次",
        retention_days="90 天",
    ),
    ScopeSpec(
        key="tenant-20-project-201",
        tenant_id="20",
        project_id="201",
        workspace_key="tenant-20-project-201",
        label="租户 20 项目 201 合成演示空间",
        lag_budget="12 分钟",
        retry_window="5 次",
        retention_days="150 天",
    ),
)


# 20 份现行主题覆盖题目指定的六大知识类型；其后 4 份历史记录用于验证“当前依据优先于
# 已过期证据”。四个范围各复制一次，所以最终是 24 * 4 = 96 份独立 Markdown 文档。
CURRENT_DOCUMENTS: tuple[DocumentSpec, ...] = (
    DocumentSpec(
        slug="architecture-rag-scope-filter",
        category="architecture",
        title="RAG 范围过滤架构说明",
        source_type="document",
        tags=("架构", "rag", "范围隔离"),
        code="RAG-ISO-401",
        summary="检索必须先按租户、项目和工作区过滤，再执行词法、向量和重排步骤。",
        actions=("核对请求范围三元组", "确认候选集只含允许范围", "记录被范围过滤的候选数量"),
        exact_question="精确码 RAG-ISO-401 的处理原则是什么？",
        semantic_question="知识检索怎样避免先排序后发现跨空间的资料？",
    ),
    DocumentSpec(
        slug="architecture-event-bridge",
        category="architecture",
        title="异步事件桥接架构说明",
        source_type="wiki",
        tags=("架构", "kafka", "事件"),
        code="ARC-EVT-212",
        summary="业务服务与 AI Runtime 通过可追溯的异步事件桥接，回执以稳定任务标识关联。",
        actions=("校验事件版本", "保留幂等键", "将失败事件转入受控重试队列"),
        exact_question="精确码 ARC-EVT-212 说明的桥接约束是什么？",
        semantic_question="服务和智能运行时之间怎样避免把回执接错任务？",
    ),
    DocumentSpec(
        slug="architecture-citation-evidence",
        category="architecture",
        title="可引用证据链架构说明",
        source_type="document",
        tags=("架构", "引用", "证据"),
        code="RAG-CIT-118",
        summary="答案只能依据已检索的片段生成，并返回可追溯 sourceUri 的引用记录。",
        actions=("保留文档标识与 sourceUri", "压缩上下文但不丢失引用", "无证据时拒绝生成"),
        exact_question="精确码 RAG-CIT-118 对引用链有什么要求？",
        semantic_question="如何让治理问答的结论能回到原始证据？",
    ),
    DocumentSpec(
        slug="product-quality-lifecycle",
        category="product",
        title="数据质量规则生命周期说明",
        source_type="wiki",
        tags=("产品", "数据质量", "生命周期"),
        code="PRD-QLT-302",
        summary="质量规则经历草案、审核、启用、观察和归档，风险动作不能跳过人工确认。",
        actions=("生成规则草案", "完成责任人审核", "记录启用后的命中趋势"),
        exact_question="精确码 PRD-QLT-302 的规则生命周期包含哪些阶段？",
    ),
    DocumentSpec(
        slug="product-agent-handoff",
        category="product",
        title="多智能体交接产品说明",
        source_type="document",
        tags=("产品", "agent", "交接"),
        code="PRD-HOF-214",
        summary="交接包只传递低敏任务摘要、范围、状态与下一步，不能复制完整会话正文。",
        actions=("固定交接输入结构", "校验接收方能力", "写入可回放的状态摘要"),
        exact_question="精确码 PRD-HOF-214 的交接包边界是什么？",
    ),
    DocumentSpec(
        slug="runbook-rag-index-rebuild",
        category="runbook",
        title="RAG 索引重建 Runbook",
        source_type="runbook",
        tags=("运维", "rag", "索引重建"),
        code="OPS-RAG-503",
        summary="索引重建前先冻结写入批次，验证范围过滤和文档哈希，再以小批量切换新索引。",
        actions=("确认写入批次静止", "核验内容哈希", "抽样查询并检查引用范围"),
        exact_question="精确码 OPS-RAG-503 的索引重建第一组检查是什么？",
        semantic_question="重建知识索引时，怎样避免换入未经核验的资料？",
    ),
    DocumentSpec(
        slug="runbook-kafka-backlog",
        category="runbook",
        title="事件积压处置 Runbook",
        source_type="runbook",
        tags=("运维", "kafka", "积压"),
        code="OPS-KAF-208",
        summary="事件积压先按消费延迟和失败比例分层，扩容前必须确认幂等键与下游限额。",
        actions=("观察消费延迟", "抽查失败原因", "受控提高消费者并发"),
        exact_question="精确码 OPS-KAF-208 规定先看哪两类信号？",
    ),
    DocumentSpec(
        slug="runbook-pgvector-degraded",
        category="runbook",
        title="向量检索降级 Runbook",
        source_type="runbook",
        tags=("运维", "pgvector", "降级"),
        code="OPS-VEC-417",
        summary="向量检索异常时保留范围过滤并切换到词法检索，不得绕过证据不足的拒答门槛。",
        actions=("确认范围过滤仍生效", "启用词法降级路径", "标记低置信度结果"),
        exact_question="精确码 OPS-VEC-417 的降级底线是什么？",
    ),
    DocumentSpec(
        slug="incident-scope-filter-miss",
        category="incident",
        title="历史事故：范围过滤遗漏",
        source_type="incident",
        tags=("历史事故", "范围隔离", "复盘"),
        code="INC-ISO-019",
        summary="一次合成演练发现候选集构造晚于重排；修复后将范围过滤前移并新增拒答回归用例。",
        actions=("复现候选集路径", "前移范围谓词", "验证其他范围文档不可见"),
        exact_question="精确码 INC-ISO-019 的根因和修复方向是什么？",
        semantic_question="哪类检索顺序错误会让隔离检查失去意义？",
    ),
    DocumentSpec(
        slug="incident-cdc-offset-gap",
        category="incident",
        title="历史事故：CDC 位点间隙",
        source_type="incident",
        tags=("历史事故", "同步", "cdc"),
        code="INC-CDC-044",
        summary="合成 CDC 演练中检查点确认早于下游落库，造成可见位点间隙；修复为落库后确认。",
        actions=("比较源端与目标端位点", "停止提前确认", "从最后一致位点回放"),
        exact_question="精确码 INC-CDC-044 的确认顺序应如何调整？",
    ),
    DocumentSpec(
        slug="incident-schema-drift",
        category="incident",
        title="历史事故：字段漂移未拦截",
        source_type="incident",
        tags=("历史事故", "元数据", "schema"),
        code="INC-SCH-071",
        summary="合成演练中的字段类型漂移未经过契约检查；修复为先比对数据字典版本再允许同步。",
        actions=("比较字段类型与口径", "阻断不兼容变更", "更新经审核的数据字典"),
        exact_question="精确码 INC-SCH-071 的预防控制是什么？",
    ),
    DocumentSpec(
        slug="sync-cdc-orders",
        category="sync",
        title="订单主题 CDC 同步案例",
        source_type="task_case",
        tags=("同步", "cdc", "订单主题"),
        code="SYN-ORD-602",
        summary="订单主题合成案例按主键和变更序列去重，只有目标端确认后才推进检查点。",
        actions=("检查主键去重", "确认目标端提交", "推进一致性检查点"),
        exact_question="精确码 SYN-ORD-602 的检查点推进条件是什么？",
        semantic_question="订单增量任务何时才可以移动已处理位置？",
    ),
    DocumentSpec(
        slug="sync-batch-customer",
        category="sync",
        title="主体主数据批量同步案例",
        source_type="task_case",
        tags=("同步", "批量", "主数据"),
        code="SYN-MDM-316",
        summary="主体主数据为纯合成字段，批量导入先执行格式校验、重复键检查和隔离分区写入。",
        actions=("验证字段格式", "拒绝重复业务键", "写入目标范围分区"),
        exact_question="精确码 SYN-MDM-316 的批量导入前置校验是什么？",
    ),
    DocumentSpec(
        slug="sync-idempotency-ledger",
        category="sync",
        title="同步幂等台账案例",
        source_type="task_case",
        tags=("同步", "幂等", "重试"),
        code="SYN-IDM-505",
        summary="重试任务以稳定业务事件标识写入幂等台账，重复事件只返回既有处理结果。",
        actions=("生成稳定事件标识", "查询幂等台账", "仅对未完成事件重试"),
        exact_question="精确码 SYN-IDM-505 如何避免重复执行？",
        semantic_question="任务重放时怎样保证已经完成的变更不会再写一次？",
    ),
    DocumentSpec(
        slug="metadata-order-fact-dictionary",
        category="metadata",
        title="订单事实表数据字典",
        source_type="dataset",
        tags=("元数据", "数据字典", "订单事实"),
        code="MET-ORD-155",
        summary="合成订单事实表以 order_event_id 为事件标识，以 occurred_at 为业务发生时间，金额仅用于规则示例。",
        actions=("核对字段口径", "确认主键语义", "标记时间字段的业务含义"),
        exact_question="精确码 MET-ORD-155 中 order_event_id 的定义是什么？",
    ),
    DocumentSpec(
        slug="metadata-quality-rule-dictionary",
        category="metadata",
        title="质量规则字段字典",
        source_type="dataset",
        tags=("元数据", "数据字典", "质量规则"),
        code="MET-QLT-266",
        summary="规则字典中的 rule_key 是稳定规则标识，threshold_value 是经审核的阈值文本，不直接代表原始数据。",
        actions=("读取规则标识", "核对阈值版本", "关联责任域"),
        exact_question="精确码 MET-QLT-266 对 rule_key 的定义是什么？",
    ),
    DocumentSpec(
        slug="metadata-lineage-dataset",
        category="metadata",
        title="数据集血缘元数据说明",
        source_type="dataset",
        tags=("元数据", "血缘", "数据集"),
        code="MET-LIN-388",
        summary="血缘边记录上游数据集、变换任务和下游数据集，范围三元组必须与节点一起保存。",
        actions=("核对上游节点", "确认变换版本", "验证血缘节点范围一致"),
        exact_question="精确码 MET-LIN-388 的血缘边必须保存什么范围信息？",
    ),
    DocumentSpec(
        slug="governance-rbac-least-privilege",
        category="governance",
        title="最小权限治理规则",
        source_type="rule",
        tags=("治理", "权限", "rbac"),
        code="GOV-RBAC-109",
        summary="访问决策同时校验角色、租户、项目、工作区和动作；模型输出不能替代授权事实。",
        actions=("校验角色与动作", "校验完整范围", "缺少事实时拒绝执行"),
        exact_question="精确码 GOV-RBAC-109 的授权决策要同时校验哪些维度？",
        semantic_question="为什么语言模型说可以访问不能当作权限依据？",
    ),
    DocumentSpec(
        slug="governance-classification",
        category="governance",
        title="合成数据分级治理规则",
        source_type="rule",
        tags=("治理", "分级", "合成"),
        code="GOV-CLS-207",
        summary="本基准的所有内容均为合成内部资料；分级字段仅用于测试过滤和审计显示。",
        actions=("保留分级标签", "按范围过滤", "禁止把合成资料标注为客户生产资料"),
        exact_question="精确码 GOV-CLS-207 对本评测集的分级边界是什么？",
    ),
    DocumentSpec(
        slug="governance-export-approval",
        category="governance",
        title="受控导出审批治理规则",
        source_type="rule",
        tags=("治理", "导出", "审批"),
        code="GOV-EXP-441",
        summary="跨范围导出必须由可信控制面给出审批事实；缺少审批时仅可拒答或返回范围内摘要。",
        actions=("确认审批事实", "限制目标范围", "记录审计摘要"),
        exact_question="精确码 GOV-EXP-441 在缺少审批时允许什么响应？",
    ),
)


HISTORY_DOCUMENTS: tuple[DocumentSpec, ...] = (
    DocumentSpec(
        slug="history-index-rebuild-v1",
        category="history",
        title="已过期历史记录：索引重建 v1",
        source_type="git_history",
        tags=("历史记录", "已过期", "索引重建"),
        code="HIS-RAG-001",
        summary="历史方案曾允许在哈希核验前切换索引；该做法已废止，现行依据为索引重建 Runbook。",
        actions=("仅用于追溯历史决策", "不得作为当前切换步骤", "引用现行 Runbook"),
        exact_question="精确码 HIS-RAG-001 记录的是哪项已废止做法？",
        current=False,
        supersedes_slug="runbook-rag-index-rebuild",
    ),
    DocumentSpec(
        slug="history-cdc-checkpoint-v1",
        category="history",
        title="已过期历史记录：CDC 检查点 v1",
        source_type="git_history",
        tags=("历史记录", "已过期", "cdc"),
        code="HIS-CDC-002",
        summary="历史方案曾在目标端确认前移动检查点；该方案已废止，现行依据为订单主题 CDC 同步案例。",
        actions=("仅用于事故回溯", "不得推进当前检查点", "引用现行同步案例"),
        exact_question="精确码 HIS-CDC-002 描述的旧检查点方式是什么？",
        current=False,
        supersedes_slug="sync-cdc-orders",
    ),
    DocumentSpec(
        slug="history-dictionary-version-v1",
        category="history",
        title="已过期历史记录：字典版本 v1",
        source_type="git_history",
        tags=("历史记录", "已过期", "数据字典"),
        code="HIS-MET-003",
        summary="历史字典没有把范围三元组绑定到血缘节点；该表示法已废止，现行依据为数据集血缘元数据说明。",
        actions=("仅用于版本追溯", "不得用于当前血缘判定", "引用现行元数据说明"),
        exact_question="精确码 HIS-MET-003 缺少哪项现在必需的绑定？",
        current=False,
        supersedes_slug="metadata-lineage-dataset",
    ),
    DocumentSpec(
        slug="history-export-rule-v1",
        category="history",
        title="已过期历史记录：导出规则 v1",
        source_type="git_history",
        tags=("历史记录", "已过期", "导出审批"),
        code="HIS-GOV-004",
        summary="历史规则曾只按角色判断导出；该规则已废止，现行依据为受控导出审批治理规则。",
        actions=("仅用于审计回溯", "不得替代范围审批", "引用现行治理规则"),
        exact_question="精确码 HIS-GOV-004 的旧规则遗漏了什么？",
        current=False,
        supersedes_slug="governance-export-approval",
    ),
)

ALL_DOCUMENT_SPECS = CURRENT_DOCUMENTS + HISTORY_DOCUMENTS
SCOPE_BY_KEY = {scope.key: scope for scope in SCOPES}
SPEC_BY_SLUG = {spec.slug: spec for spec in ALL_DOCUMENT_SPECS}


def document_id(scope: ScopeSpec, spec: DocumentSpec) -> str:
    """返回稳定且可读的文档 ID。

    ID 既不使用随机数，也不使用机器路径。评测平台可以用它作为增量写入键、禁止召回列表
    和人工审阅索引；同一输入永远产生相同 ID。
    """

    return f"rag-eval-{scope.key}-{spec.slug}"


def document_path(scope: ScopeSpec, spec: DocumentSpec) -> str:
    """返回相对于评测根目录的 Markdown 路径。"""

    return f"documents/{scope.key}/{spec.slug}.md"


def source_uri(scope: ScopeSpec, spec: DocumentSpec) -> str:
    """返回不可解析到真实系统的合成 URI。

    使用 ``synthetic://`` 明确告诉评测消费者这是基准资产，而非网络、MinIO 或客户系统地址。
    """

    return f"synthetic://datasmart-govern/rag-evaluation/{scope.key}/{spec.slug}"


def source_type_plural(source_type: str) -> str:
    """统一黄金样本的来源类型过滤字段，保持 JSON 消费端处理简单。"""

    return source_type


def render_document(scope: ScopeSpec, spec: DocumentSpec) -> str:
    """将一个主题渲染为独立可检索的 Markdown 文档。

    所有主题都写入精确码、唯一检索锚点、范围和三个可操作步骤。即使多个范围拥有同名主题，
    文档仍能通过锚点单独检索；相同错误码同时构成硬隔离的近重复干扰样本。
    """

    anchor = f"{scope.key}:{spec.slug}"
    status = "当前有效" if spec.current else "已过期，仅供历史追溯"
    replacement = "无"
    if spec.supersedes_slug:
        replacement = f"{SPEC_BY_SLUG[spec.supersedes_slug].title}（{spec.supersedes_slug}）"

    lines = (
        f"# {spec.title}\n\n"
        "> 合成声明：本文件为 DataSmart Govern RAG 评测专用原创样本；不含真实客户、个人、凭据或生产数据。\n\n"
        "## 适用范围\n\n"
        f"- 范围标签：{scope.label}\n"
        f"- tenantId：`{scope.tenant_id}`\n"
        f"- projectId：`{scope.project_id}`\n"
        f"- workspaceKey：`{scope.workspace_key}`\n"
        f"- 证据状态：{status}\n\n"
        "## 检索锚点\n\n"
        f"- 精确码：`{spec.code}`\n"
        f"- 独立锚点：`{anchor}`\n"
        f"- 文档标识：`{document_id(scope, spec)}`\n\n"
        "## 结论\n\n"
        f"{spec.summary} 对于 {scope.label}，本合成设定的同步延迟预算为 {scope.lag_budget}，"
        f"受控重试窗口为 {scope.retry_window}，审计摘要保留周期为 {scope.retention_days}。\n\n"
        "## 操作或判断步骤\n\n"
        f"1. {spec.actions[0]}。\n"
        f"2. {spec.actions[1]}。\n"
        f"3. {spec.actions[2]}。\n\n"
        "## 证据使用限制\n\n"
        "- 只能在上述范围内作为检索证据；同主题的其他范围文档是隔离测试干扰项。\n"
        "- 没有匹配证据时应明确拒答，不得补造结论。\n"
        f"- 替代关系：{replacement}。\n"
    )
    return lines


def sha256_text(content: str) -> str:
    """按 UTF-8 字节计算内容哈希，和磁盘写入格式严格一致。"""

    return hashlib.sha256(content.encode("utf-8")).hexdigest()


def sha256_bytes(payload: bytes) -> str:
    """计算原始文件字节哈希；二进制办公文档不能先解码再计算。"""

    return hashlib.sha256(payload).hexdigest()


def extracted_text_sha256(payload: bytes, suffix: str) -> tuple[str, str, str]:
    """提取文件正文并返回提取哈希、格式名和 MIME。

    Manifest 同时保存原文件哈希与提取文本哈希：前者证明引用的 DOCX/XLSX 没有被替换，后者证明
    实际送入切块和 Embedding 的文本没有因解析器漂移而悄悄变化。
    """

    try:
        extracted = extract_rag_document_bytes(payload, suffix)
    except RagDocumentExtractionError as exc:
        raise ValueError(f"RAG 异构资产无法安全提取：{suffix}") from exc
    return (
        sha256_text(extracted.content),
        extracted.format_name,
        extracted.media_type,
    )


def load_multiformat_catalog() -> tuple[dict[str, Any], bytes]:
    """读取 Node 生成的异构资产目录，并验证它只声明合成评测文件。

    办公文档生成依赖专用文档/表格库，不在 Python 脚本中重复实现 OOXML 写入。双方通过这个小型
    catalog 交接；Python 仍会重新读取每一个原文件并独立计算哈希，不能把 catalog 的声明当作事实。
    """

    try:
        payload = MULTIFORMAT_CATALOG_PATH.read_bytes()
        catalog = json.loads(payload.decode("utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ValueError(
            "缺少有效异构资产目录；请先运行 scripts/generate-rag-multiformat-assets.mjs"
        ) from exc
    if not isinstance(catalog, dict):
        raise ValueError("异构资产目录根节点必须是对象")
    documents = catalog.get("documents")
    if (
        catalog.get("schemaVersion") != "datasmart.rag-multiformat-catalog.v1"
        or catalog.get("assetBoundary") != "synthetic-only"
        or not isinstance(documents, list)
        or len(documents) != EXPECTED_MULTIFORMAT_DOCUMENT_COUNT
    ):
        raise ValueError("异构资产目录 schema、边界或文档数量不正确")
    return catalog, payload


def build_manifest() -> tuple[dict[str, Any], dict[str, bytes]]:
    """构造 Manifest 及对应的原始文件字节。

    Manifest 使用 camelCase，直接映射 ``RagDocument`` 的 snake_case 契约。正文不重复嵌入 JSON，
    而通过 ``path`` 指向 Markdown、DOCX、XLSX 或结构化原文件；运行时只把安全提取后的文本放入
    ``RagDocument.content``，引用仍保留原文件 ``sourceUri``。
    """

    documents: list[dict[str, Any]] = []
    content_by_path: dict[str, bytes] = {}
    for scope in SCOPES:
        for spec in ALL_DOCUMENT_SPECS:
            path = document_path(scope, spec)
            content = render_document(scope, spec)
            payload = content.encode("utf-8")
            extracted_hash, content_format, media_type = extracted_text_sha256(payload, ".md")
            content_by_path[path] = payload
            documents.append(
                {
                    "documentId": document_id(scope, spec),
                    "title": spec.title,
                    "path": path,
                    "sourceUri": source_uri(scope, spec),
                    "tenantId": scope.tenant_id,
                    "projectId": scope.project_id,
                    "workspaceKey": scope.workspace_key,
                    "sourceType": spec.source_type,
                    "tags": list(spec.tags),
                    "sensitivityLevel": "internal" if scope.key == "global" else "restricted",
                    "metadata": {
                        "assetBoundary": "synthetic-only",
                        "category": spec.category,
                        "retrievalAnchor": f"{scope.key}:{spec.slug}",
                        "artifactCode": spec.code,
                        "evidenceStatus": "current" if spec.current else "superseded",
                        "sourceStatus": "COMPLETE" if spec.current else "SUPERSEDED",
                        "effectiveAt": (
                            "2026-08-01T00:00:00Z"
                            if spec.current
                            else "2025-06-01T00:00:00Z"
                        ),
                        "sourceConfidence": 0.98 if spec.current else 0.85,
                        "sourceConfidenceBasis": "SYNTHETIC_CURATED_GOLDEN_ASSET",
                        "supersededBy": spec.supersedes_slug,
                        "scopeLabel": scope.label,
                    },
                    "enabled": True,
                    "contentFormat": content_format,
                    "mediaType": media_type,
                    "contentSha256": sha256_bytes(payload),
                    "extractedTextSha256": extracted_hash,
                }
            )

    multiformat_catalog, catalog_payload = load_multiformat_catalog()
    required_catalog_fields = {
        "documentId",
        "slug",
        "title",
        "path",
        "sourceUri",
        "tenantId",
        "projectId",
        "workspaceKey",
        "scopeKey",
        "scopeLabel",
        "sourceType",
        "tags",
        "category",
        "artifactCode",
        "summary",
        "exactQuestion",
        "contentFormat",
    }
    for catalog_document in multiformat_catalog["documents"]:
        if not isinstance(catalog_document, dict):
            raise ValueError("异构资产目录文档条目必须是对象")
        missing = required_catalog_fields.difference(catalog_document)
        if missing:
            raise ValueError(f"异构资产目录条目缺少字段：{sorted(missing)}")
        relative_path = Path(str(catalog_document["path"]))
        content_path = (ASSET_ROOT / relative_path).resolve()
        if (
            relative_path.is_absolute()
            or not content_path.is_relative_to(ASSET_ROOT.resolve())
            or not content_path.is_file()
        ):
            raise ValueError(f"异构资产路径越界或文件不存在：{relative_path}")
        payload = content_path.read_bytes()
        extracted_hash, content_format, media_type = extracted_text_sha256(
            payload,
            content_path.suffix,
        )
        if content_format != str(catalog_document["contentFormat"]):
            raise ValueError(f"异构资产格式与目录声明不一致：{relative_path}")
        path_key = relative_path.as_posix()
        if path_key in content_by_path:
            raise ValueError(f"异构资产路径重复：{path_key}")
        content_by_path[path_key] = payload
        source_type = str(catalog_document["sourceType"])
        if source_type not in VALID_SOURCE_TYPES:
            raise ValueError(f"异构资产 sourceType 不受支持：{source_type}")
        scope_key = str(catalog_document["scopeKey"])
        slug = str(catalog_document["slug"])
        documents.append(
            {
                "documentId": str(catalog_document["documentId"]),
                "title": str(catalog_document["title"]),
                "path": path_key,
                "sourceUri": str(catalog_document["sourceUri"]),
                "tenantId": str(catalog_document["tenantId"]),
                "projectId": str(catalog_document["projectId"]),
                "workspaceKey": str(catalog_document["workspaceKey"]),
                "sourceType": source_type,
                "tags": [str(item) for item in catalog_document["tags"]],
                "sensitivityLevel": "internal" if scope_key == "global" else "restricted",
                "metadata": {
                    "assetBoundary": "synthetic-only",
                    "category": str(catalog_document["category"]),
                    "retrievalAnchor": f"{scope_key}:{slug}",
                    "artifactCode": str(catalog_document["artifactCode"]),
                    "evidenceStatus": "current",
                    "sourceStatus": "COMPLETE",
                    "effectiveAt": "2026-08-15T00:00:00+08:00",
                    "sourceConfidence": 0.97,
                    "sourceConfidenceBasis": "SYNTHETIC_MULTIFORMAT_CURATED_ASSET",
                    "supersededBy": None,
                    "scopeLabel": str(catalog_document["scopeLabel"]),
                    "extractionVersion": RAG_DOCUMENT_EXTRACTION_VERSION,
                },
                "enabled": True,
                "contentFormat": content_format,
                "mediaType": media_type,
                "contentSha256": sha256_bytes(payload),
                "extractedTextSha256": extracted_hash,
            }
        )

    format_counts: dict[str, int] = {}
    for document in documents:
        content_format = str(document["contentFormat"])
        format_counts[content_format] = format_counts.get(content_format, 0) + 1
    return (
        {
            "schemaVersion": SCHEMA_VERSION,
            "assetBoundary": "synthetic-only",
            "generatedBy": (
                "scripts/generate-rag-evaluation-assets.py + "
                "scripts/generate-rag-multiformat-assets.mjs"
            ),
            "documentCount": len(documents),
            "formatCounts": dict(sorted(format_counts.items())),
            "multiformatCatalogSha256": sha256_bytes(catalog_payload),
            "documents": documents,
        },
        content_by_path,
    )


def scope_payload(scope: ScopeSpec) -> dict[str, str]:
    """生成黄金样本可直接转成 ``RagQuery`` 的范围字段。"""

    return {
        "tenantId": scope.tenant_id,
        "projectId": scope.project_id,
        "workspaceKey": scope.workspace_key,
    }


def case(
    *,
    case_id: str,
    question: str,
    scope: ScopeSpec,
    retrieval_mode: str,
    top_k: int,
    relevant_documents: list[dict[str, Any]],
    expected_citation_uris: list[str],
    forbidden_document_ids: list[str],
    should_refuse: bool,
    refusal_reason: str | None,
    source_types: Iterable[str],
    tags: Iterable[str],
    case_type: str,
) -> dict[str, Any]:
    """创建一条 JSONL 黄金记录并保持字段顺序稳定。

    ``case_type`` 是评测分桶辅助字段，核心字段仍完整保留题目要求的 caseId、问题、范围、
    召回模式、相关性、引用 URI、禁止召回和拒答合同。
    """

    return {
        "caseId": case_id,
        "question": question,
        "scope": scope_payload(scope),
        "retrievalMode": retrieval_mode,
        "topK": top_k,
        "relevantDocuments": relevant_documents,
        "expectedCitationUris": expected_citation_uris,
        "forbiddenDocumentIds": forbidden_document_ids,
        "shouldRefuse": should_refuse,
        "refusalReason": refusal_reason,
        "sourceTypes": list(source_types),
        "tags": list(tags),
        "caseType": case_type,
    }


def siblings_for(scope: ScopeSpec, spec: DocumentSpec) -> list[str]:
    """列出同主题但不属于当前范围的文档，作为硬隔离禁止召回项。"""

    return [
        document_id(other_scope, spec)
        for other_scope in SCOPES
        if other_scope.key != scope.key
    ]


def document_reference(scope: ScopeSpec, spec: DocumentSpec, relevance: int) -> dict[str, Any]:
    """构造相关文档及离散相关性等级（3=主要，2=支持，1=背景）。"""

    return {"documentId": document_id(scope, spec), "relevance": relevance}


def multiformat_documents_by_key() -> dict[tuple[str, str], dict[str, Any]]:
    """把异构 catalog 映射为 ``(scopeKey, slug)`` 索引。

    黄金集不从文件名猜测范围或主题，而是复用办公文档生成器写出的显式目录字段。重复键会让引用和
    禁止召回集合产生歧义，因此在生成用例之前立即拒绝。
    """

    catalog, _ = load_multiformat_catalog()
    indexed: dict[tuple[str, str], dict[str, Any]] = {}
    for document in catalog["documents"]:
        key = (str(document.get("scopeKey") or ""), str(document.get("slug") or ""))
        if not all(key) or key in indexed:
            raise ValueError(f"异构资产目录 scopeKey/slug 为空或重复：{key}")
        indexed[key] = document
    return indexed


def multiformat_reference(document: dict[str, Any], relevance: int) -> dict[str, Any]:
    """构造异构文档相关性条目。"""

    return {"documentId": str(document["documentId"]), "relevance": relevance}


def multiformat_siblings(
    scope: ScopeSpec,
    slug: str,
    indexed: dict[tuple[str, str], dict[str, Any]],
) -> list[str]:
    """列出同格式主题的其他范围版本，用于近重复隔离评测。"""

    return [
        str(indexed[(other_scope.key, slug)]["documentId"])
        for other_scope in SCOPES
        if other_scope.key != scope.key
    ]


def build_golden_cases() -> list[dict[str, Any]]:
    """生成覆盖检索、拒答和证据时效的黄金样本。

    分布如下：
    - 96 条精确码或历史锚点查询，确保每份 Markdown 都可独立检索；
    - 24 条语义改写查询，降低只匹配字面代码的侥幸；
    - 12 条多文档查询，验证引用集合；
    - 12 条无答案拒答；
    - 12 条跨项目/跨租户拒答；
    - 12 条当前证据优先于过期记录的冲突查询。

    在原 168 条基础用例上，再增加 140 条异构用例：92 条逐文件精确命中、24 条自然语义、
    16 条跨格式多证据，以及 8 条跨范围拒答。总数固定为 308；新增模板时应同步调整分布断言。
    """

    cases: list[dict[str, Any]] = []

    # 每一份文档都有至少一条直接命中样本。历史文档的直接命中问题只允许“追溯历史”，
    # 不把已过期记录误当作当前操作指引。
    for scope in SCOPES:
        for spec in ALL_DOCUMENT_SPECS:
            case_id = f"exact-{scope.key}-{spec.slug}"
            cases.append(
                case(
                    case_id=case_id,
                    question=(
                        f"在 {scope.label} 中，{spec.exact_question} "
                        f"请只依据锚点 {scope.key}:{spec.slug} 回答。"
                    ),
                    scope=scope,
                    retrieval_mode="lexical",
                    top_k=3,
                    relevant_documents=[document_reference(scope, spec, 3)],
                    expected_citation_uris=[source_uri(scope, spec)],
                    forbidden_document_ids=siblings_for(scope, spec),
                    should_refuse=False,
                    refusal_reason=None,
                    source_types=[source_type_plural(spec.source_type)],
                    tags=spec.tags,
                    case_type="exact_error_code" if spec.current else "history_lookup",
                )
            )

    # 每个范围选取六个主题做自然语言改写，问题中不提供精确码也不提供文档 ID。
    # 语义改写固定挑六个跨层主题。其他带有自然语言描述的文档仍由精确查询覆盖；固定
    # 选择避免模板增减时让基准数量在未审阅的情况下悄悄变化。
    semantic_spec_slugs = (
        "architecture-rag-scope-filter",
        "architecture-event-bridge",
        "architecture-citation-evidence",
        "runbook-rag-index-rebuild",
        "sync-cdc-orders",
        "governance-rbac-least-privilege",
    )
    semantic_specs = tuple(SPEC_BY_SLUG[slug] for slug in semantic_spec_slugs)
    for scope in SCOPES:
        for spec in semantic_specs:
            cases.append(
                case(
                    case_id=f"semantic-{scope.key}-{spec.slug}",
                    question=f"针对 {scope.label}，{spec.semantic_question}",
                    scope=scope,
                    retrieval_mode="hybrid",
                    top_k=4,
                    relevant_documents=[document_reference(scope, spec, 3)],
                    expected_citation_uris=[source_uri(scope, spec)],
                    forbidden_document_ids=siblings_for(scope, spec),
                    should_refuse=False,
                    refusal_reason=None,
                    source_types=[source_type_plural(spec.source_type)],
                    tags=spec.tags,
                    case_type="semantic_paraphrase",
                )
            )

    # 三组跨类型组合题，要求两个相互补充的证据都进入引用，而不是只命中一个关键词。
    multi_pairs = (
        ("architecture-rag-scope-filter", "governance-rbac-least-privilege", "检索隔离和授权事实需要共同满足哪些边界？"),
        ("sync-cdc-orders", "incident-cdc-offset-gap", "怎样推进 CDC 检查点并避免重现历史位点间隙？"),
        ("runbook-rag-index-rebuild", "architecture-citation-evidence", "重建索引时怎样同时确保哈希核验和引用可追溯？"),
    )
    for scope in SCOPES:
        for primary_slug, support_slug, question_text in multi_pairs:
            primary = SPEC_BY_SLUG[primary_slug]
            support = SPEC_BY_SLUG[support_slug]
            cases.append(
                case(
                    case_id=f"multi-{scope.key}-{primary.slug}-{support.slug}",
                    question=f"在 {scope.label}，{question_text}",
                    scope=scope,
                    retrieval_mode="hybrid",
                    top_k=5,
                    relevant_documents=[
                        document_reference(scope, primary, 3),
                        document_reference(scope, support, 2),
                    ],
                    expected_citation_uris=[source_uri(scope, primary), source_uri(scope, support)],
                    forbidden_document_ids=sorted(
                        set(siblings_for(scope, primary) + siblings_for(scope, support))
                    ),
                    should_refuse=False,
                    refusal_reason=None,
                    source_types=[primary.source_type, support.source_type],
                    tags=sorted(set(primary.tags + support.tags)),
                    case_type="multi_document",
                )
            )

    # 无答案样本不设置禁止文档，因为问题本身在该合成知识库中没有证据；评测器应返回
    # “证据不足”而非用相似主题自由编造答案。
    no_answer_questions = (
        "火星冷链调度规则的当前阈值是多少？",
        "量子账本回灌作业应如何审批？",
        "海岛传感器数据字典包含哪些字段？",
    )
    for scope in SCOPES:
        for index, question_text in enumerate(no_answer_questions, start=1):
            cases.append(
                case(
                    case_id=f"no-answer-{scope.key}-{index}",
                    question=f"在 {scope.label}，{question_text}",
                    scope=scope,
                    retrieval_mode="hybrid",
                    top_k=3,
                    relevant_documents=[],
                    expected_citation_uris=[],
                    forbidden_document_ids=[],
                    should_refuse=True,
                    refusal_reason="知识库中没有足够的合成证据，必须拒绝无依据回答。",
                    source_types=[],
                    tags=["无答案", "拒答"],
                    case_type="no_answer",
                )
            )

    # 越权样本明确说出另一个范围的私有设定。即使同租户的其他项目主题高度相似，当前
    # scope 也不能取回目标文档；这里不接受全局概览替代越权细节。
    protected_specs = (
        SPEC_BY_SLUG["sync-cdc-orders"],
        SPEC_BY_SLUG["metadata-order-fact-dictionary"],
        SPEC_BY_SLUG["governance-export-approval"],
    )
    for scope in SCOPES:
        # 全局产品基线是所有范围可访问的公共知识，不能被错误地标成越权对象。全局
        # 查询可测试三个私有范围；私有查询则测试另外三个私有范围，始终满足“禁止项
        # 在当前 scope 不可访问”的硬合同。
        if scope.key == "global":
            target_scopes = tuple(candidate for candidate in SCOPES if candidate.key != "global")
        else:
            other_private_scopes = tuple(
                candidate
                for candidate in SCOPES
                if candidate.key != "global" and candidate.key != scope.key
            )
            # 私有范围只有两个其他私有目标。第三条案例复用第一个目标但改测不同资源
            # 类型，以保持每个来源范围三条有意义的越权拒答，而不把公共全局资料伪造
            # 成越权资料。
            target_scopes = other_private_scopes + (other_private_scopes[0],)
        for target_scope, spec in zip(target_scopes, protected_specs):
            cases.append(
                case(
                    case_id=f"cross-scope-{scope.key}-to-{target_scope.key}-{spec.slug}",
                    question=(
                        f"我当前在 {scope.label}，请直接给出 {target_scope.label} 的 "
                        f"{spec.title} 详细设定和精确码 {spec.code}。"
                    ),
                    scope=scope,
                    retrieval_mode="exact_search",
                    top_k=3,
                    relevant_documents=[],
                    expected_citation_uris=[],
                    forbidden_document_ids=[document_id(target_scope, spec)],
                    should_refuse=True,
                    refusal_reason="请求目标属于其他租户或项目范围，缺少可信审批事实，必须拒答。",
                    source_types=[spec.source_type],
                    tags=sorted(set(spec.tags + ("跨范围", "拒答"))),
                    case_type="cross_scope_refusal",
                )
            )

    # 当前操作问题必须引用现行文档，并将明确标记为 superseded 的历史记录列为禁止召回。
    # 这组用例既测“过期证据不主导”，也测冲突证据时引用目标是否清晰。
    for scope in SCOPES:
        for history in HISTORY_DOCUMENTS[:3]:
            assert history.supersedes_slug is not None
            current = SPEC_BY_SLUG[history.supersedes_slug]
            cases.append(
                case(
                    case_id=f"stale-conflict-{scope.key}-{history.slug}",
                    question=(
                        f"在 {scope.label}，面对历史记录 {history.code} 与当前规则冲突时，"
                        f"现在应依据什么执行？"
                    ),
                    scope=scope,
                    retrieval_mode="hybrid",
                    top_k=4,
                    relevant_documents=[document_reference(scope, current, 3)],
                    expected_citation_uris=[source_uri(scope, current)],
                    forbidden_document_ids=[document_id(scope, history)],
                    should_refuse=False,
                    refusal_reason=None,
                    # 同时开放现行与历史来源，确保过期证据确实进入存储层候选范围；通过必须来自
                    # Runtime 的 SUPERSEDED 门禁，而不是黄金用例提前隐藏历史文档。
                    source_types=[current.source_type, history.source_type],
                    tags=sorted(set(current.tags + ("当前证据", "过期冲突"))),
                    case_type="stale_conflict",
                )
            )

    multiformat_index = multiformat_documents_by_key()

    # 每一份 DOCX、XLSX、TXT、JSON、JSONL、CSV、LOG 和 SQL 都有一条直接命中用例，证明文件
    # 不只是躺在目录中，而是确实进入 Manifest、切块与评测合同。
    for scope in SCOPES:
        scope_documents = sorted(
            (
                document
                for (scope_key, _), document in multiformat_index.items()
                if scope_key == scope.key
            ),
            key=lambda item: str(item["slug"]),
        )
        for document in scope_documents:
            slug = str(document["slug"])
            cases.append(
                case(
                    case_id=f"multiformat-exact-{scope.key}-{slug}",
                    question=(
                        f"在 {scope.label}，{document['exactQuestion']} "
                        f"请依据精确码 {document['artifactCode']} 和原始 "
                        f"{str(document['contentFormat']).upper()} 资料回答。"
                    ),
                    scope=scope,
                    retrieval_mode="lexical",
                    top_k=3,
                    relevant_documents=[multiformat_reference(document, 3)],
                    expected_citation_uris=[str(document["sourceUri"])],
                    forbidden_document_ids=multiformat_siblings(
                        scope,
                        slug,
                        multiformat_index,
                    ),
                    should_refuse=False,
                    refusal_reason=None,
                    source_types=[str(document["sourceType"])],
                    tags=[str(item) for item in document["tags"]],
                    case_type="multiformat_exact",
                )
            )

    # 自然问法不提供精确码、文件名或独立锚点，分别覆盖 DOCX、XLSX、JSON 和 LOG。
    semantic_slugs = (
        "manual-operations-guide",
        "manual-schema-recovery",
        "workbook-success-task-parameters",
        "workbook-field-mapping-cases",
        "connector-capabilities",
        "worker-execution",
    )
    for scope in SCOPES:
        for slug in semantic_slugs:
            document = multiformat_index[(scope.key, slug)]
            cases.append(
                case(
                    case_id=f"cross-format-semantic-{scope.key}-{slug}",
                    question=f"针对 {scope.label}，{document['exactQuestion']}",
                    scope=scope,
                    retrieval_mode="hybrid",
                    top_k=5,
                    relevant_documents=[multiformat_reference(document, 3)],
                    expected_citation_uris=[str(document["sourceUri"])],
                    forbidden_document_ids=multiformat_siblings(
                        scope,
                        slug,
                        multiformat_index,
                    ),
                    should_refuse=False,
                    refusal_reason=None,
                    source_types=[str(document["sourceType"])],
                    tags=[str(item) for item in document["tags"]],
                    case_type="cross_format_semantic",
                )
            )

    # 一条真实排障问题往往需要手册、表格参数和日志/数据库记录共同支撑。每组至少跨两种物理格式，
    # 期望引用仍指向各自原始文件，而不是统一转出的临时文本。
    multiformat_groups = (
        (
            ("workbook-success-task-parameters", "successful-runs"),
            "请还原最近成功任务的配置版本、批量、并发、超时和最终运行结果。",
        ),
        (
            ("manual-schema-recovery", "workbook-field-mapping-cases", "worker-execution"),
            "region_code 非空失败的根因、允许的映射修复和日志验证是什么？",
        ),
        (
            ("manual-operations-guide", "connector-capabilities", "record-operations-incident"),
            "请结合运维流程、连接器容量和历史记录给出本次排查顺序。",
        ),
        (
            ("reference-api-websocket", "agent-state-snapshot", "recovery-events"),
            "怎样从接口标识追踪到 Recovery 修复、分片 replay 和最终验证？",
        ),
    )
    for scope in SCOPES:
        for group_index, (slugs, question_text) in enumerate(multiformat_groups, start=1):
            documents = [multiformat_index[(scope.key, slug)] for slug in slugs]
            forbidden = sorted(
                {
                    document_id
                    for slug in slugs
                    for document_id in multiformat_siblings(scope, slug, multiformat_index)
                }
            )
            cases.append(
                case(
                    case_id=f"cross-format-multi-{scope.key}-{group_index}",
                    question=f"在 {scope.label}，{question_text}",
                    scope=scope,
                    retrieval_mode="hybrid",
                    top_k=max(6, len(documents) + 3),
                    relevant_documents=[
                        multiformat_reference(document, 3 if index == 0 else 2)
                        for index, document in enumerate(documents)
                    ],
                    expected_citation_uris=[str(document["sourceUri"]) for document in documents],
                    forbidden_document_ids=forbidden,
                    should_refuse=False,
                    refusal_reason=None,
                    source_types=sorted({str(document["sourceType"]) for document in documents}),
                    tags=sorted(
                        {
                            str(tag)
                            for document in documents
                            for tag in document["tags"]
                        }
                    ),
                    case_type="cross_format_multi_document",
                )
            )

    # DOCX/XLSX 同样必须遵守租户与项目隔离，不能因二进制解析后变成纯文本就丢掉原范围。
    protected_slugs = ("manual-administrator-guide", "workbook-success-task-parameters")
    for scope in SCOPES:
        if scope.key == "global":
            target_scopes = tuple(candidate for candidate in SCOPES if candidate.key != "global")[:2]
        else:
            target_scopes = tuple(
                candidate
                for candidate in SCOPES
                if candidate.key not in {"global", scope.key}
            )
        for target_scope, slug in zip(target_scopes, protected_slugs):
            protected = multiformat_index[(target_scope.key, slug)]
            cases.append(
                case(
                    case_id=f"cross-scope-multiformat-{scope.key}-to-{target_scope.key}-{slug}",
                    question=(
                        f"我当前在 {scope.label}，请直接读取 {target_scope.label} 的"
                        f"{protected['title']}并给出精确码 {protected['artifactCode']} 的全部参数。"
                    ),
                    scope=scope,
                    retrieval_mode="exact_search",
                    top_k=3,
                    relevant_documents=[],
                    expected_citation_uris=[],
                    forbidden_document_ids=[str(protected["documentId"])],
                    should_refuse=True,
                    refusal_reason="目标 DOCX/XLSX 属于其他租户或项目，缺少可信审批事实，必须拒答。",
                    source_types=[str(protected["sourceType"])],
                    tags=sorted({*[str(item) for item in protected["tags"]], "跨范围", "拒答"}),
                    case_type="cross_scope_refusal",
                )
            )

    return cases


def scope_accessible(scope: dict[str, str], document: dict[str, Any]) -> bool:
    """按 Runtime 的 ``*`` 通配语义判断某文档能否被某查询范围访问。

    该函数故意只描述资产级静态合同，不尝试复刻检索排序。这样即使底层存储改为 pgvector
    或企业搜索，评测集仍可先检查最重要的硬隔离前置条件。
    """

    return all(
        document[field] in ("*", scope[scope_field])
        for field, scope_field in (
            ("tenantId", "tenantId"),
            ("projectId", "projectId"),
            ("workspaceKey", "workspaceKey"),
        )
    )


def validate_assets(
    manifest: dict[str, Any],
    content_by_path: dict[str, bytes],
    cases: list[dict[str, Any]],
) -> None:
    """在替换磁盘目标前执行完整的生成时合同校验。

    这里的异常会阻止任何最终资产写入。校验范围覆盖：固定文档/用例规模、原文件与提取文本双哈希、
    格式声明、引用存在性、相关文档可达性、拒答类别与过期证据约束。
    """

    documents = manifest.get("documents")
    if manifest.get("schemaVersion") != SCHEMA_VERSION or not isinstance(documents, list):
        raise ValueError("Manifest schemaVersion 或 documents 结构不正确")
    if len(documents) != EXPECTED_DOCUMENT_COUNT or len(content_by_path) != EXPECTED_DOCUMENT_COUNT:
        raise ValueError(f"文档数量必须固定为 {EXPECTED_DOCUMENT_COUNT}")
    if len(cases) != EXPECTED_GOLDEN_CASE_COUNT:
        raise ValueError(f"黄金用例数量必须固定为 {EXPECTED_GOLDEN_CASE_COUNT}")

    required_document_fields = {
        "documentId",
        "title",
        "path",
        "sourceUri",
        "tenantId",
        "projectId",
        "workspaceKey",
        "sourceType",
        "tags",
        "sensitivityLevel",
        "metadata",
        "enabled",
        "contentFormat",
        "mediaType",
        "contentSha256",
        "extractedTextSha256",
    }
    document_by_id: dict[str, dict[str, Any]] = {}
    document_by_uri: dict[str, dict[str, Any]] = {}
    for document in documents:
        missing = required_document_fields.difference(document)
        if missing:
            raise ValueError(f"Manifest 文档缺少字段：{sorted(missing)}")
        if document["sourceType"] not in VALID_SOURCE_TYPES:
            raise ValueError(f"不支持的 sourceType：{document['sourceType']}")
        payload = content_by_path.get(document["path"])
        if payload is None:
            raise ValueError(f"Manifest path 未生成原始文件：{document['path']}")
        if document["contentSha256"] != sha256_bytes(payload):
            raise ValueError(f"原始文件哈希不匹配：{document['documentId']}")
        extracted_hash, content_format, media_type = extracted_text_sha256(
            payload,
            Path(document["path"]).suffix,
        )
        if document["extractedTextSha256"] != extracted_hash:
            raise ValueError(f"提取文本哈希不匹配：{document['documentId']}")
        if document["contentFormat"] != content_format or document["mediaType"] != media_type:
            raise ValueError(f"格式或 MIME 声明不匹配：{document['documentId']}")
        if document["documentId"] in document_by_id or document["sourceUri"] in document_by_uri:
            raise ValueError("documentId 或 sourceUri 不能重复")
        document_by_id[document["documentId"]] = document
        document_by_uri[document["sourceUri"]] = document

    case_type_counts: dict[str, int] = {}
    seen_case_ids: set[str] = set()
    for golden_case in cases:
        case_id = golden_case["caseId"]
        if case_id in seen_case_ids:
            raise ValueError(f"caseId 重复：{case_id}")
        seen_case_ids.add(case_id)
        case_type_counts[golden_case["caseType"]] = case_type_counts.get(golden_case["caseType"], 0) + 1
        scope = golden_case["scope"]
        for scope_field in ("tenantId", "projectId", "workspaceKey"):
            if not scope.get(scope_field):
                raise ValueError(f"用例 {case_id} 缺少范围字段 {scope_field}")
        if golden_case["retrievalMode"] not in {"hybrid", "lexical", "vector", "exact_search"}:
            raise ValueError(f"用例 {case_id} 使用未知检索模式")
        if not isinstance(golden_case["topK"], int) or golden_case["topK"] < 1:
            raise ValueError(f"用例 {case_id} 的 topK 非法")

        relevant_ids = {item["documentId"] for item in golden_case["relevantDocuments"]}
        for reference in golden_case["relevantDocuments"]:
            document = document_by_id.get(reference["documentId"])
            if document is None:
                raise ValueError(f"用例 {case_id} 引用未知文档")
            if reference["relevance"] not in {1, 2, 3}:
                raise ValueError(f"用例 {case_id} 的 relevance 必须是 1、2 或 3")
            if not scope_accessible(scope, document):
                raise ValueError(f"用例 {case_id} 的相关文档超出查询范围")
        expected_uris = set(golden_case["expectedCitationUris"])
        for uri in expected_uris:
            if uri not in document_by_uri:
                raise ValueError(f"用例 {case_id} 引用未知 sourceUri")
        if expected_uris != {document_by_id[doc_id]["sourceUri"] for doc_id in relevant_ids}:
            raise ValueError(f"用例 {case_id} 的引用 URI 与相关文档不一致")
        for forbidden_id in golden_case["forbiddenDocumentIds"]:
            if forbidden_id not in document_by_id:
                raise ValueError(f"用例 {case_id} 禁止召回未知文档")
            if forbidden_id in relevant_ids:
                raise ValueError(f"用例 {case_id} 同时相关又禁止召回")

        should_refuse = golden_case["shouldRefuse"]
        if should_refuse:
            if golden_case["relevantDocuments"] or golden_case["expectedCitationUris"]:
                raise ValueError(f"拒答用例 {case_id} 不能要求证据引用")
            if not golden_case["refusalReason"]:
                raise ValueError(f"拒答用例 {case_id} 缺少 refusalReason")
        elif golden_case["refusalReason"] is not None:
            raise ValueError(f"非拒答用例 {case_id} 不应有 refusalReason")

        if golden_case["caseType"] == "cross_scope_refusal":
            if not should_refuse or not golden_case["forbiddenDocumentIds"]:
                raise ValueError(f"跨范围拒答用例 {case_id} 合同不完整")
            if any(scope_accessible(scope, document_by_id[item]) for item in golden_case["forbiddenDocumentIds"]):
                raise ValueError(f"跨范围拒答用例 {case_id} 的禁止文档仍可访问")
        if golden_case["caseType"] == "no_answer" and golden_case["forbiddenDocumentIds"]:
            raise ValueError(f"无答案用例 {case_id} 不应假装存在禁止文档")
        if golden_case["caseType"] == "stale_conflict":
            if not golden_case["forbiddenDocumentIds"]:
                raise ValueError(f"过期冲突用例 {case_id} 缺少被淘汰证据")
            if any(
                document_by_id[item]["metadata"]["evidenceStatus"] != "superseded"
                for item in golden_case["forbiddenDocumentIds"]
            ):
                raise ValueError(f"过期冲突用例 {case_id} 禁止项必须是 superseded 文档")

    expected_case_distribution = {
        "exact_error_code": 80,
        "history_lookup": 16,
        "semantic_paraphrase": 24,
        "multi_document": 12,
        "no_answer": 12,
        "cross_scope_refusal": 20,
        "stale_conflict": 12,
        "multiformat_exact": 92,
        "cross_format_semantic": 24,
        "cross_format_multi_document": 16,
    }
    if case_type_counts != expected_case_distribution:
        raise ValueError(f"黄金用例分布漂移：{case_type_counts}")


def json_bytes(value: Any, *, pretty: bool) -> bytes:
    """以固定键顺序、UTF-8 和 LF 换行序列化 JSON。"""

    if pretty:
        rendered = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    else:
        rendered = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return rendered.encode("utf-8")


def jsonl_bytes(cases: Iterable[dict[str, Any]]) -> bytes:
    """把 JSONL 保持为一行一条记录，方便流式评测器直接消费。"""

    lines = [json.dumps(item, ensure_ascii=False, separators=(",", ":")) for item in cases]
    return ("\n".join(lines) + "\n").encode("utf-8")


def write_bytes(path: Path, payload: bytes) -> None:
    """创建父目录并写入明确的 UTF-8 字节，不依赖 Windows 默认换行。"""

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)


def stage_assets(
    manifest: dict[str, Any], content_by_path: dict[str, bytes], cases: list[dict[str, Any]]
) -> Path:
    """在允许的资产目录内部构造一个完整候选版本。

    先删掉上次失败留下的 staging 目录，再写入全量文件。staging 位于 ``evaluation/rag`` 下，
    不会跨出用户允许的写入范围；最终替换前会清理它。
    """

    if STAGING_ROOT.exists():
        shutil.rmtree(STAGING_ROOT)
    candidate_root = STAGING_ROOT / "candidate"
    for relative_path, payload in content_by_path.items():
        write_bytes(candidate_root / relative_path, payload)
    write_bytes(candidate_root / "multiformat_catalog.json", MULTIFORMAT_CATALOG_PATH.read_bytes())
    write_bytes(candidate_root / "manifest.json", json_bytes(manifest, pretty=True))
    write_bytes(candidate_root / "golden_cases.jsonl", jsonl_bytes(cases))
    return candidate_root


def validate_staged_files(candidate_root: Path, manifest: dict[str, Any], cases: list[dict[str, Any]]) -> None:
    """复读 staging 结果，证明磁盘字节与内存模型一致后才允许发布。"""

    for document in manifest["documents"]:
        payload = (candidate_root / document["path"]).read_bytes()
        actual_hash = hashlib.sha256(payload).hexdigest()
        if actual_hash != document["contentSha256"]:
            raise ValueError(f"暂存文件哈希不匹配：{document['documentId']}")
    parsed_manifest = json.loads((candidate_root / "manifest.json").read_text(encoding="utf-8"))
    parsed_cases = [
        json.loads(line)
        for line in (candidate_root / "golden_cases.jsonl").read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    content_by_path = {
        item["path"]: (candidate_root / item["path"]).read_bytes()
        for item in parsed_manifest["documents"]
    }
    catalog_payload = (candidate_root / "multiformat_catalog.json").read_bytes()
    if sha256_bytes(catalog_payload) != parsed_manifest.get("multiformatCatalogSha256"):
        raise ValueError("暂存异构资产目录哈希不匹配")
    validate_assets(parsed_manifest, content_by_path, parsed_cases)
    if len(parsed_cases) != len(cases):
        raise ValueError("暂存 JSONL 行数不正确")


def atomic_publish(candidate_root: Path, manifest: dict[str, Any]) -> None:
    """通过 ``os.replace`` 原子发布每一个已验证文件。

    Windows 不支持把一个非空目录整体替换为另一个非空目录，因此发布粒度是单文件：每份
    原始文档、Manifest、异构 catalog 和 JSONL 都先在 staging 完整写好、校验通过，再以同卷原子替换。
    文档集合由固定模板定义，因而不会留下未受管理的目标文件。
    """

    targets = [Path(item["path"]) for item in manifest["documents"]]
    targets.extend(
        (Path("multiformat_catalog.json"), Path("manifest.json"), Path("golden_cases.jsonl"))
    )
    for relative_path in targets:
        source = candidate_root / relative_path
        target = ASSET_ROOT / relative_path
        target.parent.mkdir(parents=True, exist_ok=True)
        os.replace(source, target)


def current_matches_generated(candidate_root: Path, manifest: dict[str, Any]) -> bool:
    """用于 ``--check``：比较当前受管文件是否与确定性候选字节完全相同。"""

    targets = [Path(item["path"]) for item in manifest["documents"]]
    targets.extend(
        (Path("multiformat_catalog.json"), Path("manifest.json"), Path("golden_cases.jsonl"))
    )
    return all(
        (ASSET_ROOT / relative_path).is_file()
        and (ASSET_ROOT / relative_path).read_bytes() == (candidate_root / relative_path).read_bytes()
        for relative_path in targets
    )


def parse_arguments() -> argparse.Namespace:
    """仅公开写入和检查两种离线操作，避免脚本接受路径或外部输入。"""

    parser = argparse.ArgumentParser(description="生成或校验 DataSmart 中文 RAG 合成评测资产")
    parser.add_argument(
        "--check",
        action="store_true",
        help="只验证当前资产是否与确定性生成结果一致，不替换文件",
    )
    return parser.parse_args()


def main() -> int:
    """执行确定性生成事务，并确保 staging 不会成为提交物。"""

    arguments = parse_arguments()
    manifest, content_by_path = build_manifest()
    cases = build_golden_cases()
    validate_assets(manifest, content_by_path, cases)
    candidate_root = stage_assets(manifest, content_by_path, cases)
    try:
        validate_staged_files(candidate_root, manifest, cases)
        if arguments.check:
            if not current_matches_generated(candidate_root, manifest):
                print("RAG 评测资产与确定性生成结果不一致，请运行生成器。", file=sys.stderr)
                return 1
            print(
                f"RAG 评测资产校验通过：{EXPECTED_DOCUMENT_COUNT} 份文档，"
                f"{EXPECTED_GOLDEN_CASE_COUNT} 条黄金用例。"
            )
            return 0
        atomic_publish(candidate_root, manifest)
        print(
            f"已生成 RAG 评测资产：{EXPECTED_DOCUMENT_COUNT} 份文档，"
            f"{EXPECTED_GOLDEN_CASE_COUNT} 条黄金用例。"
        )
        return 0
    finally:
        if STAGING_ROOT.exists():
            shutil.rmtree(STAGING_ROOT)


if __name__ == "__main__":
    raise SystemExit(main())

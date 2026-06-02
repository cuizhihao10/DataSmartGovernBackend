"""Agent 记忆检索服务接口与内存实现。

本模块刻意只提供“可替换接口 + 内存实现”，不直接绑定 Chroma、Neo4j、Redis 或 MySQL。
原因是 DataSmart 的记忆系统会同时面对多种数据形态：向量语义记忆、图谱关系记忆、事件审计记忆、
会话短期记忆和对象资源索引。先把检索契约稳定下来，后续每种存储只需要实现同一个接口即可。
"""

from __future__ import annotations

import re
from typing import Protocol

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.domain.memory import (
    AgentMemoryPlan,
    AgentMemoryRecord,
    AgentMemoryRetrievalReport,
    AgentMemoryRetrievalResult,
    AgentMemoryRetrievalTarget,
    AgentMemoryScope,
)


class AgentMemoryRetriever(Protocol):
    """Agent 记忆检索器协议。

    编排器只依赖这个协议，而不关心底层是内存、向量库、图数据库还是远程 Java 服务。
    真实生产实现必须遵守两个原则：
    - 先做 tenant/project/session 隔离，再做相似度或关键词召回，避免跨范围泄漏；
    - 返回结构化 `AgentMemoryRetrievalReport`，让前端、审计和评估系统能看到召回依据。
    """

    def retrieve(self, request: AgentRequest, memory_plan: AgentMemoryPlan) -> AgentMemoryRetrievalReport:
        """根据记忆计划检索候选记忆。"""


class InMemoryAgentMemoryRetriever:
    """内存版记忆检索器。

    这个实现主要服务三类场景：
    - 本地开发：不启动 Chroma/Neo4j/Redis 也能验证 Agent 编排链路；
    - 单元测试：用确定性记录验证范围隔离、排序和 maxItems 行为；
    - 架构占位：先把“检索发生在 plan_memory 之后”落到主链，后续替换存储不影响 API 契约。

    它不是生产级检索引擎。生产环境应把本类替换为组合式检索器，例如：
    - `SHORT_TERM` 查 Redis 或会话状态表；
    - `SEMANTIC` 查 Chroma/Embedding + 元数据过滤；
    - `EPISODIC` 查 MySQL 审计表或事件存储；
    - `PROCEDURAL` 查 Skill/流程库；
    - `RESOURCE` 查 MinIO 对象索引或报告索引。
    """

    def __init__(self, records: tuple[AgentMemoryRecord, ...] = ()) -> None:
        self._records = records

    def retrieve(self, request: AgentRequest, memory_plan: AgentMemoryPlan) -> AgentMemoryRetrievalReport:
        """执行一次确定性的内存检索。

        检索流程有意写得比较直白，方便学习和后续替换：
        1. 先按每个 `retrieval_target` 处理，保留目标级结果；
        2. 再按 scope 做强隔离过滤，任何不属于当前请求边界的记录都会被排除；
        3. 最后用轻量关键词分数和重要性分排序，返回目标声明的 `max_items`。
        """

        session_id = _resolve_session_id(request)
        results: list[AgentMemoryRetrievalResult] = []
        total_retrieved = 0
        for target in memory_plan.retrieval_targets:
            if target.scope == AgentMemoryScope.SESSION and not session_id:
                results.append(
                    AgentMemoryRetrievalResult(
                        target=target,
                        skipped_reason="目标要求会话级记忆，但当前请求没有 sessionId，已跳过以避免误召回。",
                    )
                )
                continue

            ranked_records = self._rank_records(
                request=request,
                target=target,
                session_id=session_id,
            )
            selected = tuple(record for _, record in ranked_records[: target.max_items])
            total_retrieved += len(selected)
            results.append(
                AgentMemoryRetrievalResult(
                    target=target,
                    memories=selected,
                    attributes={
                        "candidateCount": len(ranked_records),
                        "maxItems": target.max_items,
                    },
                )
            )

        return AgentMemoryRetrievalReport(
            results=tuple(results),
            total_retrieved=total_retrieved,
            retrieval_notes=(
                "当前使用内存检索器，仅用于本地开发、单元测试和接口占位。",
                "生产环境接入向量库或图数据库时，必须保留租户、项目和会话隔离过滤。",
            ),
            attributes={
                "retriever": "in_memory",
                "targetCount": len(memory_plan.retrieval_targets),
                "recordCount": len(self._records),
            },
        )

    def _rank_records(
        self,
        request: AgentRequest,
        target: AgentMemoryRetrievalTarget,
        session_id: str | None,
    ) -> list[tuple[float, AgentMemoryRecord]]:
        """过滤并排序候选记录。

        当前排序不是向量相似度，而是“重要性分 + 关键词命中”的轻量规则。这样做的目的不是追求
        最优召回质量，而是让检索结果可预测、可测试，并清楚表达未来向量检索应该替换的位置。
        """

        ranked: list[tuple[float, AgentMemoryRecord]] = []
        for record in self._records:
            if record.memory_type != target.memory_type:
                continue
            if not _scope_matches(record, target.scope, request, session_id):
                continue
            score = _keyword_score(record, request.objective, target.query_hint) + record.importance_score
            if score <= 0:
                continue
            ranked.append((score, record))
        ranked.sort(key=lambda item: (item[0], item[1].created_at), reverse=True)
        return ranked


def _resolve_session_id(request: AgentRequest) -> str | None:
    """从请求变量解析 sessionId，保持与 `AgentOrchestrator` 当前兼容策略一致。"""

    value = request.variables.get("sessionId") or request.variables.get("session_id")
    return str(value) if value else None


def _scope_matches(
    record: AgentMemoryRecord,
    requested_scope: AgentMemoryScope,
    request: AgentRequest,
    session_id: str | None,
) -> bool:
    """判断记忆记录是否落在当前请求允许的隔离范围内。

    这里使用严格匹配而不是“范围越大越可见”。例如 PROJECT 目标只检索 PROJECT 记录，不自动混入
    TENANT 或 GLOBAL 记录。这样可以降低早期实现阶段的越界风险；未来如果需要全局公共知识，可让
    `AgentMemoryPlanner` 显式生成 GLOBAL 目标。
    """

    if record.scope != requested_scope:
        return False
    if requested_scope == AgentMemoryScope.SESSION:
        return (
            record.tenant_id == request.tenant_id
            and record.project_id == request.project_id
            and record.session_id == session_id
        )
    if requested_scope == AgentMemoryScope.PROJECT:
        return record.tenant_id == request.tenant_id and record.project_id == request.project_id
    if requested_scope == AgentMemoryScope.TENANT:
        return record.tenant_id == request.tenant_id
    return record.scope == AgentMemoryScope.GLOBAL


def _keyword_score(record: AgentMemoryRecord, objective: str, query_hint: str) -> float:
    """计算轻量关键词相关性分数。

    中文场景下不能简单依赖英文空格分词，所以这里同时按常见中文分隔符切分 `query_hint`，并保留
    用户目标中的英文/数字 token。命中标题、正文、标签都会加分。未来向量检索接入后，该函数可
    被 embedding similarity 或 reranker 替换。
    """

    searchable = " ".join((record.title, record.content, record.source, " ".join(record.tags))).lower()
    terms = _extract_terms(query_hint) | _extract_terms(objective)
    return sum(1.0 for term in terms if term and term in searchable)


def _extract_terms(text: str) -> set[str]:
    """从中文/英文混合文本中抽取适合内存检索的粗粒度关键词。"""

    parts = re.split(r"[\s,，、。；;:/\\|()（）\[\]【】]+", text.lower())
    return {part for part in parts if len(part) >= 2}

"""Agent 上下文构建器。

本阶段先实现规则式上下文构建，不直接查询真实数据库、向量库或知识图谱。这样做是有意的：
先让 Agent 编排链路拥有稳定的上下文输入/输出契约，再逐步替换具体来源，避免后续把 Chroma、
Neo4j、MySQL、Java API 调用散落到编排器内部。
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Protocol

from datasmart_ai_runtime.domain.context import ContextBlock, ContextSensitivityLevel, ContextSourceType
from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.services.runtime_event_recorder import RuntimeEventRecorder


class ContextBuilder(Protocol):
    """上下文构建器协议。

    后续可以有多种实现：
    - `DefaultContextBuilder`：当前规则式、无外部依赖的本地实现；
    - `GraphRagContextBuilder`：从 Neo4j + Chroma 检索上下文；
    - `JavaControlPlaneContextBuilder`：从 Java 微服务读取权限事实、元数据快照和任务状态；
    - `HybridContextBuilder`：组合多种来源并做排序、去重、截断和脱敏。
    """

    def build(
        self,
        request: AgentRequest,
        event_recorder: RuntimeEventRecorder | None = None,
    ) -> tuple[ContextBlock, ...]:
        """为一次 Agent 请求构建上下文块集合。

        `event_recorder` 是可选参数。简单构建器可以完全忽略它；需要可观测性的构建器可以写入
        上下文收集、过滤、截断等事件。保持可选是为了兼容已有测试和未来第三方上下文插件。
        """


class DefaultContextBuilder:
    """默认上下文构建器。

    该实现只依赖 `AgentRequest`，适合本地测试和早期开发。它不会声称已经拿到了真实元数据，
    而是构造“应该检索什么上下文”的可解释上下文块。等真实 RAG/GraphRAG 接入后，可以把这些
    占位块替换为真实库表字段、权限策略、质量规则案例和图谱路径。
    """

    def build(
        self,
        request: AgentRequest,
        event_recorder: RuntimeEventRecorder | None = None,
    ) -> tuple[ContextBlock, ...]:
        """根据请求内容构建上下文块。

        当前策略覆盖三类最重要的治理上下文：
        1. 用户目标：任何规划都必须保留原始目标，避免模型脱离用户意图；
        2. 权限事实：租户、项目、操作者是所有工具调用的边界；
        3. 数据源/质量上下文：如果请求涉及 datasourceId 或质量规则，就预留对应检索块。
        """

        blocks: list[ContextBlock] = [
            self._block(
                source_type=ContextSourceType.USER_OBJECTIVE,
                title="用户治理目标",
                content=request.objective,
                relevance_score=1.0,
                metadata={"locale": request.locale},
                sensitivity_level=ContextSensitivityLevel.INTERNAL,
                source_id=f"user-objective:{request.tenant_id}:{request.project_id}:{request.actor_id}",
                ttl_minutes=30,
            ),
            self._block(
                source_type=ContextSourceType.PERMISSION_FACT,
                title="租户与项目权限边界",
                content=(
                    f"当前请求来自租户 {request.tenant_id}、项目 {request.project_id}、"
                    f"操作者 {request.actor_id}。任何工具计划都必须限制在该租户和项目范围内。"
                ),
                relevance_score=0.95,
                metadata={
                    "tenantId": request.tenant_id,
                    "projectId": request.project_id,
                    "actorId": request.actor_id,
                },
                sensitivity_level=ContextSensitivityLevel.CONFIDENTIAL,
                source_id=f"permission-fact:{request.tenant_id}:{request.project_id}:{request.actor_id}",
                ttl_minutes=5,
            ),
        ]

        datasource_id = request.variables.get("datasourceId") or request.variables.get("datasource_id")
        if datasource_id:
            blocks.append(
                self._block(
                    source_type=ContextSourceType.DATASOURCE_METADATA,
                    title="数据源元数据检索需求",
                    content=(
                        f"请求涉及数据源 {datasource_id}。后续应读取该数据源的库表结构、字段类型、"
                        "主键索引、采样统计和最近一次元数据快照，作为质量规则或同步任务规划依据。"
                    ),
                    relevance_score=0.9,
                    metadata={"datasourceId": datasource_id},
                    sensitivity_level=ContextSensitivityLevel.CONFIDENTIAL,
                    source_id=f"datasource-metadata-request:{datasource_id}",
                    ttl_minutes=15,
                )
            )

        if self._looks_like_quality_request(request.objective):
            blocks.append(
                self._block(
                    source_type=ContextSourceType.QUALITY_RULE_CASE,
                    title="质量规则案例检索需求",
                    content=(
                        "请求包含质量治理意图。后续应检索同类数据域的历史质量规则、异常样本、"
                        "清洗建议和业务口径说明，避免生成脱离业务语义的通用规则。"
                    ),
                    relevance_score=0.85,
                    metadata={"businessGoal": request.variables.get("businessGoal") or request.objective},
                    sensitivity_level=ContextSensitivityLevel.INTERNAL,
                    source_id=f"quality-rule-case-request:{request.project_id}",
                    ttl_minutes=60,
                )
            )

        return tuple(blocks)

    @staticmethod
    def _looks_like_quality_request(objective: str) -> bool:
        """轻量判断请求是否涉及质量治理。

        这里依旧是规则式判断，目的不是替代模型，而是给 RAG/GraphRAG 接入前提供可测试基线。
        后续可以由 IntentAnalysis 或专用分类器提供更准确的治理域识别结果。
        """

        keywords = ("quality", "rule", "校验", "质量", "规则", "异常", "清洗", "完整性", "准确性")
        normalized = objective.lower()
        return any(keyword in normalized for keyword in keywords)

    def _block(
        self,
        source_type: ContextSourceType,
        title: str,
        content: str,
        relevance_score: float,
        metadata: dict[str, object],
        sensitivity_level: ContextSensitivityLevel,
        source_id: str,
        ttl_minutes: int,
    ) -> ContextBlock:
        """构造带治理元数据的上下文块。

        这里统一设置过期时间和 token 估算，避免每个上下文分支都手写相同逻辑。真实 RAG/GraphRAG
        接入后，也应在聚合层统一处理 token 预算、过期策略和敏感级别，而不是散落在各个检索器里。
        """

        return ContextBlock(
            source_type=source_type,
            title=title,
            content=content,
            relevance_score=relevance_score,
            metadata=dict(metadata),
            sensitivity_level=sensitivity_level,
            source_id=source_id,
            expires_at=self._expires_at(ttl_minutes),
            token_estimate=self._estimate_tokens(title, content),
        )

    @staticmethod
    def _expires_at(ttl_minutes: int) -> datetime:
        """根据 TTL 生成上下文过期时间。

        使用 UTC aware datetime，避免后续 Python Runtime、Java 控制面、数据库和前端跨时区展示时
        出现“本地时间到底属于哪个时区”的歧义。
        """

        return datetime.now(timezone.utc) + timedelta(minutes=ttl_minutes)

    @staticmethod
    def _estimate_tokens(title: str, content: str) -> int:
        """粗略估算上下文 token 数。

        这里不引入 tokenizer 依赖，先用字符数启发式估算。中文、英文和符号的真实 token 比例不同，
        但早期用于排序和预算足够；后续接真实模型时可按模型族替换为专用 tokenizer。
        """

        total_chars = len(title) + len(content)
        return max(1, (total_chars + 3) // 4)

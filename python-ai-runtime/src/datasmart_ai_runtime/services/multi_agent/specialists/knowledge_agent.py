"""执行真实 RAG turn 的知识专业 Agent。

本模块是 DataSmart 从“多 Agent 角色描述”走向“专业 Agent 独立执行”的第一批实现。主编排 Agent
只需提交受控的 :class:`SpecialistTurnRequest`，本类便会在用户、租户、项目和工具白名单共同限定的
范围内调用已经存在的 :class:`RagPipeline`。它不会绕过 RAG 的证据门控，也不会在没有检索证据时
让模型凭空回答。

安全边界刻意分为两层：

* `SpecialistTurnResult` 可以返回供主 Agent 继续仲裁的生成答案、引用标识和治理摘要；
* `event_sink` 只接收阶段、计数、状态和耗时，绝不接收问题正文、文档正文、压缩上下文或模型答案。

这样既能支持前端实时展示“正在检索/已经生成”，又不会因为 WebSocket、日志或运行事件持久化而扩大
敏感知识的暴露范围。
"""

from __future__ import annotations

from time import perf_counter
from typing import Any, Mapping

from datasmart_ai_runtime.services.agent_gateway.session_models import AgentSessionRole
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import (
    SpecialistEventSink,
    SpecialistToolActivity,
    SpecialistTurnRequest,
    SpecialistTurnResult,
    SpecialistTurnStatus,
)
from datasmart_ai_runtime.services.rag import RAG_TOOL_CODE, RagCitation, RagPipeline, RagPipelineResult, RagQuery


class KnowledgeSpecialistAgent:
    """在受控委派范围内执行一次真实 RAG 检索与生成。

    与只返回 capability 描述的旧控制面对象不同，本类会真正调用 `RagPipeline.answer()`。管线必须由
    构造器注入，原因是专业 Agent 不应自行读取环境变量、创建模型 Provider 或选择知识库：这些依赖
    应由 Runtime 组合根统一配置，测试环境也可以注入确定性的替身。

    本类只负责一次 specialist turn，不负责递归规划、审批持久化或业务副作用。主 Agent 是否继续调用
    其他专家、是否把答案展示给用户，仍由更上层的多 Agent 编排器决定。
    """

    _ROLE = AgentSessionRole.KNOWLEDGE_AGENT
    _DEFAULT_AGENT_ID = "knowledge-agent-rag-v1"
    _EVENT_PAYLOAD_POLICY = "LOW_SENSITIVE_KNOWLEDGE_SPECIALIST_EVENT_ONLY"
    _SAFE_RETRIEVAL_SUMMARY_KEYS = frozenset(
        {
            "candidateCount",
            "evidenceAcceptedCount",
            "weakEvidenceRejectedCount",
            "selectedCount",
            "topK",
            "candidateLimit",
            "compressedContextChars",
            "maxContextChars",
            "hasVectorSignal",
            "hasLexicalSignal",
            "citationRequired",
        }
    )
    _SAFE_MODEL_SUMMARY_KEYS = frozenset(
        {
            "actualModelName",
            "cachedPromptTokens",
            "completionTokens",
            "errorCode",
            "invoked",
            "latencyMs",
            "modelName",
            "promptTokens",
            "providerInvoked",
            "providerName",
            "providerSucceeded",
            "reason",
            "responseSource",
            "skipped",
            "totalTokens",
        }
    )

    def __init__(self, rag_pipeline: RagPipeline, *, agent_id: str = _DEFAULT_AGENT_ID) -> None:
        """保存由 Runtime 组合根注入的 RAG 管线。

        Args:
            rag_pipeline: 已完成检索器、模型路由、Provider 和证据门控配置的真实 RAG 管线。
            agent_id: 写入 turn 结果和事件的稳定执行者标识，便于后续持久化双主体审计事实。

        Raises:
            ValueError: 管线为空或 Agent 标识为空时立即拒绝启动，避免运行到一半才产生不可审计结果。
        """

        if rag_pipeline is None:
            raise ValueError("KnowledgeSpecialistAgent 必须注入 RagPipeline")
        normalized_agent_id = str(agent_id or "").strip()
        if not normalized_agent_id:
            raise ValueError("KnowledgeSpecialistAgent agent_id 不能为空")
        self._rag_pipeline = rag_pipeline
        self._agent_id = normalized_agent_id

    @property
    def role(self) -> AgentSessionRole:
        """返回该实现唯一允许承接的专业角色。"""

        return self._ROLE

    def execute(
        self,
        request: SpecialistTurnRequest,
        event_sink: SpecialistEventSink | None = None,
    ) -> SpecialistTurnResult:
        """执行一次知识检索、证据门控和有依据生成。

        执行顺序体现了专业 Agent 的 fail-closed 原则：

        1. 先验证委派角色和 RAG 工具白名单，未授权时绝不触碰知识库；
        2. 将 objective 与租户、项目、用户、会话和 trace 范围转换成 `RagQuery`；
        3. 调用一次真实 `RagPipeline.answer()`，由管线完成检索、重排、门控和模型生成；
        4. 把答案、引用和治理统计封装为 specialist result，同时只发布低敏进度事件。

        管线没有返回证据并不等于技术故障，因此 turn 仍可正常结束，但结果会明确标记
        `answerAvailable=False`，也不会把管线的无证据提示伪装成知识答案。
        """

        turn_started_at = perf_counter()
        if request.role != self.role:
            return self._failed_result(
                request=request,
                event_sink=event_sink,
                error_code="KNOWLEDGE_AGENT_ROLE_MISMATCH",
                public_summary="知识专业 Agent 拒绝了不匹配的角色委派。",
                turn_started_at=turn_started_at,
            )

        # RAG 查询必须始终绑定到具体项目。不能因为知识 Agent 是只读角色，就把缺失项目
        # 降级成租户通配范围；只读查询同样可能泄露其它项目的运行手册和故障案例。
        if not self._has_project_scope(request.scope.project_id):
            return self._failed_result(
                request=request,
                event_sink=event_sink,
                error_code="KNOWLEDGE_PROJECT_SCOPE_REQUIRED",
                public_summary="知识检索缺少明确项目范围，已停止访问知识库。",
                turn_started_at=turn_started_at,
            )

        if RAG_TOOL_CODE not in request.scope.allowed_tool_names or request.budget.max_tool_calls < 1:
            return self._failed_result(
                request=request,
                event_sink=event_sink,
                error_code="KNOWLEDGE_RAG_TOOL_NOT_AUTHORIZED",
                public_summary="当前委派未授权知识检索工具，已停止本次知识查询。",
                turn_started_at=turn_started_at,
                tool_status="DENIED",
            )

        self._publish_event(
            event_sink,
            request=request,
            action="knowledge.retrieval.started",
            status="RUNNING",
            public_summary="知识专业 Agent 已开始检索授权范围内的知识证据。",
        )
        tool_started_at = perf_counter()
        try:
            rag_result = self._rag_pipeline.answer(self._build_rag_query(request))
        except Exception:
            # Provider、向量库或检索器的原始异常可能带 endpoint、查询正文或文档片段，因此这里只返回
            # 稳定错误码；详细堆栈应由受控服务日志记录，而不能进入用户可见事件或 Agent handoff。
            return self._failed_result(
                request=request,
                event_sink=event_sink,
                error_code="KNOWLEDGE_RAG_EXECUTION_FAILED",
                public_summary="知识检索或生成未能完成，请稍后重试或检查 RAG 服务状态。",
                turn_started_at=turn_started_at,
                tool_started_at=tool_started_at,
            )

        tool_duration_ms = self._elapsed_ms(tool_started_at)
        citations = self._citation_summaries(rag_result)
        evidence_references = self._evidence_references(rag_result)
        evidence_count = len(evidence_references)
        has_evidence = evidence_count > 0

        self._publish_event(
            event_sink,
            request=request,
            action="knowledge.retrieval.completed",
            status="COMPLETED" if has_evidence else "NO_EVIDENCE",
            public_summary=(
                "知识检索与有依据生成已经完成。"
                if has_evidence
                else "知识检索已经完成，但没有找到足够证据，未生成答案。"
            ),
            duration_ms=tool_duration_ms,
            statistics={
                "citationCount": evidence_count,
                "selectedCount": self._safe_count(rag_result.retrieval_summary.get("selectedCount")),
                "generated": bool(rag_result.generated and has_evidence),
            },
        )

        public_summary = (
            f"知识专业 Agent 已基于 {evidence_count} 条引用完成回答。"
            if has_evidence
            else "知识专业 Agent 未找到足够证据，因此没有生成无依据答案。"
        )
        tool_activity = SpecialistToolActivity(
            tool_name=RAG_TOOL_CODE,
            status="COMPLETED" if has_evidence else "NO_EVIDENCE",
            public_summary=public_summary,
            evidence_reference=evidence_references[0] if evidence_references else None,
            duration_ms=tool_duration_ms,
        )
        result = SpecialistTurnResult(
            agent_id=self._agent_id,
            role=self.role,
            turn_id=request.turn_id,
            status=SpecialistTurnStatus.COMPLETED,
            public_summary=public_summary,
            structured_output={
                # 只有经过证据门控的回答才回交给主 Agent。无证据时使用 None，防止上层把管线的
                # fallback 提示误当作对业务问题的成功回答。
                "answer": rag_result.answer if has_evidence else None,
                "answerAvailable": has_evidence,
                "grounded": has_evidence,
                "generated": bool(rag_result.generated and has_evidence),
                "citations": citations,
                "retrievalStatistics": self._safe_retrieval_summary(rag_result.retrieval_summary),
                "payloadPolicy": "GROUNDED_RAG_ANSWER_WITH_LOW_SENSITIVE_CITATION_METADATA",
            },
            evidence_references=evidence_references,
            tool_activities=(tool_activity,),
            model_invocation_summary=self._safe_model_summary(rag_result.model_summary),
            duration_ms=self._elapsed_ms(turn_started_at),
        )
        self._publish_event(
            event_sink,
            request=request,
            action="knowledge.turn.completed",
            status=result.status.value,
            public_summary="知识专业 Agent 已完成本次 turn。",
            duration_ms=result.duration_ms,
            statistics={"citationCount": evidence_count, "answerAvailable": has_evidence},
        )
        return result

    @staticmethod
    def _build_rag_query(request: SpecialistTurnRequest) -> RagQuery:
        """把 specialist 委派合同转换为 RAG 查询合同。

        `workspace_key` 固定使用通配范围，是因为 DataSmart 已废弃工作空间层级；真正的数据隔离由
        tenant/project/actor 三个字段承担。`trace_id` 优先复用主编排器传入的低敏 trace 标识，缺失时
        回退到 run_id，确保每次模型与检索调用都能关联到同一条 Agent 运行链路。
        """

        trace_id = str(
            request.context_summary.get("traceId")
            or request.context_summary.get("trace_id")
            or request.run_id
        ).strip()
        # 恢复场景需要用真实失败码、任务类型和同步模式缩小案例检索范围。这里只追加短状态码和类型，
        # 不追加日志正文、SQL、表名、字段映射或样本；普通知识问答没有这些上下文字段时仍只使用 objective。
        context_parts = []
        for key, label in (
            ("failureCode", "failureCode"),
            ("taskKind", "taskKind"),
            ("syncMode", "syncMode"),
            ("status", "status"),
        ):
            value = request.context_summary.get(key)
            if isinstance(value, (str, int)) and str(value).strip() and len(str(value).strip()) <= 120:
                context_parts.append(f"{label}={str(value).strip()}")
        question = request.objective
        if context_parts:
            question = f"{request.objective}\n结构化故障上下文：{', '.join(context_parts)}"
        project_id = KnowledgeSpecialistAgent._required_project_scope(request.scope.project_id)
        return RagQuery(
            tenant_id=request.scope.tenant_id,
            project_id=project_id,
            actor_id=request.scope.actor_id,
            question=question,
            workspace_key="*",
            # 知识专业 Agent 使用 auto，让受治理模型判断普通文档、GraphRAG 或联合证据路径；
            # Recovery 等需要强制特定检索合同的调用方仍可显式传入 lexical/graph。
            retrieval_mode="auto",
            generate_answer=True,
            trace_id=trace_id,
            session_id=request.session_id,
        )

    @staticmethod
    def _citation_summaries(rag_result: RagPipelineResult) -> tuple[dict[str, Any], ...]:
        """返回可追踪但不携带文档正文的引用元数据。

        `RagCitation.to_summary()` 会包含 snippet。snippet 对普通 RAG API 很有价值，但 specialist turn
        还会被持久化和跨 Agent 传递，因此这里主动去掉 snippet，只保留定位证据所需的稳定标识、标题、
        来源和分数。需要正文时，后续 Agent 必须再次通过获授权的 RAG 工具读取。
        """

        return tuple(
            {
                "citationId": _safe_public_text(citation.citation_id, 128),
                "documentId": _safe_public_text(citation.document_id, 256),
                "chunkId": _safe_public_text(citation.chunk_id, 256),
                "title": _safe_public_text(citation.title, 512),
                "sourceUri": _safe_public_text(citation.source_uri, 512),
                "finalScore": _safe_score(citation.final_score),
            }
            for citation in rag_result.citations
        )

    @staticmethod
    def _evidence_references(rag_result: RagPipelineResult) -> tuple[str, ...]:
        """为主 Agent 生成稳定、去重且不包含正文的证据引用。"""

        references: list[str] = []
        for citation in rag_result.citations:
            if not citation.document_id or not citation.chunk_id:
                continue
            reference = _safe_public_text(
                f"rag:{citation.document_id}:{citation.chunk_id}",
                512,
            )
            if reference and reference not in references:
                references.append(reference)
        return tuple(references)

    @classmethod
    def _safe_retrieval_summary(cls, summary: Mapping[str, Any] | None) -> dict[str, Any]:
        """只回传 RAG 管线定义过的统计字段，拒绝未知字段携带正文或 Provider 响应。

        ``RagPipelineResult`` 是跨模块合同，未来其它管线实现可能在 summary 中附加任意键。
        specialist 结果会进入事件、Durable handoff 和主 Agent 上下文，因此这里不能简单复制
        整个 mapping；计数和布尔值也分别归一化，避免模型或外部客户端伪造大数和复杂对象。
        """

        safe: dict[str, Any] = {}
        for key in cls._SAFE_RETRIEVAL_SUMMARY_KEYS:
            if not isinstance(summary, Mapping) or key not in summary:
                continue
            value = summary[key]
            if key.startswith("has") or key == "citationRequired":
                safe[key] = bool(value)
            else:
                safe[key] = cls._safe_count(value)
        safe["payloadPolicy"] = "LOW_SENSITIVE_RAG_RETRIEVAL_SUMMARY_ONLY"
        return safe

    @classmethod
    def _safe_model_summary(cls, summary: Mapping[str, Any] | None) -> dict[str, Any]:
        """保留模型调用统计而删除 prompt、响应正文、隐藏思维链和未知扩展字段。"""

        safe: dict[str, Any] = {"rawModelOutputStored": False}
        if not isinstance(summary, Mapping):
            return safe
        for key in cls._SAFE_MODEL_SUMMARY_KEYS:
            if key not in summary:
                continue
            value = summary[key]
            if key in {"invoked", "providerInvoked", "providerSucceeded", "skipped"}:
                safe[key] = bool(value)
            elif key.endswith("Tokens") or key == "latencyMs" or key == "totalTokens":
                safe[key] = cls._safe_count(value)
            elif value is None:
                safe[key] = None
            else:
                safe[key] = _safe_public_text(value, 256)
        return safe

    @staticmethod
    def _has_project_scope(project_id: object) -> bool:
        """拒绝空项目和通配项目，避免 RAG 从项目范围扩大到租户范围。"""

        normalized = str(project_id or "").strip()
        return bool(normalized) and normalized.casefold() not in {"*", "all", "tenant", "tenant_scope"}

    @staticmethod
    def _required_project_scope(project_id: object) -> str:
        """为 RAGQuery 生成经过 fail-closed 校验的项目值。"""

        normalized = str(project_id or "").strip()
        if not KnowledgeSpecialistAgent._has_project_scope(normalized):
            raise ValueError("RAG 查询必须携带具体 project_id")
        return normalized

    def _failed_result(
        self,
        *,
        request: SpecialistTurnRequest,
        event_sink: SpecialistEventSink | None,
        error_code: str,
        public_summary: str,
        turn_started_at: float,
        tool_status: str = "FAILED",
        tool_started_at: float | None = None,
    ) -> SpecialistTurnResult:
        """统一构造低敏失败结果，并保证失败 turn 也有结束事件。

        失败分支不接收异常文本，调用方只能传入稳定错误码和适合用户阅读的公开说明。这样可以从类型和
        调用方式上降低把数据库连接串、Provider 响应或文档片段误写入事件的概率。
        """

        duration_ms = self._elapsed_ms(turn_started_at)
        tool_duration_ms = self._elapsed_ms(tool_started_at) if tool_started_at is not None else 0
        result = SpecialistTurnResult(
            agent_id=self._agent_id,
            role=self.role,
            turn_id=request.turn_id,
            status=SpecialistTurnStatus.FAILED,
            public_summary=public_summary,
            structured_output={"answer": None, "answerAvailable": False, "grounded": False},
            tool_activities=(
                SpecialistToolActivity(
                    tool_name=RAG_TOOL_CODE,
                    status=tool_status,
                    public_summary=public_summary,
                    duration_ms=tool_duration_ms,
                ),
            ),
            model_invocation_summary={},
            error_code=error_code,
            duration_ms=duration_ms,
        )
        self._publish_event(
            event_sink,
            request=request,
            action="knowledge.turn.completed",
            status=result.status.value,
            public_summary=public_summary,
            duration_ms=duration_ms,
            statistics={"citationCount": 0, "answerAvailable": False},
            error_code=error_code,
        )
        return result

    def _publish_event(
        self,
        event_sink: SpecialistEventSink | None,
        *,
        request: SpecialistTurnRequest,
        action: str,
        status: str,
        public_summary: str,
        duration_ms: int = 0,
        statistics: Mapping[str, Any] | None = None,
        error_code: str | None = None,
    ) -> None:
        """向可选事件接收器发布低敏动作摘要。

        事件接收器通常连接 WebSocket 或异步持久化层，客户端断线不应反向破坏已经完成的 RAG turn，
        所以 sink 异常会被隔离。事件内容由本方法集中创建，调用方没有机会塞入 objective、prompt、
        文档正文、压缩上下文或模型答案正文。
        """

        if event_sink is None:
            return
        event = {
            "eventType": "SPECIALIST_ACTION",
            "agentId": self._agent_id,
            "agentRole": self.role.value,
            "turnId": request.turn_id,
            "runId": request.run_id,
            "action": action,
            "status": status,
            "publicSummary": public_summary,
            "durationMs": max(0, duration_ms),
            "statistics": dict(statistics or {}),
            "errorCode": error_code,
            "payloadPolicy": self._EVENT_PAYLOAD_POLICY,
        }
        try:
            event_sink(event)
        except Exception:
            # 进度展示是旁路能力。网络断开或消费端异常不能改变专业 Agent 的业务执行结果。
            return

    @staticmethod
    def _safe_count(value: Any) -> int:
        """把外部管线摘要中的计数规范为非负整数，避免异常统计污染事件。"""

        try:
            return max(0, int(value))
        except (TypeError, ValueError):
            return 0

    @staticmethod
    def _elapsed_ms(started_at: float) -> int:
        """使用单调时钟计算耗时，避免系统时间调整造成负数。"""

        return max(0, int((perf_counter() - started_at) * 1000))


def _safe_public_text(value: Any, limit: int) -> str:
    """规范化公开元数据文本，并限制长度和控制字符。

    引用标题、来源 URI 和 Provider 统计名称不是业务正文，但它们仍然来自外部组件，可能包含
    意外的超长内容、换行或控制字符。统一裁剪后再进入 Specialist 结果，避免事件和 Durable
    handoff 被单个异常字段放大。
    """

    text = str(value or "").replace("\x00", "").strip()
    return text[: max(1, limit)]


def _safe_score(value: Any) -> float:
    """把引用分数收敛为有限的公开浮点数。"""

    try:
        score = float(value)
    except (TypeError, ValueError):
        return 0.0
    if score != score or score in {float("inf"), float("-inf")}:
        return 0.0
    return round(score, 6)


__all__ = ["KnowledgeSpecialistAgent"]

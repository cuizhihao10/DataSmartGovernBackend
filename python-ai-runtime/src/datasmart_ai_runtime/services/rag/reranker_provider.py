"""RAG 专用 Reranker Provider 与硅基流动适配器。

Embedding 负责从大规模知识库快速召回候选，Reranker 负责联合阅读“查询 + 候选文档”并重新判断相关性。
二者的延迟、容量、模型协议和失败策略不同，因此不能复用同一个 Provider 类，也不能让主聊天模型临时
承担重排。当前实现保持 Provider 中立的业务接口，并为用户选择的硅基流动 ``POST /v1/rerank`` 提供
首个生产适配器。

安全边界：
- 调用前的候选已经通过 tenant/project/workspace 硬过滤，Reranker 无权扩大检索范围；
- 请求固定 ``return_documents=false``，避免第三方在响应中重复回显正文；
- API Key、完整 Endpoint、查询、文档和上游错误正文不会进入诊断；
- 显式启用远端 Provider 后发生协议错误会 fail-closed，不会悄悄改用规则分数伪装成功。
"""

from __future__ import annotations

import json
import math
import os
import time
from dataclasses import dataclass
from enum import Enum
from http import client as http_client
from threading import Lock
from time import monotonic
from typing import Any, Callable, Mapping, Protocol
from urllib import error, parse, request

from datasmart_ai_runtime.services.memory.memory_embedding_provider import (
    normalize_external_text_sensitivity_levels,
    require_external_text_sensitivity_approval,
)
from datasmart_ai_runtime.services.rag.knowledge_base import (
    _facet_routing_reserves,
    _responsibility_routing_reserves,
)
from datasmart_ai_runtime.services.rag.models import RagQuery, RagScoredChunk
from datasmart_ai_runtime.services.rag.text import (
    normalize_rag_retrieval_question,
    rag_query_requests_multiple_evidence,
)


class RagReranker(Protocol):
    """RAG 管线依赖的最小重排协议。"""

    def rerank(
        self,
        query: RagQuery,
        candidates: tuple[RagScoredChunk, ...],
    ) -> tuple[RagScoredChunk, ...]:
        """按与查询的相关性重新排列已经完成作用域过滤的候选。"""

    def diagnostics(self) -> dict[str, object]:
        """返回不含查询、正文、Endpoint 和凭据的低敏运行诊断。"""

    def prepare_candidates(
        self,
        candidates: tuple[RagScoredChunk, ...],
        *,
        query: RagQuery | None = None,
    ) -> tuple[RagScoredChunk, ...]:
        """返回实际会提交给远端模型的有界候选窗口。

        这是一个可选的观测协议。没有远端窗口限制的本地 Reranker 可以直接返回原 tuple；有供应商
        限制的实现应在这里完成截断，让上层评测、审计和 HTTP 请求共享同一个事实。``query`` 是
        可选的，因为旧版本地替身只实现了单参数方法；支持它的远端实现可以据此做有界的动态候选
        路由，而不是把所有决策推迟到 HTTP 请求之后。
        """


class RagRerankerProviderType(str, Enum):
    """当前支持的 Reranker Provider 类型。"""

    DISABLED = "disabled"
    SILICONFLOW = "siliconflow"


@dataclass(frozen=True)
class RagRerankerProviderSettings:
    """远程 Reranker 的运行配置。

    ``max_documents`` 是 DataSmart 自己的候选上限，不等同于供应商账户容量。即使上游允许更多文档，
    本平台仍应通过候选窗口限制延迟、费用和数据外发范围。

    ``approved_sensitivity_levels`` 默认空集，意味着远端 Reranker 不接收查询或候选正文；
    `restricted` 即使被显式列入批准集，也只有在 ``synthetic_only_evaluation`` 为真时才可用于评测边界。
    """

    provider_type: RagRerankerProviderType = RagRerankerProviderType.DISABLED
    endpoint: str = ""
    api_key: str = ""
    model: str = ""
    timeout_seconds: int = 30
    # 16 条足以覆盖职责路由后的候选，同时显著降低长文档请求的超时、限流和外发正文体积。
    max_documents: int = 16
    max_query_chars: int = 4000
    max_document_chars: int = 6000
    max_attempts: int = 3
    retry_base_delay_ms: int = 250
    retrieval_prior_weight: float = 0.0
    approved_sensitivity_levels: tuple[str, ...] = ()
    synthetic_only_evaluation: bool = False


UrlOpen = Callable[..., Any]
Sleep = Callable[[float], None]


class SiliconFlowRagReranker:
    """硅基流动 ``/v1/rerank`` 文本重排适配器。"""

    def __init__(
        self,
        settings: RagRerankerProviderSettings,
        *,
        urlopen: UrlOpen = request.urlopen,
        sleep: Sleep = time.sleep,
    ) -> None:
        """校验远端配置并创建低敏状态容器。"""

        if settings.provider_type != RagRerankerProviderType.SILICONFLOW:
            raise ValueError("硅基流动 Reranker 的 provider_type 配置不正确。")
        _validate_remote_endpoint(settings.endpoint)
        if not settings.api_key.strip():
            raise ValueError("硅基流动 Reranker 必须配置 API Key。")
        if not settings.model.strip():
            raise ValueError("硅基流动 Reranker 必须配置模型名称。")
        self._settings = settings
        self._urlopen = urlopen
        self._sleep = sleep
        self._lock = Lock()
        self._request_count = 0
        self._last_latency_ms: int | None = None
        self._last_error_code: str | None = None
        self._last_candidate_count = 0
        self._approved_sensitivity_levels = normalize_external_text_sensitivity_levels(
            settings.approved_sensitivity_levels,
        )
        self._synthetic_only_evaluation = bool(settings.synthetic_only_evaluation)

    def rerank(
        self,
        query: RagQuery,
        candidates: tuple[RagScoredChunk, ...],
    ) -> tuple[RagScoredChunk, ...]:
        """调用远端模型并把结果 index 安全映射回本地候选。

        输入候选已经按上游融合分排序。超过本地上限时只发送最靠前的窗口，未发送部分不会进入最终证据；
        返回结果必须完整覆盖已发送窗口，否则整体失败，避免文档与分数错配。
        """

        if not candidates:
            return ()
        started_at = monotonic()
        submitted = self.prepare_candidates(candidates, query=query)
        try:
            self._require_approved_query_and_candidate_bodies(query, submitted)
        except RuntimeError as exc:
            self._record_failure(
                "RAG_RERANK_PROVIDER_SENSITIVITY_NOT_APPROVED",
                started_at,
                len(submitted),
            )
            raise RuntimeError("RAG Reranker 查询或正文敏感级别未获外发批准，已阻断。") from exc
        safe_query = normalize_rag_retrieval_question(query.question)[: max(100, self._settings.max_query_chars)]
        if not safe_query:
            raise ValueError("Reranker 查询不能为空。")
        documents = [self._candidate_text(item) for item in submitted]
        payload = json.dumps(
            {
                "model": self._settings.model,
                "query": safe_query,
                "documents": documents,
                "top_n": len(documents),
                "return_documents": False,
            },
            ensure_ascii=False,
        ).encode("utf-8")
        http_request = request.Request(
            _rerank_endpoint(self._settings.endpoint),
            data=payload,
            headers={
                "Authorization": f"Bearer {self._settings.api_key}",
                "Content-Type": "application/json",
                "Accept": "application/json",
                "User-Agent": "datasmart-rag-reranker/1.0",
            },
            method="POST",
        )
        reranked = self._request_with_bounded_retry(
            http_request,
            submitted,
            started_at=started_at,
        )
        reranked = self._blend_retrieval_prior(reranked, submitted)
        self._record_success(started_at, len(submitted))
        return reranked

    def prepare_candidates(
        self,
        candidates: tuple[RagScoredChunk, ...],
        *,
        query: RagQuery | None = None,
    ) -> tuple[RagScoredChunk, ...]:
        """按本地治理上限确定真实外发窗口，并为多证据问题保留互补资料。

        ``max_documents`` 是平台自己的费用、延迟和数据外发边界，不是供应商宣称的容量。把这一步
        暴露给上层后，RAG 评测可以准确记录“模型实际看到了哪些文档”，不会把截断前的候选列表误报
        成远端重排输入。对于明确要求多个证据面的查询，先从已经通过范围过滤和召回的候选中保留
        facet 代表，再按原始融合排序补齐；这不会扫描知识库、扩大权限范围或增加外发数量。空候选
        仍返回空 tuple，调用方可以安全地跳过网络请求。
        """

        if not candidates:
            return ()
        limit = max(1, min(int(self._settings.max_documents), 200))
        # 这里不能直接使用 ``candidates[:limit]``。长 DOCX、XLSX 和日志文件会产生很多相邻 chunk，
        # 如果先按 chunk 截断，前几篇长文档就能占满远端窗口，后面的独立资料即使已经被召回也永远
        # 看不到。这是“召回正确、Reranker 却没机会判断”的典型质量损失。
        #
        # 选择顺序分成三层：精确定位资料、明确多证据 facet 的代表资料、普通文档级轮询。每一层都只
        # 使用已经通过范围过滤的候选，不会重新扫描知识库或扩大授权范围；总数始终不超过 max_documents。
        best_exact_score = max((float(item.exact_score) for item in candidates), default=0.0)
        exact_threshold = best_exact_score * 0.95
        selected: list[RagScoredChunk] = []
        selected_ids: set[str] = set()
        selected_document_ids: set[str] = set()

        def document_key(item: RagScoredChunk) -> str:
            """返回稳定文档键；缺失 document_id 时退回来源 URI。"""

            return str(item.chunk.document_id or item.chunk.source_uri or item.chunk.chunk_id)

        def append_unique(
            items: tuple[RagScoredChunk, ...] | list[RagScoredChunk],
            *,
            one_per_document: bool = False,
        ) -> None:
            """按给定优先级追加候选，并把总量锁在真实 Provider 窗口内。"""

            for item in items:
                if len(selected) >= limit:
                    return
                chunk_id = item.chunk.chunk_id
                if chunk_id in selected_ids:
                    continue
                document_id = document_key(item)
                if one_per_document and document_id in selected_document_ids:
                    continue
                selected.append(item)
                selected_ids.add(chunk_id)
                selected_document_ids.add(document_id)

        exact_candidates = tuple(
            item
            for item in candidates
            if best_exact_score > 0.0 and float(item.exact_score) >= exact_threshold
        )
        append_unique(exact_candidates, one_per_document=True)
        if query is not None and len(selected) < limit:
            # 普通单职责问题也要先保护职责明确的资料。否则长手册的重复 chunk 会在窗口里占满位置，
            # 目标错误码目录、恢复接口或管理员手册虽然已经被召回，却无法进入真实远端请求。
            append_unique(
                _responsibility_routing_reserves(candidates, query),
                one_per_document=True,
            )
            # 该函数只使用已召回候选，且最多返回八个 facet 代表。它与召回器中的同名路由规则
            # 保持一致，确保“本地看见了目标”与“远端实际看见了目标”不会出现两套标准。
            append_unique(_facet_routing_reserves(candidates, query), one_per_document=True)

        # 第一轮每篇文档只取一个最佳 chunk，先让 Reranker 比较尽可能多的独立资料。后续轮次才允许
        # 同一文档补充第二、第三个 chunk；这既保留长文档内部的多 facet 证据，也避免它挤掉其他文档。
        remaining = [item for item in candidates if item.chunk.chunk_id not in selected_ids]
        while remaining and len(selected) < limit:
            round_document_ids: set[str] = set()
            next_remaining: list[RagScoredChunk] = []
            for item in remaining:
                if len(selected) >= limit:
                    next_remaining.append(item)
                    continue
                chunk_id = item.chunk.chunk_id
                if chunk_id in selected_ids:
                    continue
                document_id = document_key(item)
                if document_id in round_document_ids:
                    next_remaining.append(item)
                    continue
                selected.append(item)
                selected_ids.add(chunk_id)
                selected_document_ids.add(document_id)
                round_document_ids.add(document_id)
            if len(next_remaining) == len(remaining):
                break
            remaining = next_remaining
        return tuple(selected[:limit])

    def _blend_retrieval_prior(
        self,
        reranked: tuple[RagScoredChunk, ...],
        submitted: tuple[RagScoredChunk, ...],
    ) -> tuple[RagScoredChunk, ...]:
        """在远端重排后融合第一阶段排名，降低相近候选的偶然换序。"""

        weight = max(0.0, min(1.0, float(self._settings.retrieval_prior_weight)))
        if weight <= 0.0 or len(submitted) <= 1:
            return reranked
        remote_rank = {
            item.chunk.chunk_id: index
            for index, item in enumerate(reranked)
        }
        original_rank = {
            item.chunk.chunk_id: index
            for index, item in enumerate(submitted)
        }
        denominator = max(1, len(submitted) - 1)
        blended: list[RagScoredChunk] = []
        for candidate in reranked:
            chunk_id = candidate.chunk.chunk_id
            remote_rank_score = 1.0 - remote_rank[chunk_id] / denominator
            retrieval_rank_score = 1.0 - original_rank[chunk_id] / denominator
            final_score = (
                (1.0 - weight) * remote_rank_score
                + weight * retrieval_rank_score
            )
            blended.append(
                RagScoredChunk(
                    chunk=candidate.chunk,
                    lexical_score=candidate.lexical_score,
                    vector_score=candidate.vector_score,
                    fused_score=candidate.fused_score,
                    # rerank_score 保留供应商原始分数；final_score 是可解释的二阶段融合分数。
                    rerank_score=candidate.rerank_score,
                    diversity_penalty=candidate.diversity_penalty,
                    final_score=final_score,
                    match_terms=candidate.match_terms,
                    exact_score=candidate.exact_score,
                    exact_match_identifiers=candidate.exact_match_identifiers,
                )
            )
        return tuple(sorted(blended, key=lambda item: item.final_score, reverse=True))

    def _request_with_bounded_retry(
        self,
        http_request: request.Request,
        submitted: tuple[RagScoredChunk, ...],
        *,
        started_at: float,
    ) -> tuple[RagScoredChunk, ...]:
        """对供应商瞬态错误执行有限退避，协议与权限错误立即失败。

        只有 429、常见 5xx、超时和短暂断连具备“稍后重试可能成功”的语义。400/401/403 通常表示请求、
        凭据或权限错误，重复调用只会浪费额度；JSON 缺项、index 错位和非法分数属于合同错误，更不能靠
        重试掩盖。最大次数由本地配置限制，退避等待也有上限，因此不会形成 Agent 外层 Loop 之外的
        无界隐式循环。
        """

        maximum_attempts = max(1, min(int(self._settings.max_attempts), 5))
        for attempt in range(1, maximum_attempts + 1):
            try:
                with self._urlopen(
                    http_request,
                    timeout=max(1, self._settings.timeout_seconds),
                ) as response:
                    response_payload = json.loads(response.read().decode("utf-8"))
                return self._map_results(response_payload, submitted)
            except error.HTTPError as exc:
                if _retryable_http_status(exc.code) and attempt < maximum_attempts:
                    self._wait_before_retry(attempt)
                    continue
                self._record_failure("RAG_RERANK_PROVIDER_HTTP_ERROR", started_at, len(submitted))
                raise RuntimeError(
                    f"RAG Reranker HTTP 调用失败，status={exc.code}。上游响应正文已隐藏。"
                ) from exc
            except (
                error.URLError,
                TimeoutError,
                ConnectionError,
                OSError,
                http_client.IncompleteRead,
                http_client.RemoteDisconnected,
            ) as exc:
                # Provider 可能在已经发送部分 JSON 后关闭连接。IncompleteRead 不是 OSError，
                # 如果不显式归入瞬态传输错误，它会直接穿透评测执行器，导致一条用例占满整轮
                # 超时而没有机会使用同一请求重新获取完整响应。这里重试整个幂等的 rerank 请求，
                # 仍然受 max_attempts 和指数退避上限约束；绝不使用 partial body 猜测候选顺序。
                if attempt < maximum_attempts:
                    self._wait_before_retry(attempt)
                    continue
                self._record_failure("RAG_RERANK_PROVIDER_NETWORK_ERROR", started_at, len(submitted))
                raise RuntimeError(
                    "RAG Reranker 网络连接失败。Endpoint 与底层错误详情已隐藏。"
                ) from exc
            except (UnicodeDecodeError, json.JSONDecodeError) as exc:
                self._record_failure("RAG_RERANK_PROVIDER_INVALID_JSON", started_at, len(submitted))
                raise RuntimeError(
                    "RAG Reranker 返回了无法解析的 JSON。上游响应正文已隐藏。"
                ) from exc
            except (RuntimeError, ValueError, TypeError) as exc:
                self._record_failure("RAG_RERANK_PROVIDER_INVALID_RESPONSE", started_at, len(submitted))
                if isinstance(exc, RuntimeError):
                    raise
                raise RuntimeError("RAG Reranker 响应合同非法。上游响应正文已隐藏。") from exc
        raise AssertionError("RAG Reranker 重试循环不应越过最大次数。")

    def _wait_before_retry(self, completed_attempt: int) -> None:
        """按 250ms、500ms 等指数退避，并把单次等待限制在 4 秒内。"""

        base_seconds = max(1, int(self._settings.retry_base_delay_ms)) / 1000.0
        self._sleep(min(4.0, base_seconds * (2 ** max(0, completed_attempt - 1))))

    def diagnostics(self) -> dict[str, object]:
        """返回低敏 Provider 状态，不暴露问题、候选、Endpoint 或 API Key。"""

        with self._lock:
            return {
                "implementation": type(self).__name__,
                "providerType": self._settings.provider_type.value,
                "model": self._settings.model,
                "configured": True,
                "endpointConfigured": bool(self._settings.endpoint),
                "apiKeyConfigured": bool(self._settings.api_key),
                "timeoutSeconds": self._settings.timeout_seconds,
                "maxDocuments": self._settings.max_documents,
                "maxAttempts": self._settings.max_attempts,
                "retryBaseDelayMs": self._settings.retry_base_delay_ms,
                "retrievalPriorWeight": self._settings.retrieval_prior_weight,
                "approvedSensitivityLevels": self._approved_sensitivity_levels,
                "syntheticOnlyEvaluation": self._synthetic_only_evaluation,
                "externalBodyFailClosed": True,
                "requestCount": self._request_count,
                "lastLatencyMs": self._last_latency_ms,
                "lastCandidateCount": self._last_candidate_count,
                "lastErrorCode": self._last_error_code,
                "failClosed": True,
                "payloadPolicy": "RAG_RERANK_DIAGNOSTICS_NO_QUERY_DOCUMENT_ENDPOINT_OR_SECRET",
            }

    def _candidate_text(self, candidate: RagScoredChunk) -> str:
        """构造只包含已授权 chunk 内容的有界重排文本。

        只发送有限的结构化资料属性，而不是把整份 metadata 原样交给第三方模型。长篇手册和 Excel
        台账的正文可能包含大量相似词；``category``、``artifactCode`` 和 ``retrievalAnchor`` 能帮助
        Reranker 区分“规范原文、Runbook、任务案例”等职责不同的资料。范围、凭据、sourceUri 和任意
        未经审查的 metadata 均不会通过这个入口外发。
        """

        chunk = candidate.chunk
        metadata = chunk.metadata or {}
        structured_lines: list[str] = []
        # 这里使用显式白名单，避免为了提高相关性把连接串、用户属性或内部审计正文误发给模型供应商。
        for key, label in (
            ("category", "资料类别"),
            ("artifactCode", "资料码"),
            ("retrievalAnchor", "检索锚点"),
            ("evidenceStatus", "证据状态"),
        ):
            value = str(metadata.get(key) or "").strip()
            if value:
                structured_lines.append(f"{label}：{_bounded_metadata_value(value)}")
        structured = "\n".join(structured_lines)
        text = (
            f"标题：{chunk.title}\n"
            f"来源类型：{chunk.source_type.value}\n"
            f"{structured}\n"
            f"正文：{chunk.text}\n"
            f"标签：{' '.join(chunk.tags)}"
        ).strip()
        bounded = text[: max(200, self._settings.max_document_chars)]
        if not bounded:
            raise ValueError("Reranker 候选文档不能为空。")
        return bounded

    def _require_approved_query_and_candidate_bodies(
        self,
        query: RagQuery,
        candidates: tuple[RagScoredChunk, ...],
    ) -> None:
        """校验查询和候选正文分级，确保未批准内容不能抵达 HTTP 载荷。

        候选在进入这里前已经完成范围过滤，但范围授权不等于可发送给第三方；用户问题也可能包含日志、
        SQL、字段或连接信息，不能因为候选已批准就自动外发。查询和所有候选都使用与 Embedding 相同的
        明确批准语义；其中 ``restricted`` 还需要 synthetic-only 评测边界。
        """

        require_external_text_sensitivity_approval(
            query.sensitivity_level,
            approved_sensitivity_levels=self._approved_sensitivity_levels,
            synthetic_only_evaluation=self._synthetic_only_evaluation,
        )
        for candidate in candidates:
            require_external_text_sensitivity_approval(
                candidate.chunk.sensitivity_level,
                approved_sensitivity_levels=self._approved_sensitivity_levels,
                synthetic_only_evaluation=self._synthetic_only_evaluation,
            )

    @staticmethod
    def _map_results(
        payload: Any,
        submitted: tuple[RagScoredChunk, ...],
    ) -> tuple[RagScoredChunk, ...]:
        """校验对象根节点与完整 index 集，并复制候选的各阶段可解释分数。"""

        if not isinstance(payload, Mapping):
            raise RuntimeError("RAG Reranker 响应根节点必须是对象。上游响应正文已隐藏。")

        raw_results = payload.get("results")
        if not isinstance(raw_results, list) or len(raw_results) != len(submitted):
            raise RuntimeError("RAG Reranker 响应数量与候选数量不一致。上游响应正文已隐藏。")
        mapped: dict[int, RagScoredChunk] = {}
        for item in raw_results:
            if not isinstance(item, Mapping):
                raise RuntimeError("RAG Reranker 响应项类型非法。上游响应正文已隐藏。")
            raw_index = item.get("index")
            score_value = item.get("relevance_score")
            if (
                isinstance(raw_index, bool)
                or not isinstance(raw_index, int)
                or isinstance(score_value, bool)
                or not isinstance(score_value, (int, float))
            ):
                raise RuntimeError("RAG Reranker 响应 index 或分数非法。上游响应正文已隐藏。")
            index = raw_index
            raw_score = float(score_value)
            if index < 0 or index >= len(submitted) or index in mapped or not math.isfinite(raw_score):
                raise RuntimeError("RAG Reranker 响应 index 重复、越界或分数非法。上游响应正文已隐藏。")
            source = submitted[index]
            normalized_score = max(0.0, min(1.0, raw_score))
            mapped[index] = RagScoredChunk(
                chunk=source.chunk,
                lexical_score=source.lexical_score,
                vector_score=source.vector_score,
                fused_score=source.fused_score,
                rerank_score=normalized_score,
                diversity_penalty=source.diversity_penalty,
                final_score=normalized_score,
                match_terms=source.match_terms,
                exact_score=source.exact_score,
                exact_match_identifiers=source.exact_match_identifiers,
            )
        if set(mapped) != set(range(len(submitted))):
            raise RuntimeError("RAG Reranker 响应缺少候选 index。上游响应正文已隐藏。")
        return tuple(sorted(mapped.values(), key=lambda candidate: candidate.final_score, reverse=True))

    def _record_success(self, started_at: float, candidate_count: int) -> None:
        """记录低基数成功状态。"""

        with self._lock:
            self._request_count += 1
            self._last_latency_ms = max(0, int((monotonic() - started_at) * 1000))
            self._last_candidate_count = candidate_count
            self._last_error_code = None

    def _record_failure(self, code: str, started_at: float, candidate_count: int) -> None:
        """记录稳定错误码，不保存异常正文。"""

        with self._lock:
            self._request_count += 1
            self._last_latency_ms = max(0, int((monotonic() - started_at) * 1000))
            self._last_candidate_count = candidate_count
            self._last_error_code = code


def rag_reranker_provider_settings_from_env(
    environ: Mapping[str, str] | None = None,
) -> RagRerankerProviderSettings:
    """读取 RAG Reranker 环境配置。

    专用 API Key 优先；``SILICONFLOW_API_KEY`` 仅作为同一部署中两个检索模型共享凭据的显式兼容项。
    诊断和异常永远不会回显任一变量的值。
    """

    source = environ if environ is not None else os.environ
    return RagRerankerProviderSettings(
        provider_type=_provider_type(source.get("DATASMART_RAG_RERANK_PROVIDER")),
        endpoint=str(source.get("DATASMART_RAG_RERANK_ENDPOINT") or "").strip(),
        api_key=str(
            source.get("DATASMART_RAG_RERANK_API_KEY")
            or source.get("SILICONFLOW_API_KEY")
            or ""
        ).strip(),
        model=str(source.get("DATASMART_RAG_RERANK_MODEL") or "").strip(),
        timeout_seconds=_positive_int(source.get("DATASMART_RAG_RERANK_TIMEOUT_SECONDS"), 30),
        max_documents=_positive_int(source.get("DATASMART_RAG_RERANK_MAX_DOCUMENTS"), 16),
        max_query_chars=_positive_int(source.get("DATASMART_RAG_RERANK_MAX_QUERY_CHARS"), 4000),
        max_document_chars=_positive_int(source.get("DATASMART_RAG_RERANK_MAX_DOCUMENT_CHARS"), 6000),
        max_attempts=_positive_int(source.get("DATASMART_RAG_RERANK_MAX_ATTEMPTS"), 3),
        retry_base_delay_ms=_positive_int(
            source.get("DATASMART_RAG_RERANK_RETRY_BASE_DELAY_MS"),
            250,
        ),
        retrieval_prior_weight=_bounded_float(
            source.get("DATASMART_RAG_RERANK_RETRIEVAL_PRIOR_WEIGHT"),
            0.0,
        ),
        approved_sensitivity_levels=normalize_external_text_sensitivity_levels(
            source.get("DATASMART_RAG_RERANK_APPROVED_SENSITIVITY_LEVELS"),
        ),
        synthetic_only_evaluation=_truthy(
            source.get("DATASMART_RAG_RERANK_SYNTHETIC_ONLY_EVALUATION"),
            default=False,
        ),
    )


def build_rag_reranker_provider(
    settings: RagRerankerProviderSettings | None = None,
    *,
    urlopen: UrlOpen = request.urlopen,
    sleep: Sleep = time.sleep,
) -> RagReranker | None:
    """按配置构建远程 Reranker；禁用时由管线继续使用规则式重排。"""

    resolved = settings or rag_reranker_provider_settings_from_env()
    if resolved.provider_type == RagRerankerProviderType.DISABLED:
        return None
    return SiliconFlowRagReranker(resolved, urlopen=urlopen, sleep=sleep)


def _retryable_http_status(status_code: int) -> bool:
    """仅允许对限流和常见服务端瞬态状态执行重试。"""

    return int(status_code) in {429, 500, 502, 503, 504}


def _bounded_metadata_value(value: str) -> str:
    """清理并限制可发送给 Reranker 的白名单 metadata 文本。"""

    normalized = " ".join(str(value).split())
    return normalized[:256]


def _provider_type(value: str | None) -> RagRerankerProviderType:
    """规范化 Provider 类型。"""

    normalized = str(value or "disabled").strip().lower().replace("_", "-")
    aliases = {
        "none": RagRerankerProviderType.DISABLED,
        "off": RagRerankerProviderType.DISABLED,
        "silicon-flow": RagRerankerProviderType.SILICONFLOW,
        "siliconcloud": RagRerankerProviderType.SILICONFLOW,
    }
    if normalized in aliases:
        return aliases[normalized]
    try:
        return RagRerankerProviderType(normalized)
    except ValueError as exc:
        raise ValueError("DATASMART_RAG_RERANK_PROVIDER 只支持 disabled 或 siliconflow。") from exc


def _validate_remote_endpoint(endpoint: str) -> None:
    """拒绝带凭据、查询参数或非 HTTPS 公网地址的 Endpoint。"""

    normalized = str(endpoint or "").strip()
    if not normalized:
        raise ValueError("硅基流动 Reranker 必须配置 Endpoint。")
    parts = parse.urlsplit(normalized)
    local_host = (parts.hostname or "").lower() in {"localhost", "127.0.0.1", "::1"}
    if parts.scheme not in ({"http", "https"} if local_host else {"https"}):
        raise ValueError("Reranker 公网 Endpoint 必须使用 HTTPS。")
    if not parts.hostname or parts.username or parts.password or parts.query or parts.fragment:
        raise ValueError("Reranker Endpoint 不能包含凭据、查询参数或 fragment。")
    if (parts.hostname or "").lower() != "api.siliconflow.cn":
        raise ValueError(
            "siliconflow Reranker 只能使用硅基流动官方主机；自定义网关需要独立 Provider 类型和密钥。"
        )


def _rerank_endpoint(endpoint: str) -> str:
    """把 base URL 规范化为硅基流动 Rerank 地址。"""

    normalized = endpoint.strip().rstrip("/")
    if normalized.endswith("/rerank"):
        return normalized
    if normalized.endswith("/v1"):
        return f"{normalized}/rerank"
    return f"{normalized}/v1/rerank"


def _positive_int(value: str | None, default: int) -> int:
    """读取正整数配置。"""

    if value is None or not str(value).strip():
        return default
    parsed = int(value)
    return parsed if parsed > 0 else default


def _bounded_float(value: str | None, default: float) -> float:
    """读取 0 到 1 之间的有限浮点配置。"""

    if value is None or not str(value).strip():
        return default
    parsed = float(value)
    if not math.isfinite(parsed):
        return default
    return max(0.0, min(1.0, parsed))


def _truthy(value: str | None, *, default: bool) -> bool:
    """读取显式开关；不认识的值保持关闭，避免生产误放行 synthetic-only 边界。"""

    if value is None or not str(value).strip():
        return default
    return str(value).strip().lower() in {"1", "true", "yes", "on", "enabled"}


__all__ = [
    "RagReranker",
    "RagRerankerProviderSettings",
    "RagRerankerProviderType",
    "SiliconFlowRagReranker",
    "build_rag_reranker_provider",
    "rag_reranker_provider_settings_from_env",
]

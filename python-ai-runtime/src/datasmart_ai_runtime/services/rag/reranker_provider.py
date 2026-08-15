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
from dataclasses import dataclass
from enum import Enum
from threading import Lock
from time import monotonic
from typing import Any, Callable, Mapping, Protocol
from urllib import error, parse, request

from datasmart_ai_runtime.services.rag.models import RagQuery, RagScoredChunk


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


class RagRerankerProviderType(str, Enum):
    """当前支持的 Reranker Provider 类型。"""

    DISABLED = "disabled"
    SILICONFLOW = "siliconflow"


@dataclass(frozen=True)
class RagRerankerProviderSettings:
    """远程 Reranker 的运行配置。

    ``max_documents`` 是 DataSmart 自己的候选上限，不等同于供应商账户容量。即使上游允许更多文档，
    本平台仍应通过候选窗口限制延迟、费用和数据外发范围。
    """

    provider_type: RagRerankerProviderType = RagRerankerProviderType.DISABLED
    endpoint: str = ""
    api_key: str = ""
    model: str = ""
    timeout_seconds: int = 30
    max_documents: int = 64
    max_query_chars: int = 4000
    max_document_chars: int = 6000


UrlOpen = Callable[..., Any]


class SiliconFlowRagReranker:
    """硅基流动 ``/v1/rerank`` 文本重排适配器。"""

    def __init__(
        self,
        settings: RagRerankerProviderSettings,
        *,
        urlopen: UrlOpen = request.urlopen,
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
        self._lock = Lock()
        self._request_count = 0
        self._last_latency_ms: int | None = None
        self._last_error_code: str | None = None
        self._last_candidate_count = 0

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
        limit = max(1, min(int(self._settings.max_documents), 200))
        submitted = tuple(candidates[:limit])
        safe_query = str(query.question or "").strip()[: max(100, self._settings.max_query_chars)]
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
        started_at = monotonic()
        try:
            with self._urlopen(http_request, timeout=max(1, self._settings.timeout_seconds)) as response:
                response_payload = json.loads(response.read().decode("utf-8"))
            reranked = self._map_results(response_payload, submitted)
        except error.HTTPError as exc:
            self._record_failure("RAG_RERANK_PROVIDER_HTTP_ERROR", started_at, len(submitted))
            raise RuntimeError(f"RAG Reranker HTTP 调用失败，status={exc.code}。上游响应正文已隐藏。") from exc
        except error.URLError as exc:
            self._record_failure("RAG_RERANK_PROVIDER_NETWORK_ERROR", started_at, len(submitted))
            raise RuntimeError("RAG Reranker 网络连接失败。Endpoint 与底层错误详情已隐藏。") from exc
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            self._record_failure("RAG_RERANK_PROVIDER_INVALID_JSON", started_at, len(submitted))
            raise RuntimeError("RAG Reranker 返回了无法解析的 JSON。上游响应正文已隐藏。") from exc
        except (RuntimeError, ValueError, TypeError) as exc:
            self._record_failure("RAG_RERANK_PROVIDER_INVALID_RESPONSE", started_at, len(submitted))
            if isinstance(exc, RuntimeError):
                raise
            raise RuntimeError("RAG Reranker 响应合同非法。上游响应正文已隐藏。") from exc
        self._record_success(started_at, len(submitted))
        return reranked

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
                "requestCount": self._request_count,
                "lastLatencyMs": self._last_latency_ms,
                "lastCandidateCount": self._last_candidate_count,
                "lastErrorCode": self._last_error_code,
                "failClosed": True,
                "payloadPolicy": "RAG_RERANK_DIAGNOSTICS_NO_QUERY_DOCUMENT_ENDPOINT_OR_SECRET",
            }

    def _candidate_text(self, candidate: RagScoredChunk) -> str:
        """构造只包含已授权 chunk 内容的有界重排文本。"""

        chunk = candidate.chunk
        text = f"标题：{chunk.title}\n正文：{chunk.text}\n标签：{' '.join(chunk.tags)}".strip()
        bounded = text[: max(200, self._settings.max_document_chars)]
        if not bounded:
            raise ValueError("Reranker 候选文档不能为空。")
        return bounded

    @staticmethod
    def _map_results(
        payload: Mapping[str, Any],
        submitted: tuple[RagScoredChunk, ...],
    ) -> tuple[RagScoredChunk, ...]:
        """校验完整 index 集并复制候选的各阶段可解释分数。"""

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
        max_documents=_positive_int(source.get("DATASMART_RAG_RERANK_MAX_DOCUMENTS"), 64),
        max_query_chars=_positive_int(source.get("DATASMART_RAG_RERANK_MAX_QUERY_CHARS"), 4000),
        max_document_chars=_positive_int(source.get("DATASMART_RAG_RERANK_MAX_DOCUMENT_CHARS"), 6000),
    )


def build_rag_reranker_provider(
    settings: RagRerankerProviderSettings | None = None,
    *,
    urlopen: UrlOpen = request.urlopen,
) -> RagReranker | None:
    """按配置构建远程 Reranker；禁用时由管线继续使用规则式重排。"""

    resolved = settings or rag_reranker_provider_settings_from_env()
    if resolved.provider_type == RagRerankerProviderType.DISABLED:
        return None
    return SiliconFlowRagReranker(resolved, urlopen=urlopen)


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


__all__ = [
    "RagReranker",
    "RagRerankerProviderSettings",
    "RagRerankerProviderType",
    "SiliconFlowRagReranker",
    "build_rag_reranker_provider",
    "rag_reranker_provider_settings_from_env",
]

"""长期记忆专用 Embedding Provider 抽象与实现。

DataSmart 的主推理模型、Embedding 模型和 Reranker 必须分开治理。原因不是“模型越多越好”，而是三类
工作负载的输入、输出、延迟、容量和升级周期不同：

- 主推理模型负责理解目标、规划工具和生成解释；
- Embedding 模型把已经审批、脱敏的记忆摘要转换为向量；
- Reranker 对候选文档做更精细的相关性重排。

本模块只解决第二类能力，并保持与具体模型家族解耦。生产环境可以接入 vLLM、SGLang、LiteLLM、
OpenAI-compatible 企业模型网关或独立 Embedding 服务；业务代码只依赖 `AgentMemoryEmbeddingProvider`。

安全边界：
- Provider 只能接收正式记忆的低敏摘要或检索提示，不能接收工具原始输出、SQL、样本数据和密钥；
- 异常不能回显上游响应正文、API Key 或完整 endpoint；
- 确定性哈希向量只用于测试和 smoke，不能被误标成生产语义模型。
"""

from __future__ import annotations

import hashlib
from http import client as http_client
import json
import math
import os
import time
from dataclasses import dataclass
from enum import Enum
from typing import Any, Callable, Iterable, Protocol
from urllib import error, parse, request


class AgentMemoryEmbeddingProvider(Protocol):
    """语义记忆 Embedding Provider 协议。

    协议刻意保持最小：输入一段低敏文本，返回固定维度的浮点向量。模型名称、endpoint、鉴权、
    超时和 HTTP 协议都属于 Provider 实现细节，不应渗透到 pgvector/Chroma 业务适配器。
    """

    def embed_text(
        self,
        text: str,
        *,
        sensitivity_level: str = "internal",
    ) -> tuple[float, ...]:
        """把已分类文本转换为向量；外部实现会校验该级别是否获准外发。"""

    def embed_texts(
        self,
        texts: tuple[str, ...],
        *,
        sensitivity_levels: tuple[str, ...] | None = None,
    ) -> tuple[tuple[float, ...], ...]:
        """批量转换已分类文本，并保持每条正文与其敏感级别严格对齐。"""


class MemoryEmbeddingProviderType(str, Enum):
    """Embedding Provider 类型。

    `DISABLED` 是本地默认，保证没有模型服务时 Runtime 仍可启动；
    `DETERMINISTIC` 只用于测试与 smoke；
    `OPENAI_COMPATIBLE` 用于接入成熟 Embedding 服务。
    """

    DISABLED = "disabled"
    DETERMINISTIC = "deterministic"
    OPENAI_COMPATIBLE = "openai-compatible"


@dataclass(frozen=True)
class MemoryEmbeddingProviderSettings:
    """Embedding Provider 运行配置。

    字段说明：
    - `provider_type`：选择禁用、确定性测试实现或 OpenAI-compatible 实现；
    - `endpoint`：Embedding base URL 或完整 `/embeddings` 地址；
    - `api_key`：可选 Bearer Token；本地 vLLM/SGLang 可能不要求；
    - `model`：独立 Embedding 模型名称，不允许复用“主聊天模型占位符”；
    - `dimensions`：确定性测试向量维度，或供诊断声明真实模型维度；
    - `timeout_seconds`：单次 HTTP 调用超时；
    - `organization`：可选组织 Header，用于兼容企业网关；
    - `max_input_chars`：发送给 Embedding Provider 的最大字符数。
    - `approved_sensitivity_levels`：明确允许发送给外部 Provider 的正文敏感级别；默认空集。
    - `synthetic_only_evaluation`：仅 synthetic-only 评测边界可与批准列表共同放行 `restricted`。
    """

    provider_type: MemoryEmbeddingProviderType = MemoryEmbeddingProviderType.DISABLED
    endpoint: str = ""
    api_key: str = ""
    model: str = ""
    dimensions: int = 16
    timeout_seconds: int = 30
    organization: str = ""
    max_input_chars: int = 4000
    max_batch_size: int = 16
    max_attempts: int = 3
    retry_base_delay_ms: int = 250
    approved_sensitivity_levels: tuple[str, ...] = ()
    synthetic_only_evaluation: bool = False


UrlOpen = Callable[..., Any]
Sleep = Callable[[float], None]


class DeterministicHashEmbeddingProvider:
    """确定性测试 Embedding Provider。

    它基于 SHA-256 生成稳定伪向量，只能验证：
- 同一文本能产生稳定向量；
- pgvector 能写入、计算距离并执行 metadata filter；
- 二级索引同步和检索状态机能够闭环。

    它不能衡量真实语义相似度，也不能用于生产召回质量评估。诊断必须明确标记
    `productionReady=False`，避免 smoke 成功被误解为模型层已经投产。
    """

    def __init__(self, *, dimensions: int = 16) -> None:
        self._dimensions = max(4, min(int(dimensions), 4096))

    def embed_text(
        self,
        text: str,
        *,
        sensitivity_level: str = "internal",
    ) -> tuple[float, ...]:
        """基于 SHA-256 生成稳定、非空、有限浮点向量。"""

        digest = hashlib.sha256(text.encode("utf-8")).digest()
        values = []
        for index in range(self._dimensions):
            byte_value = digest[index % len(digest)]
            values.append(round((byte_value / 255.0) * 2 - 1, 8))
        return tuple(values)

    def embed_texts(
        self,
        texts: tuple[str, ...],
        *,
        sensitivity_levels: tuple[str, ...] | None = None,
    ) -> tuple[tuple[float, ...], ...]:
        """逐条生成测试向量，并保持批量输入顺序。"""

        return tuple(self.embed_text(text) for text in texts)


class OpenAICompatibleMemoryEmbeddingProvider:
    """OpenAI-compatible Embeddings API 适配器。

    vLLM、SGLang、LiteLLM 和很多企业模型网关都可以暴露与
    `POST /v1/embeddings` 相近的协议。该实现只解析 `data[0].embedding`，并对返回值做严格校验。

    为了便于单元测试，`urlopen` 可以注入 fake transport；生产默认使用标准库
    `urllib.request.urlopen`，避免为了一个小型 HTTP 客户端再引入 SDK 锁定。
    """

    def __init__(
        self,
        settings: MemoryEmbeddingProviderSettings,
        *,
        urlopen: UrlOpen = request.urlopen,
        sleep: Sleep = time.sleep,
    ) -> None:
        if settings.provider_type != MemoryEmbeddingProviderType.OPENAI_COMPATIBLE:
            raise ValueError("OpenAI-compatible Embedding Provider 的 provider_type 配置不正确。")
        if not settings.endpoint.strip():
            raise ValueError("OpenAI-compatible Embedding Provider 必须配置 endpoint。")
        if not settings.model.strip():
            raise ValueError("OpenAI-compatible Embedding Provider 必须配置独立 embedding model。")
        _validate_embedding_endpoint(settings.endpoint)
        self._settings = settings
        self._urlopen = urlopen
        self._sleep = sleep
        self._approved_sensitivity_levels = normalize_external_text_sensitivity_levels(
            settings.approved_sensitivity_levels,
        )
        self._synthetic_only_evaluation = bool(settings.synthetic_only_evaluation)

    def embed_text(
        self,
        text: str,
        *,
        sensitivity_level: str = "internal",
    ) -> tuple[float, ...]:
        """调用 Embeddings API 并返回经过校验的向量。

        请求只发送截断后的低敏文本。HTTPError/URLError/JSON 解析异常都会转换成不包含 endpoint、
        API Key 和上游 body 的稳定错误，避免敏感信息进入 worker 日志或重试任务。
        """

        return self.embed_texts((text,), sensitivity_levels=(sensitivity_level,))[0]

    def embed_texts(
        self,
        texts: tuple[str, ...],
        *,
        sensitivity_levels: tuple[str, ...] | None = None,
    ) -> tuple[tuple[float, ...], ...]:
        """使用 Provider 的数组输入批量生成向量。

        企业语料摄取通常包含数十到数千个 chunk。逐条 HTTP 请求会放大网络延迟和限流压力，因此这里按
        ``max_batch_size`` 切成有界批次。每个响应必须提供完整、唯一且不越界的 index；只有单文本兼容
        旧 Provider 时允许省略 index。任何缺项或维度不一致都会整体失败，防止向量写入错误文档。
        """

        normalized = tuple(self._safe_text(text) for text in texts)
        if not normalized:
            raise ValueError("Embedding 批量输入不能为空。")
        levels = _embedding_sensitivity_levels(normalized, sensitivity_levels)
        for sensitivity_level in levels:
            require_external_text_sensitivity_approval(
                sensitivity_level,
                approved_sensitivity_levels=self._approved_sensitivity_levels,
                synthetic_only_evaluation=self._synthetic_only_evaluation,
            )
        batch_size = max(1, min(int(self._settings.max_batch_size), 128))
        vectors: list[tuple[float, ...]] = []
        for offset in range(0, len(normalized), batch_size):
            vectors.extend(self._request_batch(normalized[offset : offset + batch_size]))
        dimensions = {len(vector) for vector in vectors}
        if len(dimensions) != 1:
            raise RuntimeError("Embedding Provider 批量响应的向量维度不一致。")
        return tuple(vectors)

    def _safe_text(self, text: str) -> str:
        """裁剪单条低敏文本，并拒绝空输入。"""

        safe_text = str(text or "").strip()[: max(100, self._settings.max_input_chars)]
        if not safe_text:
            raise ValueError("Embedding 输入不能为空。")
        return safe_text

    def _request_batch(self, texts: tuple[str, ...]) -> tuple[tuple[float, ...], ...]:
        """执行一次有界批量请求并按 Provider index 恢复输入顺序。"""

        payload = json.dumps(
            {
                "model": self._settings.model,
                "input": texts[0] if len(texts) == 1 else list(texts),
                "encoding_format": "float",
            },
            ensure_ascii=False,
        ).encode("utf-8")
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json",
            "User-Agent": "datasmart-ai-memory-embedding/1.0",
        }
        if self._settings.api_key:
            headers["Authorization"] = f"Bearer {self._settings.api_key}"
        if self._settings.organization:
            headers["OpenAI-Organization"] = self._settings.organization
        http_request = request.Request(
            _embedding_endpoint(self._settings.endpoint),
            data=payload,
            headers=headers,
            method="POST",
        )
        response_payload = self._read_response_with_bounded_retry(http_request)

        try:
            raw_items = response_payload["data"]
        except (KeyError, TypeError) as exc:
            raise RuntimeError("Embedding Provider 响应缺少 data 数组。上游响应正文已隐藏。") from exc
        if not isinstance(raw_items, list) or len(raw_items) != len(texts):
            raise RuntimeError("Embedding Provider 批量响应数量与请求数量不一致。上游响应正文已隐藏。")

        indexed: dict[int, tuple[float, ...]] = {}
        for response_position, item in enumerate(raw_items):
            if not isinstance(item, dict) or "embedding" not in item:
                raise RuntimeError("Embedding Provider 响应缺少 embedding。上游响应正文已隐藏。")
            raw_index = item.get("index")
            if raw_index is None and len(texts) == 1:
                raw_index = response_position
            if isinstance(raw_index, bool) or not isinstance(raw_index, int):
                raise RuntimeError("Embedding Provider 响应 index 非法。上游响应正文已隐藏。")
            item_index = raw_index
            if item_index < 0 or item_index >= len(texts) or item_index in indexed:
                raise RuntimeError("Embedding Provider 响应 index 重复或越界。上游响应正文已隐藏。")
            indexed[item_index] = validate_embedding_vector(item["embedding"])

        if set(indexed) != set(range(len(texts))):
            raise RuntimeError("Embedding Provider 批量响应缺少输入项。上游响应正文已隐藏。")
        vectors = tuple(indexed[index] for index in range(len(texts)))
        if len({len(vector) for vector in vectors}) != 1:
            raise RuntimeError("Embedding Provider 批量响应的向量维度不一致。")
        return vectors

    def _read_response_with_bounded_retry(
        self,
        http_request: request.Request,
    ) -> dict[str, Any]:
        """对 429/5xx/超时/断连执行有限退避，其他失败保持 fail-closed。

        Embedding 摄取可能连续发送几十个批次，单次网关抖动不应使整份已经校验的文档失败；但鉴权、请求
        参数和响应 Schema 错误没有重试价值。这里最多尝试五次（默认三次），等待时间指数增长且单次不
        超过四秒。密钥、Endpoint、输入正文和上游 body 始终不进入异常。
        """

        maximum_attempts = max(1, min(int(self._settings.max_attempts), 5))
        for attempt in range(1, maximum_attempts + 1):
            try:
                with self._urlopen(
                    http_request,
                    timeout=max(1, self._settings.timeout_seconds),
                ) as response:
                    payload = json.loads(response.read().decode("utf-8"))
                if not isinstance(payload, dict):
                    raise TypeError("Embedding Provider JSON 根节点必须是对象。")
                return payload
            except error.HTTPError as exc:
                if _retryable_http_status(exc.code) and attempt < maximum_attempts:
                    self._wait_before_retry(attempt)
                    continue
                raise RuntimeError(
                    f"Embedding Provider HTTP 调用失败，status={exc.code}。上游响应正文已隐藏。"
                ) from exc
            except (
                error.URLError,
                TimeoutError,
                ConnectionError,
                OSError,
                http_client.IncompleteRead,
                http_client.RemoteDisconnected,
            ) as exc:
                if attempt < maximum_attempts:
                    self._wait_before_retry(attempt)
                    continue
                raise RuntimeError(
                    "Embedding Provider 网络连接失败。endpoint 与底层错误详情已隐藏。"
                ) from exc
            except (UnicodeDecodeError, json.JSONDecodeError, TypeError, ValueError) as exc:
                raise RuntimeError(
                    "Embedding Provider 返回了无法解析的 JSON。上游响应正文已隐藏。"
                ) from exc
        raise AssertionError("Embedding Provider 重试循环不应越过最大次数。")

    def _wait_before_retry(self, completed_attempt: int) -> None:
        """执行有上限的指数退避，避免连续批次立即再次撞上限流窗口。"""

        base_seconds = max(1, int(self._settings.retry_base_delay_ms)) / 1000.0
        self._sleep(min(4.0, base_seconds * (2 ** max(0, completed_attempt - 1))))


def memory_embedding_provider_settings_from_env(
    environ: dict[str, str] | None = None,
) -> MemoryEmbeddingProviderSettings:
    """从环境变量读取 Embedding Provider 配置。

    支持：
- `DATASMART_AI_MEMORY_EMBEDDING_PROVIDER`；
- `DATASMART_AI_MEMORY_EMBEDDING_ENDPOINT`；
- `DATASMART_AI_MEMORY_EMBEDDING_API_KEY`；
- `DATASMART_AI_MEMORY_EMBEDDING_MODEL`；
- `DATASMART_AI_MEMORY_EMBEDDING_DIMENSIONS`；
- `DATASMART_AI_MEMORY_EMBEDDING_TIMEOUT_SECONDS`；
- `DATASMART_AI_MEMORY_EMBEDDING_ORGANIZATION`；
- `DATASMART_AI_MEMORY_EMBEDDING_MAX_INPUT_CHARS`。
- `DATASMART_AI_MEMORY_EMBEDDING_MAX_BATCH_SIZE`。
- `DATASMART_AI_MEMORY_EMBEDDING_APPROVED_SENSITIVITY_LEVELS`。
- `DATASMART_AI_MEMORY_EMBEDDING_SYNTHETIC_ONLY_EVALUATION`。
    """

    source = environ if environ is not None else os.environ
    provider_type = _provider_type(source.get("DATASMART_AI_MEMORY_EMBEDDING_PROVIDER"))
    return MemoryEmbeddingProviderSettings(
        provider_type=provider_type,
        endpoint=source.get("DATASMART_AI_MEMORY_EMBEDDING_ENDPOINT") or "",
        api_key=source.get("DATASMART_AI_MEMORY_EMBEDDING_API_KEY") or "",
        model=source.get("DATASMART_AI_MEMORY_EMBEDDING_MODEL") or "",
        dimensions=_positive_int(source.get("DATASMART_AI_MEMORY_EMBEDDING_DIMENSIONS"), 16),
        timeout_seconds=_positive_int(source.get("DATASMART_AI_MEMORY_EMBEDDING_TIMEOUT_SECONDS"), 30),
        organization=source.get("DATASMART_AI_MEMORY_EMBEDDING_ORGANIZATION") or "",
        max_input_chars=_positive_int(source.get("DATASMART_AI_MEMORY_EMBEDDING_MAX_INPUT_CHARS"), 4000),
        max_batch_size=_positive_int(source.get("DATASMART_AI_MEMORY_EMBEDDING_MAX_BATCH_SIZE"), 16),
        max_attempts=_positive_int(source.get("DATASMART_AI_MEMORY_EMBEDDING_MAX_ATTEMPTS"), 3),
        retry_base_delay_ms=_positive_int(
            source.get("DATASMART_AI_MEMORY_EMBEDDING_RETRY_BASE_DELAY_MS"),
            250,
        ),
        approved_sensitivity_levels=normalize_external_text_sensitivity_levels(
            source.get("DATASMART_AI_MEMORY_EMBEDDING_APPROVED_SENSITIVITY_LEVELS"),
        ),
        synthetic_only_evaluation=_truthy(
            source.get("DATASMART_AI_MEMORY_EMBEDDING_SYNTHETIC_ONLY_EVALUATION"),
            default=False,
        ),
    )


def build_memory_embedding_provider(
    settings: MemoryEmbeddingProviderSettings,
    *,
    urlopen: UrlOpen = request.urlopen,
    sleep: Sleep = time.sleep,
) -> AgentMemoryEmbeddingProvider | None:
    """按配置创建 Embedding Provider。

    `disabled` 返回 None；调用方应把 vector index 标记为未启用并让路由回退，而不是偷偷使用伪向量。
    `deterministic` 仅用于显式测试配置。生产应使用 `openai-compatible` 或后续企业 Provider 实现。
    """

    if settings.provider_type == MemoryEmbeddingProviderType.DISABLED:
        return None
    if settings.provider_type == MemoryEmbeddingProviderType.DETERMINISTIC:
        return DeterministicHashEmbeddingProvider(dimensions=settings.dimensions)
    return OpenAICompatibleMemoryEmbeddingProvider(settings, urlopen=urlopen, sleep=sleep)


def validate_embedding_vector(value: Any) -> tuple[float, ...]:
    """校验并规范化向量。

    空向量、超大维度、NaN 和 Infinity 都会破坏 pgvector 距离计算或造成资源异常，因此在进入任何
    向量存储前统一拒绝。16384 是保守上限，不代表推荐模型维度。
    """

    if not isinstance(value, (list, tuple)):
        raise ValueError("Embedding 必须是浮点数组。")
    if not value:
        raise ValueError("Embedding Provider 返回空向量。")
    if len(value) > 16384:
        raise ValueError("Embedding 维度超过 16384，已拒绝写入。")
    normalized = tuple(float(item) for item in value)
    if not all(math.isfinite(item) for item in normalized):
        raise ValueError("Embedding 包含 NaN 或 Infinity，已拒绝写入。")
    return normalized


def memory_embedding_provider_diagnostics(settings: MemoryEmbeddingProviderSettings) -> dict[str, object]:
    """生成低敏 Provider 诊断，不返回 endpoint、API Key 或模型输入。"""

    return {
        "providerType": settings.provider_type.value,
        "model": settings.model or None,
        "configured": settings.provider_type != MemoryEmbeddingProviderType.DISABLED,
        "endpointConfigured": bool(settings.endpoint),
        "apiKeyConfigured": bool(settings.api_key),
        "declaredDimensions": settings.dimensions,
        "timeoutSeconds": settings.timeout_seconds,
        "maxInputChars": settings.max_input_chars,
        "maxBatchSize": settings.max_batch_size,
        "maxAttempts": settings.max_attempts,
        "retryBaseDelayMs": settings.retry_base_delay_ms,
        "approvedSensitivityLevels": normalize_external_text_sensitivity_levels(
            settings.approved_sensitivity_levels,
        ),
        "syntheticOnlyEvaluation": bool(settings.synthetic_only_evaluation),
        "externalBodyFailClosed": True,
        "productionReady": settings.provider_type == MemoryEmbeddingProviderType.OPENAI_COMPATIBLE,
        "notes": (
            "deterministic provider 只用于单元测试和 pgvector smoke，不能衡量语义召回质量。",
            "生产模型必须独立配置模型名、维度、最大输入、归一化策略、容量和召回评测。",
        ),
    }


def normalize_external_text_sensitivity_levels(
    values: str | Iterable[str] | None,
) -> tuple[str, ...]:
    """规范化外部模型正文的显式批准级别，不为部署猜测默认放行范围。

    该函数同时服务 Embedding 与 Reranker。空配置保持空 tuple，表示远端正文一律不能外发；这比把
    ``internal`` 或其他标签当作隐式批准更符合生产 fail-closed 边界。调用方可使用逗号分隔环境变量
    或配置层传入的序列，重复项会保持首次出现顺序并被去重。
    """

    if values is None:
        raw_values: Iterable[str] = ()
    elif isinstance(values, str):
        raw_values = values.split(",")
    else:
        raw_values = values
    normalized: list[str] = []
    seen: set[str] = set()
    for value in raw_values:
        level = _normalized_sensitivity_level(value)
        if level and level not in seen:
            seen.add(level)
            normalized.append(level)
    return tuple(normalized)


def require_external_text_sensitivity_approval(
    sensitivity_level: str,
    *,
    approved_sensitivity_levels: str | Iterable[str] | None,
    synthetic_only_evaluation: bool,
) -> None:
    """在任一 HTTP 请求创建前，校验正文分级是否已获明确外发批准。

    ``restricted`` 不因出现在普通 allowlist 中而自动放行，只有 synthetic-only 评测边界同时显式
    打开时才可提交。这让生产默认即使误配了级别，也会继续阻断受限正文；异常只返回稳定治理结论，
    不拼接正文、模型输入、Endpoint 或凭据。
    """

    level = _normalized_sensitivity_level(sensitivity_level)
    approved = normalize_external_text_sensitivity_levels(approved_sensitivity_levels)
    if not level or level not in approved:
        raise RuntimeError("外部模型正文敏感级别未获外发批准，已阻断。")
    if level == "restricted" and not synthetic_only_evaluation:
        raise RuntimeError("restricted 正文仅允许在显式 synthetic-only 评测边界内外发，已阻断。")


def _embedding_sensitivity_levels(
    texts: tuple[str, ...],
    sensitivity_levels: tuple[str, ...] | None,
) -> tuple[str, ...]:
    """校验批量正文与分级一一对应，缺失分级按内部正文处理但仍要求显式批准。"""

    if sensitivity_levels is None:
        return ("internal",) * len(texts)
    if isinstance(sensitivity_levels, str):
        raise ValueError("Embedding 批量正文敏感级别必须逐条提供。")
    levels = tuple(str(level or "") for level in sensitivity_levels)
    if len(levels) != len(texts):
        raise ValueError("Embedding 批量正文与敏感级别数量不一致。")
    return levels


def _normalized_sensitivity_level(value: object) -> str:
    """把分级标识归一为稳定的小写比较键。"""

    return str(value or "").strip().lower().replace("_", "-")


def _truthy(value: str | None, *, default: bool) -> bool:
    """读取显式布尔开关；未知值保持保守的关闭状态。"""

    if value is None or not str(value).strip():
        return default
    return str(value).strip().lower() in {"1", "true", "yes", "on", "enabled"}


def _embedding_endpoint(endpoint: str) -> str:
    """把 base URL 规范化为 Embeddings API 地址。"""

    normalized = endpoint.strip().rstrip("/")
    if normalized.endswith("/embeddings"):
        return normalized
    if normalized.endswith("/v1"):
        return f"{normalized}/embeddings"
    return f"{normalized}/v1/embeddings"


def _retryable_http_status(status_code: int) -> bool:
    """只把限流与常见服务端瞬态状态视为可重试。"""

    return int(status_code) in {429, 500, 502, 503, 504}


def _validate_embedding_endpoint(endpoint: str) -> None:
    """校验 Embedding Endpoint，防止凭据或知识正文经远程明文 HTTP 发送。

    OpenAI-compatible 是通用企业适配器，因此不会限制为某一个厂商主机；但无论上游是否要求密钥，
    查询和文档正文都属于需要保护的数据，除 localhost 开发调试外必须使用 HTTPS。URL 中的用户名、
    密码、查询参数和 fragment 也一律拒绝，避免凭据或路由参数被代理、访问日志和错误追踪意外记录。
    """

    parts = parse.urlsplit(str(endpoint or "").strip())
    local_host = (parts.hostname or "").lower() in {"localhost", "127.0.0.1", "::1"}
    allowed_schemes = {"http", "https"} if local_host else {"https"}
    if parts.scheme not in allowed_schemes:
        raise ValueError("远程 Embedding Endpoint 必须使用 HTTPS，只有 localhost 可使用 HTTP。")
    if not parts.hostname or parts.username or parts.password or parts.query or parts.fragment:
        raise ValueError("Embedding Endpoint 不能包含凭据、查询参数或 fragment。")


def _provider_type(value: str | None) -> MemoryEmbeddingProviderType:
    """规范化 Provider 类型并对非法值快速失败。

    SiliconFlow 的 Embedding 接口遵循 OpenAI-compatible ``/embeddings`` 合同，
    因此业务配置可以使用更直观的 ``siliconflow`` 名称，但底层仍复用同一套
    OpenAI-compatible HTTP 实现。这里做别名归一化，避免 RAG 层已经允许
    ``DATASMART_RAG_EMBEDDING_PROVIDER=siliconflow`` 时，在共享 Memory Provider
    解析阶段被错误拒绝，导致 Runtime 尚未启动就进入 unhealthy。
    """

    normalized = (value or "disabled").strip().lower().replace("_", "-")
    aliases = {
        "none": MemoryEmbeddingProviderType.DISABLED,
        "off": MemoryEmbeddingProviderType.DISABLED,
        "hash": MemoryEmbeddingProviderType.DETERMINISTIC,
        "test": MemoryEmbeddingProviderType.DETERMINISTIC,
        "openai": MemoryEmbeddingProviderType.OPENAI_COMPATIBLE,
        "openai-compatible": MemoryEmbeddingProviderType.OPENAI_COMPATIBLE,
        "siliconflow": MemoryEmbeddingProviderType.OPENAI_COMPATIBLE,
    }
    if normalized in aliases:
        return aliases[normalized]
    try:
        return MemoryEmbeddingProviderType(normalized)
    except ValueError as exc:
        raise ValueError(
            "DATASMART_AI_MEMORY_EMBEDDING_PROVIDER 只支持 disabled、deterministic 或 openai-compatible。"
        ) from exc


def _positive_int(value: str | None, default: int) -> int:
    """读取正整数，空值使用默认值，非法文本显式失败。"""

    if value is None or not value.strip():
        return default
    parsed = int(value)
    return parsed if parsed > 0 else default


__all__ = [
    "AgentMemoryEmbeddingProvider",
    "DeterministicHashEmbeddingProvider",
    "MemoryEmbeddingProviderSettings",
    "MemoryEmbeddingProviderType",
    "OpenAICompatibleMemoryEmbeddingProvider",
    "build_memory_embedding_provider",
    "memory_embedding_provider_diagnostics",
    "memory_embedding_provider_settings_from_env",
    "normalize_external_text_sensitivity_levels",
    "require_external_text_sensitivity_approval",
    "validate_embedding_vector",
]

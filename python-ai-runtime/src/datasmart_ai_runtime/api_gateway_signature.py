"""gateway 到 Python Runtime 的内部签名校验工具。

这个模块服务于 DataSmart 的“智能网关信任链”建设：Python Runtime 不应该只因为请求里写着
``X-DataSmart-Source-Service=datasmart-govern-gateway`` 就相信调用方。真实商业化部署中，终端用户、
脚本、第三方系统甚至误配置的内网服务都可能绕过 Java gateway 直连 Python Runtime，并伪造租户、角色、
workspace、数据范围等 Header。

当前实现采用 HMAC-SHA256 作为迁移期最小可信方案：
- Java gateway 在清理外部伪造 Header、写入身份上下文、调用 permission-admin 生成数据范围后，对一组
  可信 Header 快照做签名；
- Python Runtime 使用相同密钥复算签名，只有签名、时间窗口和 keyId 都通过时，才允许 API 边界把这些
  Header 重建为 ``trustedControlPlane``；
- 生产环境仍应叠加 HTTPS/mTLS、Secret Manager、内网访问控制、Redis nonce 去重和密钥轮换，本模块不是
  服务间安全的终点，而是让当前项目从“来源自报”升级到“来源可验证”的第一步。
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import os
import time
from dataclasses import dataclass
from typing import Mapping, Protocol


SIGNATURE_VERSION = "v1"
GATEWAY_SIGNATURE_VERSION = "X-DataSmart-Gateway-Signature-Version"
GATEWAY_SIGNATURE_TIMESTAMP = "X-DataSmart-Gateway-Signature-Timestamp"
GATEWAY_SIGNATURE_NONCE = "X-DataSmart-Gateway-Signature-Nonce"
GATEWAY_SIGNATURE_KEY_ID = "X-DataSmart-Gateway-Signature-Key-Id"
GATEWAY_SIGNATURE = "X-DataSmart-Gateway-Signature"

# 这里的顺序是签名协议的一部分，必须与 Java
# GatewayPythonRuntimeSignatureFilter.SIGNED_HEADERS 保持一致。
# 如果未来新增高敏 Header，例如 permission policy version、tenant package、service account scopes，
# 推荐升级协议版本或确保 Java/Python 双端同步发布，避免灰度期间一端验签失败。
SIGNED_HEADERS = (
    "X-DataSmart-Source-Service",
    "X-Gateway-Original-Path",
    "X-Gateway-Route-Prefix",
    "X-DataSmart-Trace-Id",
    "X-DataSmart-Tenant-Id",
    "X-DataSmart-Actor-Id",
    "X-DataSmart-Actor-Role",
    "X-DataSmart-Actor-Type",
    "X-DataSmart-Workspace-Id",
    "X-DataSmart-Request-Source",
    "X-DataSmart-Data-Scope-Level",
    "X-DataSmart-Data-Scope-Expression",
    "X-DataSmart-Authorized-Project-Ids",
    "X-DataSmart-Approval-Required",
)


@dataclass(frozen=True)
class GatewaySignatureVerificationConfig:
    """Python Runtime 对 gateway 签名的校验策略。

    字段说明：
    - ``required``：是否强制要求 gateway 请求必须验签通过。生产环境应为 true；本地学习和离线测试可以
      保持 false，以便只启动 Python Runtime 时仍能使用旧测试数据。
    - ``secret``：HMAC 共享密钥。它是真正敏感的值，必须来自环境变量、Secret Manager 或部署平台密钥注入，
      不能写入 Git。只要配置了 secret，即使 ``required=false``，也会进入验签逻辑。
    - ``key_id``：当前密钥标识。它不是秘密，用来支持灰度轮换和排障。后续可以扩展成 current/previous
      双 key 校验，允许 gateway 与 Python Runtime 滚动升级。
    - ``max_skew_seconds``：允许的时间偏移窗口。窗口越小越安全，但越依赖 gateway 与 Python Runtime 的
      系统时钟同步；当前默认 300 秒是迁移期折中值。
    - ``nonce_ttl_seconds``：nonce 去重 TTL。生产环境应确保它不小于 ``max_skew_seconds``，否则签名时间
      窗口仍有效但 nonce 已过期时，重放请求可能再次被接受。
    """

    required: bool = False
    secret: str = ""
    key_id: str = "gateway-local-v1"
    max_skew_seconds: int = 300
    nonce_ttl_seconds: int = 300

    @property
    def verification_enabled(self) -> bool:
        """是否需要执行验签。

        ``required`` 和 ``secret`` 任一存在即启用校验：这样生产只要配置了密钥就会自动收紧；同时保留
        本地无密钥时的兼容路径，避免把学习阶段变成“必须先搭全套网关才能跑 Python 单测”。
        """

        return self.required or bool(self.secret.strip())


@dataclass(frozen=True)
class GatewaySignatureVerificationResult:
    """一次验签的结果快照。

    ``reason`` 保留机器可读的失败原因，方便未来把失败原因映射为指标、审计事件或网关安全告警。
    """

    valid: bool
    reason: str = "ok"


class GatewaySignatureNonceStore(Protocol):
    """gateway 签名 nonce 去重 store 的最小协议。

    签名模块只依赖这一个方法，避免直接绑定 Redis、内存字典或企业缓存 SDK。实现方需要保证：
    - 第一次看到同一个 keyId + nonce 时返回 True；
    - TTL 内再次看到相同 keyId + nonce 时返回 False；
    - TTL 过后允许清理。
    """

    def try_mark_seen(self, *, key_id: str, nonce: str, timestamp_ms: int, ttl_seconds: int) -> bool:
        """尝试登记 nonce。"""


class GatewaySignatureVerificationError(PermissionError):
    """gateway 内部签名校验失败。

    使用独立异常而不是普通 ``ValueError``，是为了让 API 路由或统一异常处理中间件未来可以把它稳定映射为
    401/403，并记录“疑似绕过 gateway 或签名配置错误”的安全审计事件。
    """

    def __init__(self, reason: str) -> None:
        """保存机器可读失败原因。

        ``reason`` 不包含密钥、签名原文或完整 Header，只描述失败类别，例如
        ``missing-signature-headers``、``signature-mismatch`` 或 ``timestamp-out-of-window``。API 层可以把它
        安全地写入日志、指标和响应 detail，既方便排障，又避免泄漏敏感材料。
        """

        self.reason = reason
        super().__init__(f"gateway signature verification failed: {reason}")


def gateway_signature_config_from_env(
    environ: Mapping[str, str] | None = None,
) -> GatewaySignatureVerificationConfig:
    """从环境变量读取签名校验配置。

    环境变量设计：
    - ``DATASMART_GATEWAY_SIGNATURE_REQUIRED``：是否强制验签，生产建议 true；
    - ``DATASMART_GATEWAY_SIGNATURE_SECRET``：与 Java gateway 共享的 HMAC 密钥；
    - ``DATASMART_GATEWAY_SIGNATURE_KEY_ID``：密钥标识，需与 gateway 配置一致；
    - ``DATASMART_GATEWAY_SIGNATURE_MAX_SKEW_SECONDS``：允许时间偏移秒数。
    - ``DATASMART_GATEWAY_SIGNATURE_NONCE_TTL_SECONDS``：nonce 去重 TTL 秒数。
    """

    values = os.environ if environ is None else environ
    return GatewaySignatureVerificationConfig(
        required=_truthy(values.get("DATASMART_GATEWAY_SIGNATURE_REQUIRED")),
        secret=(values.get("DATASMART_GATEWAY_SIGNATURE_SECRET") or "").strip(),
        key_id=(values.get("DATASMART_GATEWAY_SIGNATURE_KEY_ID") or "gateway-local-v1").strip(),
        max_skew_seconds=_positive_int(values.get("DATASMART_GATEWAY_SIGNATURE_MAX_SKEW_SECONDS"), 300),
        nonce_ttl_seconds=_positive_int(values.get("DATASMART_GATEWAY_SIGNATURE_NONCE_TTL_SECONDS"), 300),
    )


def verify_gateway_signature(
    headers: Mapping[str, str],
    config: GatewaySignatureVerificationConfig,
    *,
    now_ms: int | None = None,
    nonce_store: GatewaySignatureNonceStore | None = None,
) -> GatewaySignatureVerificationResult:
    """校验 gateway 签名并返回可解释结果。

    这不是简单的字符串比较，而是按商业系统的服务间信任链要求分层校验：
    1. 未启用校验时直接返回 ``legacy-not-required``，兼容本地调试；
    2. 缺少密钥、签名字段、协议版本、keyId、timestamp、nonce 时拒绝；
    3. timestamp 必须落在允许时间窗口内，降低抓包后长期重放风险；
    4. 使用 ``hmac.compare_digest`` 做常量时间比较，避免普通 ``==`` 带来的微弱时序泄露；
    5. HMAC 通过后再登记 nonce，避免攻击者用无效签名抢先污染 nonce store。
    """

    if not config.verification_enabled:
        return GatewaySignatureVerificationResult(valid=True, reason="legacy-not-required")
    if not config.secret.strip():
        return GatewaySignatureVerificationResult(valid=False, reason="missing-secret")

    version = _header(headers, GATEWAY_SIGNATURE_VERSION)
    timestamp = _header(headers, GATEWAY_SIGNATURE_TIMESTAMP)
    nonce = _header(headers, GATEWAY_SIGNATURE_NONCE)
    key_id = _header(headers, GATEWAY_SIGNATURE_KEY_ID)
    provided_signature = _header(headers, GATEWAY_SIGNATURE)
    if not all((version, timestamp, nonce, key_id, provided_signature)):
        return GatewaySignatureVerificationResult(valid=False, reason="missing-signature-headers")
    if version != SIGNATURE_VERSION:
        return GatewaySignatureVerificationResult(valid=False, reason="unsupported-version")
    if key_id != config.key_id:
        return GatewaySignatureVerificationResult(valid=False, reason="unexpected-key-id")
    if not _timestamp_in_window(timestamp, config.max_skew_seconds, now_ms=now_ms):
        return GatewaySignatureVerificationResult(valid=False, reason="timestamp-out-of-window")

    expected_signature = sign_gateway_payload(
        headers,
        timestamp=timestamp,
        nonce=nonce,
        key_id=key_id,
        secret=config.secret,
    )
    if not hmac.compare_digest(expected_signature, provided_signature):
        return GatewaySignatureVerificationResult(valid=False, reason="signature-mismatch")
    if nonce_store is not None:
        timestamp_ms = _parse_timestamp_ms(timestamp)
        if timestamp_ms is None:
            return GatewaySignatureVerificationResult(valid=False, reason="timestamp-out-of-window")
        if not nonce_store.try_mark_seen(
            key_id=key_id,
            nonce=nonce,
            timestamp_ms=timestamp_ms,
            ttl_seconds=config.nonce_ttl_seconds,
        ):
            return GatewaySignatureVerificationResult(valid=False, reason="nonce-replayed")
    return GatewaySignatureVerificationResult(valid=True)


def ensure_gateway_signature(
    headers: Mapping[str, str],
    config: GatewaySignatureVerificationConfig,
    *,
    now_ms: int | None = None,
    nonce_store: GatewaySignatureNonceStore | None = None,
) -> None:
    """强制校验 gateway 签名，不通过时抛出可被 API 层捕获的异常。"""

    result = verify_gateway_signature(headers, config, now_ms=now_ms, nonce_store=nonce_store)
    if not result.valid:
        raise GatewaySignatureVerificationError(result.reason)


def sign_gateway_payload(
    headers: Mapping[str, str],
    *,
    timestamp: str,
    nonce: str,
    key_id: str,
    secret: str,
) -> str:
    """按 Java gateway 相同规则生成 URL-safe Base64 HMAC-SHA256 签名。"""

    digest = hmac.new(
        secret.encode("utf-8"),
        canonical_payload(headers, timestamp=timestamp, nonce=nonce, key_id=key_id).encode("utf-8"),
        hashlib.sha256,
    ).digest()
    return base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")


def canonical_payload(
    headers: Mapping[str, str],
    *,
    timestamp: str,
    nonce: str,
    key_id: str,
) -> str:
    """构造 Java/Python 双端共享的签名原文。

    签名原文故意保持朴素的逐行格式：第一行是协议版本，后续为 ``Header-Name:value``。这样做有两个好处：
    - 学习和排障时可以直接打印原文，快速比较 Java 与 Python 哪个 Header 值不一致；
    - 空 Header 也会写入空值，避免两端对“字段缺失时是否跳过”产生不同理解。
    """

    lines = [SIGNATURE_VERSION]
    for name in SIGNED_HEADERS:
        lines.append(f"{name}:{_header(headers, name) or ''}")
    lines.append(f"{GATEWAY_SIGNATURE_TIMESTAMP}:{_normalize(timestamp)}")
    lines.append(f"{GATEWAY_SIGNATURE_NONCE}:{_normalize(nonce)}")
    lines.append(f"{GATEWAY_SIGNATURE_KEY_ID}:{_normalize(key_id)}")
    return "\n".join(lines)


def _header(headers: Mapping[str, str], name: str) -> str | None:
    """大小写不敏感读取 Header，并统一 trim。

    Starlette 的 Headers 对象本身支持大小写不敏感读取，但单元测试经常传普通 dict，因此这里显式兜底遍历。
    """

    value = headers.get(name) or headers.get(name.lower())
    if value is None:
        lowered_name = name.lower()
        value = next((item for key, item in headers.items() if str(key).lower() == lowered_name), None)
    text = _normalize(value)
    return text or None


def _normalize(value: object | None) -> str:
    """把 Header 值规范化为签名使用的字符串。"""

    return "" if value is None else str(value).strip()


def _truthy(value: str | None) -> bool:
    """解析常见布尔环境变量写法。"""

    return (value or "").strip().lower() in {"1", "true", "yes", "y", "on"}


def _positive_int(value: str | None, default: int) -> int:
    """解析正整数环境变量，非法值回退到默认值。"""

    try:
        parsed = int((value or "").strip())
    except ValueError:
        return default
    return parsed if parsed > 0 else default


def _timestamp_in_window(timestamp: str, max_skew_seconds: int, *, now_ms: int | None) -> bool:
    """判断 gateway timestamp 是否处于允许时间窗口内。"""

    timestamp_ms = _parse_timestamp_ms(timestamp)
    if timestamp_ms is None:
        return False
    current_ms = int(time.time() * 1000) if now_ms is None else now_ms
    return abs(current_ms - timestamp_ms) <= max_skew_seconds * 1000


def _parse_timestamp_ms(timestamp: str) -> int | None:
    """解析 epoch milliseconds timestamp。"""

    try:
        return int(timestamp)
    except ValueError:
        return None

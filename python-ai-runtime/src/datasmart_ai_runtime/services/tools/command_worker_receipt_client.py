"""Java command worker receipt 写回客户端。

本文件从 `controlled_command_worker_runner.py` 中拆出，专门负责 Python Runtime -> Java agent-runtime 的 HTTP 写回。
拆分的原因有两个：

1. runner 的业务职责是“如何根据安全决策和运行模式生成低敏 receipt”，不应该同时膨胀成网络客户端；
2. HTTP 写回后续会自然扩展 mTLS、服务账号 token、重试、熔断、死信和服务发现，独立文件更容易控制行数与耦合。

安全边界保持不变：客户端只提交 Java DTO 白名单 payload；响应也只解析 accepted、duplicate、identityKey、outcome 等
低敏字段，不返回内部 URL、Java 原始响应正文、异常栈、endpoint、凭据、命令行、stdout/stderr 或业务 payload。
"""

from __future__ import annotations

import json
from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any, Callable
from urllib.request import Request, urlopen

from datasmart_ai_runtime.services.tools.controlled_command_worker_runner import (
    ControlledCommandWorkerReceipt,
    JAVA_COMMAND_WORKER_RECEIPT_ROUTE_TEMPLATE,
    _required_text,
    _safe_text,
)


@dataclass(frozen=True)
class CommandWorkerReceiptPostResult:
    """回执 POST 到 Java 后的低敏结果。

    字段说明：
    - `attempted` 表示客户端是否真正发起过 HTTP 调用；
    - `posted` 表示 Java 是否按统一响应接收；
    - `skipped` 表示客户端因未启用而主动跳过；
    - `duplicate` 表示 Java 侧按幂等键识别出重复回放；
    - `status_code` 只记录 HTTP 状态码，不记录响应正文；
    - `identity_key/outcome` 是 Java 回执响应中的低敏定位字段；
    - `error_code/message` 用于上层 worker 决定重试、死信或运维提示。
    """

    attempted: bool
    posted: bool
    skipped: bool
    duplicate: bool
    status_code: int | None
    identity_key: str | None
    outcome: str | None
    error_code: str | None
    endpoint_configured: bool
    message: str

    def to_summary(self) -> dict[str, Any]:
        """转换为 API/诊断可复用的低敏字典。"""

        return {
            "attempted": self.attempted,
            "posted": self.posted,
            "skipped": self.skipped,
            "duplicate": self.duplicate,
            "statusCode": self.status_code,
            "identityKey": self.identity_key,
            "outcome": self.outcome,
            "errorCode": self.error_code,
            "endpointConfigured": self.endpoint_configured,
            "message": self.message,
        }


@dataclass(frozen=True)
class JavaCommandWorkerReceiptClientSettings:
    """Python -> Java command worker receipt HTTP 客户端配置。

    - `enabled` 默认关闭，避免本地学习、单元测试或 preview 链路误触发真实 HTTP 写回；
    - `base_url` 只用于构造内部服务地址，永远不进入 `CommandWorkerReceiptPostResult`；
    - `timeout_seconds` 控制单次写回等待时间，receipt 写回不应长期阻塞 worker；
    - `fail_open` 为 True 时网络失败只返回失败摘要，False 时抛出异常交给真实 worker 的重试/死信框架。
    """

    enabled: bool = False
    base_url: str = "http://localhost:8091"
    timeout_seconds: int = 3
    fail_open: bool = True


class CommandWorkerReceiptClientError(RuntimeError):
    """Java receipt 客户端错误。

    该异常只在 `fail_open=False` 时抛出。默认 fail-open 是为了让当前项目在没有 Java 服务的本地环境中仍能验证合同；
    生产 worker 如果需要强一致写回，可以关闭 fail-open，让外层队列把失败任务转入重试或死信。
    """


class JavaCommandWorkerReceiptClient:
    """向 Java agent-runtime 提交 command worker receipt 的轻量客户端。

    当前使用标准库 `urllib`，避免 Python Runtime 默认依赖膨胀。未来如需 mTLS、服务账号 token、重试、熔断或服务发现，
    可以通过 `urlopen_func` 注入专用 HTTP adapter，而不改变 runner 生成 Java payload 的业务合同。
    """

    def __init__(
        self,
        settings: JavaCommandWorkerReceiptClientSettings | None = None,
        *,
        urlopen_func: Callable[..., Any] = urlopen,
    ) -> None:
        self._settings = settings or JavaCommandWorkerReceiptClientSettings()
        self._urlopen = urlopen_func

    def post_receipt(
        self,
        *,
        session_id: str,
        run_id: str,
        receipt: ControlledCommandWorkerReceipt,
        trace_id: str | None = None,
    ) -> CommandWorkerReceiptPostResult:
        """将低敏回执提交给 Java 内部接口。

        调用流程：
        1. 如果客户端未启用，直接返回 skipped，便于本地测试和 dry-run；
        2. 如果 endpoint 未配置，返回可重试失败摘要；
        3. 启用后构造 POST 请求，发送 runner 已经生成的 Java DTO payload；
        4. 只解析 Java 统一响应中的低敏 data 字段，拒绝扩散未声明字段。
        """

        endpoint_configured = bool(self._settings.base_url.strip())
        if not self._settings.enabled:
            return CommandWorkerReceiptPostResult(
                attempted=False,
                posted=False,
                skipped=True,
                duplicate=False,
                status_code=None,
                identity_key=None,
                outcome=receipt.outcome.value,
                error_code=None,
                endpoint_configured=endpoint_configured,
                message="Java command worker receipt 客户端未启用，已跳过真实 HTTP 写回。",
            )
        if not endpoint_configured:
            return self._failure(receipt.outcome.value, "ENDPOINT_NOT_CONFIGURED", None)
        request = self._build_http_request(session_id, run_id, receipt, trace_id)
        try:
            with self._urlopen(request, timeout=self._settings.timeout_seconds) as response:  # noqa: S310 - URL 来自受控配置
                status_code = int(getattr(response, "status", 200))
                body = response.read().decode("utf-8")
        except Exception as exc:  # pragma: no cover - 真实网络错误由集成环境覆盖
            if not self._settings.fail_open:
                raise CommandWorkerReceiptClientError("提交 Java command worker receipt 失败") from exc
            return self._failure(receipt.outcome.value, "HTTP_POST_FAILED", None)
        if status_code < 200 or status_code >= 300:
            if not self._settings.fail_open:
                raise CommandWorkerReceiptClientError("Java command worker receipt 接口返回非 2xx 状态")
            return self._failure(receipt.outcome.value, "JAVA_RECEIPT_STATUS_NOT_2XX", status_code)
        return self._parse_success(body, status_code, receipt.outcome.value)

    def _build_http_request(
        self,
        session_id: str,
        run_id: str,
        receipt: ControlledCommandWorkerReceipt,
        trace_id: str | None,
    ) -> Request:
        """构造 HTTP 请求。

        完整 URL 仅在请求对象中使用，不进入任何返回摘要。这样即便上层把 postResult 写入诊断或 runtime event，也不会泄露
        Java 内部服务地址。
        """

        route = JAVA_COMMAND_WORKER_RECEIPT_ROUTE_TEMPLATE.format(
            session_id=_required_text(session_id, "session_id"),
            run_id=_required_text(run_id, "run_id"),
        )
        body = json.dumps(receipt.java_payload, ensure_ascii=False).encode("utf-8")
        return Request(
            url=f"{self._settings.base_url.rstrip('/')}{route}",
            data=body,
            headers={
                "Accept": "application/json",
                "Content-Type": "application/json; charset=utf-8",
                "X-DataSmart-Trace-Id": trace_id or "",
                "X-DataSmart-Source-Service": "python-ai-runtime",
            },
            method="POST",
        )

    def _parse_success(
        self,
        response_body: str,
        status_code: int,
        fallback_outcome: str,
    ) -> CommandWorkerReceiptPostResult:
        """解析 Java `PlatformApiResponse<AgentToolActionCommandWorkerReceiptResponse>`。

        这里不把 Java 响应原文透传给调用方。即使 Java 未来为了内部审计扩展了更多字段，Python 侧也只保留白名单，
        避免把未审查字段扩散进 API、日志或 runtime event。
        """

        try:
            payload = json.loads(response_body)
        except json.JSONDecodeError:
            return self._failure(fallback_outcome, "JAVA_RECEIPT_RESPONSE_NOT_JSON", status_code)
        if not isinstance(payload, Mapping) or payload.get("code") != 0:
            return self._failure(fallback_outcome, "JAVA_RECEIPT_RESPONSE_FAILED", status_code)
        data = payload.get("data")
        if not isinstance(data, Mapping):
            return self._failure(fallback_outcome, "JAVA_RECEIPT_RESPONSE_DATA_INVALID", status_code)
        return CommandWorkerReceiptPostResult(
            attempted=True,
            posted=bool(data.get("accepted", True)),
            skipped=False,
            duplicate=bool(data.get("duplicate", False)),
            status_code=status_code,
            identity_key=_safe_text(data.get("identityKey"), max_length=260),
            outcome=_safe_text(data.get("outcome"), fallback=fallback_outcome, max_length=80),
            error_code=None,
            endpoint_configured=True,
            message="Java command worker receipt 已返回低敏接收结果。",
        )

    def _failure(
        self,
        fallback_outcome: str,
        error_code: str,
        status_code: int | None,
    ) -> CommandWorkerReceiptPostResult:
        """构造统一失败摘要，不包含异常栈或内部 endpoint。"""

        return CommandWorkerReceiptPostResult(
            attempted=True,
            posted=False,
            skipped=False,
            duplicate=False,
            status_code=status_code,
            identity_key=None,
            outcome=fallback_outcome,
            error_code=error_code,
            endpoint_configured=bool(self._settings.base_url.strip()),
            message="Java command worker receipt 写回未完成，调用方应根据重试/死信策略处理。",
        )

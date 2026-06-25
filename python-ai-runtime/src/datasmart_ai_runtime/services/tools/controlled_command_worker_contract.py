"""受控命令 Worker 的低敏公共合同与校验工具。

这个文件刻意从 `controlled_command_worker_runner.py` 中拆出来，原因不是“为了拆而拆”，而是为了把三类职责分清：

1. runner 负责“根据安全决策、沙箱准入结果和运行模式生成回执”；
2. HTTP client 负责“把低敏合同提交给 Java 控制面或读取 Java 低敏响应”；
3. 本文件负责“所有入口都必须复用的字段白名单、敏感词检测和格式校验”。

这样做可以避免后续接入真实 sandbox process、对象存储、lease 续租、MCP 工具桥接时，每个文件都各写一套
`fencingToken`、workspace 引用、artifact 引用、机器码和低敏文本校验规则。对于商业化项目来说，这种统一边界比
单个函数里“看起来能跑”更重要，因为它决定了敏感数据是否会在某个新入口里被绕过并扩散到日志、事件或投影视图。
"""

from __future__ import annotations

import re
from collections.abc import Mapping
from typing import Any


COMMAND_WORKER_RECEIPT_SCHEMA_VERSION = "datasmart.python-ai-runtime.controlled-command-worker-runner.v1"
JAVA_COMMAND_WORKER_RECEIPT_ROUTE_TEMPLATE = (
    "/internal/agent-runtime/sessions/{session_id}/runs/{run_id}/tool-executions/command-worker-receipts"
)
COMMAND_WORKER_RECEIPT_PAYLOAD_POLICY = (
    "SUMMARY_ONLY_NO_COMMAND_LINE_NO_STDIO_NO_TOOL_ARGUMENTS_NO_PROMPT_NO_SQL_NO_PAYLOAD_BODY"
)

ALLOW_CONTROLLED_EXECUTION = "ALLOW_CONTROLLED_EXECUTION"
SAFE_ARTIFACT_REFERENCE_PREFIXES = (
    "agent-artifact:",
    "artifact:",
    "minio-object:",
    "agent-output:",
    "command-output:",
    "task-artifact:",
)
SAFE_WORKSPACE_REFERENCE_PREFIXES = (
    "agent-workspace:",
    "workspace:",
    "sandbox-workspace:",
)
SAFE_MACHINE_CODE_PATTERN = re.compile(r"^[A-Z0-9_.:-]{1,120}$")
SAFE_REFERENCE_PATTERN = re.compile(r"^[a-zA-Z0-9_.:/=@+-]{1,220}$")
SAFE_FENCING_TOKEN_PATTERN = re.compile(r"^cmd-lease:([1-9][0-9]*):[a-fA-F0-9]{8,64}$")

# 这些 marker 不是严格的 DLP 引擎，而是 Python Runtime 合同层的“第一道低成本护栏”。
# 真正生产环境还应该在 Java 控制面、对象存储读取、审计入库和前端展示前继续做分层脱敏。
SENSITIVE_TEXT_MARKERS = (
    "select ",
    "insert ",
    "update ",
    "delete ",
    "authorization:",
    "bearer ",
    "password",
    "secret",
    "credential",
    "api_key",
    "apikey",
    "prompt:",
    "commandline",
    "command line",
    "stdout",
    "stderr",
    "workingdirectory",
    "workspaceroot",
    "http://",
    "https://",
    "jdbc:",
)


def _drop_none(payload: Mapping[str, Any]) -> dict[str, Any]:
    """删除值为 None 的字段，保持跨服务 JSON payload 简洁且语义明确。"""

    return {key: value for key, value in payload.items() if value is not None}


def _summary_payload(payload: Mapping[str, Any]) -> dict[str, Any]:
    """生成诊断摘要时隐藏 `fencingToken` 明文。

    `java_payload` 本身必须携带 token 才能 POST 给 Java；但 `to_summary()` 常用于日志、测试失败输出或诊断接口，
    不能把 token 这种写回资格凭证扩散出去。因此摘要里只保留 token 是否存在。
    """

    result = dict(payload)
    if result.pop("fencingToken", None):
        result["fencingTokenPresent"] = True
    return result


def _required_text(value: Any, field_name: str) -> str:
    """读取必填低敏字符串字段，空值会被视为调用方合同错误。"""

    text = _safe_text(value, max_length=260)
    if not text:
        raise ValueError(f"{field_name} 不能为空")
    return text


def _safe_text(value: Any, *, fallback: str | None = None, max_length: int) -> str | None:
    """校验普通低敏文本。

    本函数适合用于状态说明、低敏标识、策略版本、组件名等字段。它不会试图理解业务内容，只做三个基础动作：
    去空白、拦截明显敏感片段、限制长度。任何需要承载 SQL、prompt、命令正文、stdout/stderr、URL 或凭据的字段，
    都不应该走这个合同进入 runtime event、projection 或 Java 内部回执。
    """

    if value is None:
        return fallback
    text = str(value).strip()
    if not text:
        return fallback
    if _looks_sensitive(text):
        if fallback is not None:
            return fallback
        raise ValueError("低敏文本字段疑似包含命令、输出、SQL、prompt、凭据或内部地址")
    return text[:max_length]


def _safe_recommended_actions(actions: tuple[str, ...]) -> tuple[str, ...]:
    """清洗面向操作者的低敏建议动作。

    建议动作通常会进入运维台、审计说明或失败回执，所以这里限制最多 6 条，避免调用方把大量上下文伪装成
    recommendedActions 传入系统。
    """

    result: list[str] = []
    for action in actions[:6]:
        result.append(_required_safe_action(action))
    return tuple(result)


def _required_safe_action(action: str) -> str:
    """校验单条低敏建议动作，空动作没有业务意义，因此直接拒绝。"""

    value = _safe_text(action, max_length=240)
    if not value:
        raise ValueError("recommended_actions 中不能包含空动作")
    return value


def _safe_machine_codes(codes: tuple[str, ...]) -> tuple[str, ...]:
    """清洗机器可读的 evidence/issue/reason code。

    机器码会被用于聚合统计、策略判断和前端筛选，因此必须保持低基数、短文本、无敏感片段。这里允许冒号和点号，
    是为了兼容未来的分层 code，例如 `SANDBOX:WORKSPACE_REQUIRED`。
    """

    result: list[str] = []
    for code in codes[:12]:
        normalized = _normalize_machine_code(code)
        if not normalized:
            continue
        if not SAFE_MACHINE_CODE_PATTERN.fullmatch(normalized):
            raise ValueError("机器码只能包含大写字母、数字、下划线、点、冒号或短横线")
        if any(marker in normalized.lower() for marker in ("http", "password", "secret", "token", "credential")):
            raise ValueError("机器码不能携带 URL、凭据或敏感片段")
        result.append(normalized)
    return tuple(result)


def _normalize_machine_code(value: Any) -> str | None:
    """把调用方传入的状态码统一为大写机器码。"""

    if value is None:
        return None
    text = str(value).strip().upper()
    return text or None


def _non_negative_int(value: Any) -> int:
    """把预算类字段转换成非负整数，非法输入降级为 0 交给上层策略裁剪。"""

    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return 0
    return max(parsed, 0)


def _safe_fencing_token(value: str | None) -> str | None:
    """校验 command worker lease fencing token。

    token 明文只允许出现在 Python -> Java 的内部请求正文中，不能出现在响应、摘要、事件或日志里。
    """

    if value is None:
        return None
    text = str(value).strip()
    if _safe_fencing_token_version(text) is None:
        raise ValueError("fencing_token 必须使用 cmd-lease:{version}:{digest} 格式")
    return text


def _safe_fencing_token_version(value: str | None) -> int | None:
    """从 token 中提取 lease 版本，用于确认 token 与 workerLeaseVersion 没有错配。"""

    if value is None:
        return None
    match = SAFE_FENCING_TOKEN_PATTERN.fullmatch(str(value).strip())
    return int(match.group(1)) if match else None


def _is_safe_artifact_reference(value: str) -> bool:
    """判断 artifact 引用是否仍停留在低敏“引用”层，而不是对象正文或外部地址。"""

    reference = value.strip()
    lowered = reference.lower()
    if not lowered.startswith(SAFE_ARTIFACT_REFERENCE_PREFIXES):
        return False
    if _looks_sensitive(reference):
        return False
    if "://" in lowered or ".." in lowered or "\\" in reference:
        return False
    if "{" in reference or "}" in reference or "\n" in reference or "\r" in reference:
        return False
    return bool(SAFE_REFERENCE_PATTERN.fullmatch(reference))


def _is_safe_workspace_reference(value: str) -> bool:
    """判断 workspace 引用是否安全。

    workspaceReference 是 Java sandbox admission 的关键证据：它告诉 Java Host “未来进程只能在某个受控工作区里运行”。
    这里不允许真实文件路径、URL、反斜杠、目录逃逸或对象正文进入合同，避免未来真实 runner 被 prompt 或工具参数诱导到
    非授权路径执行。
    """

    reference = value.strip()
    lowered = reference.lower()
    if not lowered.startswith(SAFE_WORKSPACE_REFERENCE_PREFIXES):
        return False
    if _looks_sensitive(reference):
        return False
    if "://" in lowered or ".." in lowered or "\\" in reference:
        return False
    if "{" in reference or "}" in reference or "\n" in reference or "\r" in reference:
        return False
    return bool(SAFE_REFERENCE_PATTERN.fullmatch(reference))


def _looks_sensitive(value: Any) -> bool:
    """用轻量 marker 判断文本是否疑似包含敏感或高风险正文。"""

    if value is None:
        return False
    lowered = str(value).lower()
    return any(marker in lowered for marker in SENSITIVE_TEXT_MARKERS)

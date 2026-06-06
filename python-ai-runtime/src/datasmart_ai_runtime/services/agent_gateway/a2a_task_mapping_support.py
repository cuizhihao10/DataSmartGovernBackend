"""A2A task 规划适配器的字段抽取与低敏扫描工具。

该文件从 `a2a_task_planning_adapter.py` 拆出，主要是为了让核心 adapter 保持在 500 行以内，并让
“字段读取/敏感字段扫描”与“业务状态映射”分离。后续如果 Java DTO、真实 A2A SDK 或第三方网关字段
发生变化，通常只需要调整这里的抽取函数，不必改动规划决策状态机。
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping, Sequence
from typing import Any

from datasmart_ai_runtime.domain.protocols import A2aTaskHistoryEvent


# 字段黑名单只用于“输入里是否出现了不应进入规划层的内容”的计数，不会把字段名或路径返回给 API。
# 规划层最终只暴露 `sensitiveFieldIgnoredCount`，避免 summary 本身成为敏感字段名目录。
_FORBIDDEN_FIELD_NAMES = {
    "prompt",
    "message",
    "messages",
    "rawmessage",
    "requestbody",
    "responsebody",
    "toolarguments",
    "toolargs",
    "arguments",
    "executionpath",
    "artifactbody",
    "artifactcontent",
    "resourcebody",
    "modeloutput",
    "sql",
    "sampledata",
    "targetendpoint",
    "internalendpoint",
    "webhookurl",
    "secret",
    "apikey",
    "api-key",
    "token",
    "credential",
    "password",
}


def count_forbidden_fields(value: object) -> int:
    """递归统计被丢弃的敏感字段数量。

    这里刻意只返回数量：调用方可以知道“输入合同携带过不应进入规划层的字段”，但不会在返回摘要、
    runtime event 或测试日志里扩散字段名、路径和值。
    """

    if isinstance(value, Mapping):
        total = 0
        for key, nested in value.items():
            if normalized_key(key) in _FORBIDDEN_FIELD_NAMES:
                total += 1
                continue
            total += count_forbidden_fields(nested)
        return total
    if isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
        return sum(count_forbidden_fields(item) for item in value)
    return 0


def normalized_key(key: object) -> str:
    """归一化字段名，便于识别 `api-key`、`apiKey`、`API_KEY` 等变体。"""

    return str(key).strip().replace("_", "").replace("-", "").lower()


def first_mapping(*values: object) -> Mapping[str, Any]:
    """返回第一个 mapping；没有则返回空字典。"""

    for value in values:
        if isinstance(value, Mapping):
            return value
    return {}


def first_sequence(*values: object) -> tuple[object, ...]:
    """返回第一个非字符串序列，用于兼容 Java list、tuple 或测试输入。"""

    for value in values:
        if isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
            return tuple(value)
    return ()


def value(mapping: Mapping[str, Any], *keys: str, default: Any = None) -> Any:
    """从 mapping 中按多个候选键取值。

    Java DTO 使用 camelCase，Python 测试或未来内部调用可能传 snake_case。这里同时尝试两种命名，
    让 adapter 不必在每个字段上重复写兼容分支。
    """

    for key in keys:
        if key in mapping:
            return mapping[key]
        snake_key = camel_to_snake(key)
        if snake_key in mapping:
            return mapping[snake_key]
    return default


def camel_to_snake(raw_value: str) -> str:
    """把常见 camelCase 字段名转换为 snake_case。"""

    chars: list[str] = []
    for char in raw_value:
        if char.isupper() and chars:
            chars.append("_")
        chars.append(char.lower())
    return "".join(chars)


def safe_text(raw_value: object) -> str:
    """把低敏标识类字段转换为字符串。敏感字段不会调用这个函数进入输出。"""

    return "" if raw_value is None else str(raw_value)


def safe_text_tuple(raw_value: object) -> tuple[str, ...]:
    """把列表/元组/集合转换为字符串 tuple，非序列值按空处理。"""

    if not isinstance(raw_value, Iterable) or isinstance(raw_value, (str, bytes, bytearray, Mapping)):
        return ()
    return tuple(safe_text(item) for item in raw_value if safe_text(item))


def int_value(raw_value: object) -> int:
    """安全转换整数，转换失败时回落为 0。"""

    try:
        return int(raw_value)
    except (TypeError, ValueError):
        return 0


def int_tuple(raw_value: object) -> tuple[int, ...]:
    """把 sequence 列表转换为 int tuple。"""

    if not isinstance(raw_value, Iterable) or isinstance(raw_value, (str, bytes, bytearray, Mapping)):
        return ()
    return tuple(int_value(item) for item in raw_value)


def latest_history_sequence(events: tuple[A2aTaskHistoryEvent, ...]) -> int:
    """读取 history 中最大的 sequence，作为 task 快照缺省序号。"""

    return max((event.sequence for event in events), default=0)

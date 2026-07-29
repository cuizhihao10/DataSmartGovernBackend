"""面向用户界面的模型公开输出脱敏。

模型返回的 assistant 文本可以作为 Agent 工作过程的一部分实时展示，但它和隐藏 reasoning、系统提示词、
Provider 原始 payload 是不同的数据边界。本模块只处理已经被 Provider 标记为公开 assistant content 的文本，
并在进入 Runtime Event 或最终观察时间线前再次遮蔽常见密钥片段、限制异常响应长度。
"""

from __future__ import annotations

from dataclasses import dataclass
import re
from typing import Any


_SECRET_ASSIGNMENT_PATTERN = re.compile(
    r"(?i)\b(api[_ -]?key|access[_ -]?token|refresh[_ -]?token|password|secret)\b\s*[:=]\s*\S+"
)


@dataclass(frozen=True)
class PublicModelOutput:
    """可安全进入用户过程流的公开模型文本。"""

    content: str
    original_length: int
    truncated: bool


def sanitize_public_model_output(value: Any, *, max_chars: int = 4_000) -> PublicModelOutput:
    """遮蔽密钥型片段并限制长度，保留公开回复中的换行结构。"""

    raw_text = str(value or "").strip()
    if not raw_text:
        return PublicModelOutput(content="", original_length=0, truncated=False)
    masked_text = _SECRET_ASSIGNMENT_PATTERN.sub(r"\1=[已隐藏]", raw_text)
    truncated = len(masked_text) > max_chars
    content = masked_text[:max_chars] + ("…" if truncated else "")
    return PublicModelOutput(
        content=content,
        original_length=len(masked_text),
        truncated=truncated,
    )

"""Low-sensitive model identity handling for provider responses."""

from __future__ import annotations

import re
from typing import Any, Mapping


_MODEL_NAME_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/@+\-]{0,159}$")


def provider_reported_model_name(payload: Mapping[str, Any], fallback: str) -> str:
    """Return the actual model identifier reported by the provider.

    OpenAI-compatible relays may expose the identifier at the response root or
    under a nested ``response``/``data`` object. The value is untrusted remote
    input, so only a compact model-ID character set is admitted. When a relay
    omits or rewrites the field into an unsafe value, the configured request
    model remains the auditable fallback.
    """

    candidates: list[object] = [payload.get("model")]
    for container_name in ("response", "data"):
        nested = payload.get(container_name)
        if isinstance(nested, Mapping):
            candidates.append(nested.get("model"))

    for candidate in candidates:
        normalized = _safe_model_name(candidate)
        if normalized is not None:
            return normalized
    return fallback


def _safe_model_name(value: object) -> str | None:
    """Validate a provider model ID before it reaches logs or the UI."""

    if not isinstance(value, str):
        return None
    normalized = value.strip()
    if not normalized or not _MODEL_NAME_PATTERN.fullmatch(normalized):
        return None
    return normalized


__all__ = ["provider_reported_model_name"]

"""Deterministic extraction for explicit data-sync conversation corrections.

The model remains responsible for semantic understanding, tool selection and
multi-step planning. Exact user-controlled values such as a requested task name
or WHERE expression must not be reconstructed from a public model summary,
however. This module applies only narrowly phrased, directly observable edits to
the existing structured task snapshot. The resulting configuration still passes
normal metadata validation, precheck and human confirmation before execution.
"""

from __future__ import annotations

import re
from typing import Any


_IDENTIFIER = r"[A-Za-z_][A-Za-z0-9_$]*"


def apply_explicit_sync_corrections(
    payload: dict[str, Any],
    latest_user_message: str,
) -> dict[str, Any]:
    """Apply exact incremental edits while preserving every unrelated field."""

    corrected = dict(payload)
    mappings = [
        dict(item)
        for item in corrected.get("objectMappings", ())
        if isinstance(item, dict)
    ]
    message = str(latest_user_message or "").strip()
    if not message:
        if mappings:
            corrected["objectMappings"] = mappings
        return corrected

    task_name = _extract_task_name(message)
    if task_name:
        corrected["taskName"] = task_name

    write_strategy = _extract_write_strategy(message)
    if write_strategy:
        corrected["writeStrategy"] = write_strategy

    sync_mode = _extract_sync_mode(message)
    if sync_mode:
        corrected["syncMode"] = sync_mode

    if _accepts_mapping_defaults(message):
        corrected["mappingDefaultsConfirmed"] = True

    mappings = _apply_mapping_targets(mappings, message)
    mappings = _apply_where_corrections(mappings, message)
    if mappings:
        corrected["objectMappings"] = mappings
    return corrected


def _accepts_mapping_defaults(message: str) -> bool:
    accepts_default = re.search(
        r"(?:接受|确认|同意|采用|使用|按照?|按)\s*(?:当前|这个|以上|Agent\s*)?\s*默认",
        message,
        re.IGNORECASE,
    )
    mentions_mapping_scope = re.search(
        r"(?:同名字段|字段映射|无\s*WHERE|不需要\s*WHERE|没有\s*WHERE|全部数据|默认配置)",
        message,
        re.IGNORECASE,
    )
    return bool(accepts_default and mentions_mapping_scope)


def _extract_task_name(message: str) -> str | None:
    patterns = (
        r"(?:任务名称|任务名字|任务名)\s*(?:改为|修改为|设为|叫做?|使用)\s*"
        r"(?P<value>.+?)(?=(?:，|,)?\s*并(?:给|对|将|把|设置|修改)|[；;\n。]|$)",
        r"(?:rename\s+(?:the\s+)?task\s+(?:to|as))\s+"
        r"(?P<value>.+?)(?=(?:,\s*and)|[;\n.]|$)",
    )
    for pattern in patterns:
        match = re.search(pattern, message, re.IGNORECASE)
        if match is None:
            continue
        value = match.group("value").strip(" \t\"'“”")
        if value:
            return value[:200]
    return None


def _extract_write_strategy(message: str) -> str | None:
    match = re.search(
        r"(?:写入策略|write\s*strategy)\s*(?:改为|修改为|设为|使用|[:：=])?\s*"
        r"(?P<value>INSERT|UPDATE|MERGE|UPSERT)",
        message,
        re.IGNORECASE,
    )
    if match is None:
        return None
    value = match.group("value").upper()
    return "UPDATE" if value in {"MERGE", "UPSERT"} else value


def _extract_sync_mode(message: str) -> str | None:
    if not re.search(r"(?:同步模式|传输模式|sync\s*mode)", message, re.IGNORECASE):
        return None
    labels = (
        (r"定期全量|scheduled\s*full", "SCHEDULED_FULL"),
        (r"定期批量|scheduled\s*batch", "SCHEDULED_BATCH"),
        (r"实时(?:同步|传输)?|cdc|streaming", "CDC_STREAMING"),
        (r"SQL\s*(?:语句|模式|任务|query)?|custom\s*sql", "CUSTOM_SQL_QUERY"),
        (r"全量(?:传输|同步)?|full\s*sync", "FULL"),
    )
    for pattern, value in labels:
        if re.search(pattern, message, re.IGNORECASE):
            return value
    return None


def _apply_mapping_targets(
    mappings: list[dict[str, Any]],
    message: str,
) -> list[dict[str, Any]]:
    pattern = (
        rf"(?:将|把)\s*(?P<source>{_IDENTIFIER})\s*(?:表)?\s*"
        rf"(?:映射|同步|迁移|传输)\s*(?:到|至|为)\s*"
        rf"(?:(?P<schema>{_IDENTIFIER})\s*\.\s*)?(?P<target>{_IDENTIFIER})"
    )
    for match in re.finditer(pattern, message, re.IGNORECASE):
        source = match.group("source")
        target = match.group("target")
        schema = match.group("schema")
        mapping = _find_mapping(mappings, source)
        if mapping is None:
            mapping = {
                "objectKey": f"agent-correction-{len(mappings) + 1}",
                "sourceObjectName": source,
                "fieldMappings": [],
            }
            mappings.append(mapping)
        mapping["targetObjectName"] = target
        if schema:
            mapping["targetSchemaName"] = schema
    return mappings


def _apply_where_corrections(
    mappings: list[dict[str, Any]],
    message: str,
) -> list[dict[str, Any]]:
    clear_pattern = (
        rf"(?:给|对|为)\s*(?P<object>{_IDENTIFIER})\s*(?:表)?\s*"
        r"(?:移除|删除|清空|取消)\s*(?:WHERE|过滤)(?:条件)?"
    )
    for match in re.finditer(clear_pattern, message, re.IGNORECASE):
        mapping = _find_mapping(mappings, match.group("object"))
        if mapping is not None:
            mapping.pop("whereCondition", None)

    set_pattern = (
        rf"(?:给|对|为)\s*(?P<object>{_IDENTIFIER})\s*(?:表)?\s*"
        r"(?:增加|添加|设置|修改(?:为)?|改为)?\s*(?:WHERE|过滤)\s*(?:条件)?"
        r"\s*(?:为|[:：=])?\s*(?P<condition>.+?)"
        r"(?=(?:[；;\n。]|(?:，|,)\s*并)|$)"
    )
    for match in re.finditer(set_pattern, message, re.IGNORECASE):
        mapping = _find_mapping(mappings, match.group("object"))
        condition = match.group("condition").strip(" \t\"'“”")
        if mapping is not None and condition:
            mapping["whereCondition"] = condition
    return mappings


def _find_mapping(
    mappings: list[dict[str, Any]],
    object_name: str,
) -> dict[str, Any] | None:
    expected = object_name.strip().lower()
    for mapping in mappings:
        source = str(mapping.get("sourceObjectName") or "").strip().lower()
        target = str(mapping.get("targetObjectName") or "").strip().lower()
        if expected in {source, target}:
            return mapping
    return None


__all__ = ["apply_explicit_sync_corrections"]

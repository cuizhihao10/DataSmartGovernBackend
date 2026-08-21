"""受治理的内存 GraphRAG 核心。

本模块提供一个不依赖 Neo4j、向量数据库或外网的最小图检索实现，主要用于离线测试、
本地学习和后续 ``RagPipeline`` 注入。它把图数据、范围授权、时间有效性、别名解析、
有限跳数和证据路径放在同一个清晰的接口内，避免调用方直接拼接图查询或绕过治理规则。

当前只实现一种自然语言关系：``REPORTS_TO``，对应中文的“上级/上司/主管/领导”。
实现故意保持同步且纯内存；未来接入图数据库时，只需实现同样的 ``GraphRagRetriever``
接口，调用方不需要改变结果合同。
"""

from __future__ import annotations

import math
import re
import threading
import unicodedata
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import date, datetime, time, timezone
from enum import Enum
from typing import Any, Callable, Iterable, Mapping, Protocol, Sequence


MAX_GRAPH_RAG_HOPS = 3
"""图查询允许的最大跳数。超过此值必须拒答。"""

SCOPE_WILDCARD = "*"
"""图记录上的通配范围值；查询请求不能使用通配范围。"""


class GraphRagResultStatus(str, Enum):
    """GraphRAG 对调用方公开的三种终态。"""

    NOT_APPLICABLE = "not_applicable"
    SUCCESS = "success"
    REFUSAL = "refusal"


class GraphRagRelation(str, Enum):
    """当前内存核心支持的关系类型。"""

    REPORTS_TO = "REPORTS_TO"
    DATASOURCE_CONTAINS = "DATASOURCE_CONTAINS"
    SCHEMA_CONTAINS_TABLE = "SCHEMA_CONTAINS_TABLE"
    TABLE_HAS_FIELD = "TABLE_HAS_FIELD"
    FIELD_MAPS_TO = "FIELD_MAPS_TO"
    TABLE_HAS_PRIMARY_KEY = "TABLE_HAS_PRIMARY_KEY"
    FIELD_HAS_CONSTRAINT = "FIELD_HAS_CONSTRAINT"
    TASK_USES_DATASOURCE = "TASK_USES_DATASOURCE"
    TASK_SYNCS_TABLE = "TASK_SYNCS_TABLE"
    TASK_HAS_EXECUTION = "TASK_HAS_EXECUTION"
    TASK_HAS_SUCCESSFUL_VERSION = "TASK_HAS_SUCCESSFUL_VERSION"
    EXECUTION_FAILED_WITH = "EXECUTION_FAILED_WITH"
    INCIDENT_DOCUMENTS_ERROR = "INCIDENT_DOCUMENTS_ERROR"
    RUNBOOK_RECOMMENDS_ACTION = "RUNBOOK_RECOMMENDS_ACTION"
    RUNBOOK_ADDRESSES_ERROR = "RUNBOOK_ADDRESSES_ERROR"
    TASK_DEPENDS_ON_TASK = "TASK_DEPENDS_ON_TASK"
    EXECUTION_HAS_CHECKPOINT = "EXECUTION_HAS_CHECKPOINT"
    EXECUTION_REPLAYS_ERROR = "EXECUTION_REPLAYS_ERROR"
    EXECUTION_HAS_LOG = "EXECUTION_HAS_LOG"
    LOG_MATCHES_ERROR = "LOG_MATCHES_ERROR"
    TASK_HAS_VERSION = "TASK_HAS_VERSION"
    APPLICATION_CONTAINS_PROJECT = "APPLICATION_CONTAINS_PROJECT"
    PROJECT_OWNS_RESOURCE = "PROJECT_OWNS_RESOURCE"


class GraphRagReasonCode(str, Enum):
    """用于审计和测试的稳定结果原因码。"""

    ANSWERED = "ANSWERED"
    UNSUPPORTED_QUERY = "UNSUPPORTED_QUERY"
    SCOPE_REQUIRED = "SCOPE_REQUIRED"
    INVALID_QUERY = "INVALID_QUERY"
    RELATION_NOT_SUPPORTED = "RELATION_NOT_SUPPORTED"
    ALIAS_NOT_FOUND = "ALIAS_NOT_FOUND"
    AMBIGUOUS_ALIAS = "AMBIGUOUS_ALIAS"
    MAX_HOPS_EXCEEDED = "MAX_HOPS_EXCEEDED"
    NO_CURRENT_PATH = "NO_CURRENT_PATH"
    CONFLICTING_CURRENT_EDGES = "CONFLICTING_CURRENT_EDGES"
    TARGET_ENTITY_NOT_VISIBLE = "TARGET_ENTITY_NOT_VISIBLE"
    INCOMPLETE_PROVENANCE = "INCOMPLETE_PROVENANCE"
    RESULT_LIMIT_EXCEEDED = "RESULT_LIMIT_EXCEEDED"


GraphRagStatus = GraphRagResultStatus
"""兼容较短的状态类型名称。"""


TimestampValue = datetime | date | str | None


def _first_value(primary: Any, *alternatives: Any, default: Any = None) -> Any:
    """从多个兼容字段中选择一个非空值，并拒绝互相矛盾的输入。"""

    values = [value for value in (primary, *alternatives) if value is not None]
    if not values:
        return default
    first = values[0]
    if any(value != first for value in values[1:]):
        raise ValueError("同一字段的兼容参数不能互相矛盾")
    return first


def _text(value: Any, *, field_name: str, allow_empty: bool = False) -> str:
    """规范化文本字段，并在边界处拒绝非字符串或空值。"""

    if value is None:
        value = ""
    if not isinstance(value, str):
        value = str(value)
    value = value.strip()
    if not value and not allow_empty:
        raise ValueError(f"{field_name} 不能为空")
    return value


def _scope_text(
    value: Any,
    *,
    field_name: str,
    allow_wildcard: bool,
    allow_empty: bool = False,
) -> str:
    """规范化租户、项目和应用范围字段。"""

    if value is None:
        result = ""
    else:
        result = str(value).strip()
    if not result and not allow_empty:
        raise ValueError(f"{field_name} 不能为空")
    if not allow_wildcard and result == SCOPE_WILDCARD:
        raise ValueError(f"{field_name} 不能使用通配范围")
    return result


def _normalize_alias(value: Any) -> str:
    """把实体名称和查询中的名称归一化为可比较的键。"""

    if value is None:
        return ""
    normalized = unicodedata.normalize("NFKC", str(value)).casefold()
    normalized = re.sub(r"\s+", "", normalized)
    return normalized.strip("\u3002，,：:；;！？?!、\"'“”‘’()（）[]【】")


def _normalize_relation(value: Any) -> str:
    """把关系枚举、英文名称和中文关系词统一成标准关系 ID。"""

    if isinstance(value, GraphRagRelation):
        return value.value
    normalized = _normalize_alias(value).replace("-", "_")
    compact = normalized.replace("_", "")
    if normalized.upper() == GraphRagRelation.REPORTS_TO.value or compact.upper() == "REPORTSTO":
        return GraphRagRelation.REPORTS_TO.value
    if normalized in {"上级", "上司", "主管", "领导"}:
        return GraphRagRelation.REPORTS_TO.value
    return str(value).strip().upper() if value is not None else ""


def _timestamp(value: TimestampValue, *, field_name: str) -> datetime | None:
    """把支持的时间表示转换为带时区的 UTC 时间，仅用于比较。"""

    if value is None:
        return None
    if isinstance(value, datetime):
        parsed = value
    elif isinstance(value, date):
        parsed = datetime.combine(value, time.min)
    elif isinstance(value, str):
        raw = value.strip()
        if not raw:
            return None
        try:
            parsed = datetime.fromisoformat(raw.replace("Z", "+00:00"))
        except ValueError as exc:
            raise ValueError(f"{field_name} 不是有效的 ISO 时间") from exc
    else:
        raise ValueError(f"{field_name} 必须是日期、时间或 ISO 时间字符串")
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def _timestamp_text(value: TimestampValue) -> str | None:
    """为结果字典安全地序列化时间值。"""

    parsed = _timestamp(value, field_name="时间")
    return parsed.isoformat() if parsed is not None else None


def _now_utc() -> datetime:
    """返回当前 UTC 时间，供默认的有效性判断使用。"""

    return datetime.now(timezone.utc)


_SENSITIVITY_ORDER = {
    "public": 0,
    "internal": 1,
    "confidential": 2,
    "restricted": 3,
    "secret": 4,
}


@dataclass(frozen=True, slots=True, init=False)
class GraphRagScope:
    """一次查询或一条图记录的治理范围。

    图记录允许用 ``*`` 表示由所有具体范围继承；查询范围必须是具体值，
    这样别名解析永远不会在未授权的租户集合上运行。``workspace`` 仅作为
    旧事实包的反序列化兼容字段，绝不参与新合同的授权或输出。
    """

    tenant: str
    project: str
    workspace: str
    sensitivity: str
    application: str

    def __init__(
        self,
        tenant: str | None = None,
        project: str | None = None,
        workspace: str | None = None,
        sensitivity: str | None = None,
        *,
        tenant_id: str | None = None,
        application: str | None = None,
        application_id: str | None = None,
        project_id: str | None = None,
        workspace_key: str | None = None,
        workspace_id: str | None = None,
        sensitivity_level: str | None = None,
    ) -> None:
        resolved_tenant = _first_value(tenant, tenant_id, default="")
        resolved_project = _first_value(project, project_id, default="")
        resolved_workspace = _first_value(workspace, workspace_key, workspace_id, default="")
        resolved_sensitivity = _first_value(sensitivity, sensitivity_level, default="internal")
        resolved_application = _first_value(application, application_id, default=SCOPE_WILDCARD)
        object.__setattr__(
            self,
            "tenant",
            _scope_text(resolved_tenant, field_name="tenant", allow_wildcard=True, allow_empty=True),
        )
        object.__setattr__(
            self,
            "project",
            _scope_text(resolved_project, field_name="project", allow_wildcard=True, allow_empty=True),
        )
        object.__setattr__(
            self,
            "workspace",
            _scope_text(resolved_workspace, field_name="workspace", allow_wildcard=True, allow_empty=True),
        )
        object.__setattr__(
            self,
            "sensitivity",
            _text(resolved_sensitivity, field_name="sensitivity"),
        )
        object.__setattr__(
            self,
            "application",
            _scope_text(resolved_application, field_name="application", allow_wildcard=True, allow_empty=True),
        )

    @property
    def application_id(self) -> str:
        """返回产品应用 ID；新业务事实使用它替代 Workspace 作为应用边界。"""

        return self.application

    @property
    def tenant_id(self) -> str:
        """返回与现有 Runtime 命名约定兼容的租户字段。"""

        return self.tenant

    @property
    def project_id(self) -> str:
        """返回与现有 Runtime 命名约定兼容的项目字段。"""

        return self.project

    @property
    def workspace_key(self) -> str:
        """返回与现有 Runtime 命名约定兼容的工作空间字段。"""

        return self.workspace

    @property
    def workspace_id(self) -> str:
        """返回工作空间 ID 的兼容名称。"""

        return self.workspace

    @property
    def sensitivity_level(self) -> str:
        """返回与现有 Runtime 命名约定兼容的敏感级别字段。"""

        return self.sensitivity

    def is_concrete_query_scope(self) -> bool:
        """判断该范围是否足够具体，可以作为查询授权边界。"""

        modern_scope = all(
            value and value != SCOPE_WILDCARD
            for value in (self.tenant, self.application, self.project, self.sensitivity)
        )
        # 迁移窗口内允许旧请求继续完成离线查询，但其 workspace 只作为“请求曾经
        # 带过范围”的证据，不会参与 permits，也不会进入任何新序列化结果。
        legacy_scope = (
            self.application == SCOPE_WILDCARD
            and self.workspace
            and self.workspace != SCOPE_WILDCARD
            and all(value and value != SCOPE_WILDCARD for value in (self.tenant, self.project, self.sensitivity))
        )
        return modern_scope or legacy_scope

    def permits(self, record_scope: "GraphRagScope") -> bool:
        """判断一条记录范围是否对当前查询范围可见。"""

        if record_scope.tenant not in {SCOPE_WILDCARD, self.tenant}:
            return False
        if record_scope.project not in {SCOPE_WILDCARD, self.project}:
            return False
        if record_scope.application not in {SCOPE_WILDCARD, self.application}:
            return False
        record_level = _SENSITIVITY_ORDER.get(record_scope.sensitivity.casefold())
        query_level = _SENSITIVITY_ORDER.get(self.sensitivity.casefold())
        if record_level is None or query_level is None:
            return record_scope.sensitivity.casefold() == self.sensitivity.casefold()
        return record_level <= query_level


GraphScope = GraphRagScope


@dataclass(frozen=True, slots=True, init=False)
class GraphRagEntity:
    """图中的实体，使用稳定标准 ID，并在范围内维护可检索别名。"""

    standard_id: str
    canonical_name: str
    aliases: tuple[str, ...]
    tenant: str
    project: str
    workspace: str
    sensitivity: str
    metadata: Mapping[str, Any]
    application: str

    def __init__(
        self,
        standard_id: str | None = None,
        canonical_name: str | None = None,
        aliases: Iterable[str] | str = (),
        tenant: str | None = None,
        project: str | None = None,
        workspace: str | None = None,
        sensitivity: str | None = None,
        metadata: Mapping[str, Any] | None = None,
        *,
        application: str | None = None,
        application_id: str | None = None,
        entity_id: str | None = None,
        name: str | None = None,
        tenant_id: str | None = None,
        project_id: str | None = None,
        workspace_key: str | None = None,
        workspace_id: str | None = None,
        sensitivity_level: str | None = None,
    ) -> None:
        resolved_id = _first_value(standard_id, entity_id, default="")
        resolved_name = _first_value(canonical_name, name, default="")
        resolved_tenant = _first_value(tenant, tenant_id, default=SCOPE_WILDCARD)
        resolved_project = _first_value(project, project_id, default=SCOPE_WILDCARD)
        resolved_workspace = _first_value(workspace, workspace_key, workspace_id, default=SCOPE_WILDCARD)
        resolved_sensitivity = _first_value(sensitivity, sensitivity_level, default="internal")
        resolved_application = _first_value(application, application_id, default=SCOPE_WILDCARD)
        object.__setattr__(self, "standard_id", _text(resolved_id, field_name="standard_id"))
        object.__setattr__(self, "canonical_name", _text(resolved_name, field_name="canonical_name"))
        if isinstance(aliases, str):
            alias_values = (aliases,)
        else:
            alias_values = tuple(aliases or ())
        cleaned_aliases = tuple(
            _text(alias, field_name="alias") for alias in alias_values if str(alias).strip()
        )
        object.__setattr__(self, "aliases", tuple(dict.fromkeys(cleaned_aliases)))
        object.__setattr__(self, "tenant", _scope_text(resolved_tenant, field_name="tenant", allow_wildcard=True))
        object.__setattr__(self, "project", _scope_text(resolved_project, field_name="project", allow_wildcard=True))
        object.__setattr__(self, "workspace", _scope_text(resolved_workspace, field_name="workspace", allow_wildcard=True))
        object.__setattr__(self, "sensitivity", _text(resolved_sensitivity, field_name="sensitivity"))
        object.__setattr__(self, "metadata", dict(metadata or {}))
        object.__setattr__(self, "application", _scope_text(resolved_application, field_name="application", allow_wildcard=True))

    @property
    def entity_id(self) -> str:
        """返回标准 ID 的常用兼容名称。"""

        return self.standard_id

    @property
    def name(self) -> str:
        """返回规范名称的常用兼容名称。"""

        return self.canonical_name

    @property
    def tenant_id(self) -> str:
        """返回租户 ID。"""

        return self.tenant

    @property
    def project_id(self) -> str:
        """返回项目 ID。"""

        return self.project

    @property
    def application_id(self) -> str:
        """返回产品应用 ID。"""

        return self.application

    @property
    def workspace_key(self) -> str:
        """返回工作空间键。"""

        return self.workspace

    @property
    def workspace_id(self) -> str:
        """返回工作空间 ID。"""

        return self.workspace

    @property
    def sensitivity_level(self) -> str:
        """返回敏感级别。"""

        return self.sensitivity

    @property
    def scope(self) -> GraphRagScope:
        """把实体范围包装成统一范围对象。"""

        return GraphRagScope(
            self.tenant,
            self.project,
            self.workspace,
            self.sensitivity,
            application=self.application,
        )

    def aliases_for_lookup(self) -> tuple[str, ...]:
        """返回包含规范名称和标准 ID 的去重查找词集合。"""

        values = (self.canonical_name, self.standard_id, *self.aliases)
        return tuple(dict.fromkeys(value for value in values if _normalize_alias(value)))

    def to_dict(self) -> dict[str, Any]:
        """输出不包含隐藏实体的可审计实体摘要。"""

        return {
            "standard_id": self.standard_id,
            "canonical_name": self.canonical_name,
            "aliases": self.aliases,
            "tenant": self.tenant,
            "project": self.project,
            "application_id": self.application,
            "sensitivity": self.sensitivity,
            "metadata": dict(self.metadata),
        }


GraphEntity = GraphRagEntity


@dataclass(frozen=True, slots=True, init=False)
class GraphRagEdge:
    """带时间、来源、可信度和治理范围的有向关系边。"""

    source_entity_id: str
    target_entity_id: str
    relation: str
    source_document_id: str
    source_uri: str
    source_chunk_id: str
    asserted_at: TimestampValue
    effective_at: TimestampValue
    expires_at: TimestampValue
    confidence: float
    status: str
    tenant: str
    project: str
    workspace: str
    sensitivity: str
    application: str

    def __init__(
        self,
        source_entity_id: str | None = None,
        target_entity_id: str | None = None,
        relation: str | GraphRagRelation | None = None,
        source_document_id: str = "",
        source_uri: str = "",
        source_chunk_id: str = "",
        asserted_at: TimestampValue = None,
        effective_at: TimestampValue = None,
        expires_at: TimestampValue = None,
        confidence: float = 1.0,
        status: str = "active",
        tenant: str | None = None,
        project: str | None = None,
        workspace: str | None = None,
        sensitivity: str | None = None,
        *,
        application: str | None = None,
        application_id: str | None = None,
        source_id: str | None = None,
        target_id: str | None = None,
        from_entity_id: str | None = None,
        to_entity_id: str | None = None,
        relation_type: str | GraphRagRelation | None = None,
        tenant_id: str | None = None,
        project_id: str | None = None,
        workspace_key: str | None = None,
        workspace_id: str | None = None,
        sensitivity_level: str | None = None,
    ) -> None:
        resolved_source = _first_value(source_entity_id, source_id, from_entity_id, default="")
        resolved_target = _first_value(target_entity_id, target_id, to_entity_id, default="")
        resolved_relation = _first_value(relation, relation_type, default="")
        resolved_tenant = _first_value(tenant, tenant_id, default=SCOPE_WILDCARD)
        resolved_project = _first_value(project, project_id, default=SCOPE_WILDCARD)
        resolved_workspace = _first_value(workspace, workspace_key, workspace_id, default=SCOPE_WILDCARD)
        resolved_sensitivity = _first_value(sensitivity, sensitivity_level, default="internal")
        resolved_application = _first_value(application, application_id, default=SCOPE_WILDCARD)
        object.__setattr__(self, "source_entity_id", _text(resolved_source, field_name="source_entity_id"))
        object.__setattr__(self, "target_entity_id", _text(resolved_target, field_name="target_entity_id"))
        object.__setattr__(self, "relation", _normalize_relation(resolved_relation))
        object.__setattr__(self, "source_document_id", _text(source_document_id, field_name="source_document_id", allow_empty=True))
        object.__setattr__(self, "source_uri", _text(source_uri, field_name="source_uri", allow_empty=True))
        object.__setattr__(self, "source_chunk_id", _text(source_chunk_id, field_name="source_chunk_id", allow_empty=True))
        _timestamp(asserted_at, field_name="asserted_at")
        _timestamp(effective_at, field_name="effective_at")
        _timestamp(expires_at, field_name="expires_at")
        object.__setattr__(self, "asserted_at", asserted_at)
        object.__setattr__(self, "effective_at", effective_at)
        object.__setattr__(self, "expires_at", expires_at)
        try:
            resolved_confidence = float(confidence)
        except (TypeError, ValueError) as exc:
            raise ValueError("confidence 必须是 0 到 1 之间的数字") from exc
        if not math.isfinite(resolved_confidence) or not 0.0 <= resolved_confidence <= 1.0:
            raise ValueError("confidence 必须是 0 到 1 之间的有限数字")
        object.__setattr__(self, "confidence", resolved_confidence)
        object.__setattr__(self, "status", _text(status, field_name="status"))
        object.__setattr__(self, "tenant", _scope_text(resolved_tenant, field_name="tenant", allow_wildcard=True))
        object.__setattr__(self, "project", _scope_text(resolved_project, field_name="project", allow_wildcard=True))
        object.__setattr__(self, "workspace", _scope_text(resolved_workspace, field_name="workspace", allow_wildcard=True))
        object.__setattr__(self, "sensitivity", _text(resolved_sensitivity, field_name="sensitivity"))
        object.__setattr__(self, "application", _scope_text(resolved_application, field_name="application", allow_wildcard=True))

    @property
    def source_id(self) -> str:
        """返回源实体 ID 的简短兼容名称。"""

        return self.source_entity_id

    @property
    def target_id(self) -> str:
        """返回目标实体 ID 的简短兼容名称。"""

        return self.target_entity_id

    @property
    def application_id(self) -> str:
        """返回产品应用 ID。"""

        return self.application

    @property
    def from_entity_id(self) -> str:
        """返回有向边起点 ID。"""

        return self.source_entity_id

    @property
    def to_entity_id(self) -> str:
        """返回有向边终点 ID。"""

        return self.target_entity_id

    @property
    def relation_type(self) -> str:
        """返回标准关系类型。"""

        return self.relation

    @property
    def workspace_id(self) -> str:
        """返回工作空间 ID。"""

        return self.workspace

    @property
    def scope(self) -> GraphRagScope:
        """把边范围包装成统一范围对象。"""

        return GraphRagScope(
            self.tenant,
            self.project,
            self.workspace,
            self.sensitivity,
            application=self.application,
        )

    def is_current(self, as_of: TimestampValue = None) -> bool:
        """判断边在指定时间是否是可用于回答的当前有效边。"""

        current_time = _timestamp(as_of, field_name="as_of") or _now_utc()
        status = self.status.casefold()
        if status not in {"active", "current", "valid", "asserted", "enabled"}:
            return False
        effective = _timestamp(self.effective_at, field_name="effective_at")
        expires = _timestamp(self.expires_at, field_name="expires_at")
        if effective is not None and current_time < effective:
            return False
        if expires is not None and current_time >= expires:
            return False
        return True

    def has_complete_provenance(self) -> bool:
        """判断边是否具备回答所需的最小来源字段。"""

        return bool(self.source_document_id and self.source_uri and self.source_chunk_id)

    def to_dict(self) -> dict[str, Any]:
        """输出边的完整可审计字段。"""

        return {
            "source_entity_id": self.source_entity_id,
            "target_entity_id": self.target_entity_id,
            "relation": self.relation,
            "source_document_id": self.source_document_id,
            "source_uri": self.source_uri,
            "source_chunk_id": self.source_chunk_id,
            "asserted_at": _timestamp_text(self.asserted_at),
            "effective_at": _timestamp_text(self.effective_at),
            "expires_at": _timestamp_text(self.expires_at),
            "confidence": self.confidence,
            "status": self.status,
            "tenant": self.tenant,
            "project": self.project,
            "application_id": self.application,
            "sensitivity": self.sensitivity,
        }


GraphEdge = GraphRagEdge


@dataclass(frozen=True, slots=True)
class GraphRagParsedQuestion:
    """自然语言解析后的有限图查询。"""

    subject: str
    relation: str
    hops: int


_RELATION_WORD_PATTERN = r"(?:上级|上司|主管|领导)"
_RELATION_CHAIN_PATTERN = rf"(?P<chain>(?:(?:的)?{_RELATION_WORD_PATTERN})+)"


def parse_graph_rag_question(question: str) -> GraphRagParsedQuestion | None:
    """解析“某人的上级的上级是谁”这一类最小中文问句。

    无法证明问题属于当前支持的关系查询时返回 ``None``，调用方应把它视为
    ``not_applicable``，而不是猜测用户意图。
    """

    if not isinstance(question, str):
        return None
    text = unicodedata.normalize("NFKC", question)
    text = re.sub(r"\s+", "", text).strip()
    text = re.sub(r"^(?:请问|请告诉我|告诉我|想知道)", "", text)
    text = text.rstrip("。！？?!")
    # 联合检索的真实问法经常在关系问题后追加“依据/证据/来源”要求，例如“某人的上级是谁，
    # 以及关系依据是什么”。关系图只负责解析前半段，文档 Hybrid RAG 负责后半段；这里仅剥离
    # 一组明确的证据尾句，不吞掉未知业务条件，也不把普通复合问题误判成图查询。
    text = re.sub(
        r"[，,；;](?:以及|并且|同时)?(?:这个|该)?关系?(?:的)?(?:依据|证据|来源|原文|说明|文档).*$",
        "",
        text,
    )
    if not text:
        return None

    patterns = (
        rf"^(?P<subject>.+?){_RELATION_CHAIN_PATTERN}(?:是?(?:谁|哪位))?$",
        rf"^(?:谁|哪位)是(?P<subject>.+?){_RELATION_CHAIN_PATTERN}$",
    )
    for pattern in patterns:
        match = re.fullmatch(pattern, text)
        if match is None:
            continue
        subject = match.group("subject").strip("的")
        chain = match.group("chain")
        hops = len(re.findall(_RELATION_WORD_PATTERN, chain))
        if subject and hops > 0:
            return GraphRagParsedQuestion(
                subject=subject,
                relation=GraphRagRelation.REPORTS_TO.value,
                hops=hops,
            )
    return None


@dataclass(frozen=True, slots=True, init=False)
class GraphRagQuery:
    """供 GraphRAG Provider 和未来 ``RagPipeline`` 注入的查询请求。

    默认要求调用方提供具体的租户、应用、项目和敏感级别。旧版
    ``workspace_key/workspace_id`` 参数只用于读取历史请求，不再作为授权边界。
    """

    question: str
    tenant: str
    project: str
    workspace: str
    sensitivity: str
    application: str
    as_of: TimestampValue
    max_hops: int
    start_entity: str | None
    relation: str | None
    hops: int | None
    scope: GraphRagScope | None

    def __init__(
        self,
        question: str = "",
        tenant: str | None = None,
        project: str | None = None,
        workspace: str | None = None,
        sensitivity: str | None = None,
        as_of: TimestampValue = None,
        max_hops: int = MAX_GRAPH_RAG_HOPS,
        start_entity: str | None = None,
        relation: str | GraphRagRelation | None = None,
        hops: int | None = None,
        *,
        scope: GraphRagScope | None = None,
        application: str | None = None,
        application_id: str | None = None,
        start: str | None = None,
        start_alias: str | None = None,
        relation_type: str | GraphRagRelation | None = None,
        hop_count: int | None = None,
        now: TimestampValue = None,
        tenant_id: str | None = None,
        project_id: str | None = None,
        workspace_key: str | None = None,
        workspace_id: str | None = None,
        sensitivity_level: str | None = None,
    ) -> None:
        if scope is not None and not isinstance(scope, GraphRagScope):
            raise TypeError("scope 必须是 GraphRagScope")
        scope_values = scope or GraphRagScope(
            _first_value(tenant, tenant_id, default=""),
            _first_value(project, project_id, default=""),
            _first_value(workspace, workspace_key, workspace_id, default=""),
            _first_value(sensitivity, sensitivity_level, default="internal"),
            application=_first_value(application, application_id, default=SCOPE_WILDCARD),
        )
        resolved_start = _first_value(start_entity, start, start_alias, default=None)
        resolved_relation = _first_value(relation, relation_type, default=None)
        resolved_hops = _first_value(hops, hop_count, default=None)
        resolved_as_of = _first_value(as_of, now, default=None)
        object.__setattr__(self, "question", str(question or "").strip())
        object.__setattr__(self, "tenant", scope_values.tenant)
        object.__setattr__(self, "project", scope_values.project)
        object.__setattr__(self, "workspace", scope_values.workspace)
        object.__setattr__(self, "sensitivity", scope_values.sensitivity)
        object.__setattr__(self, "application", scope_values.application)
        object.__setattr__(self, "as_of", resolved_as_of)
        try:
            normalized_max_hops = int(max_hops)
        except (TypeError, ValueError):
            normalized_max_hops = -1
        object.__setattr__(self, "max_hops", normalized_max_hops)
        object.__setattr__(self, "start_entity", resolved_start)
        object.__setattr__(self, "relation", resolved_relation)
        object.__setattr__(self, "hops", resolved_hops)
        object.__setattr__(self, "scope", scope_values)

    @property
    def tenant_id(self) -> str:
        """返回租户 ID。"""

        return self.tenant

    @property
    def project_id(self) -> str:
        """返回项目 ID。"""

        return self.project

    @property
    def application_id(self) -> str:
        """返回产品应用 ID。"""

        return self.application

    @property
    def workspace_key(self) -> str:
        """返回工作空间键。"""

        return self.workspace

    @property
    def workspace_id(self) -> str:
        """返回工作空间 ID。"""

        return self.workspace

    @property
    def sensitivity_level(self) -> str:
        """返回敏感级别。"""

        return self.sensitivity


GraphQuery = GraphRagQuery


@dataclass(frozen=True, slots=True)
class GraphRagPathStep:
    """完整路径中的一跳，平铺实体和边的审计信息。"""

    hop: int
    source_entity: GraphRagEntity
    target_entity: GraphRagEntity
    edge: GraphRagEdge
    supporting_edges: tuple[GraphRagEdge, ...] = ()

    @property
    def source_entity_id(self) -> str:
        """返回本跳起点标准 ID。"""

        return self.source_entity.standard_id

    @property
    def target_entity_id(self) -> str:
        """返回本跳终点标准 ID。"""

        return self.target_entity.standard_id

    @property
    def source_entity_name(self) -> str:
        """返回本跳起点规范名称。"""

        return self.source_entity.canonical_name

    @property
    def target_entity_name(self) -> str:
        """返回本跳终点规范名称。"""

        return self.target_entity.canonical_name

    def __getattr__(self, name: str) -> Any:
        """把边的来源和时间字段直接暴露在路径步骤上，便于审计调用。"""

        if name in {
            "relation",
            "source_document_id",
            "source_uri",
            "source_chunk_id",
            "asserted_at",
            "effective_at",
            "expires_at",
            "confidence",
            "status",
            "tenant",
            "project",
            "workspace",
            "sensitivity",
        }:
            return getattr(self.edge, name)
        raise AttributeError(name)

    def to_dict(self) -> dict[str, Any]:
        """输出包含每一跳来源、时间和可信度的完整路径记录。"""

        edge_data = self.edge.to_dict()
        data: dict[str, Any] = {
            "hop": self.hop,
            "source_entity_id": self.source_entity.standard_id,
            "source_entity_name": self.source_entity.canonical_name,
            "target_entity_id": self.target_entity.standard_id,
            "target_entity_name": self.target_entity.canonical_name,
            **edge_data,
        }
        data["source"] = {
            "document_id": self.edge.source_document_id,
            "uri": self.edge.source_uri,
            "chunk_id": self.edge.source_chunk_id,
        }
        data["time"] = {
            "asserted_at": edge_data["asserted_at"],
            "effective_at": edge_data["effective_at"],
            "expires_at": edge_data["expires_at"],
        }
        return data


@dataclass(frozen=True, slots=True)
class GraphRagResult:
    """GraphRAG 查询结果，状态只可能是三种受控终态之一。"""

    status: str
    answer: str | None = None
    path: tuple[GraphRagPathStep, ...] = ()
    reason_code: str | None = None
    message: str | None = None
    entity_id: str | None = None
    requested_hops: int | None = None
    relation: str | None = None
    conflicting_target_ids: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if self.status not in {item.value for item in GraphRagResultStatus}:
            raise ValueError("status 必须是 not_applicable、success 或 refusal")
        object.__setattr__(self, "path", tuple(self.path or ()))
        object.__setattr__(self, "conflicting_target_ids", tuple(self.conflicting_target_ids or ()))

    @property
    def result_status(self) -> str:
        """返回结果状态的兼容名称。"""

        return self.status

    @property
    def refusal_reason(self) -> str | None:
        """返回拒答原因的兼容名称。"""

        return self.reason_code

    @property
    def hop_count(self) -> int:
        """返回结果中已经形成的路径长度。"""

        return len(self.path)

    @property
    def edges(self) -> tuple[GraphRagEdge, ...]:
        """返回路径中的边，方便调用方只消费图证据。"""

        return tuple(step.edge for step in self.path)

    @property
    def evidence_path(self) -> tuple[GraphRagPathStep, ...]:
        """返回完整证据路径的兼容名称。"""

        return self.path

    def to_dict(self) -> dict[str, Any]:
        """输出可供 API 或后续 RagPipeline 适配器消费的结果信封。"""

        return {
            "status": self.status,
            "answer": self.answer,
            "entity_id": self.entity_id,
            "reason_code": self.reason_code,
            "message": self.message,
            "requested_hops": self.requested_hops,
            "hop_count": self.hop_count,
            "relation": self.relation,
            "conflicting_target_ids": self.conflicting_target_ids,
            "path": tuple(step.to_dict() for step in self.path),
        }


GraphRagResponse = GraphRagResult


class GraphRagRetriever(Protocol):
    """供 RagPipeline 注入的最小 GraphRAG 检索接口。"""

    def retrieve(self, query: GraphRagQuery) -> GraphRagResult:
        """按受治理查询返回 GraphRAG 结果。"""


class GraphRagProvider(Protocol):
    """以 query 命名的等价注入接口，便于不同 Pipeline 风格适配。"""

    def query(self, query: GraphRagQuery) -> GraphRagResult:
        """执行一次受治理图查询。"""


def _coerce_entity(value: GraphRagEntity | Mapping[str, Any]) -> GraphRagEntity:
    """把实体对象或结构化记录转换成核心实体。"""

    if isinstance(value, GraphRagEntity):
        return value
    if isinstance(value, Mapping):
        return GraphRagEntity(**dict(value))
    raise TypeError("实体必须是 GraphRagEntity 或映射对象")


def _coerce_edge(value: GraphRagEdge | Mapping[str, Any]) -> GraphRagEdge:
    """把边对象或结构化记录转换成核心边。"""

    if isinstance(value, GraphRagEdge):
        return value
    if isinstance(value, Mapping):
        return GraphRagEdge(**dict(value))
    raise TypeError("关系边必须是 GraphRagEdge 或映射对象")


class InMemoryGraphRag:
    """可测试、无外部依赖且默认拒绝越权的内存 GraphRAG 实现。

    写入接口只保存实体和关系边；查询接口负责范围快照、当前有效性筛选、别名解析、
    冲突检测和有限跳遍历。查询过程中会复制一份不可变快照，因此并发写入不会让同一次
    回答看到半更新的图。
    """

    def __init__(
        self,
        entities: Iterable[GraphRagEntity | Mapping[str, Any]] = (),
        edges: Iterable[GraphRagEdge | Mapping[str, Any]] = (),
        *,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        self._entities: dict[str, GraphRagEntity] = {}
        self._edges: list[GraphRagEdge] = []
        self._lock = threading.RLock()
        self._clock = clock or _now_utc
        self.add_entities(entities)
        self.add_edges(edges)

    def add_entity(self, entity: GraphRagEntity | Mapping[str, Any]) -> "InMemoryGraphRag":
        """新增或幂等更新一个标准 ID 对应的实体。"""

        resolved = _coerce_entity(entity)
        with self._lock:
            existing = self._entities.get(resolved.standard_id)
            if existing is not None and existing != resolved:
                raise ValueError(f"标准 ID 已存在且内容不同: {resolved.standard_id}")
            self._entities[resolved.standard_id] = resolved
        return self

    def add_entities(self, entities: Iterable[GraphRagEntity | Mapping[str, Any]]) -> "InMemoryGraphRag":
        """批量新增实体，并沿用单实体的标准 ID 校验。"""

        for entity in entities:
            self.add_entity(entity)
        return self

    def add_edge(self, edge: GraphRagEdge | Mapping[str, Any]) -> "InMemoryGraphRag":
        """新增一条有向关系边；边的端点可以在之后补录。"""

        resolved = _coerce_edge(edge)
        with self._lock:
            self._edges.append(resolved)
        return self

    def add_edges(self, edges: Iterable[GraphRagEdge | Mapping[str, Any]]) -> "InMemoryGraphRag":
        """批量新增关系边。"""

        for edge in edges:
            self.add_edge(edge)
        return self

    def clear(self) -> None:
        """清空内存图，主要供测试生命周期使用。"""

        with self._lock:
            self._entities.clear()
            self._edges.clear()

    def entity_count(self) -> int:
        """返回当前实体数量。"""

        with self._lock:
            return len(self._entities)

    def edge_count(self) -> int:
        """返回当前边数量。"""

        with self._lock:
            return len(self._edges)

    def query(self, query: GraphRagQuery) -> GraphRagResult:
        """执行一次范围优先、时间受控且最多三跳的图查询。"""

        if not isinstance(query, GraphRagQuery):
            raise TypeError("query 必须是 GraphRagQuery")

        scope = query.scope
        if not scope.is_concrete_query_scope():
            return self._result(
                GraphRagResultStatus.REFUSAL,
                reason=GraphRagReasonCode.SCOPE_REQUIRED,
                message="缺少具体的租户、应用、项目或敏感级别范围。",
            )

        try:
            as_of = _timestamp(query.as_of, field_name="as_of") or self._clock()
        except (TypeError, ValueError):
            return self._result(
                GraphRagResultStatus.REFUSAL,
                reason=GraphRagReasonCode.INVALID_QUERY,
                message="查询时间不是有效的日期或 ISO 时间。",
            )
        if as_of.tzinfo is None:
            as_of = as_of.replace(tzinfo=timezone.utc)
        as_of = as_of.astimezone(timezone.utc)

        # 先做范围和时间快照，再解析用户提供的别名，避免未授权名称进入解析索引。
        visible_entities, visible_edges = self._visible_snapshot(scope, as_of)

        parsed, parse_reason = self._parse_query(query)
        if parsed is None:
            return self._result(
                GraphRagResultStatus.NOT_APPLICABLE,
                reason=parse_reason or GraphRagReasonCode.UNSUPPORTED_QUERY,
                message="当前 GraphRAG 只处理 REPORTS_TO 的中文上级关系问句。",
            )

        if parsed.relation != GraphRagRelation.REPORTS_TO.value:
            return self._result(
                GraphRagResultStatus.NOT_APPLICABLE,
                reason=GraphRagReasonCode.RELATION_NOT_SUPPORTED,
                message="当前未支持该关系类型。",
                requested_hops=parsed.hops,
                relation=parsed.relation,
            )

        if parsed.hops < 1 or query.max_hops < 1:
            return self._result(
                GraphRagResultStatus.REFUSAL,
                reason=GraphRagReasonCode.INVALID_QUERY,
                message="关系查询至少需要一跳。",
                requested_hops=parsed.hops,
                relation=parsed.relation,
            )
        if parsed.hops > MAX_GRAPH_RAG_HOPS or query.max_hops > MAX_GRAPH_RAG_HOPS:
            return self._result(
                GraphRagResultStatus.REFUSAL,
                reason=GraphRagReasonCode.MAX_HOPS_EXCEEDED,
                message=f"查询最多允许 {MAX_GRAPH_RAG_HOPS} 跳。",
                requested_hops=parsed.hops,
                relation=parsed.relation,
            )
        if parsed.hops > query.max_hops:
            return self._result(
                GraphRagResultStatus.REFUSAL,
                reason=GraphRagReasonCode.MAX_HOPS_EXCEEDED,
                message="查询超过调用方设置的跳数上限。",
                requested_hops=parsed.hops,
                relation=parsed.relation,
            )

        start_entity, alias_reason = self._resolve_start_entity(
            parsed.subject,
            visible_entities,
            explicit_start=query.start_entity,
        )
        if start_entity is None:
            return self._result(
                GraphRagResultStatus.REFUSAL if alias_reason == GraphRagReasonCode.AMBIGUOUS_ALIAS else GraphRagResultStatus.NOT_APPLICABLE,
                reason=alias_reason or GraphRagReasonCode.ALIAS_NOT_FOUND,
                message=(
                    "名称对应多个可见实体，无法安全判断起点。"
                    if alias_reason == GraphRagReasonCode.AMBIGUOUS_ALIAS
                    else "当前范围内没有找到该实体。"
                ),
                requested_hops=parsed.hops,
                relation=parsed.relation,
            )

        entity_by_id = {entity.standard_id: entity for entity in visible_entities}
        adjacency = self._build_adjacency(visible_edges)
        current = start_entity
        path: list[GraphRagPathStep] = []

        for hop in range(1, parsed.hops + 1):
            candidates = tuple(adjacency.get((current.standard_id, parsed.relation), ()))
            if not candidates:
                return self._result(
                    GraphRagResultStatus.NOT_APPLICABLE,
                    reason=GraphRagReasonCode.NO_CURRENT_PATH,
                    message="当前有效证据不足以形成完整路径。",
                    path=tuple(path),
                    requested_hops=parsed.hops,
                    relation=parsed.relation,
                )

            by_target: dict[str, list[GraphRagEdge]] = defaultdict(list)
            for edge in candidates:
                by_target[edge.target_entity_id].append(edge)
            target_ids = tuple(sorted(by_target))
            if len(target_ids) > 1:
                return self._result(
                    GraphRagResultStatus.REFUSAL,
                    reason=GraphRagReasonCode.CONFLICTING_CURRENT_EDGES,
                    message="同一跳存在多个相互矛盾的当前有效关系，无法安全回答。",
                    path=tuple(path),
                    requested_hops=parsed.hops,
                    relation=parsed.relation,
                    conflicting_target_ids=target_ids,
                )

            target_id = target_ids[0]
            target = entity_by_id.get(target_id)
            if target is None:
                return self._result(
                    GraphRagResultStatus.REFUSAL,
                    reason=GraphRagReasonCode.TARGET_ENTITY_NOT_VISIBLE,
                    message="关系目标不在当前治理范围内，无法安全回答。",
                    path=tuple(path),
                    requested_hops=parsed.hops,
                    relation=parsed.relation,
                )

            supporting_edges = tuple(
                sorted(
                    by_target[target_id],
                    key=self._edge_sort_key,
                    reverse=True,
                )
            )
            selected_edge = supporting_edges[0]
            if not selected_edge.has_complete_provenance():
                return self._result(
                    GraphRagResultStatus.REFUSAL,
                    reason=GraphRagReasonCode.INCOMPLETE_PROVENANCE,
                    message="关系边缺少完整来源信息，拒绝生成不可审计的答案。",
                    path=tuple(path),
                    requested_hops=parsed.hops,
                    relation=parsed.relation,
                )
            path.append(
                GraphRagPathStep(
                    hop=hop,
                    source_entity=current,
                    target_entity=target,
                    edge=selected_edge,
                    supporting_edges=supporting_edges,
                )
            )
            current = target

        return self._result(
            GraphRagResultStatus.SUCCESS,
            answer=current.canonical_name,
            entity_id=current.standard_id,
            path=tuple(path),
            reason=GraphRagReasonCode.ANSWERED,
            message="已根据当前有效关系和完整来源路径回答。",
            requested_hops=parsed.hops,
            relation=parsed.relation,
        )

    def retrieve(self, query: GraphRagQuery) -> GraphRagResult:
        """以 Retriever 命名执行查询，供 RagPipeline 直接注入。"""

        return self.query(query)

    def answer(self, query: GraphRagQuery) -> GraphRagResult:
        """以问答命名执行查询，保持结果仍然是结构化治理信封。"""

        return self.query(query)

    def _visible_snapshot(
        self,
        query_scope: GraphRagScope,
        as_of: datetime,
    ) -> tuple[tuple[GraphRagEntity, ...], tuple[GraphRagEdge, ...]]:
        """在别名解析前筛出当前调用方可见的实体和边。"""

        with self._lock:
            entities = tuple(self._entities.values())
            edges = tuple(self._edges)
        visible_entities = tuple(
            entity for entity in entities if query_scope.permits(entity.scope)
        )
        visible_ids = {entity.standard_id for entity in visible_entities}
        visible_edges = tuple(
            edge
            for edge in edges
            if query_scope.permits(edge.scope)
            and edge.is_current(as_of)
            and edge.source_entity_id in visible_ids
            and edge.target_entity_id in visible_ids
        )
        return visible_entities, visible_edges

    @staticmethod
    def _build_adjacency(
        edges: Sequence[GraphRagEdge],
    ) -> dict[tuple[str, str], tuple[GraphRagEdge, ...]]:
        """按起点和关系建立确定性的有向邻接索引。"""

        grouped: dict[tuple[str, str], list[GraphRagEdge]] = defaultdict(list)
        for edge in edges:
            grouped[(edge.source_entity_id, edge.relation)].append(edge)
        return {
            key: tuple(sorted(values, key=InMemoryGraphRag._edge_sort_key, reverse=True))
            for key, values in grouped.items()
        }

    @staticmethod
    def _edge_sort_key(edge: GraphRagEdge) -> tuple[float, datetime, datetime, str, str, str]:
        """为同一目标的多条支持证据提供稳定选择顺序。"""

        effective = _timestamp(edge.effective_at, field_name="effective_at") or datetime.min.replace(tzinfo=timezone.utc)
        asserted = _timestamp(edge.asserted_at, field_name="asserted_at") or datetime.min.replace(tzinfo=timezone.utc)
        return (
            edge.confidence,
            effective,
            asserted,
            edge.source_document_id,
            edge.source_chunk_id,
            edge.source_uri,
        )

    @staticmethod
    def _parse_query(
        query: GraphRagQuery,
    ) -> tuple[GraphRagParsedQuestion | None, GraphRagReasonCode | None]:
        """优先使用结构化查询字段，否则解析最小中文自然问句。"""

        if query.start_entity is not None or query.relation is not None or query.hops is not None:
            if not query.start_entity or not query.relation or query.hops is None:
                return None, GraphRagReasonCode.INVALID_QUERY
            relation = _normalize_relation(query.relation)
            try:
                resolved_hops = int(query.hops)
            except (TypeError, ValueError):
                return None, GraphRagReasonCode.INVALID_QUERY
            return (
                GraphRagParsedQuestion(
                    subject=str(query.start_entity),
                    relation=relation,
                    hops=resolved_hops,
                ),
                None,
            )
        parsed = parse_graph_rag_question(query.question)
        return (parsed, None if parsed is not None else GraphRagReasonCode.UNSUPPORTED_QUERY)

    @staticmethod
    def _resolve_start_entity(
        subject: str,
        visible_entities: Sequence[GraphRagEntity],
        *,
        explicit_start: str | None,
    ) -> tuple[GraphRagEntity | None, GraphRagReasonCode | None]:
        """只在可见实体集合中解析标准 ID、规范名和别名。"""

        lookup_subject = explicit_start if explicit_start is not None else subject
        normalized_subject = _normalize_alias(lookup_subject)
        if not normalized_subject:
            return None, GraphRagReasonCode.ALIAS_NOT_FOUND
        candidates: dict[str, GraphRagEntity] = {}
        for entity in visible_entities:
            if any(_normalize_alias(alias) == normalized_subject for alias in entity.aliases_for_lookup()):
                candidates[entity.standard_id] = entity
        if not candidates:
            return None, GraphRagReasonCode.ALIAS_NOT_FOUND
        if len(candidates) > 1:
            return None, GraphRagReasonCode.AMBIGUOUS_ALIAS
        return next(iter(candidates.values())), None

    @staticmethod
    def _result(
        status: GraphRagResultStatus,
        *,
        reason: GraphRagReasonCode | str | None = None,
        **kwargs: Any,
    ) -> GraphRagResult:
        """集中构造结果，确保状态和原因码格式一致。"""

        reason_value = reason.value if isinstance(reason, Enum) else reason
        return GraphRagResult(status=status.value, reason_code=reason_value, **kwargs)


GraphRagEngine = InMemoryGraphRag
InMemoryGraphRagCore = InMemoryGraphRag


def build_in_memory_graph_rag(
    entities: Iterable[GraphRagEntity | Mapping[str, Any]] = (),
    edges: Iterable[GraphRagEdge | Mapping[str, Any]] = (),
    *,
    clock: Callable[[], datetime] | None = None,
) -> InMemoryGraphRag:
    """构造内存 GraphRAG，便于测试装配和未来 Provider 工厂注入。"""

    return InMemoryGraphRag(entities, edges, clock=clock)


__all__ = [
    "MAX_GRAPH_RAG_HOPS",
    "SCOPE_WILDCARD",
    "GraphEdge",
    "GraphEntity",
    "GraphQuery",
    "GraphRagEdge",
    "GraphRagEngine",
    "GraphRagEntity",
    "GraphRagParsedQuestion",
    "GraphRagPathStep",
    "GraphRagProvider",
    "GraphRagQuery",
    "GraphRagReasonCode",
    "GraphRagRelation",
    "GraphRagResponse",
    "GraphRagResult",
    "GraphRagResultStatus",
    "GraphRagRetriever",
    "GraphRagScope",
    "GraphRagStatus",
    "GraphScope",
    "InMemoryGraphRag",
    "InMemoryGraphRagCore",
    "build_in_memory_graph_rag",
    "parse_graph_rag_question",
]

"""用户画像记忆的低敏领域模型与内存仓储。

用户画像是 Codex/Claude Code 类 Agent 体验里非常关键的一层：系统需要逐渐理解“这个用户更偏好
什么表达方式、常用哪些数据源、对自动执行的风险容忍度、习惯什么 ETL/同步模式”。但是在企业数据治理
产品里，画像不能等同于“把用户说过的话永久存下来”。本模块的设计原则是：

- 只保存受控枚举、低敏摘要和证据类型，不保存原始 prompt、SQL、样本数据、凭证或模型输出；
- 画像事实先进入候选或低风险自动激活状态，未来可以接入审批、撤销、过期和冲突处理；
- 仓储协议与内存实现分离，后续可替换为 MySQL/Redis/LangGraph store，而不影响编排器；
- 每条事实都带 tenant/project/actor 边界，避免一个用户或租户的偏好影响另一个租户。
"""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from datetime import datetime, timedelta, timezone
from enum import Enum
from threading import RLock
from typing import Any, Protocol
from uuid import uuid4


class UserProfileFacetType(str, Enum):
    """用户画像事实类型。

    这里的类型刻意围绕“Agent 如何更好地辅助 ETL/数据同步”建模，而不是泛化成社交画像：
    - `LANGUAGE_PREFERENCE`：用户希望中文、英文或双语输出；
    - `CONNECTOR_PREFERENCE`：用户常提到的连接器生态，例如 MySQL、PostgreSQL、Kafka、文件；
    - `SYNC_MODE_PREFERENCE`：用户偏好的数据同步模式，例如全量、增量、CDC、批处理；
    - `ETL_STYLE_PREFERENCE`：用户偏好的 ETL 交付方式，例如 SQL 优先、低代码优先、Spark 作业；
    - `RISK_TOLERANCE`：对自动执行、审批、人机确认的偏好；
    - `OUTPUT_FORMAT_PREFERENCE`：希望输出成表格、步骤清单、架构说明或直接代码；
    - `AGENT_AUTONOMY_PREFERENCE`：希望 Agent 主动推进，还是每一步都询问确认；
    - `PERFORMANCE_FOCUS`：更关注吞吐、低延迟、成本、可靠性或可观测性。
    """

    LANGUAGE_PREFERENCE = "language_preference"
    CONNECTOR_PREFERENCE = "connector_preference"
    SYNC_MODE_PREFERENCE = "sync_mode_preference"
    ETL_STYLE_PREFERENCE = "etl_style_preference"
    RISK_TOLERANCE = "risk_tolerance"
    OUTPUT_FORMAT_PREFERENCE = "output_format_preference"
    AGENT_AUTONOMY_PREFERENCE = "agent_autonomy_preference"
    PERFORMANCE_FOCUS = "performance_focus"


class UserProfileFacetStatus(str, Enum):
    """画像事实生命周期状态。

    - `CANDIDATE`：从请求中识别到的候选事实，尚未被用户或策略确认；
    - `ACTIVE`：当前上下文注入可以使用的事实；
    - `REJECTED`：用户、管理员或策略明确拒绝，不能再进入模型上下文；
    - `SUPERSEDED`：被更新版本替代，保留用于审计和学习，不再作为当前画像；
    - `EXPIRED`：超过保留期或租户策略要求，不能继续使用。
    """

    CANDIDATE = "candidate"
    ACTIVE = "active"
    REJECTED = "rejected"
    SUPERSEDED = "superseded"
    EXPIRED = "expired"


@dataclass(frozen=True)
class UserProfileScope:
    """用户画像隔离范围。

    字段说明：
    - `tenant_id/project_id/actor_id`：画像事实的最小治理边界。actor 代表真实用户或服务账号；
    - `workspace_key`：可选工作区边界。某些偏好只适用于某个数据同步项目或诊断工作区；
    - `profile_namespace`：后续映射到 Redis key、SQL 分区、LangGraph store namespace 或向量库 metadata。
    """

    tenant_id: str
    project_id: str
    actor_id: str
    workspace_key: str | None = None
    profile_namespace: str | None = None

    def storage_key(self) -> tuple[str, str, str, str]:
        """生成仓储分组键。

        workspace 为空时使用 `default`，这是为了避免 None 在不同存储实现中被序列化成不一致的值。
        profile_namespace 不参与主键，因为它是下游存储命名空间，不应改变画像归属。
        """

        return (self.tenant_id, self.project_id, self.actor_id, self.workspace_key or "default")


@dataclass(frozen=True)
class UserProfileFacet:
    """一条用户画像事实。

    字段说明：
    - `facet_id`：稳定事实 ID，供批准、拒绝、替换、审计和 API 查询使用；
    - `facet_type/key/value`：事实主体。key 通常是更细的业务维度，例如 `database`、`sync_mode`；
    - `confidence`：抽取置信度，低置信度事实默认保留为 candidate，不自动影响模型；
    - `status`：生命周期状态；
    - `evidence_code`：证据来源编码，例如 `MENTION_MYSQL`、`ASK_FAST_CLOSURE`。不保存原始语句；
    - `source`：事实来自 request、manual、admin、import 等渠道；
    - `expires_at`：过期时间，避免一次临时偏好永久影响后续会话；
    - `attributes`：只允许低敏扩展字段，例如 `autoActivated=true`，不能写 prompt 或参数正文。
    """

    facet_id: str
    scope: UserProfileScope
    facet_type: UserProfileFacetType
    key: str
    value: str
    confidence: float
    status: UserProfileFacetStatus
    evidence_code: str
    source: str = "request_observation"
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    updated_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    expires_at: datetime | None = None
    attributes: dict[str, Any] = field(default_factory=dict)

    def natural_key(self) -> tuple[tuple[str, str, str, str], str, str]:
        """生成事实去重键。

        同一个用户在同一工作区内，某类画像的某个 key 只能有一个当前版本。例如数据库偏好 `database`
        如果从 MySQL 切换到 PostgreSQL，应把旧事实标记为 `SUPERSEDED`，而不是保留两个互相冲突的 active。
        """

        return (self.scope.storage_key(), self.facet_type.value, self.key)

    def is_active_at(self, now: datetime | None = None) -> bool:
        """判断事实是否可进入当前上下文。

        active 还必须未过期。这样后续即便清理 worker 尚未运行，读取侧也不会继续使用过期画像。
        """

        current = now or datetime.now(timezone.utc)
        return self.status == UserProfileFacetStatus.ACTIVE and (
            self.expires_at is None or self.expires_at > current
        )

    def to_summary(self) -> dict[str, Any]:
        """转换为低敏 API 摘要。

        注意：这里不返回任何原始用户文本，只返回已归一化的画像值和证据编码。
        """

        return {
            "facetId": self.facet_id,
            "facetType": self.facet_type.value,
            "key": self.key,
            "value": self.value,
            "confidence": self.confidence,
            "status": self.status.value,
            "evidenceCode": self.evidence_code,
            "source": self.source,
            "workspaceKey": self.scope.workspace_key,
            "profileNamespace": self.scope.profile_namespace,
            "createdAt": self.created_at.isoformat(),
            "updatedAt": self.updated_at.isoformat(),
            "expiresAt": self.expires_at.isoformat() if self.expires_at else None,
            "attributes": dict(self.attributes),
        }


@dataclass(frozen=True)
class UserProfileObservationReport:
    """一次请求画像观察的结果。

    该报告既用于 `/agent/plans` 响应，也用于画像 API 预览。它把“本次识别到什么候选”和“当前生效画像
    是什么”分开，避免把尚未确认的候选误当成稳定长期记忆。
    """

    observed_facets: tuple[UserProfileFacet, ...]
    active_facets: tuple[UserProfileFacet, ...]
    candidate_count: int
    auto_activated_count: int
    skipped_reasons: tuple[str, ...] = ()

    def to_summary(self) -> dict[str, Any]:
        """返回低敏画像摘要。"""

        return {
            "observedFacetCount": len(self.observed_facets),
            "activeFacetCount": len(self.active_facets),
            "candidateCount": self.candidate_count,
            "autoActivatedCount": self.auto_activated_count,
            "observedFacets": tuple(item.to_summary() for item in self.observed_facets),
            "activeFacets": tuple(item.to_summary() for item in self.active_facets),
            "skippedReasons": self.skipped_reasons,
            "payloadPolicy": "LOW_SENSITIVE_PROFILE_FACTS_ONLY",
        }


class UserProfileStore(Protocol):
    """用户画像仓储协议。

    后续生产实现可以使用 MySQL 持久化事实、Redis 做热画像缓存、LangGraph store 保存会话级偏好。
    编排器只依赖这个协议，因此不会被某个存储技术锁死。
    """

    def upsert_observed_facet(self, facet: UserProfileFacet) -> UserProfileFacet:
        """写入或更新一条候选/激活画像事实。"""

    def activate(self, facet_id: str, *, operator_id: str, reason: str) -> UserProfileFacet:
        """把候选事实激活为可注入上下文的画像事实。"""

    def reject(self, facet_id: str, *, operator_id: str, reason: str) -> UserProfileFacet:
        """拒绝一条画像事实。"""

    def list_facets(self, scope: UserProfileScope) -> tuple[UserProfileFacet, ...]:
        """列出当前范围内的全部画像事实。"""

    def active_facets(self, scope: UserProfileScope) -> tuple[UserProfileFacet, ...]:
        """列出当前范围内可进入上下文的画像事实。"""

    def diagnostics(self) -> dict[str, Any]:
        """返回仓储低敏诊断信息。"""


class InMemoryUserProfileStore:
    """线程安全的用户画像内存仓储。

    该实现用于本地学习、单元测试和 Python Runtime 最小闭环。它不是最终生产持久化方案，但仍遵守生产
    语义：按 scope 隔离、按 natural key 替换旧版本、按状态读取 active、按事实 ID 审批/拒绝。
    """

    def __init__(self) -> None:
        self._lock = RLock()
        self._facets_by_id: dict[str, UserProfileFacet] = {}
        self._facet_id_by_natural_key: dict[tuple[tuple[str, str, str, str], str, str], str] = {}

    def upsert_observed_facet(self, facet: UserProfileFacet) -> UserProfileFacet:
        """写入观察事实，并处理同一 natural key 的版本替换。

        如果同一用户、同一工作区、同一画像类型和 key 已经存在事实：
        - 值相同：更新置信度、状态、时间和证据；
        - 值不同：旧事实标记为 `SUPERSEDED`，新事实成为当前版本。
        """

        with self._lock:
            existing_id = self._facet_id_by_natural_key.get(facet.natural_key())
            if existing_id:
                existing = self._facets_by_id[existing_id]
                if existing.value == facet.value:
                    updated = replace(
                        existing,
                        confidence=max(existing.confidence, facet.confidence),
                        status=_stronger_status(existing.status, facet.status),
                        evidence_code=facet.evidence_code,
                        updated_at=datetime.now(timezone.utc),
                        expires_at=facet.expires_at or existing.expires_at,
                        attributes={**existing.attributes, **facet.attributes},
                    )
                    self._facets_by_id[existing_id] = updated
                    return updated
                self._facets_by_id[existing_id] = replace(
                    existing,
                    status=UserProfileFacetStatus.SUPERSEDED,
                    updated_at=datetime.now(timezone.utc),
                    attributes={**existing.attributes, "supersededBy": facet.facet_id},
                )

            self._facets_by_id[facet.facet_id] = facet
            self._facet_id_by_natural_key[facet.natural_key()] = facet.facet_id
            return facet

    def activate(self, facet_id: str, *, operator_id: str, reason: str) -> UserProfileFacet:
        """激活候选事实。"""

        return self._transition(
            facet_id,
            status=UserProfileFacetStatus.ACTIVE,
            operator_id=operator_id,
            reason=reason,
        )

    def reject(self, facet_id: str, *, operator_id: str, reason: str) -> UserProfileFacet:
        """拒绝候选或已激活事实。"""

        return self._transition(
            facet_id,
            status=UserProfileFacetStatus.REJECTED,
            operator_id=operator_id,
            reason=reason,
        )

    def list_facets(self, scope: UserProfileScope) -> tuple[UserProfileFacet, ...]:
        """列出同一 scope 下的全部事实。"""

        with self._lock:
            return tuple(
                sorted(
                    (
                        facet
                        for facet in self._facets_by_id.values()
                        if facet.scope.storage_key() == scope.storage_key()
                    ),
                    key=lambda item: item.updated_at,
                    reverse=True,
                )
            )

    def active_facets(self, scope: UserProfileScope) -> tuple[UserProfileFacet, ...]:
        """列出未过期的 active 事实。"""

        now = datetime.now(timezone.utc)
        return tuple(facet for facet in self.list_facets(scope) if facet.is_active_at(now))

    def diagnostics(self) -> dict[str, Any]:
        """返回仓储低敏诊断信息。"""

        with self._lock:
            status_counts: dict[str, int] = {}
            type_counts: dict[str, int] = {}
            for facet in self._facets_by_id.values():
                status_counts[facet.status.value] = status_counts.get(facet.status.value, 0) + 1
                type_counts[facet.facet_type.value] = type_counts.get(facet.facet_type.value, 0) + 1
            return {
                "storeType": "in_memory",
                "facetCount": len(self._facets_by_id),
                "statusCounts": dict(sorted(status_counts.items())),
                "typeCounts": dict(sorted(type_counts.items())),
                "payloadPolicy": "NO_PROMPT_NO_SQL_NO_TOOL_ARGS_NO_SAMPLE_DATA",
            }

    def _transition(
        self,
        facet_id: str,
        *,
        status: UserProfileFacetStatus,
        operator_id: str,
        reason: str,
    ) -> UserProfileFacet:
        """执行状态变更，并记录低敏操作者与原因。"""

        with self._lock:
            facet = self._facets_by_id.get(facet_id)
            if facet is None:
                raise KeyError(f"用户画像事实不存在: {facet_id}")
            updated = replace(
                facet,
                status=status,
                updated_at=datetime.now(timezone.utc),
                attributes={
                    **facet.attributes,
                    "decisionOperatorId": operator_id,
                    "decisionReason": reason[:200],
                },
            )
            self._facets_by_id[facet_id] = updated
            return updated


def build_user_profile_facet(
    *,
    scope: UserProfileScope,
    facet_type: UserProfileFacetType,
    key: str,
    value: str,
    confidence: float,
    evidence_code: str,
    status: UserProfileFacetStatus = UserProfileFacetStatus.CANDIDATE,
    ttl_days: int = 90,
    attributes: dict[str, Any] | None = None,
) -> UserProfileFacet:
    """创建一条画像事实。

    构造函数统一处理置信度边界、事实 ID 和过期时间，避免抽取器、API、测试各自拼装出不一致的事实。
    """

    bounded_confidence = max(0.0, min(confidence, 1.0))
    return UserProfileFacet(
        facet_id=f"upf_{uuid4().hex}",
        scope=scope,
        facet_type=facet_type,
        key=key,
        value=value,
        confidence=bounded_confidence,
        status=status,
        evidence_code=evidence_code,
        expires_at=datetime.now(timezone.utc) + timedelta(days=max(1, ttl_days)),
        attributes=dict(attributes or {}),
    )


def _stronger_status(
    current: UserProfileFacetStatus,
    incoming: UserProfileFacetStatus,
) -> UserProfileFacetStatus:
    """在重复事实更新时选择更强状态。

    rejected/superseded/expired 不能被一次新的自动观察直接复活；active 比 candidate 更强。这样可以避免
    用户刚拒绝某条画像，下一次类似表达又被自动激活。
    """

    if current in {
        UserProfileFacetStatus.REJECTED,
        UserProfileFacetStatus.SUPERSEDED,
        UserProfileFacetStatus.EXPIRED,
    }:
        return current
    if current == UserProfileFacetStatus.ACTIVE:
        return current
    return incoming

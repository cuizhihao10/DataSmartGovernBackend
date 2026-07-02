"""用户画像低敏抽取器。

本模块负责把一次 Agent 请求中的目标和少量显式变量转换为画像候选事实。它不调用模型、不访问外部
服务，也不保存原始文本。之所以先采用规则式抽取，是因为用户画像属于长期影响 Agent 行为的能力，
需要先保证可解释、可审计、可测试，再逐步引入 LLM/embedding 辅助抽取。
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from datasmart_ai_runtime.domain.contracts import AgentRequest
from datasmart_ai_runtime.services.memory.user_profile_memory import (
    UserProfileFacet,
    UserProfileFacetStatus,
    UserProfileFacetType,
    UserProfileScope,
    build_user_profile_facet,
)


@dataclass(frozen=True)
class UserProfileExtractionPolicy:
    """用户画像抽取策略。

    字段说明：
    - `auto_activate_confidence`：达到该置信度的低风险偏好可自动激活，直接用于上下文；
    - `candidate_min_confidence`：低于该置信度的信号直接忽略，避免把偶然词汇当成长期偏好；
    - `capture_current_request_candidates`：是否把本次请求识别到的候选写入画像 store；
    - `include_request_preferences`：是否读取 `request.variables["profilePreferences"]` 中的显式偏好。
    """

    auto_activate_confidence: float = 0.84
    candidate_min_confidence: float = 0.55
    capture_current_request_candidates: bool = True
    include_request_preferences: bool = True


class RuleBasedUserProfileExtractor:
    """规则式用户画像抽取器。

    抽取器只产生低敏、归一化事实。例如用户说“以后拉镜像默认 DaoCloud”，我们记录的是
    `tooling_preference/docker_registry=daocloud`，而不是保存整句原文。这样画像既能帮助 Agent 下次
    更符合用户偏好，又不会扩大隐私和业务数据暴露面。
    """

    def __init__(self, policy: UserProfileExtractionPolicy | None = None) -> None:
        self._policy = policy or UserProfileExtractionPolicy()

    def extract(self, request: AgentRequest, scope: UserProfileScope) -> tuple[UserProfileFacet, ...]:
        """从 AgentRequest 中抽取画像候选。

        输入说明：
        - `request.objective`：只用于匹配低敏偏好关键词，不会被写入画像事实；
        - `request.variables`：只读取显式 profilePreferences/userPreferences 等受控字段；
        - `scope`：决定画像归属，不由客户端自由伪造，生产环境应来自 gateway/permission-admin。
        """

        facets: list[UserProfileFacet] = []
        text = f"{request.objective} {_controlled_variable_text(request.variables)}".lower()
        self._extract_language_preferences(scope, text, facets)
        self._extract_connector_preferences(scope, text, facets)
        self._extract_sync_preferences(scope, text, facets)
        self._extract_etl_preferences(scope, text, facets)
        self._extract_risk_and_autonomy_preferences(scope, text, facets)
        self._extract_output_preferences(scope, text, facets)
        self._extract_performance_preferences(scope, text, facets)
        if self._policy.include_request_preferences:
            self._extract_explicit_preferences(scope, request.variables, facets)
        return tuple(
            facet
            for facet in facets
            if facet.confidence >= self._policy.candidate_min_confidence
        )

    def _build(
        self,
        *,
        scope: UserProfileScope,
        facet_type: UserProfileFacetType,
        key: str,
        value: str,
        confidence: float,
        evidence_code: str,
    ) -> UserProfileFacet:
        """创建画像事实，并按策略决定是否自动激活。

        自动激活只用于低风险偏好，例如语言、输出格式、连接器偏好。真正涉及执行权限、数据范围或敏感
        写入的事实不应该通过这里自动放行；后续如果出现这类事实，应进入审批态。
        """

        status = (
            UserProfileFacetStatus.ACTIVE
            if confidence >= self._policy.auto_activate_confidence
            else UserProfileFacetStatus.CANDIDATE
        )
        return build_user_profile_facet(
            scope=scope,
            facet_type=facet_type,
            key=key,
            value=value,
            confidence=confidence,
            evidence_code=evidence_code,
            status=status,
            attributes={"autoActivated": status == UserProfileFacetStatus.ACTIVE},
        )

    def _extract_language_preferences(
        self,
        scope: UserProfileScope,
        text: str,
        facets: list[UserProfileFacet],
    ) -> None:
        """识别输出语言偏好。"""

        if any(token in text for token in ("中文", "用中文", "中文编写", "中文说明")):
            facets.append(
                self._build(
                    scope=scope,
                    facet_type=UserProfileFacetType.LANGUAGE_PREFERENCE,
                    key="response_language",
                    value="zh-CN",
                    confidence=0.96,
                    evidence_code="MENTION_CHINESE_RESPONSE",
                )
            )
        if any(token in text for token in ("english", "英文", "bilingual", "双语")):
            facets.append(
                self._build(
                    scope=scope,
                    facet_type=UserProfileFacetType.LANGUAGE_PREFERENCE,
                    key="response_language_secondary",
                    value="en-or-bilingual",
                    confidence=0.72,
                    evidence_code="MENTION_ENGLISH_OR_BILINGUAL",
                )
            )

    def _extract_connector_preferences(
        self,
        scope: UserProfileScope,
        text: str,
        facets: list[UserProfileFacet],
    ) -> None:
        """识别常用连接器和数据源生态。"""

        connector_rules = (
            ("mysql", "mysql", "MENTION_MYSQL"),
            ("pgsql", "postgresql", "MENTION_POSTGRESQL_ALIAS"),
            ("postgres", "postgresql", "MENTION_POSTGRESQL"),
            ("postgresql", "postgresql", "MENTION_POSTGRESQL"),
            ("kafka", "kafka", "MENTION_KAFKA"),
            ("mongo", "mongodb", "MENTION_MONGODB"),
            ("mongodb", "mongodb", "MENTION_MONGODB"),
            ("minio", "object_storage", "MENTION_MINIO"),
            ("s3", "object_storage", "MENTION_S3"),
            ("excel", "file_excel", "MENTION_EXCEL"),
            ("csv", "file_csv", "MENTION_CSV"),
        )
        for token, value, evidence in connector_rules:
            if token in text:
                facets.append(
                    self._build(
                        scope=scope,
                        facet_type=UserProfileFacetType.CONNECTOR_PREFERENCE,
                        key="connector_family",
                        value=value,
                        confidence=0.82,
                        evidence_code=evidence,
                    )
                )

    def _extract_sync_preferences(
        self,
        scope: UserProfileScope,
        text: str,
        facets: list[UserProfileFacet],
    ) -> None:
        """识别数据同步模式偏好。"""

        if any(token in text for token in ("cdc", "实时同步", "binlog", "增量订阅")):
            facets.append(self._sync_facet(scope, "cdc_or_realtime", 0.88, "MENTION_CDC_OR_REALTIME"))
        if any(token in text for token in ("增量", "incremental")):
            facets.append(self._sync_facet(scope, "incremental", 0.82, "MENTION_INCREMENTAL_SYNC"))
        if any(token in text for token in ("全量", "full sync", "全表")):
            facets.append(self._sync_facet(scope, "full_sync", 0.76, "MENTION_FULL_SYNC"))
        if any(token in text for token in ("批处理", "定时", "schedule", "离线")):
            facets.append(self._sync_facet(scope, "scheduled_batch", 0.78, "MENTION_BATCH_OR_SCHEDULED"))

    def _sync_facet(
        self,
        scope: UserProfileScope,
        value: str,
        confidence: float,
        evidence_code: str,
    ) -> UserProfileFacet:
        """创建同步模式画像事实。"""

        return self._build(
            scope=scope,
            facet_type=UserProfileFacetType.SYNC_MODE_PREFERENCE,
            key="sync_mode",
            value=value,
            confidence=confidence,
            evidence_code=evidence_code,
        )

    def _extract_etl_preferences(
        self,
        scope: UserProfileScope,
        text: str,
        facets: list[UserProfileFacet],
    ) -> None:
        """识别 ETL 开发方式偏好。"""

        if any(token in text for token in ("etl", "数据同步工具", "数据传输", "数据管道")):
            facets.append(
                self._build(
                    scope=scope,
                    facet_type=UserProfileFacetType.ETL_STYLE_PREFERENCE,
                    key="product_focus",
                    value="etl_and_data_sync_agent_app",
                    confidence=0.91,
                    evidence_code="MENTION_ETL_DATA_SYNC_PRODUCT_FOCUS",
                )
            )
        if any(token in text for token in ("sql优先", "sql first", "sql 脚本", "sql脚本")):
            facets.append(
                self._build(
                    scope=scope,
                    facet_type=UserProfileFacetType.ETL_STYLE_PREFERENCE,
                    key="etl_authoring_style",
                    value="sql_first",
                    confidence=0.82,
                    evidence_code="MENTION_SQL_FIRST_ETL",
                )
            )

    def _extract_risk_and_autonomy_preferences(
        self,
        scope: UserProfileScope,
        text: str,
        facets: list[UserProfileFacet],
    ) -> None:
        """识别风险容忍度和 Agent 自主性偏好。"""

        if any(token in text for token in ("直接做", "你自己把握", "听你的", "自动推进", "不用问我")):
            facets.append(
                self._build(
                    scope=scope,
                    facet_type=UserProfileFacetType.AGENT_AUTONOMY_PREFERENCE,
                    key="agent_autonomy",
                    value="proactive_with_reasonable_assumptions",
                    confidence=0.9,
                    evidence_code="ASK_AGENT_PROACTIVE_EXECUTION",
                )
            )
        if any(token in text for token in ("审批", "人工确认", "高风险", "生产", "商用")):
            facets.append(
                self._build(
                    scope=scope,
                    facet_type=UserProfileFacetType.RISK_TOLERANCE,
                    key="side_effect_policy",
                    value="human_in_the_loop_for_high_risk",
                    confidence=0.86,
                    evidence_code="MENTION_PRODUCTION_OR_APPROVAL",
                )
            )

    def _extract_output_preferences(
        self,
        scope: UserProfileScope,
        text: str,
        facets: list[UserProfileFacet],
    ) -> None:
        """识别输出内容结构偏好。"""

        if any(token in text for token in ("详细注释", "详细说明", "原理", "学习参考")):
            facets.append(
                self._build(
                    scope=scope,
                    facet_type=UserProfileFacetType.OUTPUT_FORMAT_PREFERENCE,
                    key="explanation_depth",
                    value="detailed_chinese_learning_notes",
                    confidence=0.96,
                    evidence_code="ASK_DETAILED_CHINESE_COMMENTS",
                )
            )
        if any(token in text for token in ("尽快", "极速", "最快速度", "闭环收敛")):
            facets.append(
                self._build(
                    scope=scope,
                    facet_type=UserProfileFacetType.OUTPUT_FORMAT_PREFERENCE,
                    key="delivery_style",
                    value="fast_convergent_delivery",
                    confidence=0.89,
                    evidence_code="ASK_FAST_CLOSURE",
                )
            )

    def _extract_performance_preferences(
        self,
        scope: UserProfileScope,
        text: str,
        facets: list[UserProfileFacet],
    ) -> None:
        """识别性能与可靠性关注点。"""

        if any(token in text for token in ("高并发", "多线程", "性能", "吞吐", "容量")):
            facets.append(
                self._build(
                    scope=scope,
                    facet_type=UserProfileFacetType.PERFORMANCE_FOCUS,
                    key="engineering_focus",
                    value="performance_capacity_and_concurrency",
                    confidence=0.84,
                    evidence_code="MENTION_PERFORMANCE_OR_CONCURRENCY",
                )
            )
        if any(token in text for token in ("恢复", "故障演练", "可靠性", "重试", "幂等")):
            facets.append(
                self._build(
                    scope=scope,
                    facet_type=UserProfileFacetType.PERFORMANCE_FOCUS,
                    key="reliability_focus",
                    value="recovery_retry_idempotency",
                    confidence=0.82,
                    evidence_code="MENTION_RELIABILITY_OR_RECOVERY",
                )
            )

    def _extract_explicit_preferences(
        self,
        scope: UserProfileScope,
        variables: dict[str, Any],
        facets: list[UserProfileFacet],
    ) -> None:
        """读取显式传入的画像偏好字段。

        该路径用于未来前端设置页或企业配置中心直接传入结构化偏好。只接受少量白名单 key，避免调用方
        把任意 prompt 或表单正文塞进 profilePreferences。
        """

        raw_preferences = variables.get("profilePreferences") or variables.get("userPreferences")
        if not isinstance(raw_preferences, dict):
            return
        allowed_keys = {
            "responseLanguage": (UserProfileFacetType.LANGUAGE_PREFERENCE, "response_language"),
            "connectorFamily": (UserProfileFacetType.CONNECTOR_PREFERENCE, "connector_family"),
            "syncMode": (UserProfileFacetType.SYNC_MODE_PREFERENCE, "sync_mode"),
            "etlAuthoringStyle": (UserProfileFacetType.ETL_STYLE_PREFERENCE, "etl_authoring_style"),
            "deliveryStyle": (UserProfileFacetType.OUTPUT_FORMAT_PREFERENCE, "delivery_style"),
        }
        for source_key, (facet_type, facet_key) in allowed_keys.items():
            value = raw_preferences.get(source_key)
            if value is None or not str(value).strip():
                continue
            facets.append(
                self._build(
                    scope=scope,
                    facet_type=facet_type,
                    key=facet_key,
                    value=str(value).strip()[:80],
                    confidence=0.93,
                    evidence_code=f"EXPLICIT_{source_key.upper()}",
                )
            )


def _controlled_variable_text(variables: dict[str, Any]) -> str:
    """把少量白名单变量转换为匹配文本。

    这里绝不拼接完整 variables。原因是 variables 可能包含 SQL、连接参数、任务配置、工具参数或内部
    token。只读取明确的低敏偏好字段，才能保证画像抽取不会扩大敏感数据面。
    """

    chunks: list[str] = []
    for key in ("intentHint", "domainHint", "deliveryPreference", "profileHint"):
        value = variables.get(key)
        if value is not None and str(value).strip():
            chunks.append(str(value).strip()[:200])
    return " ".join(chunks)

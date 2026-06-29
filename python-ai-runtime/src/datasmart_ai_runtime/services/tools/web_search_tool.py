"""受控网页搜索工具治理契约。

网页搜索看起来像一个“只读工具”，但在企业 Agent 平台中它并不简单：
- 查询词可能包含客户名、字段名、内部系统地址、错误栈、SQL 或凭据片段；
- 搜索 Provider 可能是公网搜索、企业搜索、合规归档库或本地 SearXNG；
- 搜索结果必须带来源引用，否则模型很容易把未经验证的信息当成事实；
- 外部网络访问需要限流、缓存、审计和 allowlist，不能让模型任意访问互联网。

因此本模块先实现“搜索请求治理契约”，而不是直接联网搜索。它把用户目标或结构化 searchQuery
转换成低敏 `searchQueryRef`、Provider 策略、缓存策略、限流策略和结果引用策略。后续无论接
Bing、Tavily、SearXNG、企业搜索还是内部知识库，都应消费这个契约，而不是绕过 Agent Host 治理。
"""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from typing import Callable, Mapping

from datasmart_ai_runtime.domain.contracts import AgentRequest, ToolDefinition, ToolPlan

WEB_SEARCH_TOOL_SCHEMA_VERSION = "datasmart.web-search-tool.v1"
WEB_SEARCH_TOOL_PAYLOAD_POLICY = "LOW_SENSITIVE_SEARCH_GOVERNANCE_METADATA_ONLY"

ToolPlanFactory = Callable[[ToolDefinition, str, dict[str, object]], ToolPlan]


@dataclass(frozen=True)
class WebSearchGovernancePolicy:
    """网页搜索治理策略。

    字段说明：
    - `provider_allowlist`：允许被控制面调用的搜索 Provider 代码。这里不是 endpoint，也不是密钥；
      真正的 endpoint/API Key 必须由部署环境或 Java 控制面管理。
    - `public_web_allowed`：是否允许走公网搜索。很多私有化客户只允许企业知识库或内网归档库。
    - `max_query_chars`：最大查询字符数，防止把 prompt、日志、SQL 或工具输出整段塞进搜索请求。
    - `max_results`：单次最多结果数。搜索结果越多，模型上下文越大，引用审核成本越高。
    - `cache_ttl_seconds`：搜索摘要缓存 TTL。真实缓存 key 必须绑定租户、项目、workspace/session。
    - `rate_limit_window_seconds/max_requests_per_window`：单用户或单会话搜索预算的默认窗口。
    - `require_citations`：要求结果必须携带来源 URL/标题/摘要引用，避免模型无来源回答。
    """

    provider_allowlist: tuple[str, ...] = ("enterprise-search", "searxng", "bing-web-search")
    public_web_allowed: bool = False
    max_query_chars: int = 240
    max_results: int = 5
    cache_ttl_seconds: int = 300
    rate_limit_window_seconds: int = 60
    max_requests_per_window: int = 6
    require_citations: bool = True


@dataclass(frozen=True)
class WebSearchGovernanceDecision:
    """单次网页搜索请求的低敏治理决策。

    该对象不会保存原始 query，也不会保存外部 Provider endpoint。它只保存可进入 ToolPlan、runtime
    event 或 Java 控制面投影的元数据：摘要引用、计数、风险码、缓存/限流/结果策略。
    """

    query_digest: str
    query_char_count: int
    query_term_count: int
    sensitivity_level: str
    issue_codes: tuple[str, ...]
    reason_codes: tuple[str, ...]
    query_ref: Mapping[str, object]
    provider_policy: Mapping[str, object]
    network_policy: Mapping[str, object]
    cache_policy: Mapping[str, object]
    rate_limit_policy: Mapping[str, object]
    result_policy: Mapping[str, object]

    @property
    def high_risk(self) -> bool:
        """判断搜索请求是否包含明显高风险信号。"""

        return self.sensitivity_level == "high" or any(code.startswith("QUERY_CONTAINS") for code in self.issue_codes)

    def to_tool_arguments(self) -> dict[str, object]:
        """输出 ToolPlan 参数。

        参数中只放低敏治理元数据和引用，不放用户原始查询。真实 Provider 执行时应由受控边界重新取回
        或重新构造 query，并再次做权限、DLP、限流和审计。
        """

        return {
            "searchQueryRef": dict(self.query_ref),
            "providerPolicy": dict(self.provider_policy),
            "networkPolicy": dict(self.network_policy),
            "cachePolicy": dict(self.cache_policy),
            "rateLimitPolicy": dict(self.rate_limit_policy),
            "resultPolicy": dict(self.result_policy),
            "issueCodes": self.issue_codes,
            "reasonCodes": self.reason_codes,
            "payloadPolicy": WEB_SEARCH_TOOL_PAYLOAD_POLICY,
            "schemaVersion": WEB_SEARCH_TOOL_SCHEMA_VERSION,
        }

    def to_governance_hints(self) -> dict[str, object]:
        """输出可并入 `ToolPlan.governance_hints` 的低敏提示。"""

        return {
            "webSearchGoverned": True,
            "webSearchSchemaVersion": WEB_SEARCH_TOOL_SCHEMA_VERSION,
            "payloadPolicy": WEB_SEARCH_TOOL_PAYLOAD_POLICY,
            "externalNetworkAccess": "CONTROLLED_PROVIDER_ONLY",
            "publicWebAllowed": bool(self.network_policy.get("publicWebAllowed")),
            "providerAllowlistCount": self.provider_policy.get("providerAllowlistCount", 0),
            "cacheTtlSeconds": self.cache_policy.get("ttlSeconds", 0),
            "rateLimitWindowSeconds": self.rate_limit_policy.get("windowSeconds", 0),
            "maxResults": self.result_policy.get("maxResults", 0),
            "citationRequired": self.result_policy.get("citationRequired", True),
            "querySensitivityLevel": self.sensitivity_level,
            "queryDigestPrefix": self.query_digest[:12],
            "issueCodes": self.issue_codes,
            "reasonCodes": self.reason_codes,
        }


class WebSearchGovernanceService:
    """把搜索意图转换为受控搜索请求契约。

    这个服务只做执行前治理，不调用搜索 Provider。这样可以保证：
    - 本地单测不依赖外网；
    - 生产环境必须显式配置 Provider 才能执行；
    - 所有搜索请求都先经过低敏引用化、敏感标记、缓存、限流和引用结果策略。
    """

    _SECRET_PATTERN = re.compile(
        r"(?i)\b(api[_-]?key|secret|password|passwd|token|authorization|bearer|private[_ -]?key)\b\s*[:=]\s*[^\s,;]+"
    )
    _INTERNAL_ENDPOINT_PATTERN = re.compile(
        r"(?i)(localhost|127\.0\.0\.1|0\.0\.0\.0|10\.\d+\.\d+\.\d+|192\.168\.\d+\.\d+|172\.(1[6-9]|2\d|3[0-1])\.\d+\.\d+|\.internal\b|\.svc\b)"
    )
    _SQL_PATTERN = re.compile(r"\b(select|insert|update|delete|drop|truncate|alter|merge)\b", re.IGNORECASE)
    _SENSITIVE_BUSINESS_PATTERN = re.compile(r"(身份证|手机号|银行卡|邮箱|客户姓名|住址|phone|email|id card)", re.IGNORECASE)

    def __init__(self, policy: WebSearchGovernancePolicy | None = None) -> None:
        self._policy = policy or WebSearchGovernancePolicy()

    def prepare(self, request: AgentRequest, query_text: str | None = None) -> WebSearchGovernanceDecision:
        """生成搜索治理决策。

        `query_text` 允许调用方显式传入查询词；未传入时从请求变量和 objective 中提取。无论来源如何，
        决策对象都不会返回原文，只返回 digest、长度和策略摘要。
        """

        normalized_query = self._normalize_query(query_text or self._query_from_request(request))
        issue_codes = list(self._query_issue_codes(normalized_query))
        reason_codes = ["SEARCH_QUERY_REFERENCED_NOT_EXPOSED", "CITATION_REQUIRED"]
        if len(normalized_query) > self._policy.max_query_chars:
            issue_codes.append("QUERY_TOO_LONG_TRUNCATION_REQUIRED")
            reason_codes.append("QUERY_LENGTH_LIMIT_APPLIED")

        query_digest = self._query_digest(request, normalized_query)
        sensitivity_level = self._sensitivity_level(issue_codes)
        provider_policy = self._provider_policy()
        network_policy = self._network_policy(request)
        cache_policy = self._cache_policy(request, query_digest)
        rate_limit_policy = self._rate_limit_policy()
        result_policy = self._result_policy()

        return WebSearchGovernanceDecision(
            query_digest=query_digest,
            query_char_count=len(normalized_query),
            query_term_count=self._term_count(normalized_query),
            sensitivity_level=sensitivity_level,
            issue_codes=tuple(issue_codes),
            reason_codes=tuple(reason_codes),
            query_ref={
                "referenceType": "WEB_SEARCH_QUERY_REF",
                "queryDigest": query_digest,
                "queryDigestPrefix": query_digest[:12],
                "queryCharCount": len(normalized_query),
                "queryTermCount": self._term_count(normalized_query),
                "sensitivityLevel": sensitivity_level,
                "rawQueryAvailable": False,
                "rawQueryPolicy": "MATERIALIZE_AT_CONTROLLED_PROVIDER_BOUNDARY_ONLY",
            },
            provider_policy=provider_policy,
            network_policy=network_policy,
            cache_policy=cache_policy,
            rate_limit_policy=rate_limit_policy,
            result_policy=result_policy,
        )

    def _query_from_request(self, request: AgentRequest) -> str:
        """按兼容字段读取搜索查询。

        如果调用方没有提供专门 searchQuery，就把 objective 作为查询意图来源。这样用户说“请联网查一下
        某技术趋势”时也能生成搜索计划，但返回的 ToolPlan 仍不会泄露 objective 原文。
        """

        for field_name in ("searchQuery", "search_query", "webSearchQuery", "web_search_query", "query"):
            value = request.variables.get(field_name)
            if value not in (None, "", [], {}):
                return str(value)
        return request.objective

    @staticmethod
    def _normalize_query(query_text: str) -> str:
        """归一化查询词用于长度和 digest 计算。"""

        return " ".join(str(query_text or "").split())

    def _query_issue_codes(self, query_text: str) -> tuple[str, ...]:
        """识别查询词中的风险信号。"""

        if not query_text:
            return ("QUERY_EMPTY",)
        issues: list[str] = []
        if self._SECRET_PATTERN.search(query_text):
            issues.append("QUERY_CONTAINS_SECRET_MARKER")
        if self._INTERNAL_ENDPOINT_PATTERN.search(query_text):
            issues.append("QUERY_MENTIONS_INTERNAL_ENDPOINT")
        if self._SQL_PATTERN.search(query_text):
            issues.append("QUERY_CONTAINS_SQL_MARKER")
        if self._SENSITIVE_BUSINESS_PATTERN.search(query_text):
            issues.append("QUERY_MENTIONS_SENSITIVE_BUSINESS_DATA")
        return tuple(issues)

    @staticmethod
    def _sensitivity_level(issue_codes: list[str]) -> str:
        """根据风险码生成查询敏感等级。"""

        if any(code in {"QUERY_CONTAINS_SECRET_MARKER", "QUERY_MENTIONS_INTERNAL_ENDPOINT"} for code in issue_codes):
            return "high"
        if issue_codes:
            return "medium"
        return "low"

    def _provider_policy(self) -> dict[str, object]:
        """生成 Provider allowlist 摘要。"""

        return {
            "providerPolicyType": "ALLOWLISTED_SEARCH_PROVIDERS",
            "providerAllowlistCount": len(self._policy.provider_allowlist),
            "providerAllowlist": self._policy.provider_allowlist,
            "providerEndpointExposed": False,
            "secretManagedBy": "DEPLOYMENT_OR_JAVA_CONTROL_PLANE",
        }

    def _network_policy(self, request: AgentRequest) -> dict[str, object]:
        """生成网络访问策略摘要。

        默认不允许公网搜索，只允许企业搜索或自托管搜索 Provider。调用方可以通过可信控制面变量启用
        publicWebAllowed，但该字段只是计划层提示，真实执行仍必须由 gateway/permission-admin 校验。
        """

        trusted = request.variables.get("trustedControlPlane")
        trusted_search = trusted.get("webSearch") if isinstance(trusted, dict) else {}
        public_allowed = bool(trusted_search.get("publicWebAllowed")) if isinstance(trusted_search, dict) else self._policy.public_web_allowed
        return {
            "networkPolicyType": "SEARCH_PROVIDER_ONLY",
            "publicWebAllowed": public_allowed,
            "arbitraryUrlFetchAllowed": False,
            "privateNetworkDenied": True,
            "domainAllowlistRequired": True,
            "rawUrlMaterializationPolicy": "DENY_IN_AGENT_PLAN",
        }

    def _cache_policy(self, request: AgentRequest, query_digest: str) -> dict[str, object]:
        """生成搜索结果缓存策略。

        搜索结果缓存不能跨权限边界复用，所以默认 session/workspace 级别；cache key 只保存 digest，
        不保存明文 query。
        """

        session_id = request.variables.get("sessionId") or request.variables.get("session_id") or "no-session"
        namespace = f"tenant:{request.tenant_id}:project:{request.project_id}:session:{session_id}:web-search"
        cache_key_digest = hashlib.sha256(f"{namespace}:{query_digest}".encode("utf-8")).hexdigest()
        return {
            "cacheScope": "SESSION_ONLY",
            "ttlSeconds": self._policy.cache_ttl_seconds,
            "cacheKeyDigest": cache_key_digest,
            "cacheKeyDigestPrefix": cache_key_digest[:12],
            "rawQueryInCacheKey": False,
        }

    def _rate_limit_policy(self) -> dict[str, object]:
        """生成搜索限流策略摘要。"""

        return {
            "rateLimitPolicyType": "FIXED_WINDOW_REQUEST_BUDGET",
            "windowSeconds": self._policy.rate_limit_window_seconds,
            "maxRequestsPerWindow": self._policy.max_requests_per_window,
            "budgetOwner": "tenant_project_actor_session",
        }

    def _result_policy(self) -> dict[str, object]:
        """生成搜索结果使用策略摘要。"""

        return {
            "resultPolicyType": "CITATION_FIRST_SNIPPET_ONLY",
            "maxResults": self._policy.max_results,
            "citationRequired": self._policy.require_citations,
            "allowedResultFields": ("title", "sourceUrlDigest", "snippet", "publishedAt", "providerName"),
            "rawHtmlAllowed": False,
            "fileDownloadAllowed": False,
            "payloadPolicy": "SNIPPET_AND_SOURCE_METADATA_ONLY",
        }

    @staticmethod
    def _query_digest(request: AgentRequest, normalized_query: str) -> str:
        """生成带租户和项目边界的查询摘要。"""

        seed = f"{request.tenant_id}|{request.project_id}|{request.actor_id}|{normalized_query}"
        return hashlib.sha256(seed.encode("utf-8")).hexdigest()

    @staticmethod
    def _term_count(query_text: str) -> int:
        """粗略统计查询词数量。"""

        return len([term for term in re.split(r"\s+", query_text.strip()) if term])


class WebSearchToolPlanBuilder:
    """为 `web.search.query` 构造低敏 ToolPlan。

    该 builder 只负责判断“本轮是否需要搜索”和“搜索请求参数应该是什么形状”。真正搜索执行、
    Provider 调用、结果抓取、引用验证和内容注入仍应由未来工具执行器完成。
    """

    _SEARCH_KEYWORDS = (
        "web search",
        "search web",
        "internet",
        "online",
        "latest",
        "current",
        "查网页",
        "网页搜索",
        "联网搜索",
        "联网查",
        "网上查",
        "搜索一下",
        "查一下最新",
        "最新资料",
        "外部资料",
        "引用来源",
    )

    def __init__(self, governance_service: WebSearchGovernanceService | None = None) -> None:
        self._governance_service = governance_service or WebSearchGovernanceService()

    def build(
        self,
        *,
        request: AgentRequest,
        objective: str,
        candidate_tools: set[str],
        tools: Mapping[str, ToolDefinition],
        plan_factory: ToolPlanFactory,
    ) -> tuple[ToolPlan, ...]:
        """按意图生成网页搜索工具计划。"""

        tool = tools.get("web.search.query")
        if tool is None or not self._wants_web_search(request, objective, candidate_tools):
            return ()
        decision = self._governance_service.prepare(request)
        plan = plan_factory(
            tool,
            (
                "用户目标需要外部资料或最新信息，但搜索不会由模型直接联网执行；"
                "当前仅生成受控搜索查询引用、Provider allowlist、缓存 TTL、限流窗口和引用结果策略。"
            ),
            decision.to_tool_arguments(),
        )
        merged_hints = {
            **plan.governance_hints,
            **decision.to_governance_hints(),
        }
        return (ToolPlan(
            tool_name=plan.tool_name,
            reason=plan.reason,
            arguments=plan.arguments,
            risk_level=plan.risk_level,
            execution_mode=plan.execution_mode,
            requires_human_approval=plan.requires_human_approval,
            parameter_validation=plan.parameter_validation,
            governance_hints=merged_hints,
        ),)

    def _wants_web_search(
        self,
        request: AgentRequest,
        objective: str,
        candidate_tools: set[str],
    ) -> bool:
        """判断是否需要生成搜索计划。"""

        if "web.search.query" in candidate_tools:
            return True
        if request.variables.get("webSearch") or request.variables.get("useWebSearch") or request.variables.get("use_web_search"):
            return True
        if request.variables.get("searchQuery") or request.variables.get("webSearchQuery") or request.variables.get("search_query"):
            return True
        return any(keyword in objective for keyword in self._SEARCH_KEYWORDS)

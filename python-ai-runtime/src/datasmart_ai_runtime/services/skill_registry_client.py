"""Java Agent Runtime Skill 描述符客户端。

Python AI Runtime 不能长期只依赖本地 `default_skill_registry()`。
本地默认 Skill 适合单元测试和离线开发，但生产环境中 Skill 是否启用、依赖哪些工具、需要哪些权限、
审批策略是什么，应该由 Java `agent-runtime` 控制面统一治理。

本客户端负责读取 Java `/agent-runtime/skills/descriptors`，并映射成 Python 的
`AgentSkillDescriptor`。这样 Python 编排器只消费稳定领域对象，不直接耦合 Java DTO 字段结构。
"""

from __future__ import annotations

from dataclasses import dataclass
import json
from typing import Any
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from datasmart_ai_runtime.domain.intent import GovernanceDomain
from datasmart_ai_runtime.domain.memory import AgentMemoryType
from datasmart_ai_runtime.domain.skills import AgentSkillDescriptor


@dataclass(frozen=True)
class AgentSkillPublicationItem:
    """Java Skill 发布 Manifest 中的单个能力摘要。

    这里不直接复用 `AgentSkillDescriptor`，是因为 Manifest item 的业务含义更偏“发布/同步/缓存”：
    它告诉 Python Runtime 某个 Skill 当前是否 READY、内容指纹是什么、有哪些发布前警告，以及应该去哪个端点回查完整 descriptor。
    真正执行前仍然要经过工具权限、审批、沙箱和 runtime-protection，不能只因为 item 是 READY 就直接产生副作用。
    """

    skill_code: str
    display_name: str
    domain: str
    publication_state: str
    content_fingerprint: str
    descriptor_endpoints: tuple[str, ...]
    enabled: bool
    risk_level: str
    approval_policy: str
    audit_required: bool
    tenant_scoped: bool
    project_scoped: bool
    required_tools: tuple[str, ...]
    required_permissions: tuple[str, ...]
    memory_dependencies: tuple[str, ...]
    publication_warnings: tuple[str, ...]


@dataclass(frozen=True)
class AgentSkillPublicationManifest:
    """Java Agent Runtime 输出的 Skill 发布 Manifest。

    Manifest 是 Python Runtime 和智能网关后续做“能力发现”的入口对象：
    - `content_fingerprint` 用于判断远端 Skill 目录是否变化，避免每次启动都刷新本地缓存；
    - `include_disabled/domain_filter/risk_level_filter` 表达这份快照的边界，防止误把后台诊断快照用于模型规划；
    - `skills` 是低敏发布摘要，完整 descriptor 仍通过 Java 控制面端点回查；
    - `consumer_guidance/compatibility_notes/recommended_actions` 让运行时诊断可以给出更接近产品后台的说明。
    """

    schema_version: str
    manifest_type: str
    protocol_hint: str
    descriptor_schema_version: str
    publication_mode: str
    content_fingerprint: str
    generated_at: str | None
    include_disabled: bool
    domain_filter: str
    risk_level_filter: str
    skill_count: int
    skills: tuple[AgentSkillPublicationItem, ...]
    consumer_guidance: tuple[str, ...]
    compatibility_notes: tuple[str, ...]
    recommended_actions: tuple[str, ...]


class SkillRegistryClientError(RuntimeError):
    """Skill 注册表客户端错误。

    单独定义异常类型，是为了让启动逻辑区分“Skill 目录不可用”和“工具目录不可用”。
    这两者的降级策略可以不同：工具目录不可用可能导致无法执行工具，Skill 目录不可用则可以先
    回退本地默认能力包，保证本地开发和基础规划能力不中断。
    """


class JavaAgentSkillRegistryClient:
    """从 Java `agent-runtime` 拉取 Skill descriptor。

    参数说明：
    - `base_url`：Java Agent Runtime 基础地址，例如 `http://localhost:8091`。
    - `timeout_seconds`：只读 descriptor 接口超时时间，避免启动时长时间阻塞。
    - `descriptors_path`：Skill descriptor 路径；如果未来统一从 gateway 暴露，可替换为
      `/api/agent/skills/descriptors`。
    """

    def __init__(
        self,
        base_url: str,
        timeout_seconds: int = 3,
        descriptors_path: str = "/agent-runtime/skills/descriptors",
        publication_manifest_path: str = "/agent-runtime/skills/publication/manifest",
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._timeout_seconds = timeout_seconds
        self._descriptors_path = descriptors_path
        self._publication_manifest_path = publication_manifest_path

    def list_skill_descriptors(self, enabled_only: bool = True, trace_id: str | None = None) -> tuple[AgentSkillDescriptor, ...]:
        """读取 Java Skill descriptor 并映射为 Python Skill 契约。

        当前只传 enabledOnly，后续可以扩展 domain/riskLevel/tenantId/projectId 等过滤条件。
        过滤条件是否下推到 Java 控制面，是一个产品决策：如果 Skill 数量很多，应由 Java 分页过滤；
        如果 Skill 数量有限，Python 本地二次筛选也可以接受。
        """

        query = urlencode({"enabledOnly": str(enabled_only).lower()})
        url = f"{self._base_url}{self._descriptors_path}?{query}"
        headers = {"Accept": "application/json"}
        if trace_id:
            headers["X-Trace-Id"] = trace_id

        request = Request(url=url, headers=headers, method="GET")
        try:
            with urlopen(request, timeout=self._timeout_seconds) as response:  # noqa: S310 - URL 来自受控配置
                payload = json.loads(response.read().decode("utf-8"))
        except Exception as exc:  # pragma: no cover - 网络错误在集成测试中覆盖
            raise SkillRegistryClientError(f"读取 Java Agent Skill 描述符失败：{exc}") from exc

        return self.parse_descriptor_platform_response(payload)

    def get_publication_manifest(
        self,
        include_disabled: bool = False,
        domain: str | None = None,
        risk_level: str | None = None,
        trace_id: str | None = None,
    ) -> AgentSkillPublicationManifest:
        """读取 Java Skill 发布 Manifest。

        `include_disabled=False` 是运行时安全默认值：模型规划通常只应该看到可用能力。
        当平台后台、运维诊断或 Skill 市场需要解释“为什么某个能力不可见”时，才建议传 `include_disabled=True`。

        当前方法仍是同步 HTTP 读取，适合启动期或低频刷新；如果未来 Skill 市场变成高频动态发布，
        应进一步引入缓存、ETag/contentFingerprint 增量判断、定时刷新和失败降级策略，而不是每次规划都打 Java 控制面。
        """

        query_params: dict[str, str] = {"includeDisabled": str(include_disabled).lower()}
        if domain:
            query_params["domain"] = domain
        if risk_level:
            query_params["riskLevel"] = risk_level
        url = f"{self._base_url}{self._publication_manifest_path}?{urlencode(query_params)}"
        headers = {"Accept": "application/json"}
        if trace_id:
            headers["X-Trace-Id"] = trace_id

        request = Request(url=url, headers=headers, method="GET")
        try:
            with urlopen(request, timeout=self._timeout_seconds) as response:  # noqa: S310 - URL 来自受控配置
                payload = json.loads(response.read().decode("utf-8"))
        except Exception as exc:  # pragma: no cover - 网络错误在集成测试中覆盖
            raise SkillRegistryClientError(f"读取 Java Agent Skill 发布 Manifest 失败：{exc}") from exc

        return self.parse_publication_manifest_platform_response(payload)

    @classmethod
    def parse_descriptor_platform_response(cls, payload: dict[str, Any]) -> tuple[AgentSkillDescriptor, ...]:
        """解析 Java 平台统一响应。

        Java 返回 `PlatformApiResponse<List<AgentSkillDescriptorView>>`。这里显式校验 `code == 0`
        和 `data` 类型，避免把错误响应当成空 Skill 列表，导致 Agent 静默失去能力包。
        """

        if payload.get("code") != 0:
            reason = payload.get("reason", "UNKNOWN")
            message = payload.get("message", "Java Skill 描述符接口返回失败")
            raise SkillRegistryClientError(f"{reason}: {message}")

        raw_descriptors = payload.get("data") or []
        if not isinstance(raw_descriptors, list):
            raise SkillRegistryClientError("Java Skill 描述符响应 data 必须是数组")
        return tuple(cls._map_skill_descriptor(item) for item in raw_descriptors)

    @classmethod
    def parse_publication_manifest_platform_response(cls, payload: dict[str, Any]) -> AgentSkillPublicationManifest:
        """解析 Java 统一响应中的 Skill 发布 Manifest。

        这里显式校验 `code` 和 `data` 类型，是为了避免运行时把错误响应误当成空 Manifest。
        对 Agent 平台来说，“能力目录为空”和“能力目录读取失败”是完全不同的故障：
        前者可能是租户策略结果，后者应该触发降级、告警或启动诊断提示。
        """

        if payload.get("code") != 0:
            reason = payload.get("reason", "UNKNOWN")
            message = payload.get("message", "Java Skill 发布 Manifest 接口返回失败")
            raise SkillRegistryClientError(f"{reason}: {message}")

        raw_manifest = payload.get("data") or {}
        if not isinstance(raw_manifest, dict):
            raise SkillRegistryClientError("Java Skill 发布 Manifest 响应 data 必须是对象")

        raw_skills = raw_manifest.get("skills") or []
        if not isinstance(raw_skills, list):
            raise SkillRegistryClientError("Java Skill 发布 Manifest 响应 skills 必须是数组")

        return AgentSkillPublicationManifest(
            schema_version=str(raw_manifest.get("schemaVersion") or ""),
            manifest_type=str(raw_manifest.get("manifestType") or ""),
            protocol_hint=str(raw_manifest.get("protocolHint") or ""),
            descriptor_schema_version=str(raw_manifest.get("descriptorSchemaVersion") or ""),
            publication_mode=str(raw_manifest.get("publicationMode") or ""),
            content_fingerprint=str(raw_manifest.get("contentFingerprint") or ""),
            generated_at=raw_manifest.get("generatedAt"),
            include_disabled=bool(raw_manifest.get("includeDisabled", False)),
            domain_filter=str(raw_manifest.get("domainFilter") or "ALL"),
            risk_level_filter=str(raw_manifest.get("riskLevelFilter") or "ALL"),
            skill_count=int(raw_manifest.get("skillCount") or len(raw_skills)),
            skills=tuple(cls._map_publication_item(item) for item in raw_skills),
            consumer_guidance=cls._string_tuple(raw_manifest.get("consumerGuidance")),
            compatibility_notes=cls._string_tuple(raw_manifest.get("compatibilityNotes")),
            recommended_actions=cls._string_tuple(raw_manifest.get("recommendedActions")),
        )

    @staticmethod
    def _map_skill_descriptor(item: dict[str, Any]) -> AgentSkillDescriptor:
        """把 Java `AgentSkillDescriptorView` 映射为 Python `AgentSkillDescriptor`。

        Java 使用大写枚举风格，Python 领域对象使用 snake_case 字符串和枚举。
        在客户端边界完成转换，可以让 `AgentSkillRegistry` 保持干净，不需要理解 Java DTO。
        """

        governance = item.get("governance") or {}
        memory = item.get("memory") or {}
        return AgentSkillDescriptor(
            skill_code=item["skillCode"],
            display_name=item.get("displayName") or item["skillCode"],
            description=item.get("description") or "",
            domain=JavaAgentSkillRegistryClient._map_domain(item.get("domain")),
            required_tools=tuple(item.get("requiredTools") or ()),
            required_permissions=tuple(item.get("requiredPermissions") or ()),
            memory_dependencies=tuple(
                JavaAgentSkillRegistryClient._map_memory_type(value)
                for value in memory.get("memoryDependencies") or ()
            ),
            risk_level=JavaAgentSkillRegistryClient._normalize_text(governance.get("riskLevel") or "LOW"),
            approval_policy=str(governance.get("approvalPolicy") or "NONE"),
            enabled=bool(governance.get("enabled", True)),
            trigger_keywords=tuple(item.get("triggerKeywords") or ()),
            examples=tuple(item.get("examples") or ()),
            attributes={
                "schemaVersion": item.get("schemaVersion") or "",
                "descriptorType": item.get("descriptorType") or "",
                "protocolHint": item.get("protocolHint") or "",
                "tenantScoped": bool(governance.get("tenantScoped", True)),
                "projectScoped": bool(governance.get("projectScoped", True)),
                "auditRequired": bool(governance.get("auditRequired", True)),
                "defaultMemoryScope": JavaAgentSkillRegistryClient._normalize_text(
                    memory.get("defaultMemoryScope") or "PROJECT"
                ),
                "retentionDays": memory.get("retentionDays"),
            },
        )

    @staticmethod
    def _map_domain(value: Any) -> GovernanceDomain:
        """把 Java 治理域转换为 Python `GovernanceDomain`。"""

        normalized = JavaAgentSkillRegistryClient._normalize_text(value or "GENERAL_GOVERNANCE")
        mapping = {
            "DATASOURCE": GovernanceDomain.DATASOURCE,
            "DATA_QUALITY": GovernanceDomain.DATA_QUALITY,
            "DATA_SYNC": GovernanceDomain.DATA_SYNC,
            "TASK_MANAGEMENT": GovernanceDomain.TASK_MANAGEMENT,
            "PERMISSION_ADMIN": GovernanceDomain.PERMISSION_ADMIN,
            "KNOWLEDGE_QA": GovernanceDomain.KNOWLEDGE_QA,
            "GENERAL_GOVERNANCE": GovernanceDomain.GENERAL_GOVERNANCE,
        }
        return mapping.get(normalized, GovernanceDomain.GENERAL_GOVERNANCE)

    @staticmethod
    def _map_memory_type(value: Any) -> AgentMemoryType:
        """把 Java 记忆类型转换为 Python `AgentMemoryType`。"""

        normalized = JavaAgentSkillRegistryClient._normalize_text(value or "SHORT_TERM")
        mapping = {
            "SHORT_TERM": AgentMemoryType.SHORT_TERM,
            "SEMANTIC": AgentMemoryType.SEMANTIC,
            "EPISODIC": AgentMemoryType.EPISODIC,
            "PROCEDURAL": AgentMemoryType.PROCEDURAL,
            "RESOURCE": AgentMemoryType.RESOURCE,
        }
        return mapping.get(normalized, AgentMemoryType.SHORT_TERM)

    @staticmethod
    def _map_publication_item(item: dict[str, Any]) -> AgentSkillPublicationItem:
        """映射 Manifest item。

        Java record 使用 camelCase 字段；Python 运行时使用 snake_case。
        在客户端边界完成转换，可以让后续 Skill 缓存、启动诊断、规划器和 MCP 适配器都消费统一 Python 对象，
        避免每个调用方都直接解析 Java DTO。
        """

        return AgentSkillPublicationItem(
            skill_code=str(item.get("skillCode") or ""),
            display_name=str(item.get("displayName") or item.get("skillCode") or ""),
            domain=str(item.get("domain") or "GENERAL_GOVERNANCE"),
            publication_state=str(item.get("publicationState") or "UNKNOWN"),
            content_fingerprint=str(item.get("contentFingerprint") or ""),
            descriptor_endpoints=JavaAgentSkillRegistryClient._string_tuple(item.get("descriptorEndpoints")),
            enabled=bool(item.get("enabled", False)),
            risk_level=JavaAgentSkillRegistryClient._normalize_text(item.get("riskLevel") or "LOW"),
            approval_policy=str(item.get("approvalPolicy") or "NONE"),
            audit_required=bool(item.get("auditRequired", False)),
            tenant_scoped=bool(item.get("tenantScoped", False)),
            project_scoped=bool(item.get("projectScoped", False)),
            required_tools=JavaAgentSkillRegistryClient._string_tuple(item.get("requiredTools")),
            required_permissions=JavaAgentSkillRegistryClient._string_tuple(item.get("requiredPermissions")),
            memory_dependencies=JavaAgentSkillRegistryClient._string_tuple(item.get("memoryDependencies")),
            publication_warnings=JavaAgentSkillRegistryClient._string_tuple(item.get("publicationWarnings")),
        )

    @staticmethod
    def _normalize_text(value: Any) -> str:
        """统一 Java 配置文本格式。"""

        return str(value or "").strip().replace("-", "_").upper()

    @staticmethod
    def _string_tuple(value: Any) -> tuple[str, ...]:
        """把 Java 响应中的可选数组安全转换为字符串元组。

        使用 tuple 而不是 list，是为了让运行时对象默认不可变，减少规划链路中被某个节点意外修改的风险。
        如果 Java 侧字段缺失或返回 None，这里统一降级为空元组，避免调用方到处写空值判断。
        """

        if not value:
            return ()
        if not isinstance(value, list):
            return (str(value),)
        return tuple(str(item) for item in value)

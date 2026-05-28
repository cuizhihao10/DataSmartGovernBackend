"""Java Agent Runtime Skill 描述符客户端。

Python AI Runtime 不能长期只依赖本地 `default_skill_registry()`。
本地默认 Skill 适合单元测试和离线开发，但生产环境中 Skill 是否启用、依赖哪些工具、需要哪些权限、
审批策略是什么，应该由 Java `agent-runtime` 控制面统一治理。

本客户端负责读取 Java `/agent-runtime/skills/descriptors`，并映射成 Python 的
`AgentSkillDescriptor`。这样 Python 编排器只消费稳定领域对象，不直接耦合 Java DTO 字段结构。
"""

from __future__ import annotations

import json
from typing import Any
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from datasmart_ai_runtime.domain.intent import GovernanceDomain
from datasmart_ai_runtime.domain.memory import AgentMemoryType
from datasmart_ai_runtime.domain.skills import AgentSkillDescriptor


class SkillRegistryClientError(RuntimeError):
    """Skill 注册表客户端错误。

    单独定义异常类型，是为了让启动逻辑区分“Skill 目录不可用”和“工具目录不可用”。
    这两者的降级策略可以不同：工具目录不可用可能导致无法执行工具，Skill 目录不可用则可以先
    回退本地默认能力包，保证本地开发和基础规划能力不中断。
    """


class JavaAgentSkillRegistryClient:
    """从 Java `agent-runtime` 拉取 Skill descriptor。

    参数说明：
    - `base_url`：Java Agent Runtime 基础地址，例如 `http://localhost:8090`。
    - `timeout_seconds`：只读 descriptor 接口超时时间，避免启动时长时间阻塞。
    - `descriptors_path`：Skill descriptor 路径；如果未来统一从 gateway 暴露，可替换为
      `/api/agent/skills/descriptors`。
    """

    def __init__(
        self,
        base_url: str,
        timeout_seconds: int = 3,
        descriptors_path: str = "/agent-runtime/skills/descriptors",
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._timeout_seconds = timeout_seconds
        self._descriptors_path = descriptors_path

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
    def _normalize_text(value: Any) -> str:
        """统一 Java 配置文本格式。"""

        return str(value or "").strip().replace("-", "_").upper()

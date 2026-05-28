"""Agent 资源引用治理解析器。

4.33 已经定义了 `AgentResourceReference`，但仅有结构还不够。真实 Agent Runtime 在把资源交给
模型、工具执行器、下载接口或审计台之前，至少要回答三个问题：

1. 这个引用属于当前工作空间吗？
2. 这个引用类型当前运行时是否认识？
3. 这个引用的 `contextPolicy` 是否允许进入模型上下文？

本文件实现的是第一阶段“治理解析”，不是外部读取：
- 不访问 MinIO；
- 不读取 Java agent-runtime 审计结果；
- 不查询 Chroma/Neo4j/MySQL；
- 不展开大文件或工具输出。

这种分层很重要：读取器可以以后替换，但 workspace 校验和模型上下文准入是所有读取器之前都必须
先执行的安全门。
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Any

from datasmart_ai_runtime.domain.resource_reference import (
    AgentResourceReference,
    AgentResourceReferenceKind,
)


class AgentResourceContextPolicy(str, Enum):
    """资源进入模型上下文的策略。

    - `MODEL_FULL_ALLOWED`：理论上允许完整内容进入模型。当前阶段仍建议慎用；
    - `MODEL_SUMMARY_ALLOWED`：只允许摘要、计数、字段名、审计引用进入模型；
    - `AUDIT_ONLY`：只能给审计台或运维台查看，不应进入模型；
    - `DOWNLOAD_ONLY`：只能通过下载/对象存储接口访问，不应进入模型；
    - `FORBIDDEN_FOR_MODEL`：明确禁止进入模型上下文。

    当前默认只把前两类视为 `model_context_allowed=True`，但 `MODEL_FULL_ALLOWED` 未来仍应叠加
    敏感级别、角色权限、大小限制和脱敏策略。
    """

    MODEL_FULL_ALLOWED = "model_full_allowed"
    MODEL_SUMMARY_ALLOWED = "model_summary_allowed"
    AUDIT_ONLY = "audit_only"
    DOWNLOAD_ONLY = "download_only"
    FORBIDDEN_FOR_MODEL = "forbidden_for_model"


class AgentResourceReferenceDecision(str, Enum):
    """资源引用治理决策。"""

    ALLOWED = "allowed"
    BLOCKED = "blocked"


@dataclass(frozen=True)
class AgentResourceReferenceResolution:
    """资源引用治理解析结果。

    字段说明：
    - `decision`：是否允许后续 resolver 继续处理；
    - `reference`：规范化后的资源引用；
    - `model_context_allowed`：是否允许进入模型上下文；
    - `reason`：中文解释，便于学习、日志和前端诊断；
    - `issues`：机器可读问题码，便于 gateway、审计和测试断言；
    - `resolver_hint`：后续真实读取器建议，例如 `java_tool_output_store` 或 `minio_object_store`。
    """

    decision: AgentResourceReferenceDecision
    reference: AgentResourceReference
    model_context_allowed: bool
    reason: str
    issues: tuple[str, ...] = ()
    resolver_hint: str = ""

    def to_summary(self) -> dict[str, Any]:
        """转换为 API、事件或日志可直接使用的摘要。"""

        return {
            "decision": self.decision.value,
            "reference": self.reference.to_payload(),
            "modelContextAllowed": self.model_context_allowed,
            "reason": self.reason,
            "issues": self.issues,
            "resolverHint": self.resolver_hint,
        }


class AgentResourceReferenceResolver:
    """资源引用治理解析器。

    它的职责边界是“能不能继续处理”，不是“把内容读出来”。后续真实读取应按 `resolver_hint`
    分派给 Java tool output store、MinIO、memory service、Chroma、Neo4j 或外部连接器。
    """

    MODEL_ALLOWED_POLICIES = {
        AgentResourceContextPolicy.MODEL_FULL_ALLOWED.value,
        AgentResourceContextPolicy.MODEL_SUMMARY_ALLOWED.value,
    }

    def resolve(
        self,
        reference: AgentResourceReference | dict[str, Any] | str,
        *,
        current_workspace_key: str | None = None,
        expected_workspace_required: bool = True,
    ) -> AgentResourceReferenceResolution:
        """解析并治理一个资源引用。

        参数说明：
        - `reference`：可以是领域对象、payload dict 或旧式 URI 字符串；
        - `current_workspace_key`：当前 Agent 运行工作空间；
        - `expected_workspace_required`：是否要求引用携带 workspaceKey。

        对于历史 `minio://` 或 `agent-runtime://` 字符串，可能暂时没有 workspaceKey。生产环境如果要
        强制所有资源带 workspace，应保持默认 `True`；兼容旧数据迁移时可临时关闭。
        """

        normalized = self._normalize_reference(reference)
        workspace_issue = self._workspace_issue(
            normalized,
            current_workspace_key=current_workspace_key,
            expected_workspace_required=expected_workspace_required,
        )
        kind_issue = self._kind_issue(normalized)
        issues = tuple(issue for issue in (workspace_issue, kind_issue) if issue)
        model_context_allowed = normalized.context_policy in self.MODEL_ALLOWED_POLICIES
        if issues:
            return AgentResourceReferenceResolution(
                decision=AgentResourceReferenceDecision.BLOCKED,
                reference=normalized,
                model_context_allowed=False,
                reason="资源引用未通过工作空间或类型治理校验，不能继续解析或进入模型上下文。",
                issues=issues,
                resolver_hint=self._resolver_hint(normalized.kind),
            )
        return AgentResourceReferenceResolution(
            decision=AgentResourceReferenceDecision.ALLOWED,
            reference=normalized,
            model_context_allowed=model_context_allowed,
            reason=self._allowed_reason(normalized, model_context_allowed),
            resolver_hint=self._resolver_hint(normalized.kind),
        )

    def _normalize_reference(self, reference: AgentResourceReference | dict[str, Any] | str) -> AgentResourceReference:
        """把多种输入形式规范化为 `AgentResourceReference`。"""

        if isinstance(reference, AgentResourceReference):
            return reference
        if isinstance(reference, str):
            return AgentResourceReference.from_uri(reference)
        if isinstance(reference, dict):
            raw_kind = str(reference.get("kind") or AgentResourceReferenceKind.EXTERNAL.value)
            kind = AgentResourceReferenceKind(raw_kind) if raw_kind in {item.value for item in AgentResourceReferenceKind} else AgentResourceReferenceKind.EXTERNAL
            return AgentResourceReference(
                kind=kind,
                uri=str(reference.get("uri") or ""),
                workspace_key=self._optional_text(reference.get("workspaceKey") or reference.get("workspace_key")),
                json_path=self._optional_text(reference.get("jsonPath") or reference.get("json_path")),
                tool_code=self._optional_text(reference.get("toolCode") or reference.get("tool_code")),
                audit_id=self._optional_text(reference.get("auditId") or reference.get("audit_id")),
                run_id=self._optional_text(reference.get("runId") or reference.get("run_id")),
                context_policy=str(reference.get("contextPolicy") or reference.get("context_policy") or AgentResourceContextPolicy.AUDIT_ONLY.value),
                attributes=dict(reference.get("attributes") or {}),
            )
        raise TypeError("资源引用必须是 AgentResourceReference、dict 或 URI 字符串。")

    @staticmethod
    def _workspace_issue(
        reference: AgentResourceReference,
        *,
        current_workspace_key: str | None,
        expected_workspace_required: bool,
    ) -> str | None:
        """检查资源引用是否越过当前工作空间边界。"""

        if expected_workspace_required and not reference.workspace_key:
            return "WORKSPACE_KEY_MISSING"
        if current_workspace_key and reference.workspace_key and reference.workspace_key != current_workspace_key:
            return "WORKSPACE_KEY_MISMATCH"
        return None

    @staticmethod
    def _kind_issue(reference: AgentResourceReference) -> str | None:
        """检查资源类型是否允许进入后续解析流程。"""

        if reference.kind == AgentResourceReferenceKind.EXTERNAL:
            return "EXTERNAL_REFERENCE_NOT_ALLOWED"
        if not reference.uri:
            return "RESOURCE_URI_MISSING"
        return None

    @staticmethod
    def _resolver_hint(kind: AgentResourceReferenceKind) -> str:
        """根据引用类型给出后续真实读取器建议。"""

        return {
            AgentResourceReferenceKind.TOOL_OUTPUT: "java_tool_output_store",
            AgentResourceReferenceKind.WORKSPACE_ARTIFACT: "workspace_artifact_store",
            AgentResourceReferenceKind.MEMORY: "agent_memory_service",
            AgentResourceReferenceKind.MINIO_OBJECT: "minio_object_store",
            AgentResourceReferenceKind.AGENT_RUNTIME: "java_agent_runtime_api",
            AgentResourceReferenceKind.EXTERNAL: "external_resource_gateway",
        }[kind]

    @staticmethod
    def _allowed_reason(reference: AgentResourceReference, model_context_allowed: bool) -> str:
        """生成允许决策的人读解释。"""

        if model_context_allowed:
            return f"资源引用已通过治理校验，contextPolicy={reference.context_policy}，允许以受控方式进入模型上下文。"
        return f"资源引用已通过治理校验，但 contextPolicy={reference.context_policy}，只能进入审计/下载/后续工具解析链路。"

    @staticmethod
    def _optional_text(value: Any) -> str | None:
        """把可选字段规范化为非空字符串。"""

        text = str(value).strip() if value is not None else ""
        return text or None

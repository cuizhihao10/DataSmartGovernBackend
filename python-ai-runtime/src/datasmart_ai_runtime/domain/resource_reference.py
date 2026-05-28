"""Agent 资源引用领域契约。

工具型 Agent 的输出不应该总是直接塞进下一轮模型上下文。真实商业化场景里，工具结果可能是：
- 一个前序工具输出中的 JSON 子路径；
- 一个工作空间内的临时文件、SQL 草案、质量报告或日志片段；
- 一个长期记忆候选或已批准记忆；
- 一个 MinIO 对象、Java 工具审计结果或外部资源。

如果这些都用普通字符串表达，后续会很难判断：
- 引用是否属于当前 workspace；
- 是否允许进入模型上下文；
- 是否只能给审计台或下载接口；
- 是否需要通过 Java 控制面、MinIO、Chroma、Neo4j 或 MySQL 解析。

本文件先定义轻量、零依赖的 `AgentResourceReference`。它不执行读取，也不访问外部存储；
只负责把 DataSmart 的资源引用统一成可审计、可序列化、可扩展的机器协议。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any
from urllib.parse import quote, urlsplit


class AgentResourceReferenceKind(str, Enum):
    """Agent 资源引用类型。

    - `TOOL_OUTPUT`：同一 run 内的前序工具输出片段，兼容 Java `AgentToolOutputReferenceResolver`；
    - `WORKSPACE_ARTIFACT`：工作空间产物，例如 SQL 草案、报告、日志片段、临时导出文件；
    - `MEMORY`：长期记忆候选或正式记忆；
    - `MINIO_OBJECT`：对象存储文件；
    - `AGENT_RUNTIME`：Java agent-runtime 工具结果、审计或运行事件引用；
    - `EXTERNAL`：其他外部系统引用，默认不允许进入模型上下文。
    """

    TOOL_OUTPUT = "tool_output"
    WORKSPACE_ARTIFACT = "workspace_artifact"
    MEMORY = "memory"
    MINIO_OBJECT = "minio_object"
    AGENT_RUNTIME = "agent_runtime"
    EXTERNAL = "external"


@dataclass(frozen=True)
class AgentResourceReference:
    """统一 Agent 资源引用。

    字段说明：
    - `kind`：引用类型，决定后续由哪个 resolver 处理；
    - `uri`：稳定 URI 表达，例如 `workspace://artifact/...` 或 `memory://candidate/...`；
    - `workspace_key`：资源所属工作空间，用于跨租户/跨项目隔离校验；
    - `json_path`：当资源是结构化 JSON 时，指向其中某个子路径；
    - `tool_code/audit_id/run_id`：工具输出或 Java 审计引用的定位字段；
    - `context_policy`：是否允许进入模型上下文，当前用字符串保持轻量，后续可升级枚举；
    - `attributes`：扩展字段，例如 bucket、objectKey、candidateId、contentType、size 等。
    """

    kind: AgentResourceReferenceKind
    uri: str
    workspace_key: str | None = None
    json_path: str | None = None
    tool_code: str | None = None
    audit_id: str | None = None
    run_id: str | None = None
    context_policy: str = "audit_only"
    attributes: dict[str, Any] = field(default_factory=dict)

    def to_payload(self) -> dict[str, Any]:
        """转换为可放入 ToolPlan 参数、governance hints、事件或 API 响应的字典。"""

        return {
            "kind": self.kind.value,
            "uri": self.uri,
            "workspaceKey": self.workspace_key,
            "jsonPath": self.json_path,
            "toolCode": self.tool_code,
            "auditId": self.audit_id,
            "runId": self.run_id,
            "contextPolicy": self.context_policy,
            "attributes": dict(self.attributes),
        }

    @classmethod
    def tool_output(
        cls,
        *,
        tool_code: str,
        json_path: str,
        audit_id: str | None = None,
        run_id: str | None = None,
        workspace_key: str | None = None,
        context_policy: str = "model_summary_allowed",
    ) -> "AgentResourceReference":
        """构造同一 run 内的工具输出引用。

        该结构兼容 Java 当前的 `toolCode/fromTool`、`auditId/fromAuditId`、`jsonPath/path` 解析能力。
        URI 只作为统一标识，真正解析仍应由 Java 控制面在 session/run/workspace 范围内完成。
        """

        safe_tool = quote(tool_code, safe="")
        safe_path = quote(json_path, safe=".[ ]")
        return cls(
            kind=AgentResourceReferenceKind.TOOL_OUTPUT,
            uri=f"workspace://tool-output/{safe_tool}?path={safe_path}",
            workspace_key=workspace_key,
            json_path=json_path,
            tool_code=tool_code,
            audit_id=audit_id,
            run_id=run_id,
            context_policy=context_policy,
        )

    @classmethod
    def workspace_artifact(
        cls,
        *,
        workspace_key: str,
        artifact_path: str,
        context_policy: str = "audit_only",
        attributes: dict[str, Any] | None = None,
    ) -> "AgentResourceReference":
        """构造工作空间产物引用。"""

        safe_path = quote(artifact_path.strip("/"), safe="/._-")
        return cls(
            kind=AgentResourceReferenceKind.WORKSPACE_ARTIFACT,
            uri=f"workspace://artifact/{safe_path}",
            workspace_key=workspace_key,
            context_policy=context_policy,
            attributes=attributes or {},
        )

    @classmethod
    def memory(
        cls,
        *,
        memory_id: str,
        workspace_key: str | None = None,
        candidate: bool = False,
        context_policy: str = "model_summary_allowed",
    ) -> "AgentResourceReference":
        """构造长期记忆或记忆候选引用。"""

        category = "candidate" if candidate else "record"
        return cls(
            kind=AgentResourceReferenceKind.MEMORY,
            uri=f"memory://{category}/{quote(memory_id, safe='')}",
            workspace_key=workspace_key,
            context_policy=context_policy,
            attributes={"memoryId": memory_id, "candidate": candidate},
        )

    @classmethod
    def minio_object(
        cls,
        *,
        bucket: str,
        object_key: str,
        workspace_key: str | None = None,
        context_policy: str = "audit_only",
    ) -> "AgentResourceReference":
        """构造 MinIO 对象引用。"""

        return cls(
            kind=AgentResourceReferenceKind.MINIO_OBJECT,
            uri=f"minio://{quote(bucket, safe='')}/{quote(object_key.strip('/'), safe='/._-')}",
            workspace_key=workspace_key,
            context_policy=context_policy,
            attributes={"bucket": bucket, "objectKey": object_key},
        )

    @classmethod
    def from_uri(cls, uri: str, *, workspace_key: str | None = None) -> "AgentResourceReference":
        """从已有字符串 URI 解析为统一引用。

        该方法用于兼容当前 `outputRef` 字符串。解析只做类型识别和基础字段提取，不访问外部系统。
        """

        parts = urlsplit(uri)
        scheme = parts.scheme.lower()
        if scheme == "workspace":
            kind = AgentResourceReferenceKind.WORKSPACE_ARTIFACT if parts.netloc == "artifact" else AgentResourceReferenceKind.TOOL_OUTPUT
        elif scheme == "memory":
            kind = AgentResourceReferenceKind.MEMORY
        elif scheme == "minio":
            kind = AgentResourceReferenceKind.MINIO_OBJECT
        elif scheme == "agent-runtime":
            kind = AgentResourceReferenceKind.AGENT_RUNTIME
        else:
            kind = AgentResourceReferenceKind.EXTERNAL
        attributes: dict[str, Any] = {}
        if kind == AgentResourceReferenceKind.MINIO_OBJECT:
            attributes = {
                "bucket": parts.netloc,
                "objectKey": parts.path.lstrip("/"),
            }
        return cls(kind=kind, uri=uri, workspace_key=workspace_key, attributes=attributes)

"""Workspace 文件工具计划构建器。

`ToolPlanner` 负责整体工具编排，但文件读写有一组特殊安全要求：路径不能原样进入计划响应，写入正文不能进入
ToolPlan，workspaceReference 必须是低敏引用，缺少路径/内容时还要让参数校验器进入澄清或审批链路。

把这些逻辑拆到本文件，可以让 `ToolPlanner` 继续保持主流程清晰，也避免单文件超过 500 行。更重要的是，
未来 MCP tools/call、A2A artifact 操作或 Java worker 如果也要构造文件工具计划，可以复用这里的低敏
引用生成规则，而不是各自实现一套容易泄露路径/正文的参数拼装。
"""

from __future__ import annotations

import hashlib
from collections.abc import Callable, Mapping

from datasmart_ai_runtime.domain.contracts import AgentRequest, ToolDefinition, ToolPlan


ToolPlanFactory = Callable[[ToolDefinition, str, dict[str, object]], ToolPlan]


class WorkspaceFileToolPlanBuilder:
    """构建 `workspace.file.read/write` 的低敏工具计划。

    该 builder 不做真实文件 IO；真实读写由 `WorkspaceFileToolService` 在执行区处理。这里的职责只有：
    - 判断是否需要规划文件读/写工具；
    - 把真实相对路径压缩成 `filePathRef/filePathDigest`；
    - 把写入正文或正文引用压缩成 `contentRef`；
    - 生成 workspaceReference，用于后续 Java/Python 控制面对齐 workspace 边界。
    """

    def build(
        self,
        *,
        request: AgentRequest,
        objective: str,
        candidate_tools: set[str],
        tools: Mapping[str, ToolDefinition],
        plan_factory: ToolPlanFactory,
    ) -> tuple[ToolPlan, ...]:
        """构建文件工具计划。

        `plan_factory` 由 `ToolPlanner._build_plan` 注入，这样文件计划仍然复用统一的参数校验、审批判断和
        governance hints 组装逻辑，不会绕过现有工具治理链路。
        """

        plans: list[ToolPlan] = []
        workspace_file_path = self._resolve_workspace_file_path(request)
        if self._wants_workspace_file_read(request, objective, candidate_tools) and "workspace.file.read" in tools:
            plans.append(
                plan_factory(
                    tools["workspace.file.read"],
                    (
                        "用户目标需要读取 Agent workspace 内文件。规划阶段只生成低敏文件路径引用，"
                        "真实 relativePath 只能由受控 workspace 文件工具在执行区内部解析。"
                    ),
                    {
                        "workspaceReference": self._workspace_reference(request),
                        **self._workspace_file_path_reference(workspace_file_path),
                    },
                )
            )
        if self._wants_workspace_file_write(request, objective, candidate_tools) and "workspace.file.write" in tools:
            plans.append(
                plan_factory(
                    tools["workspace.file.write"],
                    (
                        "用户目标需要写入 Agent workspace 文件。写操作会产生副作用，必须进入权限、审批、"
                        "readiness 和 worker receipt 链路；计划响应只携带 contentRef，不携带写入正文。"
                    ),
                    {
                        "workspaceReference": self._workspace_reference(request),
                        **self._workspace_file_path_reference(workspace_file_path),
                        **self._workspace_file_content_reference(request),
                        "writeMode": "OVERWRITE" if self._truthy(request.variables.get("overwriteFile")) else "CREATE_ONLY",
                    },
                )
            )
        return tuple(plans)

    def _wants_workspace_file_read(self, request: AgentRequest, objective: str, candidate_tools: set[str]) -> bool:
        """判断用户是否需要读取 workspace 文件。"""

        operation = str(request.variables.get("fileOperation") or request.variables.get("file_operation") or "").upper()
        if "workspace.file.read" in candidate_tools or operation in {"READ", "READ_TEXT"}:
            return True
        return self._contains_any(
            objective,
            (
                "read file",
                "读取文件",
                "查看文件",
                "打开文件",
                "读取 workspace",
                "查看 workspace",
                "readme",
                "文档文件",
            ),
        )

    def _wants_workspace_file_write(self, request: AgentRequest, objective: str, candidate_tools: set[str]) -> bool:
        """判断用户是否需要写入 workspace 文件。"""

        operation = str(request.variables.get("fileOperation") or request.variables.get("file_operation") or "").upper()
        if "workspace.file.write" in candidate_tools or operation in {"WRITE", "WRITE_TEXT", "CREATE", "OVERWRITE"}:
            return True
        if request.variables.get("workspaceFileContent") is not None or request.variables.get("workspace_file_content") is not None:
            return True
        return self._contains_any(
            objective,
            (
                "write file",
                "create file",
                "update file",
                "写文件",
                "创建文件",
                "更新文件",
                "保存到文件",
                "写入 workspace",
            ),
        )

    @staticmethod
    def _resolve_workspace_file_path(request: AgentRequest) -> str | None:
        """读取调用方提供的 workspace 相对路径，但不把它原样写入 ToolPlan。"""

        variables = request.variables or {}
        value = (
            variables.get("workspaceFilePath")
            or variables.get("workspace_file_path")
            or variables.get("relativePath")
            or variables.get("relative_path")
            or variables.get("filePath")
            or variables.get("file_path")
        )
        text = str(value).strip() if value is not None else ""
        return text or None

    def _workspace_reference(self, request: AgentRequest) -> str:
        """生成低敏 workspace 引用，而不是宿主机真实路径。"""

        variables = request.variables or {}
        session_id = variables.get("agentRuntimeSessionId") or variables.get("sessionId") or "plan"
        return (
            f"agent-workspace:tenant-{self._safe_reference_part(request.tenant_id)}"
            f"/project-{self._safe_reference_part(request.project_id)}"
            f"/session-{self._safe_reference_part(session_id)}"
        )

    @staticmethod
    def _workspace_file_path_reference(relative_path: str | None) -> dict[str, object]:
        """把真实相对路径压缩为低敏引用和 digest。"""

        if not relative_path:
            return {}
        digest = hashlib.sha256(relative_path.replace("\\", "/").encode("utf-8")).hexdigest()
        return {
            "filePathRef": f"workspace-file-path-sha256:{digest[:24]}",
            "filePathDigest": digest[:24],
        }

    @staticmethod
    def _workspace_file_content_reference(request: AgentRequest) -> dict[str, object]:
        """把待写入正文或正文引用转换成低敏 contentRef。"""

        variables = request.variables or {}
        explicit_ref = variables.get("workspaceFileContentRef") or variables.get("workspace_file_content_ref")
        if explicit_ref:
            digest = hashlib.sha256(str(explicit_ref).encode("utf-8")).hexdigest()[:24]
            return {"contentRef": f"workspace-file-content-ref-sha256:{digest}"}
        content = variables.get("workspaceFileContent")
        if content is None:
            content = variables.get("workspace_file_content")
        if content is None:
            return {}
        text = str(content)
        digest = hashlib.sha256(text.encode("utf-8")).hexdigest()
        return {
            "contentRef": f"inline-content-sha256:{digest[:24]}",
            "contentByteCount": len(text.encode("utf-8")),
        }

    @staticmethod
    def _safe_reference_part(value: object) -> str:
        """裁剪 workspaceReference 片段，避免奇怪字符进入低敏引用。"""

        text = "".join(ch for ch in str(value) if ch.isalnum() or ch in {"-", "_", "."})
        return text[:64] or "unknown"

    @staticmethod
    def _truthy(value: object) -> bool:
        """解析常见布尔开关写法。"""

        return str(value).strip().lower() in {"1", "true", "yes", "y", "on"}

    @staticmethod
    def _contains_any(text: str, keywords: tuple[str, ...]) -> bool:
        """判断文本是否命中任一关键词。"""

        return any(keyword in text for keyword in keywords)


__all__ = ["WorkspaceFileToolPlanBuilder"]

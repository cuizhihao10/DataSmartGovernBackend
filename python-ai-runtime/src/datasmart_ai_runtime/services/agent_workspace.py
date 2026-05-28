"""Agent 工作空间上下文构建器。

真实 Codex / Claude Code 类 Agent 不只是“调用模型 + 调工具”，还必须有清晰的运行边界：
- 当前任务属于哪个租户、项目、工作空间或会话；
- 工具输出、临时文件、长期记忆、模型缓存应该落在哪个 namespace；
- 哪些隔离边界不能跨越，例如不能把 A 项目的数据源元数据写进 B 项目的记忆。

Java `agent-runtime` 已经有 `AgentWorkspaceView` 和 `WorkspaceIsolationLevel`。本文件补齐 Python
Runtime 侧的同类语义，让模型规划、记忆候选、缓存治理和未来 Python 工具/Skill 执行都能复用同一
工作空间契约，而不是各自临时拼接路径或 key。
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Any

from datasmart_ai_runtime.domain.contracts import AgentRequest


class AgentWorkspaceIsolationLevel(str, Enum):
    """Agent 工作空间隔离等级。

    - `PROJECT`：默认模式，同一租户同一项目共享治理上下文，适合项目级数据源、规则、任务草案；
    - `WORKSPACE`：在项目下再细分协作空间，适合多人实验区、客户实施空间、专项治理空间；
    - `SESSION`：每个会话独占空间，适合高敏感数据、临时导出、未审批工具输出和一次性实验。

    这里不提供 `GLOBAL`，是刻意的安全选择。DataSmart 是企业数据治理平台，Python Runtime 不应
    默认创建跨租户/跨项目可见的 Agent 工作空间。真正全局知识应由管理员审批后进入受控知识库。
    """

    PROJECT = "PROJECT"
    WORKSPACE = "WORKSPACE"
    SESSION = "SESSION"


@dataclass(frozen=True)
class AgentWorkspaceContext:
    """一次 Agent 请求的工作空间上下文。

    字段说明：
    - `workspace_key`：稳定逻辑隔离键，后续可作为 Redis key、MinIO prefix、Chroma collection metadata、
      Neo4j 子图标签或审计查询条件；
    - `isolation_level`：本次隔离等级，决定 namespace 粒度；
    - `cache_namespace`：模型 prefix/KV cache 或上下文缓存的安全 namespace；
    - `memory_namespace`：长期记忆候选、检索和写入 worker 的命名空间；
    - `artifact_namespace`：工具输出、报告、SQL 草案、日志片段等资源文件的命名空间；
    - `recommended_actions`：给前端、网关或运维的提示，说明当前边界下还需要哪些保护。
    """

    workspace_key: str
    isolation_level: AgentWorkspaceIsolationLevel
    tenant_id: str
    project_id: str
    workspace_id: str | None
    session_id: str | None
    cache_namespace: str
    memory_namespace: str
    artifact_namespace: str
    recommended_actions: tuple[str, ...] = ()

    def to_summary(self) -> dict[str, Any]:
        """转换为 API 可返回的工作空间摘要。"""

        return {
            "workspaceKey": self.workspace_key,
            "isolationLevel": self.isolation_level.value,
            "tenantId": self.tenant_id,
            "projectId": self.project_id,
            "workspaceId": self.workspace_id,
            "sessionId": self.session_id,
            "cacheNamespace": self.cache_namespace,
            "memoryNamespace": self.memory_namespace,
            "artifactNamespace": self.artifact_namespace,
            "recommendedActions": self.recommended_actions,
        }

    def to_governance_hints(self) -> dict[str, Any]:
        """转换为可写入 ToolPlan 的治理提示。

        顶层 `agentWorkspace` 适合前端和网关快速读取，但真正的工具执行链路通常只拿到单个
        `ToolPlan`。因此每个 ToolPlan 也需要携带轻量 workspace hints，让 Java 控制面、审计记录、
        工具执行器和输出引用解析器都能在不回查顶层响应的情况下判断隔离边界。

        注意这里不包含 `recommendedActions`，因为治理提示会进入审计、事件和可能的工具执行 payload。
        为了控制体积和避免把人读说明扩散到机器协议里，只保留稳定机器字段。
        """

        return {
            "workspaceKey": self.workspace_key,
            "workspaceIsolationLevel": self.isolation_level.value,
            "workspaceId": self.workspace_id,
            "workspaceSessionId": self.session_id,
            "cacheNamespace": self.cache_namespace,
            "memoryNamespace": self.memory_namespace,
            "artifactNamespace": self.artifact_namespace,
        }


class AgentWorkspaceContextBuilder:
    """根据 AgentRequest 构建工作空间上下文。

    该类不创建真实目录、桶、collection 或图数据库标签，只生成稳定命名空间。这样做有两个好处：
    - 当前阶段不会引入 MinIO/Chroma/Neo4j 的强依赖，单元测试和本地学习仍然轻量；
    - 后续一旦接入真实工具执行沙箱，可以把这些 namespace 作为唯一入口，避免工具自己拼路径。
    """

    def build(self, request: AgentRequest) -> AgentWorkspaceContext:
        """构建一次请求的工作空间上下文。

        `AgentRequest.variables` 支持以下兼容键：
        - `isolationLevel` / `isolation_level`：PROJECT、WORKSPACE、SESSION；
        - `workspaceId` / `workspace_id`：项目下的协作空间 ID；
        - `sessionId` / `agentRuntimeSessionId`：会话 ID。
        """

        variables = request.variables or {}
        isolation_level = self._isolation_level(variables)
        workspace_id = self._optional_text(variables.get("workspaceId") or variables.get("workspace_id"))
        session_id = self._optional_text(variables.get("agentRuntimeSessionId") or variables.get("sessionId"))
        workspace_key = self._workspace_key(
            tenant_id=request.tenant_id,
            project_id=request.project_id,
            workspace_id=workspace_id,
            session_id=session_id,
            isolation_level=isolation_level,
        )
        return AgentWorkspaceContext(
            workspace_key=workspace_key,
            isolation_level=isolation_level,
            tenant_id=request.tenant_id,
            project_id=request.project_id,
            workspace_id=workspace_id,
            session_id=session_id,
            cache_namespace=f"cache:{workspace_key}",
            memory_namespace=f"memory:{workspace_key}",
            artifact_namespace=f"artifact:{workspace_key}",
            recommended_actions=self._recommended_actions(isolation_level, workspace_id, session_id),
        )

    @staticmethod
    def _isolation_level(variables: dict[str, Any]) -> AgentWorkspaceIsolationLevel:
        """解析隔离等级，非法值回退 PROJECT 并由推荐动作提示后续治理。"""

        raw_value = str(variables.get("isolationLevel") or variables.get("isolation_level") or "PROJECT").strip().upper()
        try:
            return AgentWorkspaceIsolationLevel(raw_value)
        except ValueError:
            return AgentWorkspaceIsolationLevel.PROJECT

    @staticmethod
    def _workspace_key(
        *,
        tenant_id: str,
        project_id: str,
        workspace_id: str | None,
        session_id: str | None,
        isolation_level: AgentWorkspaceIsolationLevel,
    ) -> str:
        """生成与 Java agent-runtime 对齐的逻辑工作空间键。"""

        workspace_part = workspace_id or "default"
        if isolation_level == AgentWorkspaceIsolationLevel.PROJECT:
            return f"tenant:{tenant_id}:project:{project_id}"
        if isolation_level == AgentWorkspaceIsolationLevel.WORKSPACE:
            return f"tenant:{tenant_id}:project:{project_id}:workspace:{workspace_part}"
        return f"tenant:{tenant_id}:project:{project_id}:workspace:{workspace_part}:session:{session_id or 'ephemeral'}"

    @staticmethod
    def _recommended_actions(
        isolation_level: AgentWorkspaceIsolationLevel,
        workspace_id: str | None,
        session_id: str | None,
    ) -> tuple[str, ...]:
        """根据隔离等级给出运维和产品提示。"""

        actions: list[str] = [
            "后续工具执行、记忆写入、模型缓存和文件输出应继承该 workspaceKey，避免跨租户或跨项目复用上下文。",
        ]
        if isolation_level == AgentWorkspaceIsolationLevel.WORKSPACE and not workspace_id:
            actions.append("当前使用 WORKSPACE 隔离但未提供 workspaceId，已落到项目默认工作空间；生产审批台应提示用户选择明确工作空间。")
        if isolation_level == AgentWorkspaceIsolationLevel.SESSION and not session_id:
            actions.append("当前使用 SESSION 隔离但未提供 sessionId，已使用 ephemeral 会话键；生产环境应由 gateway 或 Java 控制面分配稳定 sessionId。")
        if isolation_level == AgentWorkspaceIsolationLevel.PROJECT:
            actions.append("PROJECT 隔离适合项目级治理知识；如果任务包含导出样本、敏感字段或未审批工具输出，应切换到 SESSION 隔离。")
        return tuple(actions)

    @staticmethod
    def _optional_text(value: Any) -> str | None:
        """把可选变量规范化为非空字符串。"""

        text = str(value).strip() if value is not None else ""
        return text or None

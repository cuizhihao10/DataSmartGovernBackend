"""长期记忆写入链路的 workspace 绑定与一致性校验。

DataSmart 的 Agent 请求已经会生成 `workspaceKey`、`memoryNamespace`、`artifactNamespace` 等治理提示。
但如果长期记忆候选没有继承这些字段，后续正式记忆 store 只能按 tenant/project 过滤，同一项目下的专项
工作空间、会话沙箱和默认项目空间就可能错误共享上下文。

本文件把 workspace 解析与校验从候选治理、materializer 和 retriever 中拆出来。这样不同写入入口都使用
同一套规则，不会在 API、后台 worker、补偿任务和未来 Kafka consumer 中各自拼接 namespace。
"""

from __future__ import annotations

from dataclasses import dataclass

from datasmart_ai_runtime.domain.contracts import AgentRequest, ToolPlan
from datasmart_ai_runtime.domain.memory import AgentMemoryWriteCandidate
from datasmart_ai_runtime.services.agent_workspace import AgentWorkspaceContextBuilder


@dataclass(frozen=True)
class AgentMemoryWorkspaceBinding:
    """候选或正式记忆使用的工作空间绑定。

    字段说明：
    - `workspace_key`：稳定隔离键，例如 `tenant:10:project:20:workspace:30`；
    - `memory_namespace`：正式记忆命名空间，固定为 `memory:{workspaceKey}`；
    - `source`：说明绑定来自 ToolPlan hints 还是请求默认上下文，便于审计和调试。
    """

    workspace_key: str
    memory_namespace: str
    source: str


class AgentMemoryWorkspaceSupport:
    """统一生成并校验长期记忆 workspace 绑定。"""

    @staticmethod
    def bind(request: AgentRequest, tool_plan: ToolPlan) -> AgentMemoryWorkspaceBinding:
        """根据请求和 ToolPlan 生成候选 workspace。

        `api/agent/plan_response` 会把服务端构造的 workspace hints 覆盖写入每个 ToolPlan。这里仍然重新根据
        `AgentRequest` 计算一次 canonical 上下文并做一致性校验，原因是治理服务也可能被其他入口直接调用。
        如果调用方伪造或混入其他 workspace hints，必须在候选形成前 fail-closed。
        """

        context = AgentWorkspaceContextBuilder().build(request)
        hinted_workspace_key = AgentMemoryWorkspaceSupport._optional_text(
            tool_plan.governance_hints.get("workspaceKey")
        )
        hinted_memory_namespace = AgentMemoryWorkspaceSupport._optional_text(
            tool_plan.governance_hints.get("memoryNamespace")
        )
        if hinted_workspace_key and hinted_workspace_key != context.workspace_key:
            raise ValueError(
                "ToolPlan workspaceKey 与当前 AgentRequest 工作空间不一致，禁止生成跨空间长期记忆候选。"
            )
        if hinted_memory_namespace and hinted_memory_namespace != context.memory_namespace:
            raise ValueError(
                "ToolPlan memoryNamespace 与当前 AgentRequest 工作空间不一致，禁止生成跨空间长期记忆候选。"
            )
        return AgentMemoryWorkspaceBinding(
            workspace_key=context.workspace_key,
            memory_namespace=context.memory_namespace,
            source="tool-plan-hints" if hinted_workspace_key or hinted_memory_namespace else "agent-request-default",
        )

    @staticmethod
    def from_request(request: AgentRequest) -> AgentMemoryWorkspaceBinding:
        """为正式记忆检索生成当前请求的 workspace 绑定。

        检索必须使用与写入相同的 namespace 构造器。否则写入按 workspace 隔离、读取却只按项目过滤，
        仍然会产生跨空间误召回。
        """

        context = AgentWorkspaceContextBuilder().build(request)
        return AgentMemoryWorkspaceBinding(
            workspace_key=context.workspace_key,
            memory_namespace=context.memory_namespace,
            source="agent-request",
        )

    @staticmethod
    def validate_candidate(candidate: AgentMemoryWriteCandidate) -> AgentMemoryWorkspaceBinding:
        """校验一个待落成候选是否携带可信 workspace。

        历史候选可能没有这两个字段。第一阶段 materializer 对历史空值选择阻断，而不是猜测性回填：
        后台补偿工具后续可以基于原始 run/audit 重新计算并人工确认 namespace，避免旧数据进入错误空间。
        """

        workspace_key = AgentMemoryWorkspaceSupport._required_text(candidate.workspace_key, "workspaceKey")
        memory_namespace = AgentMemoryWorkspaceSupport._required_text(
            candidate.memory_namespace,
            "memoryNamespace",
        )
        if memory_namespace != f"memory:{workspace_key}":
            raise ValueError("memoryNamespace 必须与 workspaceKey 一致，禁止把候选写入不匹配的长期记忆空间。")
        expected_prefix = f"tenant:{candidate.tenant_id}:project:{candidate.project_id}"
        if not workspace_key.startswith(expected_prefix):
            raise ValueError("workspaceKey 不属于当前候选 tenant/project，禁止跨范围写入长期记忆。")
        return AgentMemoryWorkspaceBinding(
            workspace_key=workspace_key,
            memory_namespace=memory_namespace,
            source="candidate",
        )

    @staticmethod
    def _required_text(value: str | None, field_name: str) -> str:
        """读取必填文本，并为补偿台返回清晰错误。"""

        text = AgentMemoryWorkspaceSupport._optional_text(value)
        if not text:
            raise ValueError(f"长期记忆候选缺少 {field_name}，请先补齐工作空间证据再执行正式写入。")
        return text

    @staticmethod
    def _optional_text(value: object | None) -> str | None:
        """把可选值规范化为非空字符串。"""

        text = str(value).strip() if value is not None else ""
        return text or None

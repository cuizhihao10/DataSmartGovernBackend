"""工具动作 checkpoint 相关路由注册。

把 checkpoint 查询/恢复预检路由从主 `routes.py` 拆出来，是为了保持 Agent API 入口文件可读：
- `routes.py` 负责装配总入口和核心 plan/event/websocket 路由；
- 本模块只负责工具动作 checkpoint 的两个只读/预览路由；
- 后续如果 checkpoint 扩展到 Redis/MySQL、管理员审计、Prometheus 指标或权限中间件，也可以继续在本模块内演进。
"""

from __future__ import annotations

from typing import Any

from datasmart_ai_runtime.api.agent.tool_action_execution_checkpoint import (
    build_tool_action_execution_checkpoint_query_response,
    build_tool_action_execution_checkpoint_resume_preview_response,
)
from datasmart_ai_runtime.services.tools import ToolActionExecutionCheckpointStore


def register_tool_action_checkpoint_routes(
    app: Any,
    *,
    checkpoint_store: ToolActionExecutionCheckpointStore | None = None,
    resume_fact_provider: Any | None = None,
) -> None:
    """注册工具动作 checkpoint 查询与恢复预检路由。

    路由设计：
    - `/agent/tool-actions/checkpoints/query`：按 checkpointId 或 threadId 读取低敏 checkpoint；
    - `/agent/tool-actions/checkpoints/resume-preview`：检查审批/澄清/预算/outbox 等恢复事实是否齐备。

    两个路由都不是生产执行入口，不会执行工具、不写 outbox、不派发 worker。
    """

    @app.post("/agent/tool-actions/checkpoints/query")
    def query_tool_action_checkpoints(payload: dict[str, Any]) -> dict[str, Any]:
        """查询工具动作执行前图 checkpoint。

        调用方必须提供 checkpointId 或 threadId；当前不允许全局扫描。
        这能避免 checkpoint 查询接口在尚未接入完整认证前被误用成跨租户枚举接口。
        """

        return build_tool_action_execution_checkpoint_query_response(
            payload,
            checkpoint_store=checkpoint_store,
        )

    @app.post("/agent/tool-actions/checkpoints/resume-preview")
    def preview_tool_action_checkpoint_resume(payload: dict[str, Any]) -> dict[str, Any]:
        """预检 checkpoint 是否具备恢复执行图的事实条件。

        该接口只判断事实是否齐备，例如 approvalConfirmationId、clarificationFactId、
        payloadReference、policyVersion、outboxConfirmationId 等，不消费事实值、不执行任何副作用。
        """

        return build_tool_action_execution_checkpoint_resume_preview_response(
            payload,
            checkpoint_store=checkpoint_store,
            resume_fact_provider=resume_fact_provider,
        )

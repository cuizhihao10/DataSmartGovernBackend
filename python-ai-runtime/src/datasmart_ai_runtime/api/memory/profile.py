"""用户画像记忆 API 路由。

这些路由面向智能网关、管理台和本地学习脚本，主要用于观察用户画像能力是否工作、预览一次请求会抽取
哪些低敏偏好事实，以及在候选事实需要人工确认时执行激活/拒绝。它们不会执行工具、不会写 Java outbox、
不会返回用户原始 prompt、SQL、工具参数、样本数据或模型输出。
"""

from __future__ import annotations

from typing import Any

from datasmart_ai_runtime.domain.contracts import AgentRequest


def register_user_profile_routes(app: Any, user_profile_memory: Any) -> None:
    """注册用户画像记忆相关路由。

    参数说明：
    - `app`：FastAPI 应用实例。这里使用 Any 是为了避免核心运行时在未安装 FastAPI 时导入失败；
    - `user_profile_memory`：实现 `UserProfileMemoryService` 行为的对象，便于测试和未来替换持久化实现。
    """

    @app.get("/agent/memory/user-profile/diagnostics")
    @app.get("/api/agent/memory/user-profile/diagnostics")
    def user_profile_diagnostics() -> dict[str, Any]:
        """查询用户画像运行时诊断。

        返回内容只包含 store 类型、事实计数、状态分布、策略摘要和安全边界，不返回任何画像正文之外的
        用户原始输入。
        """

        return user_profile_memory.diagnostics()

    @app.post("/agent/memory/user-profile/extract-preview")
    @app.post("/api/agent/memory/user-profile/extract-preview")
    def preview_user_profile_extraction(payload: dict[str, Any]) -> dict[str, Any]:
        """预览并记录一次用户画像抽取结果。

        该接口适合前端设置页或本地调试确认“系统会从当前目标识别出哪些偏好”。当前实现会复用主链路的
        观察逻辑，因此低敏候选会进入内存 store；后续如需纯 dry-run，可在 payload 增加 `dryRun=true`。
        """

        return user_profile_memory.extract_preview(payload)

    @app.post("/agent/memory/user-profile/snapshot")
    @app.post("/api/agent/memory/user-profile/snapshot")
    def query_user_profile_snapshot(payload: dict[str, Any]) -> dict[str, Any]:
        """查询指定 tenant/project/actor/workspace 范围的画像快照。"""

        return user_profile_memory.snapshot(_request_from_payload(payload))

    @app.post("/agent/memory/user-profile/facets/{facet_id}/activate")
    @app.post("/api/agent/memory/user-profile/facets/{facet_id}/activate")
    def activate_user_profile_facet(facet_id: str, payload: dict[str, Any]) -> dict[str, Any]:
        """激活一条画像候选事实。

        激活后，该事实才会作为 active 画像进入后续 Agent 上下文。operatorId 和 reason 会作为低敏审计
        元数据保存，便于后续解释“为什么某条偏好影响了规划”。
        """

        return user_profile_memory.activate(
            facet_id,
            operator_id=str(payload.get("operatorId") or payload.get("operator_id") or "system"),
            reason=str(payload.get("reason") or "manual_activate_user_profile_facet"),
        )

    @app.post("/agent/memory/user-profile/facets/{facet_id}/reject")
    @app.post("/api/agent/memory/user-profile/facets/{facet_id}/reject")
    def reject_user_profile_facet(facet_id: str, payload: dict[str, Any]) -> dict[str, Any]:
        """拒绝一条画像事实。"""

        return user_profile_memory.reject(
            facet_id,
            operator_id=str(payload.get("operatorId") or payload.get("operator_id") or "system"),
            reason=str(payload.get("reason") or "manual_reject_user_profile_facet"),
        )


def _request_from_payload(payload: dict[str, Any]) -> AgentRequest:
    """从 API payload 构造最小 AgentRequest。

    这里只用于 scope 解析，不要求调用方提供真实 objective；如果提供，也不会在快照响应中返回。
    """

    return AgentRequest(
        tenant_id=str(payload.get("tenantId") or payload.get("tenant_id") or "default-tenant"),
        project_id=str(payload.get("projectId") or payload.get("project_id") or "default-project"),
        actor_id=str(payload.get("actorId") or payload.get("actor_id") or "default-actor"),
        objective=str(payload.get("objective") or ""),
        variables=dict(payload.get("variables") or {}),
    )

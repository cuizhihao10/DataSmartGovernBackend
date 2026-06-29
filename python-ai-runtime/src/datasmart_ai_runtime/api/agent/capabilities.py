"""Agent 能力完备度诊断路由。

该路由暴露的是 Agent Host 能力闭环矩阵，不是实时执行接口。它不会调用模型、不会执行工具、不会读取记忆
正文，也不会访问业务库。它的价值是让项目收口阶段有一个稳定的检查入口：哪些能力已经部分闭环，哪些
仍只是控制面合同，哪些还没开始，下一步该按什么顺序补齐。
"""

from __future__ import annotations

from typing import Any

from datasmart_ai_runtime.services.agent_capability import AgentCapabilityMatrixService


def register_agent_capability_routes(
    app: Any,
    *,
    capability_matrix_service: AgentCapabilityMatrixService,
) -> None:
    """注册 Agent 能力完备度诊断路由。

    路由设计：
    - `/agent/capabilities/diagnostics`：Python Runtime 直连和本地学习使用；
    - `/api/agent/capabilities/diagnostics`：预留给统一 gateway、管理台和上线前检查使用。
    - `/agent/capabilities/closure-readiness`：返回更短的项目闭口门禁视图；
    - `/api/agent/capabilities/closure-readiness`：给 gateway 或发布检查流水线使用的同等路径。

    返回边界：
    - 只返回能力域、子能力、状态、归属模块、闭环缺口、性能与安全关注点；
    - 不返回 prompt、messages、SQL、工具参数、文件正文、记忆正文、模型输出、凭据或内部 endpoint；
    - 不触发任何副作用，因此可以安全用于健康检查、路线图复盘和发布门禁。
    """

    @app.get("/agent/capabilities/diagnostics")
    def agent_capability_diagnostics() -> dict[str, Any]:
        """查询 Agent 能力完备度矩阵。"""

        return capability_matrix_service.diagnostics()

    @app.get("/api/agent/capabilities/diagnostics")
    def agent_capability_diagnostics_from_gateway() -> dict[str, Any]:
        """通过统一网关路径查询同一份 Agent 能力完备度矩阵。"""

        return capability_matrix_service.diagnostics()

    @app.get("/agent/capabilities/closure-readiness")
    def agent_capability_closure_readiness() -> dict[str, Any]:
        """查询 Agent 能力最终闭口门禁。

        该接口比完整 diagnostics 更适合发布前检查：它不展开所有子能力，而是聚焦 P0 是否还有
        planned/blocked、control-plane-only 和 partial-closed-loop 缺口。调用者可以据此判断当前工作
        应继续补核心闭环，还是进入最终硬化与收尾。
        """

        return capability_matrix_service.closure_readiness()

    @app.get("/api/agent/capabilities/closure-readiness")
    def agent_capability_closure_readiness_from_gateway() -> dict[str, Any]:
        """通过统一网关路径查询同一份 Agent 能力闭口门禁。"""

        return capability_matrix_service.closure_readiness()


__all__ = ["register_agent_capability_routes"]

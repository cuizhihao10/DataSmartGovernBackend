"""平台收敛诊断 HTTP 路由。

路由层只做一件事：把 `PlatformConvergenceDiagnosticsService` 生成的低敏收敛地图暴露为只读 API。
这样 `api/app.py` 不需要继续堆 handler，也不会把“项目规划判断”散落到 Agent 规划、模型网关或工具执行
相关文件中。
"""

from __future__ import annotations

from typing import Any

from datasmart_ai_runtime.services.platform_convergence import PlatformConvergenceDiagnosticsService


def register_platform_convergence_routes(
    app: Any,
    *,
    diagnostics_service: PlatformConvergenceDiagnosticsService,
) -> None:
    """注册平台收敛诊断路由。

    参数说明：
    - `app`：FastAPI 应用或测试用 FakeApp，只要求提供 `get(path)` 装饰器；
    - `diagnostics_service`：启动阶段装配好的诊断服务，当前是内存只读基线，未来可替换为数据库/配置中心实现。

    路由设计：
    - `/agent/platform/convergence/diagnostics`：Python Runtime 直连、本地学习和服务内诊断使用；
    - `/api/agent/platform/convergence/diagnostics`：预留给统一 gateway 代理和管理台使用。

    低敏边界：
    - 不读取数据库，不调用模型，不执行工具，不触发 runtime event；
    - 不返回真实任务、真实租户、工具实参、用户输入、模型响应、凭证或内部服务地址；
    - 只返回模块成熟度、闭环缺口、退出条件和推荐动作。
    """

    @app.get("/agent/platform/convergence/diagnostics")
    def platform_convergence_diagnostics() -> dict[str, Any]:
        """查询平台级收敛诊断。

        这个接口面向项目管理台、智能网关、开发者自检和后续发布门禁。它的返回值不是实时健康检查，
        而是“当前项目离商业闭环还差什么”的结构化地图。
        """

        return diagnostics_service.diagnostics()

    @app.get("/api/agent/platform/convergence/diagnostics")
    def platform_convergence_diagnostics_from_gateway() -> dict[str, Any]:
        """通过统一网关路径查询同一份平台收敛诊断。

        保留 gateway 前缀的原因是后续前端或 Java gateway 统一代理时无需再改变 Python Runtime 路径设计。
        两个入口刻意返回相同结果，避免直连诊断和网关诊断产生两套口径。
        """

        return diagnostics_service.diagnostics()


__all__ = ["register_platform_convergence_routes"]

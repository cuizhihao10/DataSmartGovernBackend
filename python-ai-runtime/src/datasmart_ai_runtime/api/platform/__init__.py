"""平台级 API 路由包。

这里放置不属于单个 Agent、记忆、模型网关或工具动作的跨模块控制面接口。当前的收敛诊断路由就是第一类
平台级接口：它服务整体项目闭环，而不是某个局部能力。
"""

from datasmart_ai_runtime.api.platform.convergence import register_platform_convergence_routes

__all__ = ["register_platform_convergence_routes"]

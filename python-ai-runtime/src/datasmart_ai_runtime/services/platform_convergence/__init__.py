"""平台收敛服务包。

该包放置跨模块、跨技术栈的收敛诊断能力。它不属于某个单一业务微服务，而是帮助 DataSmart 在进入
项目收口阶段后持续回答：哪些模块已经达到本轮退出条件，哪些模块还阻塞端到端商业闭环。
"""

from datasmart_ai_runtime.services.platform_convergence.platform_convergence_diagnostics import (
    ConvergencePhase,
    PlatformConvergenceDiagnosticsService,
    PlatformConvergenceDomainStatus,
    default_platform_convergence_diagnostics_service,
)

__all__ = [
    "ConvergencePhase",
    "PlatformConvergenceDiagnosticsService",
    "PlatformConvergenceDomainStatus",
    "default_platform_convergence_diagnostics_service",
]

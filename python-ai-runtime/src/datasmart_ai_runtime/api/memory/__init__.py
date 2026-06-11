"""长期记忆 HTTP 管理与诊断适配包。

长期记忆的候选、正式记忆、物化 runner、审计 outbox 和指标仍在 `services.memory` 中实现。
本包只负责把这些能力暴露为 API 启动装配、候选治理路由、分页策略和管理端补偿接口。
"""

from datasmart_ai_runtime.api.memory.materialization_admin import register_memory_materialization_admin_routes
from datasmart_ai_runtime.api.memory.runtime import api_memory_runtime_diagnostics, build_api_memory_runtime
from datasmart_ai_runtime.api.memory.write import register_memory_write_routes
from datasmart_ai_runtime.api.memory.write_pagination import paginate_memory_write_candidates

__all__ = [
    "api_memory_runtime_diagnostics",
    "build_api_memory_runtime",
    "paginate_memory_write_candidates",
    "register_memory_materialization_admin_routes",
    "register_memory_write_routes",
]

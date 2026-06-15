"""Python AI Runtime HTTP/API 层聚合入口。

这个包取代历史上的根目录 `api.py` 单文件入口。之所以把 `api.py` 升级成 `api/` 包，
是为了把 FastAPI 应用装配、Agent 路由、智能网关安全、长期记忆管理、Runtime Event
协议和模型网关治理拆到不同能力目录中，避免根包继续堆积 `api_*.py` 文件。

兼容策略：
- 外部仍然可以使用 `from datasmart_ai_runtime.api import create_app`；
- 历史测试仍然可以从该聚合入口读取常用 helper；
- 新增 HTTP 能力应优先放入 `datasmart_ai_runtime.api.<capability>` 子包，而不是重新在根包平铺文件。
"""

from typing import Any


_EXPORTS: dict[str, str] = {
    "build_context_selection_policy": "datasmart_ai_runtime.api.app",
    "build_default_orchestrator": "datasmart_ai_runtime.api.app",
    "build_event_control_response": "datasmart_ai_runtime.api.app",
    "build_event_replay_response": "datasmart_ai_runtime.api.app",
    "build_event_websocket_payloads": "datasmart_ai_runtime.api.app",
    "build_plan_response": "datasmart_ai_runtime.api.app",
    "build_tool_action_resume_fact_provider": "datasmart_ai_runtime.api.app",
    "build_tool_call_budget_policy_provider": "datasmart_ai_runtime.api.app",
    "build_tool_execution_readiness_policy_provider": "datasmart_ai_runtime.api.app",
    "create_app": "datasmart_ai_runtime.api.app",
    "load_skill_registry": "datasmart_ai_runtime.api.app",
    "load_tool_registry": "datasmart_ai_runtime.api.app",
}


def __getattr__(name: str) -> Any:
    """按需加载历史聚合入口导出的 helper。

    `datasmart_ai_runtime.api` 现在是一个包。包级 `__init__` 如果直接导入 `app.py`，
    那么调用方即使只想导入 `datasmart_ai_runtime.api.agent.plan_response`，也会先触发
    FastAPI app 装配相关依赖，容易产生循环导入和不必要的启动成本。

    因此这里使用 PEP 562 的模块级 `__getattr__` 做懒加载：
    - 老代码仍然可以 `from datasmart_ai_runtime.api import create_app`；
    - 新代码可以直接导入 `datasmart_ai_runtime.api.agent.routes` 等能力包；
    - 包初始化不会主动加载可选 HTTP 依赖或整个 Agent bootstrap。
    """

    module_name = _EXPORTS.get(name)
    if module_name is None:
        raise AttributeError(f"module 'datasmart_ai_runtime.api' has no attribute {name!r}")
    from importlib import import_module

    value = getattr(import_module(module_name), name)
    globals()[name] = value
    return value

__all__ = [
    "build_context_selection_policy",
    "build_default_orchestrator",
    "build_event_control_response",
    "build_event_replay_response",
    "build_event_websocket_payloads",
    "build_plan_response",
    "build_tool_action_resume_fact_provider",
    "build_tool_call_budget_policy_provider",
    "build_tool_execution_readiness_policy_provider",
    "create_app",
    "load_skill_registry",
    "load_tool_registry",
]

"""FastAPI 生命周期注册兼容层。

本模块只处理一件事：把 startup/shutdown 回调注册到 FastAPI 应用上。

为什么要单独拆出来：
- `api/app.py` 是 Python Runtime 的 HTTP 应用装配入口，已经需要组织 Agent、工具、Skill、长期记忆、
  Runtime Event、模型网关等很多业务组件。如果把 Web 框架版本兼容也塞在里面，文件会继续膨胀。
- 真实 E2E 环境安装 `python-ai-runtime[api]` 时可能拿到比开发时更新的 FastAPI/Starlette。
  新旧版本的生命周期 API 有差异，应该由一个小适配层吸收，而不是让业务代码到处判断框架版本。
- 生命周期回调通常会启动后台 worker、刷新低敏诊断缓存或做健康探测；这些动作必须保持幂等、低敏、
  可观察，因此把注册规则集中起来更利于后续审计。
"""

from __future__ import annotations

from typing import Any, Callable


def register_lifecycle_handler(app: Any, event: str, handler: Callable[[], None]) -> None:
    """兼容 FastAPI 新旧版本注册生命周期回调。

    参数说明：
    - `app`：FastAPI 应用实例。这里使用 `Any`，避免核心领域包在未安装 FastAPI 时也被迫导入框架类型。
    - `event`：生命周期阶段，目前只允许 `startup` 或 `shutdown`，避免未知阶段被静默忽略。
    - `handler`：无参回调。回调内部应保持幂等，例如 worker 重复 start 不应创建多个后台线程。

    兼容策略：
    1. 优先使用旧版 FastAPI 的 `app.add_event_handler(...)`，兼容当前大量示例和历史代码。
    2. 如果应用实例上没有该方法，退到 `app.router.add_event_handler(...)`。
    3. 如果 router 也没有注册方法，但仍暴露 `on_startup/on_shutdown` 列表，则直接追加 handler。

    这样做的商业化意义：
    - 部署方升级 FastAPI 时不必立刻修改 Agent、记忆、模型网关等业务组件。
    - 从零安装依赖后的真实 E2E 启动不再因为生命周期 API 微变动而失败。
    - 后续如果切换到 lifespan context，也可以继续在本模块集中演进。
    """

    if event not in {"startup", "shutdown"}:
        raise ValueError(f"Unsupported FastAPI lifecycle event: {event}")

    add_event_handler = getattr(app, "add_event_handler", None)
    if callable(add_event_handler):
        add_event_handler(event, handler)
        return

    router = getattr(app, "router", None)
    router_add_event_handler = getattr(router, "add_event_handler", None)
    if callable(router_add_event_handler):
        router_add_event_handler(event, handler)
        return

    lifecycle_handlers = getattr(router, "on_startup" if event == "startup" else "on_shutdown", None)
    if isinstance(lifecycle_handlers, list):
        lifecycle_handlers.append(handler)
        return

    raise RuntimeError("当前 FastAPI/Starlette 版本不支持已知的生命周期回调注册方式。")

"""命令 worker 租约结果的低敏展示策略。"""

from __future__ import annotations

from typing import Any


def message_for(state: Any) -> str:
    """按稳定状态码返回不包含命令、路径或输出的说明。"""

    return {
        "acquired": "命令租约领取成功，当前 worker 可以继续执行后续低敏预检。",
        "already_held_by_caller": "同一 worker 已持有租约，本次领取按幂等重试处理。",
        "already_held_by_other": "命令租约正由其他 worker 持有，当前 worker 必须停止处理。",
        "renewed": "命令租约续租成功，当前 worker 仍可继续处理。",
        "released": "命令租约已释放，后续 worker 可以在需要时重新领取。",
        "rejected": "worker 身份或 fencing token 不匹配，禁止继续处理或写回 receipt。",
        "expired": "命令租约已过期，旧 worker 必须停止并重新进入领取流程。",
        "not_found": "未找到命令租约，调用方需要先领取再续租或释放。",
    }[state.value.lower()]


def recommended_actions_for(state: Any) -> tuple[str, ...]:
    """返回控制面建议，不触发任何 worker 或租约副作用。"""

    return {
        "acquired": ("继续执行 worker precheck，并在写回 receipt 前携带 fencingToken。",),
        "already_held_by_caller": ("复用已有 fencingToken，避免重复生成执行事实。",),
        "already_held_by_other": ("停止当前 worker 处理，等待队列 visibility timeout 或调度器重试。",),
        "renewed": ("继续处理并在长任务窗口内按需再次续租。",),
        "released": ("结束当前 worker 生命周期，保留低敏审计摘要。",),
        "rejected": ("丢弃当前处理结果，不允许写回 command worker receipt。",),
        "expired": ("停止旧执行上下文，重新领取租约后再继续。",),
        "not_found": ("先执行 acquire，再进入 worker precheck 或 runner。",),
    }[state.value.lower()]

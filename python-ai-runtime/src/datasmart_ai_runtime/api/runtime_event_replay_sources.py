"""Runtime Event replay source 装配辅助。

Python Runtime 可以把 Java `agent-runtime` 中已经物化的运行时事件作为二轮规划、断线续传或审计回放的
输入来源。但这条远程链路不应该在本地学习环境默认打开，否则没有启动 Java 控制面时会拖慢 API 启动，
甚至让只读诊断接口被外部依赖阻塞。

本模块把“是否启用 replay source、使用哪个 URL、超时多久、默认拉取多少条”等环境变量解析集中起来。
这样 `api/app.py` 只负责组装应用主流程，Java replay 的细节不会继续推高入口文件复杂度。
"""

from __future__ import annotations

import os
from typing import Any

from datasmart_ai_runtime.api.agent.orchestrator_factory import (
    positive_int_env,
    truthy_env,
)
from datasmart_ai_runtime.services.agent_runtime_event_replay_client import JavaAgentRuntimeEventReplayClient


def build_runtime_event_replay_sources(agent_runtime_base_url: str | None) -> tuple[Any, ...]:
    """按环境变量装配外部 runtime-event replay source。

    输入与开关：
    - `agent_runtime_base_url`：Java `agent-runtime` 基础地址，例如 `http://localhost:8091`。
    - `DATASMART_AGENT_RUNTIME_EVENT_REPLAY_ENABLED`：只有显式开启时才接入 Java replay，默认关闭。
    - `DATASMART_AGENT_RUNTIME_EVENT_REPLAY_TIMEOUT_SECONDS`：单次 replay HTTP 调用超时，默认 3 秒。
    - `DATASMART_AGENT_RUNTIME_EVENT_REPLAY_LIMIT`：默认回放事件数量上限，避免一次请求拉取过多历史事件。

    输出：
    - 返回 tuple 而不是单对象，是为了后续继续组合 Kafka replay、审计表、对象归档、多区域事件源等。
    - 当前未启用或未配置 Java 地址时返回空 tuple，调用方可以自然保持本地单机模式。

    低敏边界：
    - 这里仅构造 client，不主动发起 HTTP 请求。
    - replay client 后续也只能消费 Java 控制面已经脱敏后的 Runtime Event 投影，不能回放 prompt、SQL、
      工具参数值、样本数据、模型输出、凭据或 artifact 正文。
    """

    if not agent_runtime_base_url or not truthy_env("DATASMART_AGENT_RUNTIME_EVENT_REPLAY_ENABLED"):
        return ()
    return (
        JavaAgentRuntimeEventReplayClient(
            base_url=agent_runtime_base_url,
            timeout_seconds=positive_int_env("DATASMART_AGENT_RUNTIME_EVENT_REPLAY_TIMEOUT_SECONDS", 3),
            replay_path=os.getenv("DATASMART_AGENT_RUNTIME_EVENT_REPLAY_PATH")
            or "/agent-runtime/runtime-events/replay",
            ack_path=os.getenv("DATASMART_AGENT_RUNTIME_EVENT_ACK_PATH")
            or "/agent-runtime/runtime-events/replay/acks",
            default_limit=positive_int_env("DATASMART_AGENT_RUNTIME_EVENT_REPLAY_LIMIT", 200),
        ),
    )

"""Python AI Runtime 的 Prometheus 指标路由注册模块。

`api.app` 应该主要负责装配运行时对象和注册各类子路由。如果把每一类指标端点都写在启动文件里，
启动入口会随着 memory、model gateway、tool checkpoint、LangGraph execution gate 等观测能力增长而膨胀。

本模块把 `/agent/metrics` 独立出来，统一合并当前 Python Runtime 的低基数指标：
- 长期记忆物化指标；
- 模型 Provider 主动健康探测指标；
- 工具动作 checkpoint query/resume-preview 指标；
- LangGraph execution gate 工具执行前门禁指标；
- LangGraph memory retrieval 长期记忆检索观察图指标。
- 受控多 Agent 执行会话 session/work item/roster 指标。

安全边界：
- 指标文本不输出 providerName、tenantId、projectId、runId、traceId、URL、prompt、工具参数或模型正文；
- 单次请求、单个 checkpoint 或单条 command 的排障继续走 runtime event、诊断接口、replay 和审计链路；
- Prometheus 只负责聚合趋势和告警判断，不能承载高基数业务明细。
"""

from __future__ import annotations

from typing import Any

from datasmart_ai_runtime.services.model_gateway import render_model_provider_health_probe_prometheus


def register_agent_metrics_route(
    app: Any,
    *,
    response_type: type[Any],
    memory_materialization_metrics: Any,
    langgraph_memory_retrieval_metrics: Any,
    model_provider_health_probe: Any,
    tool_action_checkpoint_metrics: Any,
    langgraph_execution_gate_metrics: Any,
    multi_agent_execution_session_metrics: Any,
) -> None:
    """注册 `/agent/metrics` Prometheus 文本指标端点。

    参数说明：
    - `response_type`：由 `create_app()` 延迟导入 FastAPI 后传入，避免核心测试强依赖 API 可选包；
    - `memory_materialization_metrics`：长期记忆物化运行、候选处理、补偿重排和 fencing/finalize 指标；
    - `langgraph_memory_retrieval_metrics`：长期记忆检索 LangGraph 节点、召回状态、目标类型和 MEMORY_AGENT 指标；
    - `model_provider_health_probe`：模型 Provider 主动健康探测累计结果与最近一轮状态分布；
    - `tool_action_checkpoint_metrics`：checkpoint query/resume-preview 的访问结果与恢复事实状态；
    - `langgraph_execution_gate_metrics`：工具执行前 LangGraph dominant gate、fallback、readiness 和 resume fact 指标。
    - `multi_agent_execution_session_metrics`：受控多 Agent 会话状态、工作项状态和 active/standby roster 指标。

    该函数只注册只读路由，不触发探测、不刷新远端、不执行工具，也不访问任何业务数据源。
    """

    @app.get("/agent/metrics")
    def agent_runtime_prometheus_metrics() -> Any:
        """导出 Python AI Runtime 的 Prometheus 文本指标。"""

        metric_parts = (
            memory_materialization_metrics.render_prometheus().rstrip(),
            langgraph_memory_retrieval_metrics.render_prometheus().rstrip(),
            render_model_provider_health_probe_prometheus(model_provider_health_probe).rstrip(),
            tool_action_checkpoint_metrics.render_prometheus().rstrip(),
            langgraph_execution_gate_metrics.render_prometheus().rstrip(),
            multi_agent_execution_session_metrics.render_prometheus().rstrip(),
            "",
        )

        return response_type(
            content="\n".join(metric_parts),
            media_type="text/plain; version=0.0.4; charset=utf-8",
        )

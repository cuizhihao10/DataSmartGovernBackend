"""模型路由服务。

模型路由的目标是让 Agent 编排代码只声明“我需要哪类能力”，而不是直接写死模型名称、URL、
超时和 Provider。这样当项目从本地 dry-run 升级到 vLLM、SGLang 或 OpenAI-compatible 网关时，
只需要替换路由配置，不需要重写工具规划、审批判断或上下文构建逻辑。
"""

from __future__ import annotations

from datasmart_ai_runtime.domain.contracts import ModelRoute, WorkloadType


class ModelRouteRegistry:
    """按工作负载维护模型路由。

    当前实现使用内存字典，足够支撑第一版骨架测试。生产化后可以把这里替换为：
    - Nacos/配置中心：适合按环境快速切换模型。
    - 数据库配置表：适合租户级、项目级模型策略。
    - 模型网关 API：适合统一接入多个模型供应商并做熔断、限流、成本统计。
    """

    def __init__(self, routes: tuple[ModelRoute, ...]) -> None:
        self._routes: dict[WorkloadType, tuple[ModelRoute, ...]] = {}
        grouped: dict[WorkloadType, list[ModelRoute]] = {}
        for route in sorted(routes, key=lambda item: item.priority):
            # 同一工作负载可能有多个候选模型。早期 `route_for(...)` 仍返回优先级最高的一个，保持
            # 兼容；新增的 `candidate_routes_for(...)` 则给模型网关治理服务提供 fallback 候选集。
            grouped.setdefault(route.workload, []).append(route)
        self._routes = {
            workload: tuple(items)
            for workload, items in grouped.items()
        }

    def route_for(self, workload: WorkloadType) -> ModelRoute:
        """根据工作负载返回模型路由。

        如果请求的工作负载没有显式配置，则回退到 `AGENT_REASONING`。这样做能保证早期功能
        不会因为漏配一个专用模型而完全不可用，但生产环境应配合告警提示运维补齐模型配置。
        """

        if workload in self._routes:
            return self._routes[workload][0]
        if WorkloadType.AGENT_REASONING in self._routes:
            return self._routes[WorkloadType.AGENT_REASONING][0]
        raise ValueError("模型路由表为空，至少需要配置一个 AGENT_REASONING 路由")

    def candidate_routes_for(self, workload: WorkloadType) -> tuple[ModelRoute, ...]:
        """返回某类工作负载的候选模型路由集合。

        该方法服务于模型网关治理：生产环境不能只知道“默认模型是谁”，还要知道当主模型不可用、
        超预算、延迟等级不匹配时可以 fallback 到哪些候选。若指定工作负载没有配置，则沿用
        `route_for(...)` 的兜底语义，返回 `AGENT_REASONING` 候选集。
        """

        if workload in self._routes:
            return self._routes[workload]
        if WorkloadType.AGENT_REASONING in self._routes:
            return self._routes[WorkloadType.AGENT_REASONING]
        raise ValueError("模型路由表为空，至少需要配置一个 AGENT_REASONING 路由")

    def all_routes(self) -> tuple[ModelRoute, ...]:
        """返回当前注册表中的全部模型路由。

        该方法主要服务诊断和运营面板，而不是常规路由选择。Provider 健康摘要需要知道某个
        providerName 关联了哪些 workload/model，否则运维人员只看到“某 Provider 不可用”，却无法判断
        影响的是 Agent 推理、Embedding、Rerank 还是代码生成。这里返回扁平 tuple，避免调用方直接读取
        内部 `_routes` 字典并依赖它的存储结构。
        """

        return tuple(route for routes in self._routes.values() for route in routes)

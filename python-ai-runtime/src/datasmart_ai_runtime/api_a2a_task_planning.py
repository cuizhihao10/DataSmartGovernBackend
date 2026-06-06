"""A2A Task 规划预览 API 响应组装。

Java Agent Runtime 已经提供 A2A task 状态机、事件契约和查询预览；Python Runtime 现在需要一个低风险
HTTP 入口，用于把这些低敏合同转换成 Master Agent 可消费的 planning decision。这个模块专门负责
“API 响应形态”，不负责真实 A2A 网络调用，也不负责执行工具。

为什么单独拆文件：
- `api_agent_routes.py` 只负责注册路由，如果继续把响应组装、字段说明和推荐动作都写进去，很快会重新
  变成大文件；
- `A2aTaskPlanningAdapter` 是服务层，只关心状态映射，不应该知道 HTTP schemaVersion、routePolicy、
  missingProductionRequirements 这些 API 展示字段；
- 将 API 组装拆出来后，测试可以不安装 FastAPI，直接调用 helper 验证契约。
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from datasmart_ai_runtime.services.agent_gateway import A2aTaskPlanningAdapter


def build_a2a_task_planning_preview_response(payload: Mapping[str, Any] | None) -> dict[str, Any]:
    """构建 A2A task planning preview 响应。

    路由用途：
    - 给 Java 控制面、gateway、联调脚本或后续 LangGraph 节点提供一个只读预览入口；
    - 调用方提交低敏 A2A task 合同，Python 返回状态映射后的 planning decision；
    - 响应不会回显原始 payload，也不会创建 task、取消 task、执行工具、写 outbox 或读取 artifact 正文。

    输入形态：
    - 可以直接提交 Java 5.30 `task-query-preview` 风格 JSON；
    - 也可以提交 `{"contract": {...}}`，方便未来 API 网关在同一请求体中加入 trace 或测试元信息。
    """

    contract = _contract_from_payload(payload)
    decision = A2aTaskPlanningAdapter().adapt(contract)
    decision_summary = decision.to_summary()
    return {
        "schemaVersion": "datasmart.python-ai-runtime.a2a-task-planning-preview.v1",
        "previewOnly": True,
        "taskExecutionEnabled": False,
        "protocolFamily": "A2A",
        "route": {
            "method": "POST",
            "path": "/agent/protocol-adapters/a2a/task-planning-preview",
            "intent": "把低敏 A2A task 控制面合同转换为 Python Runtime 可消费的 Agent 规划决策。",
        },
        "inputPayloadPolicy": {
            "accepted": "LOW_SENSITIVE_A2A_TASK_CONTROL_PLANE_CONTRACT",
            "notEchoed": True,
            "rawExecutionPayloadAllowed": False,
            "sensitiveFieldHandling": "COUNT_AND_DROP_WITHOUT_FIELD_NAMES_OR_VALUES",
        },
        "planningDecision": decision_summary,
        "productionReadiness": _production_readiness(decision_summary),
        "nextSteps": _next_steps(decision_summary),
    }


def _contract_from_payload(payload: Mapping[str, Any] | None) -> Mapping[str, Any] | None:
    """从请求体中提取真正的 A2A task 合同。

    当前支持两种形态：
    - 直接把 Java `task-query-preview` 响应作为请求体；
    - 用 `contract` 包一层，以便未来同一请求体中加入 `traceId`、`source`、`dryRun` 等 API 元数据。

    这里不做字段白名单过滤，因为过滤逻辑已经集中在 `A2aTaskPlanningAdapter` 与 mapping support 中。
    API helper 只负责选择“哪一段 payload 是合同”，避免两处重复维护敏感字段规则。
    """

    if not isinstance(payload, Mapping):
        return None
    nested_contract = payload.get("contract")
    if isinstance(nested_contract, Mapping):
        return nested_contract
    return payload


def _production_readiness(decision_summary: Mapping[str, Any]) -> dict[str, Any]:
    """生成面向产品和运维的生产化缺口摘要。

    planning preview 的价值是让状态语义可联调，但它本身不能被误解成真实 A2A Server。因此响应里显式写出
    `readyForExecution=false` 和缺失能力，避免调用方把预览接口当成执行入口。
    """

    mode = str(decision_summary.get("mode") or "")
    return {
        "readyForExecution": False,
        "planningMode": mode,
        "missingProductionRequirements": (
            "A2A_TASK_FACT_STORE",
            "TASK_MANAGEMENT_BINDING",
            "PERMISSION_ADMIN_SCOPE_CHECK",
            "IDEMPOTENCY_AND_RATE_LIMIT",
            "CONFIRMATION_OUTBOX_AND_WORKER_PRECHECK",
            "ARTIFACT_SERVICE_SECONDARY_AUTHORIZATION",
            "RUNTIME_EVENT_AND_WEBSOCKET_TIMELINE",
        ),
        "boundary": "本接口只返回 planning decision，不代表 A2A message/send、tasks/get、tasks/cancel 或 subscribe 已上线。",
    }


def _next_steps(decision_summary: Mapping[str, Any]) -> tuple[str, ...]:
    """根据规划模式生成下一步建议。

    这些建议是产品路线提示，不是执行命令。真实执行仍应由 Java 控制面和受控 worker 完成。
    """

    mode = str(decision_summary.get("mode") or "")
    if mode == "PRECHECK_REQUIRED":
        return (
            "先把该 task 映射到 permission-admin 预检和幂等键校验，再决定是否允许进入 worker pre-check。",
            "将该 planning decision 写入 runtime event，方便前端和 Java replay timeline 看到状态变化。",
        )
    if mode == "WORKER_PLANNING_ALLOWED":
        return (
            "可以规划 worker pre-check，但真实副作用仍必须经过 outbox、容量保护和 worker receipt。",
            "查询 task history 与 artifact metadata，确认没有迟到取消、审批变更或终态事件。",
        )
    if mode == "WAIT_FOR_USER_INPUT":
        return (
            "让 Master Agent 生成低敏追问，等待用户补充信息后再提交新的 continuation。",
            "不要让模型自行猜测缺失参数，尤其是数据源、SQL、导出路径或审批原因。",
        )
    if mode == "WAIT_FOR_AUTHORIZATION":
        return (
            "等待 permission-admin、审批单或租户能力包返回新的控制面事实。",
            "凭证和授权材料应通过受控 Header、Token 或审批系统传递，不能进入 A2A 消息正文。",
        )
    if mode == "TERMINAL_NO_EXECUTION":
        return (
            "终态 task 不允许重新进入 working；如需重跑，应创建新 task 或管理员补偿记录。",
            "artifact 只能展示 metadata-only 引用，正文读取必须走 artifact 服务二次鉴权。",
        )
    return (
        "未知或缺失状态已按 fail-closed 处理，需要先检查 A2A 协议版本、Java task fact 或 gateway adapter。",
        "不要在诊断态启动 worker 或执行工具。",
    )

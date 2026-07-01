"""Agent 计划的人读摘要与参数问题展示规则。

该模块只负责把结构化 ``ToolPlan`` 转换为响应摘要和下一步动作，不参与模型调用、工具执行、
审批写入或状态持久化。把展示规则从编排器中拆出，可以让 ``AgentOrchestrator`` 聚焦工作流状态，
也能让 HTTP、WebSocket 和未来通知通道复用相同的“可执行、待澄清、待审批”产品语义。

输出只包含工具名、参数名、问题说明和固定动作，不包含参数值、prompt、SQL、样本数据或模型输出。
"""

from datasmart_ai_runtime.domain.contracts import ToolParameterIssueAction, ToolPlan


def build_response_summary(tool_plans: tuple[ToolPlan, ...], requires_human_approval: bool) -> str:
    """生成控制面人读摘要；自动化调用方仍应以 ToolPlan 的结构化状态为准。"""

    tool_count = len(tool_plans)
    parameter_issue_count = sum(len(plan.parameter_validation.issues) for plan in tool_plans)
    if tool_count == 0:
        return "已完成目标解析，但当前没有命中可调用工具；建议补充工具注册或接入语义规划器。"
    if parameter_issue_count > 0 and requires_human_approval:
        return f"已生成 {tool_count} 个工具计划，其中 {parameter_issue_count} 个参数需要补齐，且包含需审批操作。"
    if parameter_issue_count > 0:
        return f"已生成 {tool_count} 个工具计划，但发现 {parameter_issue_count} 个参数需要补齐；当前不应直接执行。"
    if requires_human_approval:
        return f"已生成 {tool_count} 个工具计划，其中包含高风险或需审批操作，必须先进入人工确认。"
    return f"已生成 {tool_count} 个工具计划，当前均属于可自动进入控制面执行的低风险操作。"


def build_next_actions(tool_plans: tuple[ToolPlan, ...], requires_human_approval: bool) -> tuple[str, ...]:
    """根据参数问题和审批要求生成下一步产品动作。"""

    parameter_actions = build_parameter_next_actions(tool_plans)
    if requires_human_approval:
        return parameter_actions + (
            "在 Java agent-runtime 中创建工具执行审计记录。",
            "由项目负责人、平台管理员或具备授权的审批人确认高风险工具计划。",
            "审批通过后再调用对应业务微服务执行。",
        )
    if parameter_actions:
        return parameter_actions + ("参数补齐后再将工具计划提交给 Java agent-runtime。",)
    return (
        "将工具计划提交给 Java agent-runtime 生成审计记录。",
        "按工具注册表的目标微服务触发执行并回写运行状态。",
    )


def has_parameter_issues(tool_plans: tuple[ToolPlan, ...]) -> bool:
    """判断计划是否包含任意参数问题；参数问题与审批问题保持独立。"""

    return any(plan.parameter_validation.issues for plan in tool_plans)


def requires_parameter_clarification(tool_plans: tuple[ToolPlan, ...]) -> bool:
    """判断是否存在必须由用户或管理员明确补充的参数。"""

    return any(
        issue.action == ToolParameterIssueAction.MUST_CLARIFY
        for plan in tool_plans
        for issue in plan.parameter_validation.issues
    )


def build_parameter_next_actions(tool_plans: tuple[ToolPlan, ...]) -> tuple[str, ...]:
    """把参数问题转换为低敏动作，并限制为前三项以避免响应噪声。"""

    actions: list[str] = []
    for plan in tool_plans:
        for issue in plan.parameter_validation.issues:
            if len(actions) >= 3:
                return tuple(actions)
            actions.append(f"补齐工具 `{plan.tool_name}` 的参数 `{issue.parameter_name}`：{issue.message}")
    return tuple(actions)

"""真实专业 Agent 的进程内注册表与统一调用入口。"""

from __future__ import annotations

from threading import RLock

from datasmart_ai_runtime.services.agent_gateway.session_models import AgentSessionRole
from datasmart_ai_runtime.services.multi_agent.specialist_contracts import (
    SpecialistAgent,
    SpecialistEventSink,
    SpecialistTurnRequest,
    SpecialistTurnResult,
    SpecialistTurnStatus,
)


class SpecialistAgentRegistry:
    """按稳定角色编码管理真实专业 Agent 实例。

    注册表本身不做权限判断，也不直接访问模型。它只确保一个角色在当前 Python Runtime 实例中最多
    有一个实现，并在执行前复核请求角色。跨实例调度、租约和持久化仍由 Java 控制面负责。
    """

    def __init__(self, agents: tuple[SpecialistAgent, ...] = ()) -> None:
        """创建线程安全注册表，并按与运行期相同的校验规则装配初始 Agent。

        初始实例不直接写入字典，而是逐个经过 ``register``，从而保证重复角色、非法角色
        和缺少执行协议的问题都在应用启动阶段暴露。
        """

        self._lock = RLock()
        self._agents: dict[AgentSessionRole, SpecialistAgent] = {}
        for agent in agents:
            self.register(agent)

    def register(self, agent: SpecialistAgent, *, replace: bool = False) -> None:
        """注册一个专业 Agent；默认拒绝静默覆盖已有实现。

        注册是 Runtime 的安全边界，而不是简单的字典赋值。启动阶段就拒绝空对象、非法角色
        和缺少 ``execute`` 方法的对象，可以把装配错误提前暴露；否则系统可能在 UI 上宣称某类
        Specialist 已上线，直到真实请求到来才因为属性错误进入不可审计的失败路径。
        """

        if agent is None:
            raise TypeError("专业 Agent 不能为 None")
        role = getattr(agent, "role", None)
        if not isinstance(role, AgentSessionRole):
            raise TypeError("专业 Agent role 必须是 AgentSessionRole")
        if not callable(getattr(agent, "execute", None)):
            raise TypeError("专业 Agent 必须提供可调用的 execute 方法")
        if role == AgentSessionRole.MASTER_ORCHESTRATOR:
            raise ValueError("MASTER_ORCHESTRATOR 不能注册为专业 Agent")
        with self._lock:
            if role in self._agents and not replace:
                raise ValueError(f"专业 Agent 角色已注册：{role.value}")
            self._agents[role] = agent

    def unregister(self, role: AgentSessionRole) -> bool:
        """移除专业 Agent 实例，供热更新或测试隔离使用。"""

        with self._lock:
            return self._agents.pop(role, None) is not None

    def available_roles(self) -> tuple[AgentSessionRole, ...]:
        """返回当前实例真正可执行的角色，而不是 README 中的规划角色。"""

        with self._lock:
            return tuple(sorted(self._agents, key=lambda item: item.value))

    def execute(
        self,
        request: SpecialistTurnRequest,
        event_sink: SpecialistEventSink | None = None,
    ) -> SpecialistTurnResult:
        """把一次受控 turn 路由给对应专业 Agent，并复核回传身份。

        这里故意不提供“找不到就让主 Agent 自己做”的 fallback。真实多 Agent 必须 fail-closed，否则
        生产环境会出现界面宣称已委派、实际仍由单体主 Agent 完成的审计错觉。
        """

        if not isinstance(request, SpecialistTurnRequest):
            raise TypeError("专业 Agent 请求必须是 SpecialistTurnRequest")

        with self._lock:
            agent = self._agents.get(request.role)
        if agent is None:
            raise LookupError(f"专业 Agent 尚未注册：{request.role.value}")

        result = agent.execute(request, event_sink)
        if not isinstance(result, SpecialistTurnResult):
            raise TypeError("专业 Agent 必须返回 SpecialistTurnResult")
        if not isinstance(result.role, AgentSessionRole):
            raise TypeError("专业 Agent 返回结果的 role 必须是 AgentSessionRole")
        if not isinstance(result.status, SpecialistTurnStatus):
            raise TypeError("专业 Agent 返回结果的 status 必须是 SpecialistTurnStatus")
        if result.role != request.role:
            raise ValueError(
                "专业 Agent 回传角色不一致："
                f"expected={request.role.value}, actual={getattr(result.role, 'value', result.role)}"
            )
        if result.turn_id != request.turn_id:
            raise ValueError(
                f"专业 Agent 回传 turnId 不一致：expected={request.turn_id}, actual={result.turn_id}"
            )
        return result

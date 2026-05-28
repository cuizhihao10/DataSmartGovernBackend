"""Agent Runtime 实时事件订阅授权策略。

3.43 之前，我们已经有了订阅会话状态机和控制消息处理器，但还没有真正处理“谁能订阅什么”的问题。
在商业化数据治理产品里，实时事件流可能包含任务审计、审批等待、参数草案、敏感上下文和运行进度，
如果不先把订阅授权边界建模清楚，后续 WebSocket、前端大盘和 Java Gateway 都会默认把事件流当成
公开通道，这会非常危险。

因此本文件只负责订阅授权策略，不负责身份认证、token 解析或用户登录。它接收一个已经解析好的
访问上下文，然后判断这次订阅是否允许进入实时事件流。这样未来可以接：
- Java gateway 的登录态和 JWT claim；
- permission-admin 的租户、项目、角色、会话权限；
- service account 的机器身份和固定授权范围；
- 管理员/审计员的更高权限视图。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Iterable

from datasmart_ai_runtime.domain.event_transport import RuntimeEventSubscriptionRequest


@dataclass(frozen=True)
class RuntimeEventAccessContext:
    """实时事件订阅访问上下文。

    这个对象由 API 层或网关层在进入实时事件服务前组装，表示“当前请求者是谁、属于哪个租户/项目、
    拥有哪些角色、可以访问哪些会话范围”。它不是身份认证结果本身，而是认证后的授权上下文。

    字段说明：
    - `tenant_id` / `project_id` / `actor_id`：当前请求者的归属信息；
    - `roles`：当前请求者的角色集合，例如 `platform_admin`、`tenant_admin`、`auditor`、`operator`；
    - `allowed_session_ids` / `allowed_run_ids` / `allowed_request_ids`：显式允许访问的会话范围；
    - `is_platform_admin`：平台管理员兜底放行；
    - `is_tenant_admin`：租户管理员在同租户范围内可放行；
    - `is_auditor`：审计员通常只能读，不能执行状态写操作，但可看更宽的历史范围。
    """

    tenant_id: str | None = None
    project_id: str | None = None
    actor_id: str | None = None
    roles: tuple[str, ...] = ()
    allowed_session_ids: tuple[str, ...] = ()
    allowed_run_ids: tuple[str, ...] = ()
    allowed_request_ids: tuple[str, ...] = ()
    is_platform_admin: bool = False
    is_tenant_admin: bool = False
    is_auditor: bool = False
    attributes: dict[str, str] = field(default_factory=dict)

    def has_role(self, role: str) -> bool:
        """判断当前上下文是否拥有某个角色。"""

        return role in self.roles


@dataclass(frozen=True)
class RuntimeEventAuthorizationDecision:
    """实时事件订阅授权决策。"""

    allowed: bool
    reason: str = ""
    effective_scope: str = ""


class RuntimeEventSubscriptionAuthorizer:
    """实时事件订阅授权器。

    授权器的目标不是做复杂规则引擎，而是先把当前产品里最关键的几层边界固定住：
    1. 平台管理员可跨租户查看受控范围内的订阅；
    2. 租户管理员与普通用户只能看自己租户/项目下的订阅；
    3. 审计员默认只读，不允许写操作，但允许查看历史 replay；
    4. 如果请求明确指向某个 session/run/request，必须在允许范围内，否则拒绝。

    这套策略后续可以和 permission-admin 的项目成员、角色与路由策略结合，再进一步细化。
    """

    def authorize(
        self,
        request: RuntimeEventSubscriptionRequest,
        context: RuntimeEventAccessContext,
    ) -> RuntimeEventAuthorizationDecision:
        """判断当前访问上下文是否允许订阅指定事件流。"""

        if context.is_platform_admin:
            return RuntimeEventAuthorizationDecision(
                allowed=True,
                reason="platform_admin_bypass",
                effective_scope=self._scope_description(request),
            )

        if request.tenant_id and context.tenant_id and request.tenant_id != context.tenant_id:
            return RuntimeEventAuthorizationDecision(
                allowed=False,
                reason="tenant_mismatch",
            )
        if request.project_id and context.project_id and request.project_id != context.project_id:
            return RuntimeEventAuthorizationDecision(
                allowed=False,
                reason="project_mismatch",
            )

        if request.session_id and not self._is_allowed(request.session_id, context.allowed_session_ids):
            return RuntimeEventAuthorizationDecision(
                allowed=False,
                reason="session_not_allowed",
            )
        if request.run_id and not self._is_allowed(request.run_id, context.allowed_run_ids):
            return RuntimeEventAuthorizationDecision(
                allowed=False,
                reason="run_not_allowed",
            )
        if request.request_id and not self._is_allowed(request.request_id, context.allowed_request_ids):
            return RuntimeEventAuthorizationDecision(
                allowed=False,
                reason="request_not_allowed",
            )

        if context.is_auditor:
            return RuntimeEventAuthorizationDecision(
                allowed=True,
                reason="auditor_read_only",
                effective_scope=self._scope_description(request),
            )

        if context.is_tenant_admin:
            return RuntimeEventAuthorizationDecision(
                allowed=True,
                reason="tenant_admin_allowed",
                effective_scope=self._scope_description(request),
            )

        if context.tenant_id is None and context.project_id is None and request.tenant_id is None and request.project_id is None:
            return RuntimeEventAuthorizationDecision(
                allowed=True,
                reason="anonymous_limited_allowed",
                effective_scope=self._scope_description(request),
            )

        if request.tenant_id and context.tenant_id is None:
            return RuntimeEventAuthorizationDecision(
                allowed=False,
                reason="tenant_context_missing",
            )
        if request.project_id and context.project_id is None:
            return RuntimeEventAuthorizationDecision(
                allowed=False,
                reason="project_context_missing",
            )

        return RuntimeEventAuthorizationDecision(
            allowed=True,
            reason="allowed",
            effective_scope=self._scope_description(request),
        )

    @staticmethod
    def _is_allowed(value: str, allowed_values: Iterable[str]) -> bool:
        """判断某个 ID 是否在显式允许范围内。"""

        allowed_tuple = tuple(allowed_values)
        return not allowed_tuple or value in allowed_tuple

    @staticmethod
    def _scope_description(request: RuntimeEventSubscriptionRequest) -> str:
        """生成便于审计和排查的授权范围描述。"""

        parts: list[str] = []
        if request.tenant_id:
            parts.append(f"tenant={request.tenant_id}")
        if request.project_id:
            parts.append(f"project={request.project_id}")
        if request.session_id:
            parts.append(f"session={request.session_id}")
        if request.run_id:
            parts.append(f"run={request.run_id}")
        if request.request_id:
            parts.append(f"request={request.request_id}")
        return ",".join(parts) if parts else "global"

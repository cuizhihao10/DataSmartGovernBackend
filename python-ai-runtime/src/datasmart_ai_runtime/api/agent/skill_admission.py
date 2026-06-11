"""Agent Skill 准入策略启动装配。

`api.py` 已经接近单文件 500 行上限，因此与 Skill admission 远程 provider 相关的环境变量解析和对象装配
放到本模块中。这样 API 路由层只负责调用 builder，不继续堆积跨服务集成细节。
"""

from __future__ import annotations

import os

from datasmart_ai_runtime.services.skill_admission_policy import (
    AgentSkillAdmissionPolicy,
    JavaPermissionAdminSkillAdmissionClient,
    RemoteThenLocalAgentSkillAdmissionPolicy,
)


def build_skill_admission_policy(
    permission_admin_base_url: str | None = None,
    *,
    trace_id: str | None = None,
    enable_remote: bool | None = None,
    allow_remote_fallback: bool = True,
) -> AgentSkillAdmissionPolicy | RemoteThenLocalAgentSkillAdmissionPolicy:
    """构建 Agent Skill 准入策略 provider。

    默认返回 Python 本地准入策略；只有显式启用远程且存在 permission-admin 地址时，才调用 Java 控制面。
    这种默认关闭的设计能保证本地学习、单元测试和离线开发不需要启动 Java 服务；生产或联调环境则可以
    通过环境变量把准入事实切到 permission-admin。
    """

    local_policy = AgentSkillAdmissionPolicy()
    remote_enabled = (
        _truthy_env("DATASMART_PERMISSION_ADMIN_SKILL_ADMISSION_ENABLED")
        if enable_remote is None
        else enable_remote
    )
    base_url = permission_admin_base_url or os.getenv("DATASMART_PERMISSION_ADMIN_BASE_URL")
    if not remote_enabled or not base_url:
        return local_policy
    remote_client = JavaPermissionAdminSkillAdmissionClient(
        base_url=base_url,
        timeout_seconds=_positive_int_env("DATASMART_PERMISSION_ADMIN_SKILL_ADMISSION_TIMEOUT_SECONDS", 3),
    )
    return RemoteThenLocalAgentSkillAdmissionPolicy(
        remote_client,
        local_policy=local_policy,
        allow_remote_fallback=allow_remote_fallback,
        trace_id=trace_id,
    )


def _truthy_env(name: str) -> bool:
    """解析布尔环境变量，避免 `bool("false") == True` 的 Python 陷阱。"""

    value = os.getenv(name)
    if value is None:
        return False
    return str(value).strip().lower() in {"1", "true", "yes", "on", "enabled"}


def _positive_int_env(name: str, default: int) -> int:
    """读取正整数环境变量，非法值回退默认值。"""

    value = os.getenv(name)
    if value is None or not value.strip():
        return default
    try:
        parsed = int(value)
    except ValueError:
        return default
    return parsed if parsed > 0 else default

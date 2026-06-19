"""工具动作恢复事实 provider 启动装配。

`/agent/tool-actions/checkpoints/resume-preview` 需要一个服务端事实源来回答：
“这个已暂停 checkpoint 是否具备继续预览所需的审批、澄清、outbox、worker receipt 等 host facts？”

本模块只负责根据环境变量选择 provider，不执行恢复动作，也不解析具体 checkpoint。把它从
`orchestrator_factory.py` 拆出来有两个原因：
- 恢复事实链路已经从 permission-admin 单点审批扩展到 Java fact bundle 和 Java gate graph，继续放在
  编排器工厂中会让启动文件再次变成难维护的巨石；
- 后续 MCP `tools/call`、A2A action、模型 tool_call 统一 adapter 都会复用同一条 resume gate 语义，
  因此恢复事实 provider 应成为独立、可学习、可测试的启动能力。
"""

from __future__ import annotations

import os

from datasmart_ai_runtime.api.agent.bootstrap_env import csv_env, positive_int_env, truthy_env
from datasmart_ai_runtime.services.tools import (
    DEFAULT_AGENT_RUNTIME_RESUME_FACT_BUNDLE_PATH,
    DEFAULT_AGENT_RUNTIME_RESUME_GATE_GRAPH_PATH,
    DEFAULT_PERMISSION_ADMIN_APPROVAL_FACT_EVALUATE_PATH,
    AgentRuntimeResumeFactBundleClientSettings,
    AgentRuntimeResumeGateGraphClientSettings,
    EmptyToolActionResumeFactProvider,
    JavaAgentRuntimeToolActionResumeFactBundleClient,
    JavaAgentRuntimeToolActionResumeGateGraphClient,
    JavaPermissionAdminToolActionResumeFactClient,
    PermissionAdminResumeFactClientSettings,
    ToolActionResumeFactProvider,
)


def build_tool_action_resume_fact_provider(
    permission_admin_base_url: str | None = None,
    *,
    enable_remote: bool | None = None,
) -> ToolActionResumeFactProvider:
    """构建工具动作恢复事实 provider。

    provider 选择顺序是一个明确的产品策略，而不是简单的技术偏好：
    1. **Java gate graph provider**：优先消费 Java 5.85 的 host-controlled resume gate graph，获得
       READY/WAITING/REJECTED 图级语义；
    2. **Java fact bundle provider**：当 Java 环境还没有 gate graph 时，仍可读取 5.70 的扁平事实包；
    3. **permission-admin 单点审批 provider**：兼容更早期只校验 approvalFactId 的环境；
    4. **空 provider**：默认本地学习和 CI 不访问远程服务。

    重要边界：
    - provider 只返回低敏事实类型、缺失/拒绝类型和错误码；
    - 不执行工具、不写 outbox、不派发 worker、不修改 checkpoint；
    - token 只进入 HTTP Header，不进入 API 响应摘要。
    """

    agent_runtime_base_url = os.getenv("DATASMART_AGENT_RUNTIME_BASE_URL")
    if truthy_env("DATASMART_AGENT_RUNTIME_RESUME_GATE_GRAPH_ENABLED") and agent_runtime_base_url:
        return JavaAgentRuntimeToolActionResumeGateGraphClient(
            AgentRuntimeResumeGateGraphClientSettings(
                enabled=True,
                base_url=agent_runtime_base_url,
                graph_path=os.getenv("DATASMART_AGENT_RUNTIME_RESUME_GATE_GRAPH_PATH")
                or DEFAULT_AGENT_RUNTIME_RESUME_GATE_GRAPH_PATH,
                timeout_seconds=positive_int_env("DATASMART_AGENT_RUNTIME_RESUME_GATE_GRAPH_TIMEOUT_SECONDS", 3),
                service_token=os.getenv("DATASMART_AGENT_RUNTIME_SERVICE_TOKEN")
                or os.getenv("DATASMART_PERMISSION_ADMIN_SERVICE_TOKEN"),
                service_account_actor_id=os.getenv("DATASMART_AGENT_RUNTIME_RESUME_GATE_GRAPH_SERVICE_ACCOUNT_ACTOR_ID")
                or "900001",
                service_account_role=os.getenv("DATASMART_AGENT_RUNTIME_RESUME_GATE_GRAPH_SERVICE_ACCOUNT_ROLE")
                or "SERVICE_ACCOUNT",
                data_scope_level=os.getenv("DATASMART_AGENT_RUNTIME_RESUME_GATE_GRAPH_DATA_SCOPE_LEVEL") or "PLATFORM",
                authorized_project_ids=csv_env("DATASMART_AGENT_RUNTIME_RESUME_GATE_GRAPH_AUTHORIZED_PROJECT_IDS"),
            )
        )

    if truthy_env("DATASMART_AGENT_RUNTIME_RESUME_FACT_BUNDLE_ENABLED") and agent_runtime_base_url:
        return JavaAgentRuntimeToolActionResumeFactBundleClient(
            AgentRuntimeResumeFactBundleClientSettings(
                enabled=True,
                base_url=agent_runtime_base_url,
                bundle_path=os.getenv("DATASMART_AGENT_RUNTIME_RESUME_FACT_BUNDLE_PATH")
                or DEFAULT_AGENT_RUNTIME_RESUME_FACT_BUNDLE_PATH,
                timeout_seconds=positive_int_env("DATASMART_AGENT_RUNTIME_RESUME_FACT_BUNDLE_TIMEOUT_SECONDS", 3),
                service_token=os.getenv("DATASMART_AGENT_RUNTIME_SERVICE_TOKEN")
                or os.getenv("DATASMART_PERMISSION_ADMIN_SERVICE_TOKEN"),
                service_account_actor_id=os.getenv("DATASMART_AGENT_RUNTIME_RESUME_FACT_BUNDLE_SERVICE_ACCOUNT_ACTOR_ID")
                or "900001",
                service_account_role=os.getenv("DATASMART_AGENT_RUNTIME_RESUME_FACT_BUNDLE_SERVICE_ACCOUNT_ROLE")
                or "SERVICE_ACCOUNT",
                data_scope_level=os.getenv("DATASMART_AGENT_RUNTIME_RESUME_FACT_BUNDLE_DATA_SCOPE_LEVEL") or "PLATFORM",
                authorized_project_ids=csv_env("DATASMART_AGENT_RUNTIME_RESUME_FACT_BUNDLE_AUTHORIZED_PROJECT_IDS"),
            )
        )

    permission_admin_enabled = (
        truthy_env("DATASMART_PERMISSION_ADMIN_TOOL_ACTION_RESUME_FACTS_ENABLED")
        if enable_remote is None
        else enable_remote
    )
    base_url = permission_admin_base_url or os.getenv("DATASMART_PERMISSION_ADMIN_BASE_URL")
    if not permission_admin_enabled or not base_url:
        return EmptyToolActionResumeFactProvider()
    return JavaPermissionAdminToolActionResumeFactClient(
        PermissionAdminResumeFactClientSettings(
            enabled=True,
            base_url=base_url,
            approval_fact_evaluate_path=os.getenv("DATASMART_PERMISSION_ADMIN_TOOL_ACTION_APPROVAL_FACT_EVALUATE_PATH")
            or DEFAULT_PERMISSION_ADMIN_APPROVAL_FACT_EVALUATE_PATH,
            timeout_seconds=positive_int_env(
                "DATASMART_PERMISSION_ADMIN_TOOL_ACTION_RESUME_FACTS_TIMEOUT_SECONDS",
                3,
            ),
            service_token=os.getenv("DATASMART_PERMISSION_ADMIN_SERVICE_TOKEN"),
        )
    )

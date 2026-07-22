"""Python AI Runtime 的默认配置。

当前文件提供“可运行的默认值”，不是生产环境最终配置中心。后续商业化部署时，这些内容应逐步
迁移到 Nacos、环境变量、数据库配置表或密钥中心中。现在先保留在代码中，是为了让运行时骨架
可以被测试、被阅读，并且明确说明模型与工具的设计意图。
"""

from __future__ import annotations

import os

from datasmart_ai_runtime.domain.contracts import (
    ModelCacheKeyScope,
    ModelCostTier,
    ModelLatencyTier,
    ModelRoute,
    ProviderType,
    WorkloadType,
)
from datasmart_ai_runtime.domain.intent import GovernanceDomain
from datasmart_ai_runtime.domain.memory import AgentMemoryType
from datasmart_ai_runtime.domain.skills import AgentSkillDescriptor
from datasmart_ai_runtime.config_tool_registry import default_tool_registry


def default_model_routes() -> tuple[ModelRoute, ...]:
    """返回默认模型路由组合。

    这里故意不再使用旧的 `Qwen2` 基线。配置名称采用“当前新一代开源模型可替换组合”的写法：
    主路由偏向 Qwen3.5，同时保留 DeepSeek-V3.2、当前 Mistral Open 系列等替换空间。
    `DRY_RUN` 表示当前骨架还不会真的发起模型请求，后续接入 vLLM/SGLang/OpenAI-compatible
    服务时只需要替换 provider 与 endpoint，不应改动 Agent 编排逻辑。
    """

    return (
        ModelRoute(
            workload=WorkloadType.AGENT_REASONING,
            provider_name="open-weight-agent-router",
            provider_type=ProviderType.DRY_RUN,
            model_name="Qwen3.5-or-DeepSeek-V3.2-agent-placeholder",
            max_context_tokens=131072,
            timeout_seconds=90,
            fallback_group="agent-reasoning",
            latency_tier=ModelLatencyTier.STANDARD,
            cost_tier=ModelCostTier.HIGH,
            cache_key_scope=ModelCacheKeyScope.SESSION_ONLY,
            notes="用于多步骤工具规划、风险判断和治理任务拆解；生产环境可接入 vLLM 或 SGLang。",
        ),
        ModelRoute(
            workload=WorkloadType.GOVERNANCE_QA,
            provider_name="governance-knowledge-router",
            provider_type=ProviderType.DRY_RUN,
            model_name="Qwen3.5-governance-qa-placeholder",
            max_context_tokens=65536,
            timeout_seconds=60,
            fallback_group="governance-qa",
            latency_tier=ModelLatencyTier.INTERACTIVE,
            cost_tier=ModelCostTier.MEDIUM,
            cache_key_scope=ModelCacheKeyScope.PROJECT_SAFE,
            notes="用于数据治理规则解释、权限说明、审计摘要等中文知识问答。",
        ),
        ModelRoute(
            workload=WorkloadType.CODE_GENERATION,
            provider_name="code-agent-router",
            provider_type=ProviderType.DRY_RUN,
            model_name="Qwen3.5-code-or-Devstral-2-placeholder",
            max_context_tokens=131072,
            timeout_seconds=120,
            fallback_group="code-generation",
            latency_tier=ModelLatencyTier.BATCH,
            cost_tier=ModelCostTier.HIGH,
            cache_key_scope=ModelCacheKeyScope.SESSION_ONLY,
            notes="用于 SQL、清洗脚本、规则 DSL 和任务编排片段生成，必须配合沙箱与审批。",
        ),
        ModelRoute(
            workload=WorkloadType.EMBEDDING,
            provider_name="embedding-router",
            provider_type=ProviderType.DRY_RUN,
            model_name="current-generation-qwen-embedding-placeholder",
            max_context_tokens=8192,
            timeout_seconds=30,
            fallback_group="embedding",
            latency_tier=ModelLatencyTier.INTERACTIVE,
            cost_tier=ModelCostTier.LOW,
            cache_key_scope=ModelCacheKeyScope.PROJECT_SAFE,
            notes="用于 RAG/GraphRAG 向量召回，独立于主推理模型升级。",
        ),
        ModelRoute(
            workload=WorkloadType.RERANK,
            provider_name="rerank-router",
            provider_type=ProviderType.DRY_RUN,
            model_name="current-generation-qwen-reranker-placeholder",
            max_context_tokens=8192,
            timeout_seconds=30,
            fallback_group="rerank",
            latency_tier=ModelLatencyTier.INTERACTIVE,
            cost_tier=ModelCostTier.LOW,
            cache_key_scope=ModelCacheKeyScope.PROJECT_SAFE,
            notes="用于检索结果重排，提升上下文质量并减少主模型幻觉风险。",
        ),
    )


def model_routes_from_env(environ: dict[str, str] | None = None) -> tuple[ModelRoute, ...]:
    """根据环境变量生成模型路由。

    默认 `default_model_routes()` 仍然使用 dry-run，是为了保证没有模型服务的本地学习环境也能运行。
    当配置 OpenAI-compatible endpoint 后，本函数会把 `AGENT_REASONING` 主路由切换为真实模型调用。

    支持的核心环境变量：
    - `DATASMART_AI_OPENAI_COMPATIBLE_BASE_URL`：OpenAI-compatible base URL 或完整 chat completions URL；
    - `DATASMART_AI_AGENT_REASONING_MODEL`：Agent 推理模型名称；
    - `DATASMART_AI_AGENT_REASONING_PROVIDER_NAME`：Provider 名称，用于诊断、预算和健康状态；
    - `DATASMART_AI_AGENT_REASONING_TIMEOUT_SECONDS`：模型调用超时时间；
    - `DATASMART_AI_AGENT_REASONING_MAX_CONTEXT_TOKENS`：模型上下文长度。

    这里先只切换 Agent 推理主路由，而不是一次性把 embedding/rerank/code 都改成真实 Provider。
    原因是这些工作负载通常需要不同模型和不同接口形态；盲目共用聊天模型反而会让后续架构返工。
    """

    source = environ if environ is not None else os.environ
    endpoint = source.get("DATASMART_AI_OPENAI_COMPATIBLE_BASE_URL") or source.get(
        "DATASMART_AI_OPENAI_COMPATIBLE_ENDPOINT"
    )
    if not endpoint:
        return default_model_routes()

    default_routes = default_model_routes()
    real_agent_route = ModelRoute(
        workload=WorkloadType.AGENT_REASONING,
        provider_name=source.get("DATASMART_AI_AGENT_REASONING_PROVIDER_NAME") or "openai-compatible-agent-router",
        provider_type=ProviderType.OPENAI_COMPATIBLE,
        model_name=source.get("DATASMART_AI_AGENT_REASONING_MODEL") or "agent-reasoning-model",
        endpoint=endpoint,
        max_context_tokens=_positive_int(
            source.get("DATASMART_AI_AGENT_REASONING_MAX_CONTEXT_TOKENS"),
            default=131072,
        ),
        timeout_seconds=_positive_int(
            source.get("DATASMART_AI_AGENT_REASONING_TIMEOUT_SECONDS"),
            default=90,
        ),
        priority=1,
        fallback_group="agent-reasoning",
        latency_tier=ModelLatencyTier.STANDARD,
        cost_tier=ModelCostTier.HIGH,
        cache_key_scope=ModelCacheKeyScope.SESSION_ONLY,
        notes="由环境变量启用的真实 OpenAI-compatible Agent 推理路由。",
    )
    return (real_agent_route,) + tuple(route for route in default_routes if route.workload != WorkloadType.AGENT_REASONING)


def default_skill_registry() -> tuple[AgentSkillDescriptor, ...]:
    """返回默认 Agent Skill 注册表。

    这些 Skill 是“能力包骨架”，不是最终 prompt 市场。当前先把每类治理能力的工具依赖、记忆依赖、
    审批策略和触发条件写清楚，让编排器可以解释“为什么选择某个能力包”。后续可迁移到 Java
    `agent-runtime` 或租户级 Skill 配置中心，Python Runtime 只消费同一份 descriptor。
    """

    return (
        AgentSkillDescriptor(
            skill_code="knowledge.rag.answer",
            display_name="治理知识 RAG 问答 Skill",
            description=(
                "用于从平台治理知识库、业务口径、规则说明、Runbook 和项目文档中检索证据，"
                "再通过证据门控和 citation 约束生成可解释回答。"
            ),
            domain=GovernanceDomain.KNOWLEDGE_QA,
            required_tools=("knowledge.rag.query",),
            required_permissions=("agent:rag:query",),
            memory_dependencies=(AgentMemoryType.SEMANTIC, AgentMemoryType.PROCEDURAL),
            risk_level="low",
            approval_policy="NONE",
            trigger_keywords=("rag", "知识库", "知识问答", "治理知识", "业务口径", "数据标准", "解释", "说明", "为什么", "怎么"),
            examples=("请解释数据质量规则生成为什么需要先读取元数据，并给出证据来源",),
        ),
        AgentSkillDescriptor(
            skill_code="sync.task.import.troubleshoot",
            display_name="同步任务导入故障诊断 Skill",
            description=(
                "读取任务 Excel/CSV 导入的低敏校验结果引用，结合产品文档、错误码、历史案例和 Runbook "
                "定位唯一键冲突、模板字段缺失、枚举非法、权限不足或任务配置不一致，并给出可验证的修复步骤。"
            ),
            domain=GovernanceDomain.DATA_SYNC,
            required_tools=("knowledge.rag.query",),
            required_permissions=("agent:rag:query",),
            memory_dependencies=(AgentMemoryType.SEMANTIC, AgentMemoryType.PROCEDURAL),
            risk_level="low",
            approval_policy="NONE",
            trigger_keywords=(
                "任务导入",
                "导入任务",
                "Excel 导入",
                "XLSX 导入",
                "CSV 导入",
                "导入失败",
                "导入报错",
                "唯一键冲突",
            ),
            examples=("任务 Excel 导入提示任务编码冲突，请结合产品文档告诉我怎么修复。",),
            attributes={"requiresExplicitTrigger": True, "artifactPayloadPolicy": "REFERENCE_ONLY"},
        ),
        AgentSkillDescriptor(
            skill_code="datasource.profiling",
            display_name="数据源画像分析 Skill",
            description="用于分析数据源结构、字段画像、主键索引和基础元数据风险。",
            domain=GovernanceDomain.DATASOURCE,
            required_tools=("datasource.metadata.read",),
            required_permissions=("datasource:metadata:read",),
            memory_dependencies=(AgentMemoryType.SEMANTIC,),
            risk_level="low",
            approval_policy="NONE",
            trigger_keywords=("数据源", "表结构", "metadata", "mysql", "postgresql"),
            examples=("请分析这个 MySQL 数据源的表结构",),
        ),
        AgentSkillDescriptor(
            skill_code="quality.rule.design",
            display_name="质量规则设计 Skill",
            description="用于根据元数据、业务目标和历史异常生成质量规则草案。",
            domain=GovernanceDomain.DATA_QUALITY,
            required_tools=("datasource.metadata.read", "quality.rule.suggest"),
            required_permissions=("quality:rule:draft",),
            memory_dependencies=(AgentMemoryType.SEMANTIC, AgentMemoryType.EPISODIC),
            risk_level="medium",
            approval_policy="DRAFT_REVIEW",
            trigger_keywords=("质量", "规则", "校验", "异常", "清洗"),
            examples=("请为客户主数据生成完整性和手机号格式校验规则",),
        ),
        AgentSkillDescriptor(
            skill_code="quality.anomaly.remediation",
            display_name="质量异常治理任务 Skill",
            description=(
                "用于把质量报告或异常工作台中的低敏异常聚合转成治理/复核任务草案，"
                "帮助项目负责人、运营人员或 Agent 在人工确认前完成派单预览。"
            ),
            domain=GovernanceDomain.DATA_QUALITY,
            required_tools=("quality.remediation.task.draft",),
            required_permissions=("quality:anomaly:remediation:create-draft",),
            memory_dependencies=(AgentMemoryType.EPISODIC, AgentMemoryType.PROCEDURAL),
            risk_level="medium",
            approval_policy="DRAFT_REVIEW",
            trigger_keywords=("治理任务", "异常复核", "派单", "整改", "修复任务", "remediation"),
            examples=("请把 77 号质量报告里的高危异常生成治理任务草案，先人工复核",),
            attributes={
                "requiresExplicitTrigger": True,
                "triggerPolicyReason": "质量异常治理任务会影响后续派单/复核链路，不能仅凭 DATA_QUALITY 领域命中就启用。",
            },
        ),
        AgentSkillDescriptor(
            skill_code="governed.task.creation",
            display_name="受控任务创建 Skill",
            description="用于把治理目标转换为任务草案，并进入审批、审计和任务管理链路。",
            domain=GovernanceDomain.TASK_MANAGEMENT,
            required_tools=("task.create.draft", "task.draft.persist"),
            required_permissions=("task:create",),
            memory_dependencies=(AgentMemoryType.PROCEDURAL, AgentMemoryType.EPISODIC),
            risk_level="high",
            approval_policy="HUMAN_APPROVAL_REQUIRED",
            trigger_keywords=("创建任务", "调度", "执行", "同步任务", "run"),
            examples=("请创建一个同步任务并在审批后执行",),
        ),
        AgentSkillDescriptor(
            skill_code="permission.boundary.explain",
            display_name="权限边界解释 Skill",
            description="用于解释角色、项目范围、审批要求和越权风险。",
            domain=GovernanceDomain.PERMISSION_ADMIN,
            required_tools=(),
            required_permissions=("permission:policy:view",),
            memory_dependencies=(AgentMemoryType.EPISODIC, AgentMemoryType.SEMANTIC),
            risk_level="medium",
            approval_policy="AUDIT_ONLY",
            trigger_keywords=("权限", "角色", "审批", "项目范围", "越权"),
            examples=("解释为什么我不能访问这个项目的质量报告",),
        ),
    )


def _positive_int(value: str | None, default: int) -> int:
    """读取正整数配置，非法或空值回退默认值。

    模型路由配置来自环境变量时，经常会出现空字符串、0 或错误值。这里统一做保守兜底，避免因为
    一个非关键配置写错导致 Python Runtime 无法启动。
    """

    if value is None or not value.strip():
        return default
    parsed = int(value)
    return parsed if parsed > 0 else default

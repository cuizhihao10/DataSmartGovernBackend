"""Agent Host 能力完备度矩阵。

本模块把“一个完整 AI Agent 至少需要哪些能力”固化为可测试、可诊断、可持续维护的结构化矩阵。

设计原则：
- 不把用户随口举的能力当作全部范围，而是主动扩展到商业化 Agent Host 需要的能力域；
- 不把 preview、控制面合同、只读诊断误标成“完成”，避免项目收口阶段产生虚假的完备感；
- 不读取 prompt、工具参数、SQL、文件正文、记忆正文、模型输出或内部 endpoint；
- 只输出能力名称、状态、归属模块、闭环缺口、下一步动作、性能和安全关注点等低敏治理元数据。

它解决的是产品管理与工程收敛问题：后续继续开发时，我们可以问“这个能力矩阵里哪一项还没闭环”，而不是
每次临时讨论“Agent 还差什么”。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any


class AgentCapabilityStatus(str, Enum):
    """Agent 子能力当前成熟度。

    这里不用简单的 done/todo，是因为当前项目已经有大量“合同就绪但未产生真实副作用”的能力。如果把这些
    preview 直接算作完成，就会误导收敛节奏。
    """

    OPERATIONAL = "operational"
    PARTIAL_CLOSED_LOOP = "partial_closed_loop"
    CONTROL_PLANE_READY = "control_plane_ready"
    PLANNED = "planned"
    BLOCKED = "blocked"


@dataclass(frozen=True)
class AgentSubCapability:
    """Agent 能力域下的一项子能力。

    字段说明：
    - `capability_id`：稳定机器 ID，后续测试、管理台或路线图引用它，而不是引用中文名；
    - `display_name`：中文展示名，便于学习和复盘；
    - `status`：当前真实成熟度；
    - `owner_module`：主要归属模块，帮助判断后续应该改 Python Runtime、Java agent-runtime、gateway 还是 permission-admin；
    - `current_evidence`：当前项目里已经存在的证据，避免重复建设；
    - `closure_gap`：要把能力真正闭环还缺什么；
    - `next_action`：下一步最小收敛动作；
    - `performance_notes`：性能、并发、缓存、token 或队列方面需要关注什么；
    - `safety_notes`：权限、审批、低敏、隔离或审计方面需要关注什么。
    """

    capability_id: str
    display_name: str
    status: AgentCapabilityStatus
    owner_module: str
    current_evidence: str
    closure_gap: str
    next_action: str
    performance_notes: str
    safety_notes: str

    def to_summary(self) -> dict[str, Any]:
        """输出单项子能力摘要。"""

        return {
            "capabilityId": self.capability_id,
            "displayName": self.display_name,
            "status": self.status.value,
            "ownerModule": self.owner_module,
            "currentEvidence": self.current_evidence,
            "closureGap": self.closure_gap,
            "nextAction": self.next_action,
            "performanceNotes": self.performance_notes,
            "safetyNotes": self.safety_notes,
        }


@dataclass(frozen=True)
class AgentCapabilityDomain:
    """Agent 一级能力域。

    一个能力域不是一个代码包，而是一组产品能力。例如 tools 同时涉及 Python Runtime 的工具计划、
    Java agent-runtime 的 outbox/worker、permission-admin 的审批策略和 gateway 的入口保护。
    """

    domain_id: str
    display_name: str
    priority: str
    owner_layer: str
    closure_goal: str
    sub_capabilities: tuple[AgentSubCapability, ...] = field(default_factory=tuple)

    def to_summary(self) -> dict[str, Any]:
        """输出完整能力域摘要。"""

        status_counts = _status_counts(self.sub_capabilities)
        open_gap_count = sum(1 for item in self.sub_capabilities if item.status != AgentCapabilityStatus.OPERATIONAL)
        return {
            "domainId": self.domain_id,
            "displayName": self.display_name,
            "priority": self.priority,
            "ownerLayer": self.owner_layer,
            "closureGoal": self.closure_goal,
            "subCapabilityCount": len(self.sub_capabilities),
            "openGapCount": open_gap_count,
            "statusCounts": status_counts,
            "subCapabilities": tuple(item.to_summary() for item in self.sub_capabilities),
        }

    def to_plan_summary(self) -> dict[str, Any]:
        """输出给 `/agent/plans` 的压缩能力域摘要。

        同步计划响应是高频接口，不能把完整矩阵全部塞进去。这里保留每个领域的计数、状态和最关键缺口，
        详细字段留给诊断接口。
        """

        gaps = tuple(
            item.closure_gap
            for item in self.sub_capabilities
            if item.status in {AgentCapabilityStatus.CONTROL_PLANE_READY, AgentCapabilityStatus.PLANNED, AgentCapabilityStatus.BLOCKED}
        )
        return {
            "domainId": self.domain_id,
            "displayName": self.display_name,
            "priority": self.priority,
            "ownerLayer": self.owner_layer,
            "subCapabilityCount": len(self.sub_capabilities),
            "statusCounts": _status_counts(self.sub_capabilities),
            "topClosureGaps": gaps[:3],
        }


@dataclass(frozen=True)
class AgentCapabilityMatrixService:
    """生成 Agent 能力完备度矩阵。

    当前默认矩阵使用代码内置基线，是为了让项目状态在测试、CI、本地学习和长线程恢复时保持一致。
    后续如果做管理台，可以把矩阵迁移到数据库或配置中心，但响应结构不需要推翻。
    """

    domains: tuple[AgentCapabilityDomain, ...] = field(default_factory=tuple)

    def diagnostics(self) -> dict[str, Any]:
        """生成完整 Agent 能力诊断矩阵。"""

        sub_capabilities = tuple(item for domain in self.domains for item in domain.sub_capabilities)
        return {
            "schemaVersion": "datasmart.agent-capability-matrix.v1",
            "diagnosticType": "AGENT_CAPABILITY_COMPLETENESS_MATRIX",
            "convergenceMode": "COMPLETE_CORE_AGENT_CAPABILITIES_THEN_CLOSE_PROJECT_LOOP",
            "payloadPolicy": "LOW_SENSITIVE_CAPABILITY_METADATA_ONLY",
            "designPrinciple": (
                "Agent 能力按 tools、skills、memory、query engine、context、permission、sub-agent、sessions、"
                "command、hook、tech stack、LLM 等域收敛；preview 和控制面合同不会被误判为真实执行完成。"
            ),
            "sourceReferences": (
                "OpenAI Agents SDK: orchestration/tools/approvals/state/handoffs",
                "MCP Tools: tools/list、tools/call、schema、human-in-the-loop",
                "LangChain Context Engineering: model context、tool context、life-cycle context",
                "DataSmart current-repo-state.md",
            ),
            "domainCount": len(self.domains),
            "subCapabilityCount": len(sub_capabilities),
            "statusCounts": _status_counts(sub_capabilities),
            "p0GapDomains": self._p0_gap_domains(),
            "nextClosureActions": self._next_closure_actions(),
            "domains": tuple(domain.to_summary() for domain in self.domains),
        }

    def plan_summary(self) -> dict[str, Any]:
        """生成 `/agent/plans` 可返回的压缩 Agent 能力闭环摘要。"""

        sub_capabilities = tuple(item for domain in self.domains for item in domain.sub_capabilities)
        return {
            "schemaVersion": "datasmart.agent-capability-closure.v1",
            "snapshotType": "AGENT_CAPABILITY_CLOSURE",
            "payloadPolicy": "LOW_SENSITIVE_CAPABILITY_METADATA_ONLY",
            "domainCount": len(self.domains),
            "subCapabilityCount": len(sub_capabilities),
            "statusCounts": _status_counts(sub_capabilities),
            "p0GapDomains": self._p0_gap_domains(),
            "nextClosureActions": self._next_closure_actions(),
            "domains": tuple(domain.to_plan_summary() for domain in self.domains),
        }

    def _p0_gap_domains(self) -> tuple[str, ...]:
        """列出 P0 且仍有未闭环子能力的领域。"""

        return tuple(
            domain.domain_id
            for domain in self.domains
            if domain.priority == "P0"
            and any(item.status != AgentCapabilityStatus.OPERATIONAL for item in domain.sub_capabilities)
        )

    def _next_closure_actions(self) -> tuple[str, ...]:
        """按收敛价值抽取下一步动作，避免一次响应给出过长路线图。"""

        actions: list[str] = []
        for domain in self.domains:
            for item in domain.sub_capabilities:
                if item.status in {AgentCapabilityStatus.CONTROL_PLANE_READY, AgentCapabilityStatus.BLOCKED}:
                    actions.append(item.next_action)
                if len(actions) >= 6:
                    return tuple(actions)
        for domain in self.domains:
            for item in domain.sub_capabilities:
                if item.status == AgentCapabilityStatus.PLANNED:
                    actions.append(item.next_action)
                if len(actions) >= 6:
                    return tuple(actions)
        return tuple(actions)


def default_agent_capability_matrix_service() -> AgentCapabilityMatrixService:
    """构建默认 Agent 能力完备度矩阵。"""

    return AgentCapabilityMatrixService(domains=_default_capability_domains())


def _status_counts(items: tuple[AgentSubCapability, ...]) -> dict[str, int]:
    """聚合能力状态数量。"""

    counts: dict[str, int] = {}
    for item in items:
        counts[item.status.value] = counts.get(item.status.value, 0) + 1
    return counts


def _cap(
    capability_id: str,
    display_name: str,
    status: AgentCapabilityStatus,
    owner_module: str,
    current_evidence: str,
    closure_gap: str,
    next_action: str,
    performance_notes: str,
    safety_notes: str,
) -> AgentSubCapability:
    """缩短默认矩阵声明，保持每个子能力字段仍然显式可读。"""

    return AgentSubCapability(
        capability_id=capability_id,
        display_name=display_name,
        status=status,
        owner_module=owner_module,
        current_evidence=current_evidence,
        closure_gap=closure_gap,
        next_action=next_action,
        performance_notes=performance_notes,
        safety_notes=safety_notes,
    )


def _default_capability_domains() -> tuple[AgentCapabilityDomain, ...]:
    """维护默认 Agent 能力域。

    这里覆盖用户点名的 tools、skills、memory、query engine、context、permission、sub-agent、sessions、
    command、hook、tech stack、LLM，并把每个域拆成当前项目需要真正闭环的子能力。
    """

    S = AgentCapabilityStatus
    return (
        AgentCapabilityDomain(
            domain_id="tools",
            display_name="工具系统",
            priority="P0",
            owner_layer="python-ai-runtime + java-agent-runtime",
            closure_goal="所有工具意图统一进入 ToolPlan -> readiness -> approval/resume gate -> outbox/worker receipt。",
            sub_capabilities=(
                _cap("tool.registry", "工具注册与发现", S.PARTIAL_CLOSED_LOOP, "python-ai-runtime/services/tools", "已有默认工具目录、Java manifest 加载和 MCP/A2A intake preview。", "真实动态工具市场、版本灰度和租户可见性还需继续对齐 Java 发布事实。", "把 tool registry 与 Java Skill/Tool Manifest 的版本指纹统一到一个发布事实源。", "工具列表应支持分页、缓存和变更通知，避免高频请求反复加载全量 schema。", "工具 schema 只能暴露低敏参数说明，高风险工具必须进入 readiness 与审批。"),
                _cap("tool.file-read-write", "文件读写工具", S.PLANNED, "agent-runtime worker", "当前已有 workspace/artifact namespace 与安全边界说明。", "尚未提供受控文件读取、写入、路径白名单和 artifact 引用物化。", "在 Java/Python worker 中实现 dangerous-path denylist、workspace root 校验和 artifactReference 写入。", "大文件读取需要分块、大小上限和摘要缓存。", "禁止越权路径、隐藏文件和凭据文件读取；写入必须产生审计事件。"),
                _cap("tool.exec-run-program", "命令执行与程序运行", S.CONTROL_PLANE_READY, "agent-runtime command/outbox", "已有 command proposal、checkpoint、resume gate、handoff 摘要、Java command safety precheck、worker precheck 低敏安全证据复核、最小 command worker receipt 写回合同、Python 受控 runner，以及 Python 进程内 worker lease/token fencing 合同。", "尚未落地真实沙箱进程执行、stdout/stderr 裁剪、生产级 Redis/Java durable lease、artifact 二次鉴权和 dead-letter 补偿。", "下一步接 sandbox/stdout-stderr 裁剪与 artifact 二次鉴权，同时把 in-memory lease 替换为跨实例 durable lease。", "命令执行必须有超时、输出大小限制、并发队列、重试退避、lease 续租和 receipt 幂等键。", "默认拒绝危险命令；即使 worker 回写副作用已发生，也只能记录低敏 outcome、decision、issueCode 计数、leaseVersion 和 artifactReference。"),
                _cap("tool.web-search", "网页搜索工具", S.PLANNED, "python-ai-runtime tool adapter", "当前架构已有外部工具 intake 和低敏事件边界。", "尚未定义搜索 Provider、结果缓存、引用来源和网络权限策略。", "先定义 web search 工具契约、网络 allowlist、结果引用与缓存 TTL，不直接开放任意联网。", "需要 rate limit、结果去重、缓存和超时控制。", "搜索摘要必须带来源，不保存用户敏感查询正文到公共日志。"),
                _cap("tool.readiness-checkpoint", "执行前准备度与恢复点", S.PARTIAL_CLOSED_LOOP, "python-ai-runtime/services/tools", "已有 readiness、graph、checkpoint query/resume-preview、controlPlaneHandoff。", "还缺 Java 侧真实 graphId、payloadReference、outbox fact 和 worker receipt。", "把 handoff 缺失证据由 Java execution graph/payload store/outbox writer 真实补齐。", "checkpoint store 需支持 Redis、多实例和 TTL 清理。", "恢复事实只能采信 Java host facts，不能采信请求自报。"),
            ),
        ),
        AgentCapabilityDomain(
            domain_id="skills",
            display_name="Skill 能力包",
            priority="P0",
            owner_layer="agent-runtime + python-ai-runtime",
            closure_goal="Skill 可获取、可加载、可见性可审计，创建/发布/下线进入管理闭环。",
            sub_capabilities=(
                _cap("skill.load", "加载 Skill", S.PARTIAL_CLOSED_LOOP, "python-ai-runtime/services/skills", "已有默认 Skill registry、admission、visibility snapshot 和 manifest diagnostics。", "还需把 Skill 版本、灰度、租户可见性与 Java 发布事实完全绑定。", "把 READY Skill manifest 作为唯一生产事实源，Python 默认 Skill 仅用于本地回退。", "Skill manifest 应缓存并支持指纹比较，避免每次规划远程拉取。", "Skill prompt、内部工具说明和权限明细不能在普通计划响应中扩散。"),
                _cap("skill.list", "获取 Skill 列表", S.PARTIAL_CLOSED_LOOP, "agent-runtime Skill Manifest", "已有 Skill Manifest 诊断和可见性 runtime event。", "还缺面向管理台的分页、过滤、版本历史和发布状态查询。", "在 Java agent-runtime 建立 Skill marketplace 只读查询与审计投影。", "列表查询需分页和按租户/项目缓存。", "只能返回低敏 descriptor，隐藏内部 prompt 与高风险参数模板。"),
                _cap("skill.create-publish", "创建与发布 Skill", S.PLANNED, "agent-runtime marketplace", "当前只有发布目录消费侧，没有创建/审批/发布闭环。", "缺少 Skill 创建、审核、灰度、回滚、兼容性校验和下线流程。", "先实现 Skill draft -> review -> ready -> deprecated 的控制面状态机。", "发布要避免阻塞主计划链路，建议异步校验和后台索引。", "高风险 Skill 发布必须经过管理员审批并产生日志。"),
                _cap("skill.security", "Skill 准入与权限", S.PARTIAL_CLOSED_LOOP, "permission-admin + python-ai-runtime", "已有 role/permission/policyVersion 对 Skill admission 的影响。", "权限事实仍需与真实 RBAC 表、审批单和租户套餐统一。", "把 Skill requiredPermissions 与 permission-admin 权限模型正式对齐。", "准入结果应按 session 缓存，减少重复策略评估。", "拒绝原因只返回 code，不暴露内部策略表达式。"),
            ),
        ),
        AgentCapabilityDomain(
            domain_id="memory",
            display_name="记忆系统",
            priority="P0",
            owner_layer="python-ai-runtime/services/memory",
            closure_goal="短期会话、长期记忆、profile、检索、创建和物化都具备低敏治理与审计。",
            sub_capabilities=(
                _cap("memory.short-term", "短期记忆", S.PARTIAL_CLOSED_LOOP, "runtime-events + sessions", "已有 runtime event、session manager、WebSocket replay 和 workspace namespace。", "还缺持久化会话快照和跨实例 session 热状态。", "将关键 session state 接 Redis 或 Java 控制面投影，支持断线与重启恢复。", "需要 TTL、热窗口裁剪和事件 replay limit。", "短期记忆不能保存未脱敏 prompt 或工具结果正文。"),
                _cap("memory.long-term", "长期记忆", S.PARTIAL_CLOSED_LOOP, "python-ai-runtime/services/memory", "已有 retrieval plan、write candidate、materialization worker、Chroma-compatible adapter。", "还缺 profile memory、删除/修订、质量评分和跨租户隔离压测。", "补 profile/semantic/episodic/procedural 类型治理和审批后物化 receipt。", "向量检索需 namespace filter、topK、超时和缓存。", "长期记忆写入必须经过候选、审批、审计与低敏裁剪。"),
                _cap("memory.sqlite-fts", "SQLite FTS 本地全文记忆", S.PLANNED, "python-ai-runtime memory adapter", "当前路线中提到 FTS，但未实现独立 adapter。", "缺少 SQLite FTS 本地索引、迁移、清理和检索融合。", "将 SQLite FTS 作为本地学习/轻量部署 adapter，保持与 Chroma adapter 同一接口。", "FTS 需要分词、增量更新、索引压缩和查询超时。", "本地索引不能跨 workspace 泄露，敏感正文需加密或不落库。"),
                _cap("memory.m-create-retrieve", "m-create / m-retrieve", S.PARTIAL_CLOSED_LOOP, "memory write/retrieval", "已有记忆写入候选与检索摘要。", "还缺统一 m-create/m-retrieve 工具契约和对 Agent loop 的恢复点接入。", "把记忆创建/检索变成受 permission-admin 保护的工具能力，而不是隐式副作用。", "检索结果要做 token budget 裁剪和缓存。", "返回模型前必须做低敏摘要，不直接注入敏感记忆正文。"),
            ),
        ),
        AgentCapabilityDomain(
            domain_id="query-engine",
            display_name="查询与模型调用引擎",
            priority="P0",
            owner_layer="python-ai-runtime/model_gateway",
            closure_goal="API、stream、cache、error、retry、rate limit、token limit 形成稳定模型调用治理。",
            sub_capabilities=(
                _cap("query.api", "同步 API 调用", S.PARTIAL_CLOSED_LOOP, "model_gateway", "已有 OpenAI-compatible Provider、route registry、health registry。", "还缺生产级调用审计、fallback 事件和 per-tenant 计费。", "补模型调用审计 outbox 和 provider fallback runtime event。", "需要连接池、超时、重试和熔断窗口。", "错误必须低敏，不返回 endpoint、key、prompt 或上游 body。"),
                _cap("query.stream", "流式输出", S.PARTIAL_CLOSED_LOOP, "openai_compatible_provider", "已有 stream chunk 与低敏错误处理。", "还缺统一 WebSocket/SSE 转发、断线恢复和 backpressure。", "将模型 stream 与 runtime event live push 的断线恢复策略对齐。", "需要 chunk 限速、缓冲上限和客户端取消。", "流式错误同样不能泄露 prompt、工具参数或 provider 原文。"),
                _cap("query.cache-token-limit", "缓存与 token 限制", S.CONTROL_PLANE_READY, "context/model_gateway", "已有 context selection、tool budget 和 maxContext 能力矩阵。", "还缺 prefix/KV cache 命中率治理、token 预算统一账本和模型侧 cache scope。", "建立 provider-neutral cache governance 摘要，先记录 cache scope、预算和命中率字段。", "缓存需按租户/项目/workspace 隔离，避免污染。", "缓存键不能包含明文 prompt、SQL 或工具参数。"),
                _cap("query.retry-rate-limit", "重试与限流", S.PLANNED, "gateway + model_gateway", "已有健康探测和预算策略基础。", "缺少统一 rate limit、retry policy、provider fallback 和租户配额闭环。", "先把模型调用 rate limit 与 gateway/permission-admin 套餐策略对齐。", "需要指数退避、熔断和队列拥塞保护。", "限流响应只返回 code 和窗口信息，不暴露内部配额公式。"),
            ),
        ),
        AgentCapabilityDomain(
            domain_id="context",
            display_name="上下文工程",
            priority="P0",
            owner_layer="python-ai-runtime/context",
            closure_goal="system prompt、消息、工具上下文、micro-compact、tool-compact 和 token 预算可治理。",
            sub_capabilities=(
                _cap("context.system-prompt", "System Prompt 与动态指令", S.PARTIAL_CLOSED_LOOP, "agent_orchestrator", "已有 AgentRequest、Skill admission 和 model route 对计划的影响。", "还缺版本化系统提示词、按角色动态提示词和提示词审计。", "将系统提示词模板与 Skill manifest/policyVersion 绑定。", "提示词拼装应可测并受 token budget 约束。", "普通诊断不能返回系统提示词全文。"),
                _cap("context.micro-compact", "微压缩与对话摘要", S.PLANNED, "context selection", "已有 context selection policy 基础。", "缺少 micro-compact、tool-compact、长会话摘要和摘要可信度标记。", "先实现低敏 micro-compact 摘要契约，再接入会话长上下文。", "摘要应按消息数量/token 阈值触发。", "摘要不能把敏感工具输出扩散到长期上下文。"),
                _cap("context.tool-compact", "工具上下文裁剪", S.CONTROL_PLANE_READY, "tool readiness + context", "已有 readiness 低敏字段名和参数问题计数。", "缺少真实工具结果裁剪、引用化和模型二轮注入策略。", "把 worker receipt/artifactReference 转成可注入模型的低敏 tool context。", "工具结果要按大小、类型和重要性裁剪。", "工具结果正文默认不注入，除非通过权限和脱敏。"),
            ),
        ),
        AgentCapabilityDomain(
            domain_id="permission",
            display_name="权限、安全与 HITL",
            priority="P0",
            owner_layer="gateway + permission-admin + agent-runtime",
            closure_goal="read/write/exec/network、dangerous-path、safe-cmd、human-in-the-loop 全部进入策略和审计。",
            sub_capabilities=(
                _cap("permission.read-write-exec-network", "读写执行联网权限", S.CONTROL_PLANE_READY, "permission-admin", "已有 readiness policy、gateway signature、tool budget 和 HITL 方向。", "还缺统一 read/write/exec/network 权限点与服务账号策略。", "把工具 capability 映射到 permission-admin 权限点和数据范围。", "策略结果应请求级缓存，避免同步链路重复调用。", "所有高风险能力必须 fail-closed。"),
                _cap("permission.dangerous-path-safe-cmd", "危险路径与安全命令", S.CONTROL_PLANE_READY, "agent-runtime worker", "已有 checkpoint/resume、command proposal、Java safety-precheck 路由、worker precheck 低敏证据复核、command worker receipt 合同，以及 Python worker lease/token fencing 合同，可在领取前阻断缺失/泄露/非 allow verdict，并在执行前避免多 worker 重复处理。", "还缺真实容器/进程级隔离、环境变量过滤、stdout/stderr 裁剪、跨实例 durable lease、artifact 二次鉴权和补偿台。", "补 sandbox/stdout-stderr 裁剪，再把 lease/receipt 与 dead-letter 补偿、artifact 权限和任务最终态串起来。", "命令执行必须限制 CPU/内存/时间和输出体积，并避免失败任务热循环。", "禁止访问凭据路径、系统路径和跨 workspace 文件；receipt 也不能回显命令、真实路径、stdout/stderr 或工具参数。"),
                _cap("permission.human-in-the-loop", "人工确认与审批", S.PARTIAL_CLOSED_LOOP, "permission-admin + agent-runtime", "已有审批/澄清门禁、resume gate、closure missing evidence。", "还缺真实审批单、确认事实、审批回放和过期/撤销策略。", "落地 approvalConfirmationFact 并接入 resume fact bundle。", "审批查询应支持低延迟和缓存失效。", "审批详情不能暴露工具参数值，只暴露低敏 reason code。"),
            ),
        ),
        AgentCapabilityDomain(
            domain_id="sub-agent",
            display_name="多 Agent 与子 Agent",
            priority="P1",
            owner_layer="python-ai-runtime + agent-runtime",
            closure_goal="八类专业 Agent、handoff、A2A、manager-as-tools 和死锁防护形成真实协作控制面。",
            sub_capabilities=(
                _cap("subagent.roster", "专业 Agent 名册", S.PARTIAL_CLOSED_LOOP, "agent_gateway", "已有会话级多 Agent 调度视图和八类 Agent 规划。", "还缺真实 Agent lifecycle、实例状态和动态扩缩容。", "把 Agent roster 发布为 Java 控制面事实，并接入健康/负载。", "需要 agent 并发上限和队列调度。", "每个 Agent 只能访问授权 workspace 和工具集合。"),
                _cap("subagent.handoff", "Handoff 与协作", S.CONTROL_PLANE_READY, "agent-runtime protocol adapters", "已有 A2A task preview、session scheduling、handoff 建议。", "还缺真实 handoff task、状态持久化和取消/恢复。", "实现最小 A2A/task handoff fact，而不是继续 preview。", "handoff 需要 timeout、deadlock 检测和重试。", "handoff 不能绕过权限、审批和 outbox。"),
                _cap("subagent.manager-as-tools", "Manager 调度子 Agent", S.PLANNED, "python-ai-runtime orchestrator", "当前仍主要是单次计划与调度摘要。", "缺少可恢复多轮 graph、子 Agent 工具化和结果仲裁。", "在 durable runner 之后再引入 manager-as-tools，避免提前复杂化。", "需要限制循环深度和子任务数量。", "子 Agent 输出必须低敏裁剪后才能进入主 Agent 上下文。"),
            ),
        ),
        AgentCapabilityDomain(
            domain_id="sessions",
            display_name="会话与运行状态",
            priority="P0",
            owner_layer="runtime_events + gateway",
            closure_goal="session、thread、run、event replay、WebSocket、状态恢复和多实例一致性闭环。",
            sub_capabilities=(
                _cap("session.lifecycle", "Session 生命周期", S.PARTIAL_CLOSED_LOOP, "runtime_events", "已有 sessionId、RuntimeEventSessionManager 和 envelope。", "还缺持久化 session store、过期清理和多实例共享。", "把 session 热状态接入 Redis 或 Java session fact 表。", "需要 TTL、最大事件数和快速查询索引。", "session 不能跨 tenant/project 混用。"),
                _cap("session.replay-stream", "事件回放与实时流", S.PARTIAL_CLOSED_LOOP, "runtime_events", "已有 event envelope、replay、control、WebSocket payload。", "还缺生产级 Kafka/Redis stream 适配和 ack 补偿。", "将 runtime event publisher 升级为 outbox/异步发布以降低同步请求风险。", "需要 backpressure、分页 cursor 和断点恢复。", "事件 attributes 必须维持低敏白名单。"),
            ),
        ),
        AgentCapabilityDomain(
            domain_id="command",
            display_name="命令与 Durable Action",
            priority="P0",
            owner_layer="agent-runtime",
            closure_goal="命令 proposal、execution graph、payloadReference、outbox、worker receipt 串成最小 durable action 闭环。",
            sub_capabilities=(
                _cap("command.proposal", "命令 Proposal", S.PARTIAL_CLOSED_LOOP, "python-ai-runtime + agent-runtime", "已有 command proposal template、handoff 摘要和 Java proposal 规划。", "还缺真实 proposal 写入和 proposal 状态查询。", "让 Python handoff 调用 Java proposal preflight 并获得 host facts。", "proposal 应幂等、可分页查询并记录低敏指标。", "proposal body 必须引用 payloadReference，不直接承载敏感参数。"),
                _cap("command.outbox-worker-receipt", "Outbox 与 Worker Receipt", S.PARTIAL_CLOSED_LOOP, "agent-runtime worker", "已有 outbox writer、dispatcher precheck、运行时事件/指标、命令安全证据复核、command worker receipt 低敏写回/索引/timeline 合同、Python 受控 runner，以及 Python in-memory worker lease/token fencing 合同。", "真实 sandbox runner、跨实例 durable lease、stdout/stderr 裁剪、失败补偿和 artifact 二次鉴权尚未完成。", "优先把 lease store 替换为 Redis/Java durable fact，并接入 sandbox 输出裁剪与 artifact 权限闭环。", "需要队列积压、并发、重试、死信、幂等键、lease 续租和 receipt replaySequence 对账。", "worker receipt 是副作用证据，必须携带有效 fencingToken 才能写回；同时不能携带命令、路径、输出正文或凭据。"),
            ),
        ),
        AgentCapabilityDomain(
            domain_id="hook",
            display_name="生命周期 Hook 与观测",
            priority="P1",
            owner_layer="runtime_events + observability",
            closure_goal="before/after model、before/after tool、approval、memory、error、trace hook 全部事件化。",
            sub_capabilities=(
                _cap("hook.lifecycle-events", "生命周期事件", S.PARTIAL_CLOSED_LOOP, "runtime_events", "已有大量 runtime event、timeline projection 和 low-sensitive attributes。", "还缺统一 hook registry、before/after 节点和自定义插件 hook。", "定义 hook taxonomy，并把关键 hook 纳入 replay/projection。", "hook 不能阻塞主链路，复杂处理应异步。", "hook payload 必须按事件类型白名单裁剪。"),
                _cap("hook.metrics-tracing", "指标与追踪", S.PARTIAL_CLOSED_LOOP, "observability + python metrics", "已有 Prometheus 文本指标、provider health 和 checkpoint 指标。", "还缺端到端 traceId、跨 Java/Python trace 聚合和告警闭环。", "统一 trace/event/metric correlation id，并接入 observability 告警。", "避免高基数 label，聚合低敏 code。", "trace 不能包含 prompt、工具参数或模型输出正文。"),
            ),
        ),
        AgentCapabilityDomain(
            domain_id="tech-stack",
            display_name="技术栈与运行底座",
            priority="P0",
            owner_layer="platform",
            closure_goal="Java/Python/Redis/Kafka/Chroma/Neo4j/MinIO/vLLM 等底座可替换、可诊断、可部署。",
            sub_capabilities=(
                _cap("stack.fixed-backend", "固定后端技术栈", S.OPERATIONAL, "root/maven/docker", "JDK 21、Spring Boot、Kafka、MySQL、Redis、Neo4j、Chroma、MinIO 已在项目契约中固定。", "生产部署硬化和环境分层仍需后续完善。", "继续维护 JDK 21 toolchain 与本地/生产配置说明。", "需要按环境拆分资源配额和连接池。", "配置不能包含明文密钥。"),
                _cap("stack.replaceable-adapters", "可替换适配层", S.PARTIAL_CLOSED_LOOP, "python-ai-runtime services", "已有 model provider、memory store、checkpoint store 等抽象。", "vector store、workflow engine、web search provider 仍需更明确 adapter 契约。", "把 Chroma/SQLite FTS/未来 pgvector 保持在同一 memory adapter 边界。", "适配层要支持健康诊断和降级。", "替换实现不得改变权限和低敏边界。"),
            ),
        ),
        AgentCapabilityDomain(
            domain_id="llm",
            display_name="LLM 与推理服务",
            priority="P0",
            owner_layer="model_gateway",
            closure_goal="模型接入、路由、健康、fallback、能力矩阵、缓存治理和 token 预算闭环；不做项目内训练或底层推理内核。",
            sub_capabilities=(
                _cap("llm.provider-routing", "Provider 路由", S.PARTIAL_CLOSED_LOOP, "model_gateway", "已有 route registry、OpenAI-compatible provider、dry-run/real provider 区分。", "还缺真实 fallback 策略事件和租户级模型套餐。", "把 provider health、capability matrix 和 permission-admin 套餐合并为路由评分。", "路由需要健康窗口、延迟评分和预算控制。", "路由响应不返回 endpoint 或 API Key。"),
                _cap("llm.model-family-adaptation", "新一代模型适配", S.CONTROL_PLANE_READY, "model capability registry", "已有模型能力矩阵，路线不再锁死 Qwen2。", "DeepSeek/Qwen/GLM 等具体 SKU 的工具调用、上下文、结构化输出和压测仍需验证。", "按 provider-neutral capability profile 管理 DeepSeek、Qwen、GLM 等模型，不写死业务逻辑。", "需要上下文长度、并发、吞吐和 cache 命中率基线。", "模型输出进入工具链前必须经过 readiness 和安全裁剪。"),
                _cap("llm.inference-optimization", "成熟推理优化接入", S.PLANNED, "vLLM/SGLang/LiteLLM adapter", "项目路线已明确不做自研推理内核、微调或后训练。", "缺少 prefix/KV cache 指标、batching 配置和推理服务健康诊断接入。", "接入成熟推理服务的 cache/batching/parallel 配置诊断，而不是在项目内自研。", "需要 TTFT、TPS、queue time、cache hit rate 指标。", "cache scope 必须按租户和 workspace 隔离。"),
            ),
        ),
    )

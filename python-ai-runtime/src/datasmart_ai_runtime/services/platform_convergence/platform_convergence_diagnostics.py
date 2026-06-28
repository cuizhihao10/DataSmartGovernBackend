"""平台收敛诊断服务。

这个文件不是再扩展一个“业务功能点”，而是给 DataSmart Govern 增加一块收敛控制面：
当项目同时存在 Java 微服务、Python AI Runtime、智能网关、A2A/MCP 协议适配、模型网关、长期记忆、
数据同步和治理模块时，如果没有一个统一的闭环视图，后续开发很容易继续在某一个局部模块无限加字段、
无限 preview，却忘记平台最终要形成可商用、可运营、可审计的端到端产品。

本服务的设计目标：
- 把“当前已经完成什么”与“还没闭环什么”放在同一份响应里；
- 用稳定 domainId 管理大模块，避免后续讨论只盯着 datasource 或 agent-runtime 单点；
- 明确每个模块进入下一阶段前的退出条件，帮助项目从发散走向收敛；
- 只输出低敏治理元数据，不读取业务库、不调用模型、不返回用户输入、工具实参、样本数据或内部服务地址。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any


class ConvergencePhase(str, Enum):
    """平台收敛阶段枚举。

    阶段不是传统项目管理里的“完成百分比”，而是表达当前模块距离商业闭环的真实状态：
    - `FOUNDATION_READY`：基础骨架、配置或关键抽象已经存在，但还不能承接完整生产流程；
    - `CONTROL_PLANE_READY`：控制面合同、只读诊断、权限/预算/事件等治理能力已有清晰形态；
    - `PARTIAL_CLOSED_LOOP`：至少有一条主链路能跑通，但可靠性、持久化、权限或运营面仍需补齐；
    - `PREVIEW_ONLY`：当前主要是 preview/contract，故意不产生副作用，不能被误认为真实执行；
    - `COMMERCIAL_HARDENING_PENDING`：功能链路基本成形，但还缺生产部署、压测、安全或可观测硬化。
    """

    FOUNDATION_READY = "foundation_ready"
    CONTROL_PLANE_READY = "control_plane_ready"
    PARTIAL_CLOSED_LOOP = "partial_closed_loop"
    PREVIEW_ONLY = "preview_only"
    COMMERCIAL_HARDENING_PENDING = "commercial_hardening_pending"


@dataclass(frozen=True)
class PlatformConvergenceDomainStatus:
    """单个产品域的收敛状态。

    字段说明：
    - `domain_id`：稳定机器标识，后续管理台、测试和文档都应引用它，而不是依赖中文展示名；
    - `display_name`：面向用户和项目复盘的中文名称；
    - `owner_layer`：该能力主要归属的系统层，帮助我们判断是否把逻辑放错模块；
    - `priority`：P0/P1/P2/P3，对齐项目 playbook，避免低优先级能力抢走闭环节奏；
    - `current_phase`：当前真实成熟度，不因为某个接口存在就直接标记为完成；
    - `target_phase`：本轮收敛希望达到的阶段，用来衡量“这一块什么时候该停下转向下个模块”；
    - `completed_capabilities`：已经存在且可以继续复用的能力，避免重复建设；
    - `open_gaps`：还没有闭环的产品/技术缺口，是下一步排期的主要来源；
    - `closure_exit_criteria`：退出条件，满足后本模块应暂停扩张，转去补其他大模块；
    - `next_actions`：推荐的近期动作，强调端到端闭环而不是局部加字段；
    - `depends_on`：跨模块依赖，帮助识别谁不能单独闭环；
    - `risk_level`：当前对商业化闭环的风险程度；
    - `commercial_blockers`：上线或售卖前必须正视的阻塞项。
    """

    domain_id: str
    display_name: str
    owner_layer: str
    priority: str
    current_phase: ConvergencePhase
    target_phase: ConvergencePhase
    completed_capabilities: tuple[str, ...] = field(default_factory=tuple)
    open_gaps: tuple[str, ...] = field(default_factory=tuple)
    closure_exit_criteria: tuple[str, ...] = field(default_factory=tuple)
    next_actions: tuple[str, ...] = field(default_factory=tuple)
    depends_on: tuple[str, ...] = field(default_factory=tuple)
    risk_level: str = "MEDIUM"
    commercial_blockers: tuple[str, ...] = field(default_factory=tuple)

    def to_summary(self) -> dict[str, Any]:
        """输出 API 可返回的低敏领域摘要。

        这里故意不包含 endpoint、配置原值、内部路径、真实租户、真实任务、工具参数或模型调用内容。
        它是一张“闭环地图”，不是运行时数据导出。
        """

        return {
            "domainId": self.domain_id,
            "displayName": self.display_name,
            "ownerLayer": self.owner_layer,
            "priority": self.priority,
            "currentPhase": self.current_phase.value,
            "targetPhase": self.target_phase.value,
            "completedCapabilities": self.completed_capabilities,
            "openGaps": self.open_gaps,
            "closureExitCriteria": self.closure_exit_criteria,
            "nextActions": self.next_actions,
            "dependsOn": self.depends_on,
            "riskLevel": self.risk_level,
            "commercialBlockers": self.commercial_blockers,
        }


@dataclass(frozen=True)
class PlatformConvergenceDiagnosticsService:
    """生成平台级收敛诊断。

    当前实现使用代码内置基线，这是有意为之：
    - 它不依赖数据库或远端服务，确保本地学习、CI 和早期部署都能看到同一份收敛地图；
    - 它把“我们决定先收敛哪些模块”固化成可测试契约，避免只停留在聊天记录；
    - 后续商业化管理台可以把这些状态迁移到配置中心或数据库，但 API 响应结构不必推翻。
    """

    domains: tuple[PlatformConvergenceDomainStatus, ...] = field(default_factory=tuple)

    def diagnostics(self) -> dict[str, Any]:
        """生成平台收敛诊断响应。

        响应回答四个问题：
        1. 整体项目当前应该如何从发散转为闭环；
        2. 哪些大模块已经有控制面或部分闭环，哪些仍只是 preview；
        3. 下一阶段最应该补的端到端缺口是什么；
        4. 哪些模块达到退出条件后应停止继续局部扩张。
        """

        phase_counts = self._phase_counts()
        risk_counts = self._risk_counts()
        p0_domains = tuple(domain.domain_id for domain in self.domains if domain.priority == "P0")
        return {
            "schemaVersion": "datasmart.platform-convergence-diagnostics.v1",
            "diagnosticType": "PLATFORM_CONVERGENCE_CONTROL_PLANE",
            "convergenceBoundary": (
                "当前阶段开始从能力发散转向产品闭环：优先补齐智能网关、Agent Host、任务、权限、数据同步、"
                "模型接入、记忆和可观测之间的端到端链路；不再无限扩展单个 preview 或局部字段。"
            ),
            "sensitiveDataPolicy": (
                "本诊断只返回模块成熟度、缺口、退出条件和推荐动作；不读取或返回业务正文、用户输入、"
                "工具实参、模型响应、凭证、真实租户数据或内部服务地址。"
            ),
            "sourceReferences": (
                "README.md",
                "references/project-playbook.md",
                "references/commercial-readiness-review.md",
                "references/enterprise-product-requirements.md",
                "references/current-repo-state.md",
            ),
            "domainCount": len(self.domains),
            "phaseCounts": phase_counts,
            "riskCounts": risk_counts,
            "p0FocusDomains": p0_domains,
            "overallStrategy": (
                "先把 P0 链路闭环：gateway 会话与签名、permission-admin 策略、task-management 任务事实、"
                "agent-runtime 事件/工具控制面、Python Runtime 编排与模型网关、datasource/data-sync 真实任务生命周期。",
                "每个模块只做到本轮退出条件即可转向下一个关键缺口，避免继续在局部能力上无限精修。",
                "模型层坚持成熟 Provider/推理服务接入、健康探测、能力矩阵、预算和缓存治理，不启动项目内训练或底层推理内核研发。",
                "Agent 能力要向 Codex/Claude Code 类 Host 靠拢，但所有副作用必须进入权限、审批、outbox、worker receipt 和 runtime event 闭环。",
            ),
            "commercialClosureRoute": (
                "第一步：把 MCP/A2A/model tool_call 统一进入 ToolPlan -> readiness -> resume gate -> outbox/worker 的同一治理链路。",
                "第二步：把 datasource-management/data-sync 的连接器、同步模式、checkpoint、重试和任务台接入 task-management。",
                "第三步：把 data-quality 从规则草稿推进到规则生命周期、执行历史、报告和质量任务联动。",
                "第四步：补平台级安全、审计、低基数指标、生产配置和部署硬化，而不是继续新增孤立 preview。",
            ),
            "domains": tuple(domain.to_summary() for domain in self.domains),
        }

    def _phase_counts(self) -> dict[str, int]:
        """按收敛阶段聚合模块数量，便于管理台快速判断项目是否仍偏 preview。"""

        counts: dict[str, int] = {}
        for domain in self.domains:
            counts[domain.current_phase.value] = counts.get(domain.current_phase.value, 0) + 1
        return counts

    def _risk_counts(self) -> dict[str, int]:
        """按风险等级聚合模块数量，用于后续接入运营看板或发布门禁。"""

        counts: dict[str, int] = {}
        for domain in self.domains:
            counts[domain.risk_level] = counts.get(domain.risk_level, 0) + 1
        return counts


def default_platform_convergence_diagnostics_service() -> PlatformConvergenceDiagnosticsService:
    """构建默认平台收敛诊断服务。

    这些领域状态来自当前项目实现进度与 skill/reference 文档的规划边界。它们不是一次性路线图，
    而是后续每完成一个大阶段都应更新的“工程事实基线”。
    """

    return PlatformConvergenceDiagnosticsService(domains=_default_domain_statuses())


def _default_domain_statuses() -> tuple[PlatformConvergenceDomainStatus, ...]:
    """维护默认领域状态表。

    拆成独立函数是为了让 `default_platform_convergence_diagnostics_service()` 保持清爽，也方便后续单测
    直接检查领域集合，而不用实例化 FastAPI 应用。
    """

    return (
        PlatformConvergenceDomainStatus(
            domain_id="gateway-intelligent-access",
            display_name="智能网关与统一入口",
            owner_layer="gateway",
            priority="P0",
            current_phase=ConvergencePhase.PARTIAL_CLOSED_LOOP,
            target_phase=ConvergencePhase.COMMERCIAL_HARDENING_PENDING,
            completed_capabilities=(
                "已有 gateway 到 Python Runtime 的 Agent plan 主链路和 HMAC 签名上下文。",
                "已有 READY Skill 准入缓存上下文，避免 Python 侧自行扩大工具可见性。",
                "已有 gateway signature 诊断和 nonce/replay 防护基础。",
            ),
            open_gaps=(
                "还需要把服务账号、租户、项目、角色、数据范围和 rate limit 统一成正式入口策略。",
                "还需要把多渠道入口、会话路由、WebSocket 事件恢复和异常降级整理为一套网关产品合同。",
            ),
            closure_exit_criteria=(
                "Agent plan、runtime event replay、checkpoint/resume-preview 等 P0 入口具备统一签名或等价认证。",
                "网关能把权限、预算、Skill 可见性和追踪上下文稳定传递到 Java/Python 控制面。",
            ),
            next_actions=(
                "优先补服务间认证统一策略，避免 Python/Java 控制面长期依赖分散式令牌配置。",
                "把 P0 Agent 入口的限流、熔断和低敏审计事件纳入统一网关规则。",
            ),
            depends_on=("permission-admin", "agent-runtime", "python-ai-runtime"),
            risk_level="HIGH",
            commercial_blockers=("生产级服务间认证、统一限流和入口审计尚未完全闭环。",),
        ),
        PlatformConvergenceDomainStatus(
            domain_id="permission-admin",
            display_name="权限、角色与策略中心",
            owner_layer="java-core-business",
            priority="P0",
            current_phase=ConvergencePhase.CONTROL_PLANE_READY,
            target_phase=ConvergencePhase.COMMERCIAL_HARDENING_PENDING,
            completed_capabilities=(
                "已有工具预算和执行准备度策略，可向 Python Runtime 输出受控 policy snapshot。",
                "已有角色、套餐、workspace 风险和 worker backlog 对 Agent 工具调用的影响模型。",
            ),
            open_gaps=(
                "菜单、按钮、数据源、任务、导出、模型配置、服务账号等权限维度还需形成统一产品模型。",
                "审批、审计、权限缓存一致性和权限变更传播还需要进入真实管理闭环。",
            ),
            closure_exit_criteria=(
                "P0 API 都能用 tenant/project/actor/role/dataScope 做一致授权判断。",
                "高风险工具、数据同步、质量规则发布和导出动作能进入审批与审计链路。",
            ),
            next_actions=(
                "把 tool readiness policy 与真实角色/权限表、审批记录和服务账号策略对齐。",
                "补权限管理的低敏审计事件和权限缓存失效策略。",
            ),
            depends_on=("gateway-intelligent-access", "task-management", "agent-runtime"),
            risk_level="HIGH",
            commercial_blockers=("权限维度尚未覆盖完整企业管理面，审批与审计仍需产品化。",),
        ),
        PlatformConvergenceDomainStatus(
            domain_id="task-management",
            display_name="任务管理与运营控制台",
            owner_layer="java-core-business",
            priority="P0",
            current_phase=ConvergencePhase.PARTIAL_CLOSED_LOOP,
            target_phase=ConvergencePhase.COMMERCIAL_HARDENING_PENDING,
            completed_capabilities=(
                "任务模块是当前 Java 业务层中最成熟的基础模块，可作为其他模块分层范式参考。",
                "已有任务生命周期、服务层、持久化和测试基础，适合承接数据同步/质量/Agent 任务事实。",
            ),
            open_gaps=(
                "还需要把 Agent task、A2A task、data-sync run、quality run 统一映射到平台任务事实。",
                "还需要完善队列、优先级、暂停/恢复、选择性重试、补偿和运营操作审计。",
            ),
            closure_exit_criteria=(
                "至少一条 data-sync 或 data-quality 业务流程能创建任务、执行、失败重试、恢复和展示历史。",
                "任务状态、runtime event、outbox/worker receipt 和操作审计能按同一 taskId 关联查询。",
            ),
            next_actions=(
                "优先把 datasource-management/data-sync 的同步任务接入统一任务生命周期。",
                "把 Agent runtime 的 preview task 合同逐步落到 task-management 的 durable task fact。",
            ),
            depends_on=("datasource-management-data-sync", "agent-runtime", "observability"),
            risk_level="HIGH",
            commercial_blockers=("跨模块任务事实尚未统一，真实运营控制面仍不完整。",),
        ),
        PlatformConvergenceDomainStatus(
            domain_id="datasource-management-data-sync",
            display_name="数据源管理与数据同步",
            owner_layer="java-core-business",
            priority="P0",
            current_phase=ConvergencePhase.CONTROL_PLANE_READY,
            target_phase=ConvergencePhase.PARTIAL_CLOSED_LOOP,
            completed_capabilities=(
                "已有数据源管理基础能力和大量注释整改，模块边界逐步清晰。",
                "产品文档已定义连接器矩阵、同步模式、状态机、API outline、领域模型和 schema 设计方向。",
                "data-sync 已新增低敏连接器能力矩阵和源/目标/模式兼容性预检，为 MySQL、PostgreSQL、Kafka、文件和对象存储等场景提供统一能力事实。",
            ),
            open_gaps=(
                "真实 connector worker 读取写入、datasource-management 实例能力探测、密钥绑定和元数据采集仍需产品化落地。",
                "全量/增量/CDC/批量模式还需要与 task-management、checkpoint、重试、回放和告警形成端到端闭环。",
            ),
            closure_exit_criteria=(
                "MySQL/PostgreSQL 至少形成统一连接器抽象、连接测试、能力探测和元数据采集闭环。",
                "至少一种同步模式能进入任务创建、执行记录、checkpoint、失败重试和低敏运行历史。",
            ),
            next_actions=(
                "把 connector capability 与 datasource-management 连接测试、metadata discovery 和模板校验打通。",
                "同步任务应先支持全量/增量最小闭环，再逐步扩展 CDC、回放、backfill 和跨租户限流。",
            ),
            depends_on=("task-management", "permission-admin", "observability"),
            risk_level="HIGH",
            commercial_blockers=("数据同步仍未形成可执行闭环，是平台从治理概念走向业务产品的关键缺口。",),
        ),
        PlatformConvergenceDomainStatus(
            domain_id="data-quality",
            display_name="数据质量治理",
            owner_layer="java-core-business",
            priority="P1",
            current_phase=ConvergencePhase.FOUNDATION_READY,
            target_phase=ConvergencePhase.PARTIAL_CLOSED_LOOP,
            completed_capabilities=(
                "已有质量模块基础结构，能承接后续规则、检测和报告链路。",
                "Python Agent 规划中已有质量规则建议工具和 readiness 治理入口。",
            ),
            open_gaps=(
                "规则草稿、审批、发布、执行、异常样本引用、清洗建议和报告导出仍未形成完整生命周期。",
                "质量任务还未与 task-management、数据源元数据和长期记忆候选闭环。",
            ),
            closure_exit_criteria=(
                "质量规则能从 Agent 建议进入草稿、人工确认、发布和执行历史。",
                "质量执行能产生低敏报告元数据、任务状态和可观测指标。",
            ),
            next_actions=(
                "在数据同步最小闭环后，优先补质量规则生命周期和质量执行 run。",
                "质量报告正文应进入 MinIO 或报告存储，事件和诊断只返回 metadata/ref。",
            ),
            depends_on=("datasource-management-data-sync", "task-management", "permission-admin"),
            risk_level="MEDIUM",
            commercial_blockers=("质量治理尚未从建议型 Agent 能力推进到真实规则执行和报告闭环。",),
        ),
        PlatformConvergenceDomainStatus(
            domain_id="agent-runtime",
            display_name="Java Agent Host 与控制面",
            owner_layer="agent-runtime",
            priority="P0",
            current_phase=ConvergencePhase.PARTIAL_CLOSED_LOOP,
            target_phase=ConvergencePhase.COMMERCIAL_HARDENING_PENDING,
            completed_capabilities=(
                "已有 A2A/MCP 发现、A2A task 状态机、事件合同、查询预览等控制面草案。",
                "已有 runtime event projection、工具准备度投影、Skill 发布和恢复门控图等低敏事实视图。",
                "质量治理真实提交链路已经具备 readiness、payloadReference、人审确认、Host submit、提交事实、下游任务创建幂等、UNKNOWN 人工恢复和低敏查询入口。",
                "文件拆分和 500 行约束已纳入持续治理，避免控制器/Impl 无限膨胀。",
            ),
            open_gaps=(
                "真实副作用链路目前主要在质量治理垂直场景闭合，尚未沉淀成所有高风险工具共享的 durable runner 模板。",
                "A2A/MCP/model tool_call 还需要统一 adapter contract，进入同一 ToolPlan、readiness、approval、outbox、worker receipt 和 recovery 链路。",
                "容器级 sandbox、对象级 DLP、服务账号签名、自动调度型终态回调 worker 和审计事件仍需继续收敛。",
            ),
            closure_exit_criteria=(
                "除质量治理外，至少再有一类高风险工具能复用同一 ToolPlan -> readiness -> approval -> outbox -> worker receipt -> recovery 模板。",
                "preview-only 控制面明确升级为真实执行链路前的发布门禁，已闭合链路不再继续无限扩字段。",
            ),
            next_actions=(
                "把质量治理链路沉淀为统一 tool action adapter 和 OpenClaw-style 可暂停/可恢复执行模板。",
                "实现 MCP/A2A/model tool_call 到 ToolPlan 的统一 adapter contract，而不是继续为单个工具单独铺流程。",
            ),
            depends_on=("python-ai-runtime", "permission-admin", "task-management"),
            risk_level="HIGH",
            commercial_blockers=("真实副作用闭环尚未通用化，质量治理以外的工具仍需复用同一安全执行模板。",),
        ),
        PlatformConvergenceDomainStatus(
            domain_id="python-ai-runtime",
            display_name="Python AI Runtime 与 Agent 编排",
            owner_layer="python-ai-runtime",
            priority="P0",
            current_phase=ConvergencePhase.PARTIAL_CLOSED_LOOP,
            target_phase=ConvergencePhase.PARTIAL_CLOSED_LOOP,
            completed_capabilities=(
                "已有 Agent plan、工具规划、readiness、resume-preview、checkpoint、MCP intake preview、A2A planning preview。",
                "已有长期记忆写入治理、物化 worker、模型网关治理、Provider health 和模型能力矩阵。",
                "已有面向 Codex/Claude Code 类 Agent Host 的事件、恢复和工具控制面基础。",
            ),
            open_gaps=(
                "真实工具执行仍受控于 preview/门控阶段，尚未形成 durable side-effect runner。",
                "LangGraph/OpenClaw-style runner 还需要把条件节点、暂停、恢复、审批和 checkpoint 串成真实链路。",
            ),
            closure_exit_criteria=(
                "至少一条工具动作能从 plan 到 readiness、outbox、worker receipt、checkpoint/replay 形成闭环。",
                "Agent 编排的失败、超时、人工输入、预算阻断和恢复预检都有可解释事件。",
            ),
            next_actions=(
                "优先做 unified tool action adapter 和 OpenClaw-style runner，不再单独增加更多散点 preview。",
                "保持模型、记忆、工具和事件目录解耦，避免 Python Runtime 再变回平铺大文件结构。",
            ),
            depends_on=("agent-runtime", "model-gateway", "permission-admin"),
            risk_level="HIGH",
            commercial_blockers=("仍缺真实可恢复执行器和 Java durable action 的最终闭环。",),
        ),
        PlatformConvergenceDomainStatus(
            domain_id="model-gateway",
            display_name="模型网关与推理接入治理",
            owner_layer="python-ai-runtime",
            priority="P1",
            current_phase=ConvergencePhase.CONTROL_PLANE_READY,
            target_phase=ConvergencePhase.PARTIAL_CLOSED_LOOP,
            completed_capabilities=(
                "已有 Provider 抽象、模型路由、预算、健康探测、能力矩阵和 DeepSeek/Qwen/GLM 可替换画像。",
                "已明确不在项目内做算法训练、后训练或底层推理内核优化，而是接入成熟推理服务。",
            ),
            open_gaps=(
                "仍缺模型 benchmark/eval 小闭环、灰度权重、fallback 策略和真实 Provider 压测报告。",
                "vLLM/SGLang 仍是服务形态建议，未形成部署脚本、压测配置或 GPU 运维基线。",
            ),
            closure_exit_criteria=(
                "Agent planning、tool calling、治理问答、Embedding、Rerank 至少有最小评测集与低敏报告。",
                "真实 Provider 路由能完成 health probe、预算、缓存边界和失败 fallback 验证。",
            ),
            next_actions=(
                "补最小 benchmark/eval，而不是继续扩展模型画像字段。",
                "把模型路由灰度、fallback 和缓存隔离接入统一诊断。",
            ),
            depends_on=("python-ai-runtime", "observability"),
            risk_level="MEDIUM",
            commercial_blockers=("模型接入治理有控制面，但缺真实评测、灰度和压测事实。",),
        ),
        PlatformConvergenceDomainStatus(
            domain_id="observability",
            display_name="可观测性与运维诊断",
            owner_layer="observability",
            priority="P1",
            current_phase=ConvergencePhase.FOUNDATION_READY,
            target_phase=ConvergencePhase.COMMERCIAL_HARDENING_PENDING,
            completed_capabilities=(
                "已有 Spring Actuator/Prometheus/Grafana 基础方向。",
                "Python Runtime 已为模型健康、记忆物化、checkpoint 等能力输出低基数指标。",
            ),
            open_gaps=(
                "跨 Java/Python 的 trace、runtime event、taskId、runId 和告警规则尚未统一。",
                "日志集中化、告警分级、SLO、仪表盘和生产排障手册仍需补齐。",
            ),
            closure_exit_criteria=(
                "P0 链路具备统一 traceId、低基数指标、关键告警和 runtime event replay。",
                "至少能从一次失败任务追踪到 gateway、permission、agent-runtime、Python Runtime 和 worker 证据。",
            ),
            next_actions=(
                "围绕 P0 闭环补指标和告警，不先做大而全监控平台。",
                "统一事件/指标敏感边界，避免把高基数字段放入 Prometheus label。",
            ),
            depends_on=("gateway-intelligent-access", "task-management", "python-ai-runtime"),
            risk_level="MEDIUM",
            commercial_blockers=("端到端排障链路还不完整，生产事故定位能力不足。",),
        ),
        PlatformConvergenceDomainStatus(
            domain_id="deployment-middleware",
            display_name="部署、中间件与生产配置",
            owner_layer="infrastructure",
            priority="P0",
            current_phase=ConvergencePhase.FOUNDATION_READY,
            target_phase=ConvergencePhase.COMMERCIAL_HARDENING_PENDING,
            completed_capabilities=(
                "已有 Docker Compose、Maven Toolchain/JDK 21 说明和本地中间件基线。",
                "MySQL、Redis、Kafka、Neo4j、Chroma、MinIO 等目标栈已在架构中明确。",
            ),
            open_gaps=(
                "开发、测试、生产配置隔离、密钥管理、数据库迁移、备份恢复和资源限制仍需硬化。",
                "Compose 适合本地与集成验证，但不是最终商业生产部署形态。",
            ),
            closure_exit_criteria=(
                "本地一键启动、测试 profile、生产 profile、密钥注入和迁移脚本都有清晰说明。",
                "P0 服务能在 clean environment 下按文档启动并完成核心 smoke test。",
            ),
            next_actions=(
                "补生产配置档与密钥注入边界，避免把本地配置当成上线配置。",
                "为核心表和新控制面事实补迁移/初始化脚本与 smoke test。",
            ),
            depends_on=("gateway-intelligent-access", "task-management", "datasource-management-data-sync"),
            risk_level="HIGH",
            commercial_blockers=("部署与配置仍偏本地开发形态，商业交付前必须硬化。",),
        ),
    )


__all__ = [
    "ConvergencePhase",
    "PlatformConvergenceDiagnosticsService",
    "PlatformConvergenceDomainStatus",
    "default_platform_convergence_diagnostics_service",
]

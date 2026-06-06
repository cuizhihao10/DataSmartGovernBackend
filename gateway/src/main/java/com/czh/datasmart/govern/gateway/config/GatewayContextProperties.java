/**
 * @Author : Cui
 * @Date: 2026/04/25 22:45
 * @Description DataSmart Govern Backend - GatewayContextProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关上下文传播配置。
 *
 * <p>为什么单独抽出配置类，而不是把常量直接写死在过滤器里？
 * 网关是整个后端平台的“入口控制层”，它未来会同时承载 Web UI、OpenAPI、Agent 工具调用、服务账号调用、
 * 定时调度回调等多种入口。如果这些入口的身份来源、可信边界、Header 兼容规则都写死在过滤器代码里，
 * 后续接入真实认证中心、租户隔离、灰度网关或内部服务网关时，就会反复修改核心过滤器逻辑。
 *
 * <p>这个配置类把“运行期策略”从“过滤器流程”中拆开：
 * 过滤器负责执行上下文生成和 Header 写入；配置类负责描述当前环境是否信任上游 Header、默认请求来源是什么、
 * 是否需要兼容旧的 X-Request-Id。这样既方便学习，也方便后续做生产环境和本地开发环境差异化配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.gateway.context")
public class GatewayContextProperties {

    /**
     * 是否信任外部请求携带的平台上下文 Header。
     *
     * <p>默认值必须是 false，这是一个安全边界选择：
     * 1. traceId 可以由外部传入，因为它主要用于排查链路，不直接决定权限；
     * 2. tenantId、actorId、actorRole、actorType 不能默认信任，否则调用方可以伪造租户和用户身份；
     * 3. 只有当请求来自可信上游网关、服务网格、内部批处理入口或本地联调环境时，才应打开该开关。
     *
     * <p>后续接入真实 JWT / OAuth2 / IdP 后，推荐做法不是把该值简单设为 true，
     * 而是由认证过滤器解析 token 后主动写入平台 Header，让外部原始 Header 仍然不可信。
     */
    private boolean trustIncomingPlatformContext = false;

    /**
     * 网关向下游服务声明的来源服务名。
     *
     * <p>下游服务记录审计事件、日志和指标时，可以通过该字段知道请求是从统一网关进入的。
     * 如果未来拆分外部网关、内部网关、Agent 网关，可以分别配置成不同名称，便于排障和审计。
     */
    private String sourceService = "datasmart-govern-gateway";

    /**
     * 默认请求来源。
     *
     * <p>当前项目还没有完整登录态和多入口识别能力，因此先把未知外部请求标记为 OPEN_API。
     * 后续可以根据认证方式或路由来源细分为 WEB_UI、OPEN_API、SCHEDULER、AGENT_TOOL_CALL 等。
     */
    private String defaultRequestSource = "OPEN_API";

    /**
     * 旧版请求 ID Header。
     *
     * <p>早期网关过滤器已经使用过 X-Request-Id。为了避免一次性破坏已有联调习惯，
     * 新的 X-DataSmart-Trace-Id 会在一段时间内同步回写到该 Header。
     */
    private String legacyRequestIdHeader = "X-Request-Id";

    /**
     * 是否把平台 traceId 同步写入旧版请求 ID Header。
     *
     * <p>保留该兼容开关可以降低迁移成本。等所有模块、日志模板、前端调试工具都改为读取
     * X-DataSmart-Trace-Id 后，再关闭该兼容行为。
     */
    private boolean mirrorTraceIdToLegacyRequestId = true;

    /**
     * 网关命中的路由前缀 Header。
     *
     * <p>这不是权限判断字段，而是调试和审计辅助字段。下游看到该字段后，可以知道请求是通过哪个网关入口进入的。
     */
    private String routePrefixHeader = "X-Gateway-Route-Prefix";

    /**
     * 原始请求路径 Header。
     *
     * <p>Spring Cloud Gateway 转发后，下游服务看到的路径可能已经被路由规则改变。
     * 保留原始路径有助于排查“前端访问路径、网关路由、后端控制器”三者不一致的问题。
     */
    private String originalPathHeader = "X-Gateway-Original-Path";

    /**
     * gateway -> Python AI Runtime 服务间签名配置。
     *
     * <p>当前平台已经能够在 gateway 清理外部伪造 Header 后重建租户、操作者、工作区和数据范围上下文，
     * 但 Python Runtime 如果只检查 `X-DataSmart-Source-Service=datasmart-govern-gateway`，仍然无法区分：
     * 1. 请求确实由 gateway 转发；
     * 2. 调用方绕过 gateway 直连 Python Runtime，并自己伪造同名 Header。
     *
     * <p>因此这里增加 HMAC-SHA256 签名。它属于迁移期的最小服务间信任链：
     * - 适合本地联调、单集群内网和没有服务网格的阶段；
     * - 生产环境仍应叠加 TLS/mTLS、Secret Manager、密钥轮换、nonce Redis 去重和网络访问控制；
     * - 不应把共享密钥提交到 Git，必须通过环境变量或外部密钥系统注入。
     */
    private PythonRuntimeSignature pythonRuntimeSignature = new PythonRuntimeSignature();

    /**
     * gateway -> Python Runtime 会话级 Skill 可见性缓存上下文配置。
     *
     * <p>这里的“缓存”不是 HTTP 响应缓存，也不是把用户 objective、prompt 或模型结果存在 gateway。
     * 它只生成一个低敏、可签名、可失效的控制面事实摘要，让 Python Runtime 可以把“哪些 Skill
     * 在当前租户/角色/workspace/数据范围/预算策略下通过准入”缓存起来。
     *
     * <p>为什么先由 gateway 生成缓存上下文：
     * - gateway 最接近认证、授权、数据范围和服务间签名边界；
     * - Python Runtime 可以继续专注 Agent 编排，不需要猜测哪些 Header 是可信的；
     * - 缓存 key 可以在服务间签名中被保护，避免终端伪造 key 诱导 Python 复用错误准入结果。</p>
     */
    private SkillVisibilityCache skillVisibilityCache = new SkillVisibilityCache();

    /**
     * gateway -> Python Runtime 工具治理策略信封配置。
     *
     * <p>5.41 已经让 Java/Python 双端具备 `X-DataSmart-Tool-Policy-Envelope` 的签名与解析契约；
     * 本配置用于 5.42 继续把它放进真实 `/api/agent/plans` 请求链路。它和 Skill 可见性缓存不同：
     * - Skill 缓存上下文回答“哪些 Skill 在当前控制面边界下可见/可准入”；
     * - 工具策略信封回答“模型本轮工具预算是多少、ToolPlan 形成后 readiness 应如何收敛”。</p>
     *
     * <p>默认不强制调用 permission-admin 远程接口，是为了保持本地学习环境轻量可启动。
     * 生产环境可以打开 `remoteEvaluationEnabled`，让 gateway 在签名前把 permission-admin 的真实策略结果注入
     * Python Runtime，从而避免 Python 内部预算 provider 和 readiness provider 重复同步调用同一个策略接口。</p>
     */
    private ToolPolicyEnvelope toolPolicyEnvelope = new ToolPolicyEnvelope();

    /**
     * Python Runtime 下游签名参数。
     */
    @Data
    public static class PythonRuntimeSignature {

        /**
         * 是否为 Python Runtime 转发请求生成内部签名。
         *
         * <p>默认关闭，保证本地只启动 gateway 或 Python Runtime 时仍能独立学习和调试。
         * 生产环境应开启，并在 Python Runtime 同时配置签名密钥和强制验证开关。
         */
        private boolean enabled = false;

        /**
         * HMAC-SHA256 共享密钥。
         *
         * <p>该值默认留空。开启签名但密钥为空时，过滤器会拒绝生成签名并记录错误，
         * 避免运维误以为已经启用安全保护，实际却仍在发送无签名请求。
         */
        private String secret = "";

        /**
         * 当前签名密钥标识。
         *
         * <p>keyId 可以公开写入 Header，用于灰度升级和密钥轮换；真正敏感的是 secret。
         */
        private String keyId = "gateway-local-v1";

        /**
         * 需要签名的 gateway 原始路径。
         *
         * <p>当前先保护 `/api/agent/plans`，因为它会向 Python Runtime 注入 trustedControlPlane。
         * 后续如果 replay/control/WebSocket 也开始消费高敏控制面事实，应继续扩展此列表。
         */
        private List<String> targetPaths = new ArrayList<>(List.of("/api/agent/plans"));
    }

    /**
     * Skill 可见性缓存上下文参数。
     */
    @Data
    public static class SkillVisibilityCache {

        /**
         * 是否为 Agent 规划入口写入 Skill 可见性缓存上下文 Header。
         *
         * <p>默认开启是因为它不缓存响应体，也不改变路由结果；只是在可信 Header 中增加一个低敏摘要。
         * Python Runtime 如果没有启用对应缓存，看到这些 Header 也只会当作普通上下文忽略。</p>
         */
        private boolean enabled = true;

        /**
         * 缓存协议版本。
         *
         * <p>该版本会进入 key 原文和 Header。未来如果 key 因子发生不兼容变化，可以升级版本并让
         * Python Runtime 同时接受新旧版本一段时间。</p>
         */
        private String version = "v1";

        /**
         * 当前缓存语义范围。
         *
         * <p>session-ready-skill-admission 表示“按会话控制面边界缓存 Skill 准入结果”，
         * 不代表缓存完整计划、模型输出或工具执行结果。</p>
         */
        private String scope = "session-ready-skill-admission";

        /**
         * 缓存 TTL 秒数。
         *
         * <p>TTL 不宜过长：权限、套餐、workspace 风险和预算策略都可能变化。
         * 后续接入权限策略变更事件后，可以在 TTL 之外增加主动失效。</p>
         */
        private int ttlSeconds = 300;

        /**
         * 当前缓存上下文适用的 gateway 原始路径。
         *
         * <p>先只覆盖 `/api/agent/plans`，因为这个入口会触发 Skill admission、工具预算和模型网关治理。
         * WebSocket replay/control 等入口当前不做 Skill 准入，不应误写该缓存上下文。</p>
         */
        private List<String> targetPaths = new ArrayList<>(List.of("/api/agent/plans"));

        /**
         * 租户套餐默认值。
         *
         * <p>真实商业化部署应由 permission-admin、租户中心或套餐服务返回真实值。
         * 当前先使用配置默认值，让 key 结构从一开始就包含套餐维度，避免后续改 key 造成大范围迁移。</p>
         */
        private String defaultTenantPlanCode = "STANDARD";

        /**
         * workspace 风险等级默认值。
         *
         * <p>真实值未来可以来自 workspace 风险评估、数据分类分级或人工标记。
         * 默认 NORMAL 只代表“当前还没有接入风险事实源”，不代表所有 workspace 都低风险。</p>
         */
        private String defaultWorkspaceRiskLevel = "NORMAL";

        /**
         * 工具预算策略版本默认值。
         *
         * <p>缓存 key 必须包含预算策略版本。否则策略从宽变窄后，Python Runtime 可能继续复用旧的
         * READY Skill 准入结果，导致模型看到不该自动规划的能力。</p>
         */
        private String defaultToolBudgetPolicyVersion = "gateway-default-v1";
    }

    /**
     * 工具治理策略信封参数。
     */
    @Data
    public static class ToolPolicyEnvelope {

        /**
         * 是否为 Agent 规划入口写入工具策略信封。
         *
         * <p>默认开启但远程评估默认关闭：gateway 会生成一份保守的本地低敏 envelope。
         * 这样 Python Runtime 可以优先验证和消费 `trustedControlPlane.toolBudget` /
         * `trustedControlPlane.toolExecutionReadinessPolicy`，但本地环境不需要同时启动 permission-admin。</p>
         */
        private boolean enabled = true;

        /**
         * 需要注入工具策略信封的 gateway 原始路径。
         *
         * <p>当前只覆盖 `/api/agent/plans`。WebSocket replay、运行时事件查询和诊断接口不触发模型工具规划，
         * 不应误写工具预算或 readiness policy。</p>
         */
        private List<String> targetPaths = new ArrayList<>(List.of("/api/agent/plans"));

        /**
         * 是否调用 permission-admin 生成真实策略。
         *
         * <p>false 时使用 gateway 本地保守策略，适合开发、学习、单元测试和 permission-admin 尚未启动的环境。
         * true 时调用 `permissionAdminEvaluateUrl`，把 Java 权限控制面的正式结果写入 envelope。</p>
         */
        private boolean remoteEvaluationEnabled = false;

        /**
         * 远程 permission-admin 工具策略评估地址。
         *
         * <p>当前使用本地直连 HTTP 地址，避免过早依赖服务发现 WebClient 能力。
         * 后续如果接入 Spring Cloud LoadBalancer，可灰度切到 lb://permission-admin 路径。</p>
         */
        private String permissionAdminEvaluateUrl =
                "http://localhost:8085/permissions/agent/tool-budget-policies/evaluate";

        /**
         * 远程评估超时时间。
         *
         * <p>gateway 位于入口关键路径，不能为了工具策略无限等待 permission-admin。
         * 商业化目标应监控该调用的 P95/P99；默认 500ms 是开发期上限，生产可按 SLA 调整。</p>
         */
        private java.time.Duration timeout = java.time.Duration.ofMillis(500);

        /**
         * 远程评估异常时是否回退本地保守策略。
         *
         * <p>默认 true 是为了保护本地和灰度环境可用性；生产若要求严格控制面一致性，可以设为 false，
         * 此时 permission-admin 不可用会导致 gateway 拒绝本次 Agent 规划请求。</p>
         */
        private boolean failOpenOnRemoteError = true;

        /**
         * Header 最大字节数。
         *
         * <p>Python Runtime 也使用同样的 4KB 上限。这个限制能阻止误把 prompt、SQL、工具参数、
         * 权限明细或模型输出塞进 Header。策略信封应该永远是小而稳定的低敏摘要。</p>
         */
        private int maxHeaderBytes = 4096;

        /**
         * 本地 fallback 使用的 worker backlog 等级。
         *
         * <p>真实值未来应来自 task-management/data-sync/data-quality worker 指标。
         * 当前默认 NORMAL 表示“没有观测到容量压力”，不是对生产容量的承诺。</p>
         */
        private String defaultWorkerBacklogLevel = "NORMAL";

        /**
         * 本地 fallback 使用的请求工具风险等级。
         *
         * <p>gateway 当前不读取 request body，所以无法知道模型本轮将提出哪些工具。
         * 本地 fallback 先按 LOW 处理；打开远程评估后，未来可以由 agent-runtime 或 request body 摘要提供更准的风险。</p>
         */
        private String defaultRequestedToolRiskLevel = "LOW";

        /**
         * 本地 fallback 最大候选工具数。
         */
        private int defaultMaxProposedToolCalls = 5;

        /**
         * 本地 fallback 最大自动执行工具数。
         */
        private int defaultMaxAutoExecutableToolCalls = 2;

        /**
         * 本地 fallback 最大高风险工具数。
         *
         * <p>默认 0 是保守选择：没有 permission-admin 真实策略时，不允许 Python 自动推进高风险工具。</p>
         */
        private int defaultMaxHighRiskToolCalls = 0;

        /**
         * 本地 fallback 单个工具参数体积预算。
         */
        private int defaultMaxSingleArgumentsBytes = 16_384;

        /**
         * 本地 fallback 本轮工具参数总体积预算。
         */
        private int defaultMaxTotalArgumentsBytes = 48_000;
    }
}

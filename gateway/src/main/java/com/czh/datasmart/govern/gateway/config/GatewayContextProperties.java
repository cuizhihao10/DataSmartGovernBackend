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
}

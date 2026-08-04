/**
 * @Author : Cui
 * @Date: 2026/08/05 00:00
 * @Description DataSmart Govern Backend - AgentSessionTrustedAccessProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Agent 会话内部访问凭证配置。
 *
 * <p>浏览器访问始终使用 Gateway 注入的用户租户、项目和 actor 做对象级鉴权；Python Runtime
 * 需要在模型工具循环中直连 Java 控制面，因此必须额外满足服务白名单与共享凭证。共享凭证只用于
 * 证明调用方是受信服务，实际业务边界仍从数据库中的会话所有者恢复，不能由调用方自行扩大。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.agent-runtime.session-trusted-access")
public class AgentSessionTrustedAccessProperties {

    /**
     * 内部服务共享凭证。
     *
     * <p>该值只用于证明请求来自平台控制的服务进程，不代表用户权限，也不能替代 tenant/project/actor
     * 对象归属检查。生产环境必须由 Secret Manager、Vault 或服务网格注入；保持空字符串时，
     * {@code AgentSessionEndpointAccessResolver} 会拒绝把任何调用方识别为可信内部服务，从而 fail-closed。</p>
     */
    private String sharedToken = "";

    /**
     * 可以代替会话所有者读取控制面事实的内部服务白名单。
     *
     * <p>进入白名单仍必须同时提供正确共享凭证。解析器随后从持久化 session 恢复原始用户边界，
     * 而不是采用服务请求自报的 tenantId、projectId 或 actorId。</p>
     */
    private Set<String> allowedReadSourceServices = new LinkedHashSet<>(Set.of("python-ai-runtime"));

    /**
     * 可以进入“自动执行访问解析”分支的内部服务白名单。
     *
     * <p>该名单不是通用写权限。后续仍会经过会话所有者校验、delegation 范围、工具风险等级、
     * 只读/幂等属性、沙箱策略与运行时保护策略；任何一层不满足都必须拒绝执行。</p>
     */
    private Set<String> allowedAutomatedExecutionSourceServices = new LinkedHashSet<>(Set.of("python-ai-runtime"));
}

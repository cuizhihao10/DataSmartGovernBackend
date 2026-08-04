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

    /** 通过 Secret 或环境变量注入；为空时所有内部服务访问均 fail-closed。 */
    private String sharedToken = "";

    /** 允许读取会话工具审计、策略和结果的内部服务。 */
    private Set<String> allowedReadSourceServices = new LinkedHashSet<>(Set.of("python-ai-runtime"));

    /** 允许触发低风险、只读、幂等同步工具自动执行的内部服务。 */
    private Set<String> allowedAutomatedExecutionSourceServices = new LinkedHashSet<>(Set.of("python-ai-runtime"));
}

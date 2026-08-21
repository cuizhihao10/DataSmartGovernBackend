/**
 * @Author : Cui
 * @Date: 2026/08/04 00:00
 * @Description DataSmart Govern Backend - AgentApprovalFactTrustProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Agent 审批事实登记端点的服务间信任配置。
 *
 * <p>Gateway 的角色策略只是第一道门；permission-admin 还会校验来源服务名和内部共享凭据，以防普通
 * 客户端伪造 APPROVED 事实。该凭据只用于证明调用方是受信服务，不能扩大原用户权限，生产环境应由
 * Secret Manager 注入并定期轮换。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.permission.agent-approval-facts.trusted-registration")
public class AgentApprovalFactTrustProperties {
    /** 内部请求必须携带的共享凭据；空值会使登记校验按 fail-closed 方式全部拒绝。 */
    private String sharedToken = "";

    /**
     * 可以登记审批事实的服务身份白名单。
     *
     * <p>服务名不区分大小写，但仍需同时匹配共享凭据，单独伪造来源 Header 不会获得权限。</p>
     */
    private Set<String> allowedSourceServices = new LinkedHashSet<>(
            Set.of("agent-runtime", "approval-service", "permission-admin", "python-ai-runtime"));

    /**
     * 可以作出最终审批决定的受信服务白名单。
     *
     * <p>运行时编排服务需要登记 {@code PENDING} 事实，方便后续 worker 知道自己应当等待人工确认；
     * 但它不应当同时拥有把同一事实改写为 {@code APPROVED} 或 {@code REJECTED} 的能力。
     * 否则只要运行时的共享凭据被误用，就能够绕过真正的审批中心。这个集合将“可以写入审批流程”
     * 与“可以作出审批决定”拆成两个权限面，默认只信任专门审批服务和 permission-admin 控制面。</p>
     */
    private Set<String> approvalDecisionSourceServices = new LinkedHashSet<>(
            Set.of("approval-service", "permission-admin"));
}

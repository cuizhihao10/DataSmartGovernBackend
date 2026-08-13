/**
 * @Author : Cui
 * @Date: 2026/08/11 23:00
 * @Description DataSmart Govern Backend - AgentWorkspaceTextSearchWorkerProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the Java to Python exact-text-search worker bridge.
 *
 * <p>这里的 workspace 只沿用工具族的历史名称，表示 Agent 执行时可见的文件沙箱，不是产品中的业务
 * Workspace 层级。产品业务范围仍由 tenantId、applicationId 和 projectId 决定；本配置只描述 Python
 * 容器内的只读代码仓库挂载。</p>
 */
@Data
@ConfigurationProperties(prefix = "datasmart.agent-runtime.workspace-text-search-worker")
public class AgentWorkspaceTextSearchWorkerProperties {

    /** Enables the dedicated dispatch target. It is disabled in single-module development by default. */
    private boolean enabled = false;

    /** Internal Python Runtime base URL; it must not be a public Internet endpoint. */
    private String baseUrl = "http://localhost:8090";

    /** Internal worker route registered by python-ai-runtime. */
    private String runPath = "/internal/agent/workspace-text/command-worker/run";

    /** Connect timeout used before the durable outbox records a retryable delivery failure. */
    private long connectTimeoutMs = 1500;

    /** Read timeout covering the bounded filesystem scan and response parsing. */
    private long readTimeoutMs = 15000;

    /**
     * Real root as seen inside the Python container.
     *
     * <p>This value is injected into control facts and overwrites any similarly named model argument. An empty value
     * fails closed, because silently falling back to the Python process directory would expose an unintended scope.</p>
     */
    private String repositoryRoot = "";

    /**
     * Dedicated service token used only on the internal HTTP request.
     *
     * <p>The worker route can read a mounted source tree, so network isolation alone is not authentication. Production
     * dispatch fails closed when this value is blank; Compose, Kubernetes or the secret manager must inject the same
     * value into Java and Python without writing it to logs, runtime events or receipts.</p>
     */
    private String serviceAccountToken = "";
}

/**
 * @Author : Cui
 * @Date: 2026/08/11 23:08
 * @Description DataSmart Govern Backend - AgentWorkspaceTextSearchWorkerPropertiesTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * workspace 文本检索 worker 配置绑定测试。
 *
 * <p>Compose 使用 kebab-case 环境映射，Java 字段使用 camelCase。该测试确保 Spring Boot relaxed binding
 * 可以把部署值准确落到 worker target，避免服务看似启动成功却仍使用 localhost 或空根目录。</p>
 */
class AgentWorkspaceTextSearchWorkerPropertiesTest {

    /**
     * 验证完整生产式配置可以绑定到强类型属性对象。
     *
     * <p>测试特意覆盖开关、地址、路由、两个超时、只读根目录和服务令牌。服务令牌只比较测试占位符，
     * 不读取环境变量或真实秘密。</p>
     */
    @Test
    void shouldBindDeploymentPropertiesUsingSpringBootRelaxedNames() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "datasmart.agent-runtime.workspace-text-search-worker.enabled", "true",
                "datasmart.agent-runtime.workspace-text-search-worker.base-url", "http://python-ai-runtime:8090",
                "datasmart.agent-runtime.workspace-text-search-worker.run-path",
                "/internal/agent/workspace-text/command-worker/run",
                "datasmart.agent-runtime.workspace-text-search-worker.connect-timeout-ms", "2200",
                "datasmart.agent-runtime.workspace-text-search-worker.read-timeout-ms", "17000",
                "datasmart.agent-runtime.workspace-text-search-worker.repository-root", "/repositories/backend",
                "datasmart.agent-runtime.workspace-text-search-worker.service-account-token", "test-token-placeholder"
        ));

        AgentWorkspaceTextSearchWorkerProperties properties = new Binder(source)
                .bind(
                        "datasmart.agent-runtime.workspace-text-search-worker",
                        Bindable.of(AgentWorkspaceTextSearchWorkerProperties.class)
                )
                .orElseThrow(() -> new AssertionError("workspace text-search worker properties should bind"));

        assertTrue(properties.isEnabled());
        assertEquals("http://python-ai-runtime:8090", properties.getBaseUrl());
        assertEquals("/internal/agent/workspace-text/command-worker/run", properties.getRunPath());
        assertEquals(2200L, properties.getConnectTimeoutMs());
        assertEquals(17000L, properties.getReadTimeoutMs());
        assertEquals("/repositories/backend", properties.getRepositoryRoot());
        assertEquals("test-token-placeholder", properties.getServiceAccountToken());
    }
}

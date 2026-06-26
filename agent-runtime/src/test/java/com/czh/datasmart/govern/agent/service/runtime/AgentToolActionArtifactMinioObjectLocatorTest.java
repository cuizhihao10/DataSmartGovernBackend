/**
 * @Author : Cui
 * @Date: 2026/06/26 23:46
 * @Description DataSmart Govern Backend - AgentToolActionArtifactMinioObjectLocatorTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentArtifactObjectStoreMinioProperties;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MinIO objectName 定位器测试。
 *
 * <p>定位器不访问真实 MinIO，它保护的是更重要的安全合同：外部只传低敏 artifactReference，
 * 真实 objectName 由服务端配置映射生成；URL、本机路径、路径逃逸、bucket/key 明文必须被拒绝。</p>
 */
class AgentToolActionArtifactMinioObjectLocatorTest {

    @Test
    void shouldResolveControlledArtifactReferenceToObjectName() {
        AgentToolActionArtifactMinioObjectLocator locator = new AgentToolActionArtifactMinioObjectLocator(properties());

        String objectName = locator.resolveObjectName("agent-artifact:run-command/receipt-001");

        assertEquals("agent-runtime/artifacts/agent-artifact/run-command/receipt-001", objectName);
    }

    @Test
    void shouldUseLongestConfiguredPrefix() {
        AgentArtifactObjectStoreMinioProperties properties = properties();
        Map<String, String> mappings = new LinkedHashMap<>(properties.getReferencePrefixObjectKeyPrefixes());
        mappings.put("agent-artifact:special/", "special-agent-artifact");
        properties.setReferencePrefixObjectKeyPrefixes(mappings);
        AgentToolActionArtifactMinioObjectLocator locator = new AgentToolActionArtifactMinioObjectLocator(properties);

        String objectName = locator.resolveObjectName("agent-artifact:special/run-001/report");

        assertEquals("agent-runtime/artifacts/special-agent-artifact/run-001/report", objectName);
    }

    @Test
    void shouldRejectPathEscapeAndUrlLikeReferences() {
        AgentToolActionArtifactMinioObjectLocator locator = new AgentToolActionArtifactMinioObjectLocator(properties());

        assertThrows(IllegalArgumentException.class,
                () -> locator.resolveObjectName("agent-artifact:../secret"));
        assertThrows(IllegalArgumentException.class,
                () -> locator.resolveObjectName("agent-artifact:https://internal.example/object"));
        assertThrows(IllegalArgumentException.class,
                () -> locator.resolveObjectName("agent-artifact:c:\\windows\\secret"));
    }

    @Test
    void shouldRejectUnknownPrefix() {
        AgentToolActionArtifactMinioObjectLocator locator = new AgentToolActionArtifactMinioObjectLocator(properties());

        assertThrows(IllegalArgumentException.class,
                () -> locator.resolveObjectName("unknown-artifact:run-command/receipt-001"));
    }

    private AgentArtifactObjectStoreMinioProperties properties() {
        AgentArtifactObjectStoreMinioProperties properties = new AgentArtifactObjectStoreMinioProperties();
        properties.setObjectKeyRootPrefix("agent-runtime/artifacts");
        return properties;
    }
}

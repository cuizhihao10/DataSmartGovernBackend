/**
 * @Author : Cui
 * @Date: 2026-07-11 04:05
 * @Description DataSmart Govern Backend - AgentRuntimeConfigurationBindingTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the packaged YAML contract instead of constructing properties by hand.
 * Dotted tool codes must use Spring Boot's bracketed map-key syntax; otherwise the
 * binder treats each dot as a nested property separator and silently produces an
 * empty runtime registry even though the YAML text is present in the application JAR.
 */
class AgentRuntimeConfigurationBindingTest {

    @Test
    void applicationYamlBindsDottedToolCodesAsCompleteRegistryKeys() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources propertySources = environment.getPropertySources();
        var yamlSources = new YamlPropertySourceLoader().load(
                "agent-runtime-application",
                new ClassPathResource("application.yml")
        );
        for (int index = yamlSources.size() - 1; index >= 0; index--) {
            propertySources.addFirst(yamlSources.get(index));
        }

        AgentRuntimeProperties properties = Binder.get(environment)
                .bind("datasmart.agent-runtime", Bindable.of(AgentRuntimeProperties.class))
                .orElseThrow(() -> new IllegalStateException("agent-runtime properties were not bound"));

        assertThat(properties.getToolRegistry())
                .hasSizeGreaterThanOrEqualTo(14)
                .containsKeys(
                        "datasource.source.connection.test",
                        "datasource.target.connection.test",
                        "datasource.source.metadata.read",
                        "datasource.target.metadata.read",
                        "sync.task.draft.save",
                        "sync.task.precheck",
                        "sync.task.publish",
                        "sync.task.run",
                        "sync.execution.status"
                );
    }
}

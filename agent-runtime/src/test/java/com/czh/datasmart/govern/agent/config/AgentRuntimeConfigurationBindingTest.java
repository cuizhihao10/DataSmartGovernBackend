/**
 * @Author : Cui
 * @Date: 2026-07-11 04:05
 * @Description DataSmart Govern Backend - AgentRuntimeConfigurationBindingTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.model.AgentToolRiskLevel;
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
                        "datasource.source.catalog.search",
                        "datasource.target.catalog.search",
                        "datasource.source.connection.test",
                        "datasource.target.connection.test",
                        "datasource.source.metadata.read",
                        "datasource.target.metadata.read",
                        "sync.task.draft.save",
                        "sync.task.precheck",
                        "sync.task.publish",
                        "sync.task.run",
                        "sync.execution.status",
                        "workspace.text.search",
                        "knowledge.rag.query"
                );
        AgentRuntimeProperties.ToolDefinitionProperties repositorySearch =
                properties.getToolRegistry().get("workspace.text.search");
        assertThat(repositorySearch.getToolCode()).isEqualTo("workspace.text.search");
        assertThat(repositorySearch.getExecutionMode()).isEqualTo(AgentToolExecutionMode.ASYNC_TASK);
        assertThat(repositorySearch.getRiskLevel()).isEqualTo(AgentToolRiskLevel.MEDIUM);
        assertThat(repositorySearch.getReadOnly()).isTrue();
        assertThat(repositorySearch.getIdempotent()).isTrue();
        assertThat(repositorySearch.getRequiresApproval()).isFalse();
        assertThat(repositorySearch.getTenantScoped()).isTrue();
        assertThat(repositorySearch.getProjectScoped()).isTrue();
        assertThat(repositorySearch.getAllowedActions()).containsExactly("SEARCH_TEXT_LITERAL");
        assertThat(repositorySearch.getInputSchema())
                .extracting(AgentRuntimeProperties.ToolInputFieldProperties::getName)
                .containsExactly(
                        "repositoryReference",
                        "query",
                        "relativePathPrefix",
                        "caseSensitive",
                        "searchMode",
                        "maxResults"
                );
        AgentAsyncTaskCommandOutboxProperties outboxProperties = Binder.get(environment)
                .bind(
                        "datasmart.agent-runtime.async-task-commands.outbox",
                        Bindable.of(AgentAsyncTaskCommandOutboxProperties.class)
                )
                .orElseThrow(() -> new IllegalStateException("async command outbox properties were not bound"));
        assertThat(outboxProperties.getDispatcherAllowedToolCodes()).isEmpty();
        AgentWorkspaceTextSearchWorkerProperties workerProperties = Binder.get(environment)
                .bind(
                        "datasmart.agent-runtime.workspace-text-search-worker",
                        Bindable.of(AgentWorkspaceTextSearchWorkerProperties.class)
                )
                .orElseThrow(() -> new IllegalStateException("workspace text search worker properties were not bound"));
        assertThat(workerProperties.isEnabled()).isFalse();
        assertThat(workerProperties.getRepositoryRoot()).isEmpty();
        assertThat(workerProperties.getRunPath())
                .isEqualTo("/internal/agent/workspace-text/command-worker/run");
        AgentSkillRegistryProperties skillProperties = Binder.get(environment)
                .bind("datasmart.agent-runtime", Bindable.of(AgentSkillRegistryProperties.class))
                .orElseThrow(() -> new IllegalStateException("agent skill properties were not bound"));
        assertThat(skillProperties.getSkillRegistry())
                .containsKeys("knowledge.rag.answer", "sync.task.import.troubleshoot");
        assertThat(skillProperties.getSkillRegistry().get("sync.task.import.troubleshoot").getRequiredTools())
                .containsExactly("knowledge.rag.query");
    }
}

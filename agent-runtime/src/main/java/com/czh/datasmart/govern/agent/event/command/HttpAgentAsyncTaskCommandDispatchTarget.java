/**
 * @Author : Cui
 * @Date: 2026/05/31 17:10
 * @Description DataSmart Govern Backend - HttpAgentAsyncTaskCommandDispatchTarget.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import com.czh.datasmart.govern.agent.config.AgentAsyncTaskCommandOutboxProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 基于 HTTP 的 task-management 命令投递目标。
 *
 * <p>这是 Kafka producer 完成前的安全联调路径。它仍然由 outbox dispatcher 驱动，
 * 因此具备失败重试和阻断语义，而不是在业务流程中直接同步调用 task-management。</p>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "datasmart.agent-runtime.async-task-commands.outbox",
        name = "dispatcher-http-enabled",
        havingValue = "true"
)
public class HttpAgentAsyncTaskCommandDispatchTarget implements AgentAsyncTaskCommandDispatchTarget {

    private final AgentAsyncTaskCommandOutboxProperties properties;
    private final RestClient.Builder restClientBuilder;

    @Override
    public String targetName() {
        return "http:" + properties.getTaskManagementConsumeUrl();
    }

    @Override
    public void dispatch(AgentAsyncTaskCommandOutboxRecord record) {
        RestClient client = restClientBuilder.build();
        client.post()
                .uri(properties.getTaskManagementConsumeUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .body(record.payloadJson())
                .retrieve()
                .toBodilessEntity();
    }
}

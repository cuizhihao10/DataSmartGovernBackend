/**
 * @Author : Cui
 * @Date: 2026/07/05 01:35
 * @Description DataSmart Govern Backend - HttpAgentAsyncTaskCommandDispatchTargetTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import com.czh.datasmart.govern.agent.config.AgentAsyncTaskCommandOutboxProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * task-management HTTP command target 的路由边界测试。
 *
 * <p>HTTP target 是早期联调和本地闭环里最容易被误用的通用投递通道：它会把 outbox payload
 * 直接投递给 task-management 的 async-task inbox。因此当系统新增 RAG、MCP 这类“有独立 Python worker、
 * 有独立低敏回执、有独立权限/租约/副作用语义”的命令后，HTTP target 必须明确排除这些命令，不能继续把它们
 * 当作普通数据同步任务投给 task-management。</p>
 *
 * <p>本测试不验证 RestClient 的真实 HTTP 调用，因为那属于集成测试范围；这里专门固定 supports 选择逻辑，
 * 保证 dispatcher 在多 target 并存时可以把命令送到正确的 worker，而不是出现广播式误消费。</p>
 */
class HttpAgentAsyncTaskCommandDispatchTargetTest {

    @Test
    void supportsShouldExcludeRagCommandAndKeepGenericCommand() {
        HttpAgentAsyncTaskCommandDispatchTarget target = new HttpAgentAsyncTaskCommandDispatchTarget(
                new AgentAsyncTaskCommandOutboxProperties(),
                mock(RestClient.Builder.class)
        );

        assertFalse(target.supports(record(
                "datasmart.agent.rag.commands",
                RagAgentAsyncTaskCommandDispatchTarget.RAG_TOOL_CODE,
                RagAgentAsyncTaskCommandDispatchTarget.RAG_CONSUMER_SERVICE,
                RagAgentAsyncTaskCommandDispatchTarget.RAG_CONSUMER_SERVICE
        )));
        assertTrue(target.supports(record(
                "datasmart.agent.tool.async.commands",
                "data-sync.execute",
                "task-management",
                "data-sync"
        )));
    }

    /**
     * 构造只用于 supports 判断的 outbox record。
     *
     * <p>record 字段里同时包含 commandTopic、consumerService、toolCode 和 targetService，是为了模拟真实 dispatcher
     * 选择 target 时能看到的完整路由事实。RAG 命令必须被 HTTP target 排除；普通 data-sync 命令仍应保留通用
     * task-management 投递能力，避免为了接入 RAG worker 破坏原有异步任务闭环。</p>
     */
    private AgentAsyncTaskCommandOutboxRecord record(String topic,
                                                     String toolCode,
                                                     String consumerService,
                                                     String targetService) {
        String payload = "{\"schemaVersion\":\"datasmart.agent.async-task-command.v1\"}";
        return AgentAsyncTaskCommandOutboxRecord.pending(
                "cmd-http-target-001",
                "idem-http-target-001",
                "datasmart.agent.async-task-command.v1",
                "AGENT_TOOL_ASYNC_TASK_REQUESTED",
                topic,
                consumerService,
                "session-http-target-001",
                "run-http-target-001",
                "audit-http-target-001",
                toolCode,
                targetService,
                "/sync-tasks",
                10L,
                20L,
                30L,
                "actor-http-target",
                "trace-http-target",
                "agent-tool-audit://session-http-target-001/run-http-target-001/audit-http-target-001/arguments",
                payload,
                payload.length(),
                Instant.now()
        );
    }
}

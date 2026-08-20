/**
 * @Author : Cui
 * @Date: 2026/08/20 00:00
 * @Description DataSmart Govern Backend - AgentCommandTaskFinalStateCallbackWorkerDeploymentContractTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 最终态 callback worker 的部署合同回归。
 *
 * <p>Java 单元测试只能证明 worker 和启动校验器本身正确；本测试继续保护完整 Compose 是否真的选择了
 * PostgreSQL receipt index、启用了 Flyway 和 callback worker。源码默认值仍保持关闭，避免只运行单模块的
 * 开发者在没有数据库时误触发后台副作用。</p>
 */
class AgentCommandTaskFinalStateCallbackWorkerDeploymentContractTest {

    /**
     * 验证源码默认关闭，但全平台 Compose 显式启用全部 durable 前置条件。
     */
    @Test
    void shouldEnableCallbackWorkerOnlyWithPostgresqlReceiptAndFlywayInCompose() throws IOException {
        String applicationYaml = Files.readString(Path.of("src/main/resources/application.yml"))
                .replace("\r\n", "\n");
        String composeYaml = Files.readString(Path.of("..", "docker-compose.application.yml"))
                .replace("\r\n", "\n");
        String service = composeService(composeYaml, "agent-runtime", "observability");

        assertThat(applicationYaml)
                .contains("enabled: ${DATASMART_AGENT_RUNTIME_ASYNC_TASK_FINAL_STATE_CALLBACK_WORKER_ENABLED:false}")
                .contains("worker-receipt-index-store: ${DATASMART_AGENT_RUNTIME_WORKER_RECEIPT_INDEX_STORE:memory}");
        assertThat(service)
                .contains("DATASMART_AGENT_RUNTIME_DATABASE_ENABLED: \"true\"")
                .contains("DATASMART_AGENT_RUNTIME_FLYWAY_ENABLED: \"true\"")
                .contains("DATASMART_AGENT_RUNTIME_WORKER_RECEIPT_INDEX_STORE: postgresql")
                .contains("DATASMART_AGENT_RUNTIME_ASYNC_TASK_FINAL_STATE_CALLBACK_WORKER_ENABLED: \"true\"");
    }

    /**
     * 从 Compose 中提取一个顶层服务块，避免测试依赖固定行号。
     */
    private String composeService(String composeYaml, String serviceName, String nextServiceName) {
        String serviceMarker = "  " + serviceName + ":\n";
        String nextServiceMarker = "\n  " + nextServiceName + ":\n";
        int start = composeYaml.indexOf(serviceMarker);
        int end = composeYaml.indexOf(nextServiceMarker, start + serviceMarker.length());

        assertThat(start).isGreaterThanOrEqualTo(0);
        return composeYaml.substring(start, end < 0 ? composeYaml.length() : end);
    }
}

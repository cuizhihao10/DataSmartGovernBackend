/**
 * @Author : Cui
 * @Date: 2026/07/27 20:00
 * @Description DataSmart Govern Backend - DefaultCdcInfrastructureReadinessProbe.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.czh.datasmart.govern.datasource.config.CdcReadinessProperties;
import com.czh.datasmart.govern.datasource.controller.dto.DataSourceCdcReadinessResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Performs bounded, read-only health checks against Kafka and Kafka Connect. */
@Component
@RequiredArgsConstructor
public class DefaultCdcInfrastructureReadinessProbe implements CdcInfrastructureReadinessProbe {

    private final CdcReadinessProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public List<DataSourceCdcReadinessResult.CheckItem> probe() {
        List<DataSourceCdcReadinessResult.CheckItem> checks = new ArrayList<>();
        checks.add(probeKafka());
        checks.addAll(probeKafkaConnect());
        return List.copyOf(checks);
    }

    private DataSourceCdcReadinessResult.CheckItem probeKafka() {
        String bootstrapServers = trim(properties.getKafkaBootstrapServers());
        if (bootstrapServers == null) {
            return DataSourceCdcReadinessResult.CheckItem.failed(
                    "CDC_KAFKA_NOT_CONFIGURED", "INFRASTRUCTURE",
                    "系统未配置 CDC 使用的 Kafka 集群。",
                    "请由管理员配置 Kafka bootstrap servers，并确认网络、认证和 ACL 可用。", Map.of());
        }
        int timeoutMillis = timeoutSeconds() * 1000;
        Map<String, Object> config = Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, timeoutMillis,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeoutMillis
        );
        try (AdminClient adminClient = AdminClient.create(config)) {
            String clusterId = adminClient.describeCluster().clusterId()
                    .get(timeoutSeconds(), TimeUnit.SECONDS);
            int nodeCount = adminClient.describeCluster().nodes()
                    .get(timeoutSeconds(), TimeUnit.SECONDS).size();
            return DataSourceCdcReadinessResult.CheckItem.passed(
                    "CDC_KAFKA_REACHABLE", "INFRASTRUCTURE",
                    "Kafka 集群可达，可以承载 CDC 变更事件。",
                    Map.of("clusterIdPresent", clusterId != null && !clusterId.isBlank(), "nodeCount", nodeCount));
        } catch (Exception exception) {
            return DataSourceCdcReadinessResult.CheckItem.failed(
                    "CDC_KAFKA_UNREACHABLE", "INFRASTRUCTURE",
                    "无法连接 CDC 使用的 Kafka 集群。",
                    "请由管理员检查 Kafka 服务状态、网络、认证和 ACL；修复后重新执行准入检查。",
                    Map.of("errorType", exception.getClass().getSimpleName()));
        }
    }

    private List<DataSourceCdcReadinessResult.CheckItem> probeKafkaConnect() {
        String baseUrl = trim(properties.getKafkaConnectBaseUrl());
        if (baseUrl == null) {
            return List.of(DataSourceCdcReadinessResult.CheckItem.failed(
                    "CDC_KAFKA_CONNECT_NOT_CONFIGURED", "INFRASTRUCTURE",
                    "系统未配置 Kafka Connect 服务，无法托管 Debezium CDC 连接器。",
                    "请由管理员部署 Kafka Connect、安装匹配源数据库的 Debezium 插件，并配置服务地址。",
                    Map.of()));
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeoutSeconds()))
                    .build();
            HttpResponse<String> pluginResponse = get(client, baseUrl, "/connector-plugins");
            if (pluginResponse.statusCode() < 200 || pluginResponse.statusCode() >= 300) {
                return List.of(connectFailed("Kafka Connect 返回非成功状态码。", pluginResponse.statusCode()));
            }
            List<Map<String, Object>> plugins = objectMapper.readValue(
                    pluginResponse.body(), new TypeReference<>() { });
            boolean debeziumAvailable = plugins.stream()
                    .map(item -> String.valueOf(item.getOrDefault("class", "")))
                    .anyMatch(className -> className.startsWith("io.debezium.connector."));

            HttpResponse<String> connectorResponse = get(client, baseUrl, "/connectors");
            int connectorCount = 0;
            if (connectorResponse.statusCode() >= 200 && connectorResponse.statusCode() < 300) {
                List<String> connectors = objectMapper.readValue(
                        connectorResponse.body(), new TypeReference<>() { });
                connectorCount = connectors.size();
            }
            List<DataSourceCdcReadinessResult.CheckItem> checks = new ArrayList<>();
            checks.add(DataSourceCdcReadinessResult.CheckItem.passed(
                    "CDC_KAFKA_CONNECT_REACHABLE", "INFRASTRUCTURE",
                    "Kafka Connect 服务可达。",
                    Map.of("registeredConnectorCount", connectorCount)));
            checks.add(debeziumAvailable
                    ? DataSourceCdcReadinessResult.CheckItem.passed(
                    "CDC_DEBEZIUM_PLUGIN_AVAILABLE", "INFRASTRUCTURE",
                    "Kafka Connect 已安装 Debezium 连接器插件。", Map.of())
                    : DataSourceCdcReadinessResult.CheckItem.failed(
                    "CDC_DEBEZIUM_PLUGIN_MISSING", "INFRASTRUCTURE",
                    "Kafka Connect 可达，但未发现 Debezium 连接器插件。",
                    "请安装与源数据库版本匹配的 Debezium 连接器插件并重启 Kafka Connect。", Map.of()));
            return List.copyOf(checks);
        } catch (Exception exception) {
            return List.of(DataSourceCdcReadinessResult.CheckItem.failed(
                    "CDC_KAFKA_CONNECT_UNREACHABLE", "INFRASTRUCTURE",
                    "无法连接 Kafka Connect 服务。",
                    "请由管理员检查 Kafka Connect 进程、网络和服务地址；修复后重新检查。",
                    Map.of("errorType", exception.getClass().getSimpleName())));
        }
    }

    private HttpResponse<String> get(HttpClient client, String baseUrl, String path) throws Exception {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        HttpRequest request = HttpRequest.newBuilder(URI.create(normalized + path))
                .timeout(Duration.ofSeconds(timeoutSeconds()))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private DataSourceCdcReadinessResult.CheckItem connectFailed(String reason, int statusCode) {
        return DataSourceCdcReadinessResult.CheckItem.failed(
                "CDC_KAFKA_CONNECT_UNHEALTHY", "INFRASTRUCTURE",
                reason,
                "请由管理员检查 Kafka Connect 日志和健康状态。",
                Map.of("httpStatus", statusCode));
    }

    private int timeoutSeconds() {
        return Math.max(1, Math.min(properties.getProbeTimeoutSeconds(), 15));
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

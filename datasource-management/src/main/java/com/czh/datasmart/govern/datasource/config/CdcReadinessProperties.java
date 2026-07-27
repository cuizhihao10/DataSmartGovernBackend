/**
 * @Author : Cui
 * @Date: 2026/07/27 20:00
 * @Description DataSmart Govern Backend - CdcReadinessProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Operational endpoints and time bounds used by the read-only CDC infrastructure probe. */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.datasource.cdc-readiness")
public class CdcReadinessProperties {

    private String kafkaBootstrapServers = "localhost:9092";

    /** Empty means Kafka Connect is not deployed/configured and must be reported as a blocker. */
    private String kafkaConnectBaseUrl;

    private int probeTimeoutSeconds = 3;
}

/**
 * @Author : Cui
 * @Date: 2026/07/28 02:38
 * @Description DataSmart Govern Backend - SyncTaskExecutionPrecheckResponseTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SyncTaskExecutionPrecheckResponseTest {

    @Test
    void shouldProjectInternalTemplatePrecheckToTaskContract() {
        SyncTemplateExecutionPrecheckResponse internal = new SyncTemplateExecutionPrecheckResponse(
                7001L,
                10L,
                101L,
                301L,
                "FULL",
                "JDBC_BATCH",
                "DATA_SYNC_WORKER",
                "TABLE",
                "BLOCKED",
                true,
                false,
                true,
                true,
                true,
                true,
                true,
                true,
                false,
                true,
                false,
                true,
                false,
                true,
                List.of("TARGET_NOT_EMPTY"),
                List.of("Choose MERGE or empty the target table."),
                List.of("Use bounded channels."),
                List.of("Keep credentials encrypted."),
                "LOW_SENSITIVE_METADATA_ONLY");

        SyncTaskExecutionPrecheckResponse response =
                SyncTaskExecutionPrecheckResponse.from(9001L, internal);

        assertThat(response.taskId()).isEqualTo(9001L);
        assertThat(response.tenantId()).isEqualTo(10L);
        assertThat(response.projectId()).isEqualTo(101L);
        assertThat(response.precheckStatus()).isEqualTo("BLOCKED");
        assertThat(response.issueCodes()).containsExactly("TARGET_NOT_EMPTY");
    }
}

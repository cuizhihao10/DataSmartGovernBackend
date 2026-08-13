/**
 * @Author : Cui
 * @Date: 2026/08/11 21:50
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryMonitoringContractTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the V23 dead-letter meter and repository-owned Prometheus rules on one explicit contract.
 */
class SyncAutopilotRecoveryMonitoringContractTest {

    /**
     * Dead-letter rows require a named critical alert, while transient sidecar failures retain a lower-severity
     * early-warning rule. The assertions intentionally check only fixed metric/rule identifiers, never business
     * IDs or user data.
     */
    @Test
    void prometheusRulesShouldAlertOnV23SidecarCompensationDeadLetters() throws IOException {
        String rules = Files.readString(findRepositoryFile("docker/prometheus/rules/data-sync-alerts.yml"),
                StandardCharsets.UTF_8);

        assertThat(rules).contains("DataSyncAutopilotSidecarFailuresIncreasing");
        assertThat(rules).contains("datasmart_data_sync_autopilot_recovery_sidecar_failure_total");
        assertThat(rules).contains("DataSyncAutopilotSidecarCompensationDeadLetterDetected");
        assertThat(rules).contains("datasmart_data_sync_autopilot_recovery_sidecar_compensation_dead_letter_total");
    }

    /**
     * Locates a repository-owned monitoring file regardless of whether Surefire starts in the module or root.
     *
     * <p>The test walks only parent directories from the local working directory and requires an existing regular
     * file. It never reads user home folders, old task transcripts, or runtime logs; this keeps the contract test
     * deterministic in IDE, Maven, and CI launches.</p>
     *
     * @param repositoryRelativePath slash-separated path rooted at the backend repository
     * @return existing monitoring configuration file
     * @throws IOException when no parent directory contains the requested repository file
     */
    private Path findRepositoryFile(String repositoryRelativePath) throws IOException {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(repositoryRelativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IOException("Repository monitoring file was not found: " + repositoryRelativePath);
    }
}

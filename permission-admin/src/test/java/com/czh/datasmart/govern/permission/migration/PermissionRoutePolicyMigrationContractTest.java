package com.czh.datasmart.govern.permission.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionRoutePolicyMigrationContractTest {

    private static final Path MIGRATION_DIRECTORY = Path.of(
            "src/main/resources/db/migration/postgresql/permission-admin");

    @Test
    void routePolicyMigrationsUseBaselineColumnNames() throws IOException {
        List<Path> invalidMigrations;
        try (var paths = Files.list(MIGRATION_DIRECTORY)) {
            invalidMigrations = paths
                    .filter(path -> path.getFileName().toString().matches("V(4[0-9]|[5-9][0-9])__.*\\.sql"))
                    .filter(this::containsLegacyRoutePolicyColumn)
                    .toList();
        }

        assertThat(invalidMigrations)
                .as("permission_route_policy uses action/create_time/update_time from the V1 baseline")
                .isEmpty();
    }

    @Test
    void agentCancellationMigrationGrantsEveryInteractiveAgentRole() throws IOException {
        String sql = Files.readString(MIGRATION_DIRECTORY.resolve(
                "V45__agent_plan_cancellation_route_policy.sql"));

        assertThat(sql)
                .contains("'/api/agent/plans/cancel'")
                .contains("'CANCEL_INFERENCE'")
                .contains("'ORDINARY_USER'")
                .contains("'PROJECT_OWNER'");
    }

    private boolean containsLegacyRoutePolicyColumn(Path path) {
        try {
            String sql = Files.readString(path);
            return sql.contains("action_code")
                    || sql.contains("created_at")
                    || sql.contains("updated_at");
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read migration " + path, exception);
        }
    }
}

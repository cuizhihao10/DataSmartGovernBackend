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

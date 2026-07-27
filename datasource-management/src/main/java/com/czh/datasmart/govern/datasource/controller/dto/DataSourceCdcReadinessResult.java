/**
 * @Author : Cui
 * @Date: 2026/07/27 20:00
 * @Description DataSmart Govern Backend - DataSourceCdcReadinessResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.controller.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Low-sensitive CDC admission report suitable for Agent tool results and UI display. */
public record DataSourceCdcReadinessResult(
        String schemaVersion,
        boolean ready,
        String decision,
        Long sourceDatasourceId,
        Long targetDatasourceId,
        String sourceConnectorType,
        String targetConnectorType,
        int passedCount,
        int warningCount,
        int failedCount,
        List<String> issueCodes,
        List<String> recommendedActions,
        List<CheckItem> checks,
        LocalDateTime checkedAt
) {

    /**
     * One independently explainable readiness fact.
     * Details may contain counts and public database settings, never credentials or row values.
     */
    public record CheckItem(
            String code,
            String category,
            String status,
            String message,
            String recommendation,
            Map<String, Object> details
    ) {

        public static CheckItem passed(String code, String category, String message,
                                       Map<String, Object> details) {
            return new CheckItem(code, category, "PASSED", message, null, safe(details));
        }

        public static CheckItem warning(String code, String category, String message,
                                        String recommendation, Map<String, Object> details) {
            return new CheckItem(code, category, "WARNING", message, recommendation, safe(details));
        }

        public static CheckItem failed(String code, String category, String message,
                                       String recommendation, Map<String, Object> details) {
            return new CheckItem(code, category, "FAILED", message, recommendation, safe(details));
        }

        private static Map<String, Object> safe(Map<String, Object> details) {
            return details == null ? Map.of() : Map.copyOf(details);
        }
    }
}

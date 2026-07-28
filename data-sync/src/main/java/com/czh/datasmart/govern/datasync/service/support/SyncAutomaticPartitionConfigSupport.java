/**
 * @Author : Cui
 * @Date: 2026/07/27 21:16
 * @Description DataSmart Govern Backend - SyncAutomaticPartitionConfigSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.support.SyncMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Derives the system-managed {@code AUTO_SPLIT_PK} declaration from verified field metadata.
 *
 * <p>Users configure business semantics such as objects, fields, filters and write mode.  Shard count,
 * channel and TaskGroup size are runtime governance decisions and therefore must not be copied into the
 * task wizard.  This component only identifies a safe integral source primary key and writes a minimal
 * declaration.  The worker later probes {@code min/max/count} and applies the effective administrator
 * policy to calculate the actual number of shards for each execution.</p>
 *
 * <p>No source database is contacted here.  If metadata is incomplete, the key is composite, or its type
 * is not an integral numeric type, the component leaves partitioning disabled and the task uses the normal
 * object ledger.  Explicit internal/imported partition contracts remain unchanged for compatibility.</p>
 */
@Component
public class SyncAutomaticPartitionConfigSupport {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    private static final Set<String> INTEGRAL_TYPES = Set.of(
            "TINYINT", "SMALLINT", "MEDIUMINT", "INT", "INTEGER", "BIGINT",
            "INT2", "INT4", "INT8", "SMALLSERIAL", "SERIAL", "BIGSERIAL");

    private final ObjectMapper objectMapper;

    public SyncAutomaticPartitionConfigSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Resolves the primary-key hint and minimal partition declaration persisted with a draft definition.
     */
    public AutomaticPartitionConfig resolve(SyncMode syncMode,
                                            String syncScopeType,
                                            String fieldMappingConfig,
                                            String explicitPartitionConfig) {
        String explicit = trimToNull(explicitPartitionConfig);
        String splitPk = inferSingleIntegralPrimaryKey(fieldMappingConfig);
        if (explicit != null) {
            return new AutomaticPartitionConfig(splitPk, explicit, false);
        }
        if (!supportsAutomaticSplit(syncMode, syncScopeType) || splitPk == null) {
            return new AutomaticPartitionConfig(splitPk, null, false);
        }

        Map<String, Object> declaration = new LinkedHashMap<>();
        declaration.put("strategy", "AUTO_SPLIT_PK");
        declaration.put("splitPk", splitPk);
        declaration.put("policyManaged", true);
        try {
            return new AutomaticPartitionConfig(
                    splitPk,
                    objectMapper.writeValueAsString(declaration),
                    true
            );
        } catch (Exception ignored) {
            return new AutomaticPartitionConfig(splitPk, null, false);
        }
    }

    private boolean supportsAutomaticSplit(SyncMode syncMode, String syncScopeType) {
        return syncMode != null
                && (syncMode == SyncMode.FULL || syncMode == SyncMode.SCHEDULED_FULL)
                && "SINGLE_OBJECT".equalsIgnoreCase(trimToNull(syncScopeType));
    }

    private String inferSingleIntegralPrimaryKey(String fieldMappingConfig) {
        if (trimToNull(fieldMappingConfig) == null) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(fieldMappingConfig);
            List<JsonNode> mappings = mappingRows(root);
            Set<String> candidates = new LinkedHashSet<>();
            for (JsonNode mapping : mappings) {
                if (!syncEnabled(mapping) || !primaryKey(mapping)) {
                    continue;
                }
                String sourceField = firstText(mapping,
                        "sourceField", "source", "from", "sourceColumn");
                String sourceType = firstText(mapping,
                        "sourceType", "sourceDataType", "dataTypeName", "type");
                if (safeIdentifier(sourceField) && integralType(sourceType)) {
                    candidates.add(sourceField);
                }
            }
            return candidates.size() == 1 ? candidates.iterator().next() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<JsonNode> mappingRows(JsonNode root) {
        List<JsonNode> rows = new ArrayList<>();
        if (root == null) {
            return rows;
        }
        if (root.isArray()) {
            root.forEach(rows::add);
            return rows;
        }
        JsonNode direct = firstArray(root, "mappings", "fieldMappings");
        if (direct != null) {
            direct.forEach(rows::add);
        }
        JsonNode objectMappings = root.path("objectMappings");
        if (objectMappings.isArray()) {
            for (JsonNode objectMapping : objectMappings) {
                JsonNode nested = firstArray(objectMapping, "mappings", "fieldMappings");
                if (nested != null) {
                    nested.forEach(rows::add);
                }
            }
        }
        return rows;
    }

    private JsonNode firstArray(JsonNode node, String... names) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String name : names) {
            JsonNode candidate = node.get(name);
            if (candidate != null && candidate.isArray()) {
                return candidate;
            }
        }
        return null;
    }

    private boolean syncEnabled(JsonNode mapping) {
        JsonNode value = mapping == null ? null : mapping.get("syncEnabled");
        return value == null || value.isNull() || value.asBoolean(true);
    }

    private boolean primaryKey(JsonNode mapping) {
        return booleanValue(mapping, "primaryKey")
                || booleanValue(mapping, "sourcePrimaryKey")
                || booleanValue(mapping, "isPrimaryKey");
    }

    private boolean booleanValue(JsonNode node, String name) {
        JsonNode value = node == null ? null : node.get(name);
        return value != null && !value.isNull() && value.asBoolean(false);
    }

    private String firstText(JsonNode node, String... names) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull() && trimToNull(value.asText()) != null) {
                return value.asText().trim();
            }
        }
        return null;
    }

    private boolean integralType(String sourceType) {
        String normalized = trimToNull(sourceType);
        if (normalized == null) {
            return false;
        }
        normalized = normalized.toUpperCase(Locale.ROOT)
                .replace(" UNSIGNED", "")
                .replaceAll("\\s*\\(.*$", "")
                .trim();
        return INTEGRAL_TYPES.contains(normalized);
    }

    private boolean safeIdentifier(String value) {
        return value != null && SAFE_IDENTIFIER.matcher(value).matches();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * @param splitPk inferred source split key; it is intentionally separate from the target conflict key
     * @param partitionConfig persisted minimal partition declaration, or {@code null} for object execution
     * @param systemManaged whether this declaration was derived by the platform rather than supplied explicitly
     */
    public record AutomaticPartitionConfig(
            String splitPk,
            String partitionConfig,
            boolean systemManaged
    ) {
    }
}

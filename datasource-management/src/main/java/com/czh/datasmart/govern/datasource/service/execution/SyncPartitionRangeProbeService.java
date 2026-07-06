/**
 * @Author : Cui
 * @Date: 2026/07/07 23:24
 * @Description DataSmart Govern Backend - SyncPartitionRangeProbeService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution;

import com.czh.datasmart.govern.datasource.controller.dto.SyncPartitionRangeProbeInternalRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPartitionRangeProbeInternalResponse;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.SyncJdbcConnectionProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * DataX-style splitPk 范围探测服务。
 *
 * <p>DataX 的关系型 Reader 在用户配置 splitPk 后，会先探测分片字段的 min/max，再生成多个范围任务。
 * 本服务承担同样的“只读探测”职责，但把它放在 datasource-management 模块中，而不是 data-sync 模块中：</p>
 * <p>1. datasource-management 才持有数据源连接和凭据读取能力；</p>
 * <p>2. data-sync 只消费低敏探测事实并生成执行账本，不跨服务读取源库；</p>
 * <p>3. 探测 SQL 在本服务内部生成，字段名和对象名都经过白名单校验，业务值不参与拼接。</p>
 *
 * <p>当前边界：</p>
 * <p>第一阶段只支持数值型 splitPk，并使用精确 COUNT(*)。这能快速闭环自动范围切分，但生产大表后续应支持
 * 数据库统计信息估算、采样探测、探测超时和源端压力保护。</p>
 */
@Service
@RequiredArgsConstructor
public class SyncPartitionRangeProbeService {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    private final SyncJdbcConnectionProvider connectionProvider;

    /**
     * 探测 splitPk 的数值范围。
     *
     * @param request internal 探测请求，来自 data-sync 服务账号。
     * @return 低敏探测结果；失败时返回 fail-closed 状态，不抛出包含 SQL 或连接细节的异常。
     */
    public SyncPartitionRangeProbeInternalResponse probeRange(SyncPartitionRangeProbeInternalRequest request) {
        validate(request);
        String sql = "SELECT MIN(" + quote(request.getConnectorType(), request.getSplitPk()) + ") AS min_value, "
                + "MAX(" + quote(request.getConnectorType(), request.getSplitPk()) + ") AS max_value, "
                + "COUNT(*) AS row_count FROM " + qualifiedObject(request.getConnectorType(), request.getObjectLocator());
        try (Connection connection = connectionProvider.openConnection(request.getDatasourceId(), true);
             PreparedStatement preparedStatement = connection.prepareStatement(sql);
             ResultSet resultSet = preparedStatement.executeQuery()) {
            if (!resultSet.next()) {
                return response("RANGE_EMPTY", null, null, 0L, false, List.of("SPLIT_PK_RANGE_EMPTY"));
            }
            Object minValue = resultSet.getObject("min_value");
            Object maxValue = resultSet.getObject("max_value");
            long rowCount = resultSet.getLong("row_count");
            if (rowCount <= 0L || minValue == null || maxValue == null) {
                return response("RANGE_EMPTY", null, null, rowCount, false, List.of("SPLIT_PK_RANGE_EMPTY"));
            }
            Long minLong = numericLong(minValue);
            Long maxLong = numericLong(maxValue);
            if (minLong == null || maxLong == null) {
                return response("RANGE_NOT_NUMERIC", null, null, rowCount, false,
                        List.of("SPLIT_PK_NUMERIC_RANGE_REQUIRED"));
            }
            return response("RANGE_PROBED", minLong, maxLong, rowCount, true,
                    List.of("SPLIT_PK_MIN_MAX_PROBED_BY_DATASOURCE_MANAGEMENT"));
        } catch (SQLException | ClassNotFoundException | RuntimeException exception) {
            return response("RANGE_PROBE_FAILED", null, null, 0L, false,
                    List.of("SPLIT_PK_RANGE_PROBE_FAILED", exception.getClass().getSimpleName()));
        }
    }

    private void validate(SyncPartitionRangeProbeInternalRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("partition range probe request 不能为空");
        }
        requiredIdentifier(request.getSplitPk(), "splitPk");
        if (request.getDatasourceId() == null) {
            throw new IllegalArgumentException("datasourceId 不能为空");
        }
        qualifiedObject(request.getConnectorType(), request.getObjectLocator());
    }

    private Long numericLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private SyncPartitionRangeProbeInternalResponse response(String status,
                                                             Long minValue,
                                                             Long maxValue,
                                                             Long rowCount,
                                                             boolean numericRange,
                                                             List<String> warnings) {
        return new SyncPartitionRangeProbeInternalResponse(
                status,
                minValue,
                maxValue,
                rowCount,
                numericRange,
                warnings == null ? List.of() : List.copyOf(warnings),
                SyncPartitionRangeProbeInternalResponse.PAYLOAD_POLICY
        );
    }

    private String qualifiedObject(String connectorType, String objectLocator) {
        if (objectLocator == null || objectLocator.isBlank()) {
            throw new IllegalArgumentException("objectLocator 不能为空");
        }
        String[] parts = objectLocator.trim().split("\\.");
        if (parts.length == 0 || parts.length > 3) {
            throw new IllegalArgumentException("objectLocator 只允许 table、schema.table 或 database.schema.table");
        }
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < parts.length; index++) {
            if (index > 0) {
                builder.append('.');
            }
            builder.append(quote(connectorType, requiredIdentifier(parts[index], "objectLocator")));
        }
        return builder.toString();
    }

    private String quote(String connectorType, String identifier) {
        String safe = requiredIdentifier(identifier, "identifier");
        return switch (normalize(connectorType)) {
            case "MYSQL" -> "`" + safe + "`";
            case "SQLSERVER", "SQL_SERVER" -> "[" + safe + "]";
            default -> "\"" + safe + "\"";
        };
    }

    private String requiredIdentifier(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        String trimmed = value.trim();
        if (!SAFE_IDENTIFIER.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(fieldName + " 包含不安全标识符");
        }
        return trimmed;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}

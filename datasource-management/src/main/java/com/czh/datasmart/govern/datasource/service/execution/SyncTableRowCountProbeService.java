/**
 * @Author : Cui
 * @Date: 2026/07/09 22:38
 * @Description DataSmart Govern Backend - SyncTableRowCountProbeService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution;

import com.czh.datasmart.govern.datasource.controller.dto.SyncTableRowCountProbeInternalRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncTableRowCountProbeInternalResponse;
import com.czh.datasmart.govern.datasource.service.execution.jdbc.SyncJdbcConnectionProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 同步目标表行数探测服务。
 *
 * <p>这个服务解决的是创建同步任务预检查中的一个真实产品问题：如果用户选择“全量传输 + INSERT 写入”，
 * 系统必须知道目标表是否为空。INSERT 不会覆盖已有数据；如果目标表已经有相同主键或唯一键，数据库通常会直接报错；
 * 即使没有唯一约束，也可能产生重复业务数据。因此预检查不能只提示“建议确认”，而应该尽可能在服务端直接判断。</p>
 *
 * <p>为什么该能力放在 datasource-management：</p>
 * <p>1. datasource-management 是数据源连接和凭据的所有者；data-sync 不应直接打开目标库连接；</p>
 * <p>2. row-count 虽然不返回业务行，但仍会触达客户数据库，必须走受控 internal 路由、只读连接和服务账号边界；</p>
 * <p>3. 后续如果要把精确 COUNT(*) 替换为统计信息估算、采样估算或异步预检查，也只需要替换本服务实现。</p>
 */
@Service
@RequiredArgsConstructor
public class SyncTableRowCountProbeService {

    /**
     * 标识符白名单。
     *
     * <p>objectLocator 会被拆成 table/schema/table 等普通标识符段。这里故意只允许字母、数字和下划线，
     * 不允许双引号、反引号、空格、函数、注释、分号或表达式。这样服务端可以安全拼接由自己生成的 COUNT SQL，
     * 而不是执行调用方传入的任意 SQL。</p>
     */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    private final SyncJdbcConnectionProvider connectionProvider;

    /**
     * 探测目标表行数。
     *
     * @param request internal 请求，通常来自 data-sync 服务账号。
     * @return 低敏行数探测事实；失败时返回 fail-closed 状态，避免把数据库异常细节泄露给普通用户。
     */
    public SyncTableRowCountProbeInternalResponse probeRowCount(SyncTableRowCountProbeInternalRequest request) {
        long startTime = System.currentTimeMillis();
        try {
            validate(request);
            String sql = "SELECT COUNT(*) AS row_count FROM "
                    + qualifiedObject(request.getConnectorType(), request.getObjectLocator());
            try (Connection connection = connectionProvider.openConnection(request.getDatasourceId(), true);
                 PreparedStatement preparedStatement = connection.prepareStatement(sql);
                 ResultSet resultSet = preparedStatement.executeQuery()) {
                long rowCount = 0L;
                if (resultSet.next()) {
                    rowCount = resultSet.getLong("row_count");
                }
                List<String> warnings = new ArrayList<>();
                warnings.add("当前使用精确 COUNT(*) 判断目标表是否为空；生产大表可升级为统计信息估算，但必须显式标记估算语义。");
                if (rowCount > 0L) {
                    warnings.add("目标表已存在数据；全量 INSERT 不会覆盖旧行，但可能发生主键/唯一键冲突或重复写入。");
                }
                return response("ROW_COUNT_PROBED", rowCount, System.currentTimeMillis() - startTime, warnings);
            }
        } catch (IllegalArgumentException exception) {
            return response("ROW_COUNT_PROBE_REJECTED", null, System.currentTimeMillis() - startTime,
                    List.of("ROW_COUNT_PROBE_REQUEST_INVALID", exception.getMessage()));
        } catch (SQLException | ClassNotFoundException | RuntimeException exception) {
            return response("ROW_COUNT_PROBE_FAILED", null, System.currentTimeMillis() - startTime,
                    List.of("ROW_COUNT_PROBE_FAILED", exception.getClass().getSimpleName()));
        }
    }

    private void validate(SyncTableRowCountProbeInternalRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("row-count probe request 不能为空");
        }
        if (request.getDatasourceId() == null) {
            throw new IllegalArgumentException("datasourceId 不能为空");
        }
        if (request.getConnectorType() == null || request.getConnectorType().isBlank()) {
            throw new IllegalArgumentException("connectorType 不能为空");
        }
        qualifiedObject(request.getConnectorType(), request.getObjectLocator());
    }

    private SyncTableRowCountProbeInternalResponse response(String status,
                                                            Long rowCount,
                                                            Long durationMs,
                                                            List<String> warnings) {
        return new SyncTableRowCountProbeInternalResponse(
                status,
                rowCount,
                rowCount == null ? null : rowCount <= 0L,
                durationMs,
                warnings == null ? List.of() : List.copyOf(warnings),
                SyncTableRowCountProbeInternalResponse.PAYLOAD_POLICY
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

/**
 * @Author : Cui
 * @Date: 2026/06/20 03:05
 * @Description DataSmart Govern Backend - AbstractSyncJdbcDialect.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution.jdbc;

import com.czh.datasmart.govern.datasource.support.SyncWriteStrategy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * JDBC 方言公共基类。
 *
 * <p>该类承担三类公共责任：</p>
 * <p>1. 做对象名和字段名校验，避免把 `schema.table`、列名或主键名拼成 SQL 注入片段；</p>
 * <p>2. 生成全量读取、增量读取、追加写入等多数关系型数据库都相似的 SQL 模板；</p>
 * <p>3. 把 UPSERT、INSERT_IGNORE、REPLACE 等冲突写入语义分发给具体数据库方言。</p>
 *
 * <p>这里出现 SQL 字符串并不违背项目的低敏原则，因为这些 SQL 只在内部执行层使用，
 * 不进入外部接口、事件投影、审计摘要或 current-repo-state。真正的业务值也不会拼接进 SQL，
 * 只能通过 `?` 占位符和 `parameterNames` 绑定。</p>
 */
public abstract class AbstractSyncJdbcDialect implements SyncJdbcDialect {

    /**
     * 只允许普通标识符，暂不允许空格、引号、函数调用、表达式和注释符。
     * 这会牺牲一部分非常规表名兼容性，但换来第一阶段更安全、更容易审计的 SQL 生成边界。
     */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SQL_DANGEROUS_TOKEN = Pattern.compile(
            "\\b(insert|update|delete|merge|drop|alter|create|truncate|grant|revoke|call|exec|execute|copy|load|replace)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * 内部 SQL 安全说明。
     */
    private static final List<String> INTERNAL_SQL_SAFETY_NOTES = List.of(
            "SQL_TEMPLATE_INTERNAL_ONLY",
            "BUSINESS_VALUES_MUST_USE_PREPARED_STATEMENT_PARAMETERS",
            "DO_NOT_EXPOSE_SQL_IN_API_EVENT_OR_AUDIT_PROJECTION"
    );

    @Override
    public boolean supportsConnector(String connectorType) {
        return connectorType() != null && connectorType().equalsIgnoreCase(connectorType);
    }

    @Override
    public SyncPreparedJdbcStatement buildFullReadStatement(SyncJdbcReadStatementSpec spec) {
        requireReadSpec(spec);
        List<SyncJdbcFilterCondition> filters = safeFilters(spec.getFilterConditions());
        String sql = buildFullReadSql(
                projection(spec.getSelectedColumns()),
                qualifiedObject(spec.getObjectLocator()),
                whereClause(filters),
                orderClause(spec.getStableSortColumns(), spec.getSelectedColumns()),
                positiveLimit(spec.getLimit())
        );
        return statement("FULL_READ", sql, fullReadParameterNames(filters));
    }

    @Override
    public SyncPreparedJdbcStatement buildIncrementalReadStatement(SyncJdbcReadStatementSpec spec) {
        requireReadSpec(spec);
        List<SyncJdbcFilterCondition> filters = safeFilters(spec.getFilterConditions());
        String checkpointColumn = quoteIdentifier(requiredIdentifier(spec.getCheckpointColumn(), "checkpointColumn"));
        String sql = buildIncrementalReadSql(
                projection(spec.getSelectedColumns()),
                qualifiedObject(spec.getObjectLocator()),
                whereClause(filters),
                checkpointColumn,
                positiveLimit(spec.getLimit())
        );
        return statement("INCREMENTAL_READ", sql, incrementalReadParameterNames(filters));
    }

    @Override
    public SyncPreparedJdbcStatement buildCustomSqlReadStatement(SyncJdbcReadStatementSpec spec) {
        requireCustomSqlReadSpec(spec);
        String sql = buildCustomSqlResultSetSql(
                projection(spec.getSelectedColumns()),
                requiredReadOnlySql(spec.getCustomSql()),
                positiveLimit(spec.getLimit())
        );
        return statement("CUSTOM_SQL_RESULT_SET_READ", sql, List.of("limit", "offset"));
    }

    @Override
    public SyncPreparedJdbcStatement buildAppendWriteStatement(SyncJdbcWriteStatementSpec spec) {
        requireWriteSpec(spec);
        String sql = appendInsertSql(qualifiedObject(spec.getObjectLocator()), quotedColumns(spec.getColumns()), placeholders(spec.getColumns().size()));
        return statement("APPEND_WRITE", sql, List.copyOf(spec.getColumns()));
    }

    @Override
    public SyncPreparedJdbcStatement buildConflictAwareWriteStatement(SyncJdbcWriteStatementSpec spec) {
        requireWriteSpec(spec);
        SyncWriteStrategy writeStrategy = spec.getWriteStrategy() == null ? SyncWriteStrategy.APPEND : spec.getWriteStrategy();
        return switch (writeStrategy) {
            case APPEND -> buildAppendWriteStatement(spec);
            case UPSERT -> buildUpsertWriteStatement(spec);
            case INSERT_IGNORE -> buildInsertIgnoreWriteStatement(spec);
            case REPLACE -> buildReplaceWriteStatement(spec);
            case OVERWRITE -> throw new UnsupportedOperationException("OVERWRITE 属于破坏性写入，需要先接入审批、备份和回滚策略，当前 JDBC 方言层不直接生成覆盖 SQL");
        };
    }

    /**
     * 构建当前数据库的全量读取 SQL。
     */
    protected String buildFullReadSql(String projection,
                                      String qualifiedObject,
                                      String whereClause,
                                      String orderClause,
                                      int limit) {
        return "SELECT " + projection + " FROM " + qualifiedObject + whereClause + orderClause + " LIMIT ? OFFSET ?";
    }

    /**
     * 兼容旧子类覆盖点。
     */
    protected String buildFullReadSql(String projection, String qualifiedObject, int limit) {
        return buildFullReadSql(projection, qualifiedObject, "", "", limit);
    }

    /**
     * 构建当前数据库的增量读取 SQL。
     */
    protected String buildIncrementalReadSql(String projection,
                                             String qualifiedObject,
                                             String whereClause,
                                             String checkpointColumn,
                                             int limit) {
        String prefix = whereClause == null || whereClause.isBlank() ? " WHERE " : whereClause + " AND ";
        return "SELECT " + projection + " FROM " + qualifiedObject
                + prefix + checkpointColumn + " > ? ORDER BY " + checkpointColumn + " ASC LIMIT ?";
    }

    /**
     * 兼容旧子类覆盖点。
     */
    protected String buildIncrementalReadSql(String projection, String qualifiedObject, String checkpointColumn, int limit) {
        return buildIncrementalReadSql(projection, qualifiedObject, "", checkpointColumn, limit);
    }

    /**
     * 增量读取参数顺序。
     * MySQL/PostgreSQL 是 checkpointValue 在前、limit 在后；SQL Server 的 TOP 语义会覆盖该顺序。
     */
    protected List<String> incrementalReadParameterNames(List<SyncJdbcFilterCondition> filters) {
        List<String> parameterNames = new ArrayList<>(filterParameterNames(filters));
        parameterNames.add("checkpointValue");
        parameterNames.add("limit");
        return List.copyOf(parameterNames);
    }

    /**
     * 全量读取参数顺序。
     */
    protected List<String> fullReadParameterNames(List<SyncJdbcFilterCondition> filters) {
        List<String> parameterNames = new ArrayList<>(filterParameterNames(filters));
        parameterNames.add("limit");
        parameterNames.add("offset");
        return List.copyOf(parameterNames);
    }

    /**
     * 兼容旧子类覆盖点。
     */
    protected List<String> incrementalReadParameterNames() {
        return incrementalReadParameterNames(List.of());
    }

    /**
     * 将自定义 SQL 包装成可分页结果集。
     *
     * <p>包装的原因有两个：第一，用户 SQL 只负责描述“读什么”，批量执行器仍要控制“每批读多少”；
     * 第二，外层 projection 使用字段映射声明的安全列名，可以让结果集字段顺序更稳定。这里不向 SQL 中拼接任何业务值，
     * 只拼接经过白名单校验的列名和已经通过只读门禁的查询正文。</p>
     */
    protected String buildCustomSqlResultSetSql(String projection, String customSql, int limit) {
        return "SELECT " + projection + " FROM (" + customSql + ") datasmart_custom_sql_result LIMIT ? OFFSET ?";
    }

    /**
     * 生成过滤条件 SQL 片段。
     *
     * <p>这里仅拼接经过白名单校验的字段名和操作符；业务值不进入 SQL 字符串，
     * 而是通过 {@link #filterParameterNames(List)} 声明为 filter_0、filter_1 等参数名。</p>
     */
    protected String whereClause(List<SyncJdbcFilterCondition> filters) {
        if (filters == null || filters.isEmpty()) {
            return "";
        }
        List<String> clauses = new ArrayList<>();
        for (int index = 0; index < filters.size(); index++) {
            SyncJdbcFilterCondition condition = filters.get(index);
            String column = quoteIdentifier(requiredIdentifier(condition.getColumn(), "filterColumn"));
            String operator = sqlOperator(condition.getOperator());
            if (Boolean.TRUE.equals(condition.getValueRequired())) {
                clauses.add(column + " " + operator + " ?");
            } else {
                clauses.add(column + " " + operator);
            }
        }
        return " WHERE " + String.join(" AND ", clauses);
    }

    /**
     * 生成全量扫描的稳定排序片段。
     *
     * <p>这里的排序不是为了业务展示，而是为了让多批次同步具备可重复的读取顺序。
     * DataX 风格批量同步的核心原则之一是 reader 每次只拉取一个受控批次，
     * writer 写入后再推进下一批；如果没有稳定排序，下一批 offset 可能落到不确定的位置。</p>
     */
    protected String orderClause(List<String> stableSortColumns, List<String> selectedColumns) {
        List<String> orderColumns = stableSortColumns == null || stableSortColumns.isEmpty()
                ? selectedColumns
                : stableSortColumns;
        if (orderColumns == null || orderColumns.isEmpty()) {
            return "";
        }
        return " ORDER BY " + orderColumns.stream()
                .map(column -> quoteIdentifier(requiredIdentifier(column, "orderColumn")) + " ASC")
                .collect(Collectors.joining(", "));
    }

    /**
     * 过滤条件参数名顺序。
     */
    protected List<String> filterParameterNames(List<SyncJdbcFilterCondition> filters) {
        if (filters == null || filters.isEmpty()) {
            return List.of();
        }
        List<String> parameterNames = new ArrayList<>();
        for (int index = 0; index < filters.size(); index++) {
            SyncJdbcFilterCondition condition = filters.get(index);
            if (Boolean.TRUE.equals(condition.getValueRequired())) {
                parameterNames.add("filter_" + index);
            }
        }
        return parameterNames;
    }

    private List<SyncJdbcFilterCondition> safeFilters(List<SyncJdbcFilterCondition> filters) {
        if (filters == null) {
            return List.of();
        }
        filters.forEach(condition -> {
            if (condition == null) {
                throw new IllegalArgumentException("filter condition 不能为空");
            }
            requiredIdentifier(condition.getColumn(), "filterColumn");
            sqlOperator(condition.getOperator());
        });
        return List.copyOf(filters);
    }

    private String sqlOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new IllegalArgumentException("filterOperator 不能为空");
        }
        return switch (operator.trim().toUpperCase()) {
            case "EQ" -> "=";
            case "NE" -> "<>";
            case "GT" -> ">";
            case "GTE" -> ">=";
            case "LT" -> "<";
            case "LTE" -> "<=";
            case "LIKE" -> "LIKE";
            case "IS_NULL" -> "IS NULL";
            case "IS_NOT_NULL" -> "IS NOT NULL";
            default -> throw new IllegalArgumentException("filterOperator 不支持: " + operator);
        };
    }

    /**
     * 构建追加写入 SQL。
     */
    protected String appendInsertSql(String qualifiedObject, List<String> columns, String placeholders) {
        return "INSERT INTO " + qualifiedObject + " (" + String.join(", ", columns) + ") VALUES (" + placeholders + ")";
    }

    /**
     * 构建 UPSERT 写入 SQL。
     */
    protected abstract SyncPreparedJdbcStatement buildUpsertWriteStatement(SyncJdbcWriteStatementSpec spec);

    /**
     * 构建冲突忽略写入 SQL。
     */
    protected abstract SyncPreparedJdbcStatement buildInsertIgnoreWriteStatement(SyncJdbcWriteStatementSpec spec);

    /**
     * 构建替换写入 SQL。
     */
    protected abstract SyncPreparedJdbcStatement buildReplaceWriteStatement(SyncJdbcWriteStatementSpec spec);

    /**
     * 获取标识符左引用符。
     */
    protected abstract String quoteOpen();

    /**
     * 获取标识符右引用符。
     */
    protected abstract String quoteClose();

    /**
     * 生成统一语句对象。
     */
    protected SyncPreparedJdbcStatement statement(String intent, String sql, List<String> parameterNames) {
        return new SyncPreparedJdbcStatement(intent, sql, parameterNames, INTERNAL_SQL_SAFETY_NOTES);
    }

    /**
     * 将对象定位符按点分段后逐段引用。
     */
    protected String qualifiedObject(String objectLocator) {
        String required = requiredText(objectLocator, "objectLocator");
        String[] parts = required.split("\\.");
        List<String> quotedParts = new ArrayList<>();
        for (String part : parts) {
            quotedParts.add(quoteIdentifier(requiredIdentifier(part, "objectLocator")));
        }
        return String.join(".", quotedParts);
    }

    /**
     * 将字段列表转换为 SQL projection。
     */
    protected String projection(List<String> selectedColumns) {
        if (selectedColumns == null || selectedColumns.isEmpty()) {
            return "*";
        }
        return String.join(", ", quotedColumns(selectedColumns));
    }

    /**
     * 引用字段列表。
     */
    protected List<String> quotedColumns(List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("columns 不能为空，写入语句必须显式声明字段清单");
        }
        return columns.stream()
                .map(column -> quoteIdentifier(requiredIdentifier(column, "column")))
                .toList();
    }

    /**
     * 引用单个标识符。
     */
    protected String quoteIdentifier(String identifier) {
        String required = requiredIdentifier(identifier, "identifier");
        return quoteOpen() + required + quoteClose();
    }

    /**
     * 计算非主键字段。
     */
    protected List<String> nonPrimaryKeyColumns(SyncJdbcWriteStatementSpec spec) {
        Set<String> primaryKeys = requirePrimaryKeys(spec).stream().collect(Collectors.toSet());
        return spec.getColumns().stream()
                .filter(column -> !primaryKeys.contains(column))
                .toList();
    }

    /**
     * 校验并返回主键字段。
     */
    protected List<String> requirePrimaryKeys(SyncJdbcWriteStatementSpec spec) {
        if (spec.getPrimaryKeyColumns() == null || spec.getPrimaryKeyColumns().isEmpty()) {
            throw new IllegalArgumentException("冲突写入策略必须提供 primaryKeyColumns");
        }
        spec.getPrimaryKeyColumns().forEach(column -> requiredIdentifier(column, "primaryKeyColumn"));
        return spec.getPrimaryKeyColumns();
    }

    /**
     * 生成指定数量的占位符。
     */
    protected String placeholders(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("占位符数量必须大于 0");
        }
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    /**
     * 读取规格基础校验。
     */
    private void requireReadSpec(SyncJdbcReadStatementSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("read statement spec 不能为空");
        }
        requiredText(spec.getObjectLocator(), "objectLocator");
    }

    /**
     * 自定义 SQL 读取规格校验。
     *
     * <p>与普通表读取不同，自定义 SQL 不需要 objectLocator；它的源对象已经被 SQL 查询表达。
     * 但它必须声明 selectedColumns，因为 writer 需要根据字段映射把 SQL 别名写入目标字段。
     * 这里同时做二次只读 SQL 校验，防止未来某个调用方绕过 data-sync 门禁直接访问 datasource-management internal 路由。</p>
     */
    private void requireCustomSqlReadSpec(SyncJdbcReadStatementSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("custom sql read statement spec 不能为空");
        }
        if (spec.getSelectedColumns() == null || spec.getSelectedColumns().isEmpty()) {
            throw new IllegalArgumentException("CUSTOM_SQL_RESULT_SET 必须声明 selectedColumns，用于绑定 SQL 别名与目标字段");
        }
        spec.getSelectedColumns().forEach(column -> requiredIdentifier(column, "customSqlSelectedColumn"));
        requiredReadOnlySql(spec.getCustomSql());
    }

    /**
     * 写入规格基础校验。
     */
    private void requireWriteSpec(SyncJdbcWriteStatementSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("write statement spec 不能为空");
        }
        requiredText(spec.getObjectLocator(), "objectLocator");
        quotedColumns(spec.getColumns());
    }

    /**
     * 校验文本不为空。
     */
    protected String requiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return value.trim();
    }

    /**
     * 校验 SQL 标识符安全。
     */
    protected String requiredIdentifier(String value, String fieldName) {
        String required = requiredText(value, fieldName);
        if (!SAFE_IDENTIFIER.matcher(required).matches()) {
            throw new IllegalArgumentException(fieldName + " 包含不安全标识符: " + value);
        }
        return required;
    }

    /**
     * limit 兜底。
     */
    private int positiveLimit(Integer limit) {
        return limit == null || limit <= 0 ? 1000 : limit;
    }

    protected String requiredReadOnlySql(String sql) {
        String required = requiredText(sql, "customSql");
        String normalized = required.strip().replaceAll("\\s+", " ");
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (normalized.contains(";")
                || normalized.contains("--")
                || normalized.contains("/*")
                || normalized.contains("*/")
                || (!lower.startsWith("select ") && !lower.startsWith("with "))
                || SQL_DANGEROUS_TOKEN.matcher(lower).find()) {
            throw new IllegalArgumentException("customSql 只允许单条 SELECT/WITH 只读查询");
        }
        return normalized;
    }
}

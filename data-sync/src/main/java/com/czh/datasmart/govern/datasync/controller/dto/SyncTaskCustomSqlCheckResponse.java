/**
 * @Author : Cui
 * @Date: 2026/07/08 16:41
 * @Description DataSmart Govern Backend - SyncTaskCustomSqlCheckResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.util.List;

/**
 * SQL 语句模式创建向导的 SQL 检查响应。
 *
 * <p>该响应刻意分成四类信息：</p>
 * <p>1. 流程判断：{@code passed/staticSafe/remoteProbeExecuted} 告诉前端是否允许进入下一步；</p>
 * <p>2. 输出结构：{@code outputColumns} 告诉前端 SQL 的 SELECT 输出列或别名，用于字段映射；</p>
 * <p>3. 问题提示：{@code blockingIssues/warnings/recommendedActions} 帮助用户理解为什么不能继续；</p>
 * <p>4. 安全摘要：{@code sqlFingerprint} 用于日志、审计和排查关联，但不泄露 SQL 正文。</p>
 *
 * <p>安全边界：响应不包含样本行、不包含连接串、不包含密码、不包含完整 SQL。
 * 如果需要保存 SQL，应通过同步模板创建/编辑接口保存受控配置，而不是通过检查接口保存。</p>
 */
public record SyncTaskCustomSqlCheckResponse(
        boolean passed,
        boolean staticSafe,
        boolean remoteProbeExecuted,
        Long sourceDatasourceId,
        Long targetDatasourceId,
        String targetSchemaName,
        String targetObjectName,
        String sqlFingerprint,
        Integer outputColumnCount,
        Integer remoteReturnedRowCount,
        Long remoteDurationMs,
        List<SqlOutputColumn> outputColumns,
        List<FieldMappingHint> derivedFieldMappingHints,
        List<String> blockingIssues,
        List<String> warnings,
        List<String> recommendedActions
) {

    /**
     * SQL 输出列摘要。
     *
     * <p>列名来自 JDBC {@code ResultSetMetaData.getColumnLabel(...)}，因此会优先使用 SQL alias。
     * 例如 {@code select user_id as id_alias} 会返回 {@code id_alias}，前端字段映射也应以 alias 作为源端字段名。</p>
     */
    public record SqlOutputColumn(
            Integer ordinalPosition,
            String columnName,
            String dataTypeName,
            boolean derivedFromSqlAlias,
            String description
    ) {
    }

    /**
     * 字段映射提示。
     *
     * <p>当前版本只根据 SQL 输出列生成“同名目标字段候选”，不会直接替用户决定最终映射。
     * 后续如果同一个检查请求携带目标表字段摘要，或 data-sync 进一步调用目标端元数据发现，就可以把该提示升级为
     * “SQL 输出列 -> 目标表字段”的强建议。</p>
     */
    public record FieldMappingHint(
            String sourceColumnName,
            String targetColumnCandidate,
            boolean syncEnabledByDefault,
            String reason
    ) {
    }
}

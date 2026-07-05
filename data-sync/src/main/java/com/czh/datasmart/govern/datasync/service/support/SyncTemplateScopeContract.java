/**
 * @Author : Cui
 * @Date: 2026/07/05 23:18
 * @Description DataSmart Govern Backend - SyncTemplateScopeContract.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.util.List;

/**
 * 同步范围解析结果。
 *
 * <p>该对象是 {@code SyncTemplateScopeContractSupport} 对模板范围字段做安全解析后的低敏摘要。
 * 它不会携带 objectMappingConfig 或 customSqlConfig 原文，只暴露范围类型、对象数量、是否需要审批、
 * 是否能被当前最小执行桥接器处理以及低敏问题码。这样 controller、预览、预检、worker bridge 可以复用同一套判断，
 * 避免“创建时放行、预览时提示、执行时又用另一套规则”的产品不一致。</p>
 *
 * @param scopeType 归一化后的范围类型，例如 SINGLE_OBJECT、OBJECT_LIST、SCHEMA_FULL。
 * @param singleObjectScope 是否是单对象范围。
 * @param multiObjectScope 是否涉及多对象、schema 或 database 级执行。
 * @param customSqlScope 是否是自定义 SQL 查询范围。
 * @param selectedObjectCount objectMappingConfig 中显式声明的对象映射数量；未声明时为 0。
 * @param objectMappingDeclared 是否声明了对象映射配置正文。
 * @param customSqlDeclared 是否声明了自定义 SQL 配置正文。
 * @param requiresApproval 当前范围是否天然需要审批，例如整库迁移、自定义 SQL 或覆盖写入组合。
 * @param executableByMinimalBridge 当前最小 data-sync -> datasource-management run-once 桥是否可直接执行。
 * @param issueCodes 阻断或警告问题码。具体是否阻断由 {@link #blockingIssueCodes()} 判断。
 * @param warnings 非阻断提醒，适合在预检或运营台展示。
 * @param recommendedActions 推荐动作，不包含 SQL、字段映射原文、样本数据或连接凭据。
 */
public record SyncTemplateScopeContract(
        String scopeType,
        boolean singleObjectScope,
        boolean multiObjectScope,
        boolean customSqlScope,
        int selectedObjectCount,
        boolean objectMappingDeclared,
        boolean customSqlDeclared,
        boolean requiresApproval,
        boolean executableByMinimalBridge,
        List<String> issueCodes,
        List<String> warnings,
        List<String> recommendedActions
) {

    /**
     * 返回阻断性问题码。
     *
     * <p>不是所有 issue 都应该阻断模板保存。例如“多表执行器尚未接入最小桥”是执行成熟度问题，
     * 对预检和 worker bridge 是阻断，但对产品配置建模不一定阻断；而“自定义 SQL 包含 DML/DDL”则必须
     * 在模板创建阶段就拒绝。因此这里把真正违反安全或必填合同的 issue 判定为 blocking。</p>
     */
    public List<String> blockingIssueCodes() {
        return issueCodes.stream()
                .filter(SyncTemplateScopeContract::isBlockingIssueCode)
                .toList();
    }

    public boolean hasBlockingIssues() {
        return !blockingIssueCodes().isEmpty();
    }

    public boolean hasIssue(String issueCode) {
        return issueCodes.contains(issueCode);
    }

    private static boolean isBlockingIssueCode(String issueCode) {
        return "SYNC_SCOPE_TYPE_UNSUPPORTED".equals(issueCode)
                || "SYNC_SCOPE_MODE_MISMATCH".equals(issueCode)
                || "SINGLE_OBJECT_SOURCE_NOT_DECLARED".equals(issueCode)
                || "SINGLE_OBJECT_TARGET_NOT_DECLARED".equals(issueCode)
                || "OBJECT_MAPPING_CONFIG_REQUIRED".equals(issueCode)
                || "OBJECT_MAPPING_JSON_INVALID".equals(issueCode)
                || "OBJECT_MAPPING_EMPTY".equals(issueCode)
                || "OBJECT_MAPPING_TOO_LARGE".equals(issueCode)
                || "OBJECT_MAPPING_IDENTIFIER_UNSAFE".equals(issueCode)
                || "SCHEMA_FULL_REQUIRES_SCHEMA_PAIR".equals(issueCode)
                || "DATABASE_FULL_REQUIRES_DISCOVERY_POLICY".equals(issueCode)
                || "CUSTOM_SQL_CONFIG_REQUIRED".equals(issueCode)
                || "CUSTOM_SQL_JSON_INVALID".equals(issueCode)
                || "CUSTOM_SQL_QUERY_MISSING".equals(issueCode)
                || "CUSTOM_SQL_RAW_SQL_UNSAFE".equals(issueCode)
                || "CUSTOM_SQL_TARGET_OBJECT_REQUIRED".equals(issueCode)
                || "CUSTOM_SQL_FIELD_MAPPING_REQUIRED".equals(issueCode);
    }
}

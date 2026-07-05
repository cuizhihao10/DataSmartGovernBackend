/**
 * @Author : Cui
 * @Date: 2026/06/29 02:40
 * @Description DataSmart Govern Backend - SyncTemplatePlanningPreviewSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncConnectorCompatibilityView;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTemplatePlanningPreviewResponse;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.support.SyncMode;
import com.czh.datasmart.govern.datasync.support.SyncTransferChannel;
import com.czh.datasmart.govern.datasync.support.SyncWriteStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 同步模板规划预览支撑组件。
 *
 * <p>预览接口处在“模板保存”和“执行预检查”之间：它不访问真实数据源，也不执行 SQL，只根据模板中已经保存的
 * 低敏配置事实生成一份可解释报告。这样用户和 Agent 可以在创建任务前看到：配置缺了什么、是否需要审批、
 * 当前最小 runner 能否执行、哪些性能或安全风险需要补充。</p>
 *
 * <p>为什么不把 preview 做成 validate 的别名：</p>
 * <p>1. validate 是 fail-fast，适合阻断继续操作；preview 是问题清单，适合一次性展示多个缺口；</p>
 * <p>2. preview 需要区分“可进入任务草稿但暂不能执行”的高级范围，例如整库迁移、自定义 SQL；</p>
 * <p>3. preview 的返回值必须遵循低敏原则，不返回字段映射、对象映射、SQL、过滤条件、分区窗口或连接配置原文。</p>
 */
@Component
public class SyncTemplatePlanningPreviewSupport {

    private static final String PAYLOAD_POLICY = "LOW_SENSITIVE_TEMPLATE_PLANNING_PREVIEW";
    private static final String READY = "READY";
    private static final String NEEDS_REVIEW = "NEEDS_REVIEW";
    private static final String BLOCKED = "BLOCKED";

    private final SyncConnectorCapabilityRegistry connectorCapabilityRegistry;
    private final SyncTemplateScopeContractSupport scopeContractSupport;

    /**
     * 兼容旧测试的构造器。
     */
    public SyncTemplatePlanningPreviewSupport(SyncConnectorCapabilityRegistry connectorCapabilityRegistry) {
        this(connectorCapabilityRegistry, new SyncTemplateScopeContractSupport());
    }

    /**
     * Spring 注入构造器。
     */
    @Autowired
    public SyncTemplatePlanningPreviewSupport(SyncConnectorCapabilityRegistry connectorCapabilityRegistry,
                                              SyncTemplateScopeContractSupport scopeContractSupport) {
        this.connectorCapabilityRegistry = connectorCapabilityRegistry;
        this.scopeContractSupport = scopeContractSupport;
    }

    /**
     * 生成同步模板规划预览。
     *
     * @param template 已通过数据范围校验读取出的模板。
     * @return 低敏规划预览，不包含配置原文或样本数据。
     */
    public SyncTemplatePlanningPreviewResponse preview(SyncTemplate template) {
        List<String> issueCodes = new ArrayList<>();
        List<String> recommendedActions = new ArrayList<>();
        List<String> performanceNotes = new ArrayList<>();
        List<String> safetyNotes = new ArrayList<>();

        SyncTemplateScopeContract scopeContract = scopeContractSupport.evaluate(template);
        issueCodes.addAll(scopeContract.issueCodes());
        recommendedActions.addAll(scopeContract.recommendedActions());
        safetyNotes.addAll(scopeContract.warnings());

        SyncMode syncMode = resolveMode(template, issueCodes, recommendedActions);
        SyncWriteStrategy writeStrategy = resolveWriteStrategy(template, issueCodes, recommendedActions);
        SyncConnectorCompatibilityView compatibility = resolveCompatibility(template, syncMode, issueCodes, recommendedActions);
        SyncTransferChannel transferChannel = SyncTransferChannelSupport.resolve(syncMode);
        performanceNotes.add(SyncTransferChannelSupport.explanation(transferChannel));

        boolean sourceObjectDeclared = hasText(template.getSourceObjectName());
        boolean targetObjectDeclared = hasText(template.getTargetObjectName());
        boolean primaryKeyDeclared = hasText(template.getPrimaryKeyField());
        boolean incrementalFieldDeclared = hasText(template.getIncrementalField());
        boolean fieldMappingDeclared = hasText(template.getFieldMappingConfig());
        boolean filterDeclared = hasText(template.getFilterConfig());
        boolean partitionDeclared = hasText(template.getPartitionConfig());
        boolean retryPolicyDeclared = hasText(template.getRetryPolicy());
        boolean timeoutPolicyDeclared = hasText(template.getTimeoutPolicy());

        evaluateConfigurationHints(syncMode, writeStrategy, compatibility, scopeContract,
                sourceObjectDeclared, targetObjectDeclared, primaryKeyDeclared, incrementalFieldDeclared,
                fieldMappingDeclared, filterDeclared, partitionDeclared, retryPolicyDeclared, timeoutPolicyDeclared,
                issueCodes, recommendedActions, performanceNotes, safetyNotes);

        List<String> distinctIssues = distinct(issueCodes);
        String previewStatus = resolvePreviewStatus(distinctIssues);
        boolean connectorSupported = compatibility != null && compatibility.supported();
        boolean executionPrecheckReady = READY.equals(previewStatus)
                && connectorSupported
                && scopeContract.executableByMinimalBridge()
                && fieldMappingDeclared
                && !scopeContract.requiresApproval();

        return new SyncTemplatePlanningPreviewResponse(
                template.getId(),
                template.getTenantId(),
                template.getProjectId(),
                template.getWorkspaceId(),
                template.getSourceDatasourceId(),
                template.getTargetDatasourceId(),
                normalize(template.getSourceConnectorType()),
                normalize(template.getTargetConnectorType()),
                normalize(template.getSyncMode()),
                transferChannel == null ? null : transferChannel.name(),
                SyncTransferChannelSupport.referenceRuntime(transferChannel),
                scopeContract.scopeType(),
                scopeContract.singleObjectScope(),
                scopeContract.multiObjectScope(),
                scopeContract.customSqlScope(),
                scopeContract.selectedObjectCount(),
                scopeContract.objectMappingDeclared(),
                scopeContract.customSqlDeclared(),
                scopeContract.requiresApproval(),
                scopeContract.executableByMinimalBridge(),
                sourceObjectDeclared,
                targetObjectDeclared,
                writeStrategy == null ? null : writeStrategy.name(),
                writeStrategy != null && writeStrategy.requiresConflictKey(),
                primaryKeyDeclared,
                incrementalFieldDeclared,
                previewStatus,
                !BLOCKED.equals(previewStatus),
                executionPrecheckReady,
                connectorSupported,
                compatibility != null && compatibility.checkpointRequired(),
                fieldMappingDeclared,
                filterDeclared,
                partitionDeclared,
                retryPolicyDeclared,
                timeoutPolicyDeclared,
                distinctIssues,
                distinct(recommendedActions),
                distinct(performanceNotes),
                distinct(safetyNotes),
                PAYLOAD_POLICY
        );
    }

    /**
     * 解析同步模式。
     *
     * <p>预览接口不直接抛出异常，而是把问题压缩成 issueCode，便于前端一次性展示多类缺口。</p>
     */
    private SyncMode resolveMode(SyncTemplate template, List<String> issueCodes, List<String> recommendedActions) {
        String syncMode = normalize(template.getSyncMode());
        if (syncMode == null) {
            issueCodes.add("SYNC_MODE_MISSING");
            recommendedActions.add("先为同步模板选择 FULL、INCREMENTAL_TIME、CDC_STREAMING、CUSTOM_SQL_QUERY 等平台支持的同步模式");
            return null;
        }
        try {
            return SyncMode.valueOf(syncMode);
        } catch (IllegalArgumentException exception) {
            issueCodes.add("SYNC_MODE_UNSUPPORTED");
            recommendedActions.add("将 syncMode 调整为平台 SyncMode 枚举支持的值");
            return null;
        }
    }

    /**
     * 解析写入策略，并将未知策略压缩为低敏 issueCode。
     */
    private SyncWriteStrategy resolveWriteStrategy(SyncTemplate template,
                                                   List<String> issueCodes,
                                                   List<String> recommendedActions) {
        try {
            SyncWriteStrategy writeStrategy = SyncWriteStrategy.fromValue(template.getWriteStrategy());
            if (!hasText(template.getWriteStrategy())) {
                issueCodes.add("WRITE_STRATEGY_DEFAULTED_TO_APPEND");
                recommendedActions.add("建议显式声明 writeStrategy；默认 APPEND 能兼容历史模板，但重试、回放或补数时更容易产生重复记录");
            }
            return writeStrategy;
        } catch (IllegalArgumentException exception) {
            issueCodes.add("WRITE_STRATEGY_UNSUPPORTED");
            recommendedActions.add("将 writeStrategy 调整为 APPEND、UPSERT、INSERT_IGNORE、REPLACE 或 OVERWRITE 之一");
            return null;
        }
    }

    /**
     * 调用连接器能力矩阵生成兼容性结果。
     */
    private SyncConnectorCompatibilityView resolveCompatibility(SyncTemplate template,
                                                               SyncMode syncMode,
                                                               List<String> issueCodes,
                                                               List<String> recommendedActions) {
        String sourceConnectorType = normalize(template.getSourceConnectorType());
        String targetConnectorType = normalize(template.getTargetConnectorType());
        if (sourceConnectorType == null || targetConnectorType == null || syncMode == null) {
            issueCodes.add("CONNECTOR_FACTS_INCOMPLETE");
            recommendedActions.add("先通过 datasource-management 能力快照补全源端和目标端 connector type");
            return null;
        }
        SyncConnectorCompatibilityView compatibility = connectorCapabilityRegistry.checkCompatibility(
                sourceConnectorType, targetConnectorType, syncMode.name());
        if (!compatibility.supported()) {
            issueCodes.add("CONNECTOR_COMPATIBILITY_UNSUPPORTED");
        }
        issueCodes.addAll(compatibility.issueCodes());
        recommendedActions.addAll(compatibility.recommendedActions());
        return compatibility;
    }

    /**
     * 根据同步模式、范围契约和已声明配置生成规划建议。
     */
    private void evaluateConfigurationHints(SyncMode syncMode,
                                            SyncWriteStrategy writeStrategy,
                                            SyncConnectorCompatibilityView compatibility,
                                            SyncTemplateScopeContract scopeContract,
                                            boolean sourceObjectDeclared,
                                            boolean targetObjectDeclared,
                                            boolean primaryKeyDeclared,
                                            boolean incrementalFieldDeclared,
                                            boolean fieldMappingDeclared,
                                            boolean filterDeclared,
                                            boolean partitionDeclared,
                                            boolean retryPolicyDeclared,
                                            boolean timeoutPolicyDeclared,
                                            List<String> issueCodes,
                                            List<String> recommendedActions,
                                            List<String> performanceNotes,
                                            List<String> safetyNotes) {
        if (scopeContract.singleObjectScope() && !sourceObjectDeclared) {
            issueCodes.add("SOURCE_OBJECT_NOT_DECLARED");
            recommendedActions.add("声明 sourceObjectName，单对象 runner 必须知道从哪个表、视图、topic 或逻辑资源读取");
        }
        if (scopeContract.singleObjectScope() && !targetObjectDeclared) {
            issueCodes.add("TARGET_OBJECT_NOT_DECLARED");
            recommendedActions.add("声明 targetObjectName，单对象 runner 必须知道写入哪个目标对象");
        }
        if (writeStrategy != null && writeStrategy.requiresConflictKey() && !primaryKeyDeclared) {
            issueCodes.add("PRIMARY_KEY_NOT_DECLARED_FOR_CONFLICT_WRITE");
            recommendedActions.add(writeStrategy.name() + " 写入策略需要 primaryKeyField，用于目标端冲突判断和幂等写入");
        }
        if (writeStrategy != null && writeStrategy.isDestructiveRewrite()) {
            issueCodes.add("DESTRUCTIVE_WRITE_STRATEGY_REQUIRES_REVIEW");
            recommendedActions.add("OVERWRITE 属于覆盖式高风险写入，建议接入 permission-admin 审批、影响范围评估和回滚预案后再运行");
            safetyNotes.add("覆盖式写入不应由普通 worker 静默执行，应在执行前完成人审、审计和备份策略确认");
        }
        if ((syncMode == SyncMode.INCREMENTAL_TIME || syncMode == SyncMode.INCREMENTAL_ID) && !incrementalFieldDeclared) {
            issueCodes.add("INCREMENTAL_FIELD_NOT_DECLARED");
            recommendedActions.add("增量同步必须声明 incrementalField，用于 checkpoint 推进、断点续行和失败恢复");
        }
        if (!fieldMappingDeclared) {
            issueCodes.add("FIELD_MAPPING_NOT_DECLARED");
            recommendedActions.add("建议补充 fieldMappingConfig；真实执行需要明确源字段、目标字段、类型兼容和主键/冲突字段位置");
        }
        if (syncMode == SyncMode.INCREMENTAL_TIME || syncMode == SyncMode.INCREMENTAL_ID) {
            addIncrementalHints(filterDeclared, partitionDeclared, issueCodes, recommendedActions);
        }
        if (syncMode == SyncMode.CDC_STREAMING && !partitionDeclared) {
            issueCodes.add("STREAM_PARTITION_POLICY_NOT_DECLARED");
            recommendedActions.add("CDC/流式同步建议声明 partitionConfig，用于表达 topic/binlog 分区、offset 或 worker 并发策略");
        }
        if (compatibility != null && compatibility.checkpointRequired() && !retryPolicyDeclared) {
            issueCodes.add("RETRY_POLICY_NOT_DECLARED");
            recommendedActions.add("需要 checkpoint 的同步模式建议声明 retryPolicy，避免失败后无法解释重试边界");
        }
        if (!timeoutPolicyDeclared) {
            issueCodes.add("TIMEOUT_POLICY_NOT_DECLARED");
            recommendedActions.add("建议声明 timeoutPolicy，避免大表扫描、目标端写入阻塞或外部 API 慢响应长期占用 worker");
        }
        if (!partitionDeclared) {
            performanceNotes.add("未声明 partitionConfig：小表或低频任务可以接受，大表/高并发场景建议补充分片或批量策略");
        }
        if (!scopeContract.executableByMinimalBridge()) {
            safetyNotes.add("当前同步范围已完成控制面建模，但现有最小 run-once bridge 不会执行该范围，避免在 runner 未成熟时误读写真实数据");
        }
        if (scopeContract.requiresApproval()) {
            safetyNotes.add("当前模板涉及高影响范围或高风险写入，建议在执行前进入审批或人工确认流程");
        }
        safetyNotes.add("预览结果不代表真实执行已经通过；执行前仍需要权限、审批、连接测试、元数据兼容、worker 租约和 checkpoint 回调共同满足");
    }

    private void addIncrementalHints(boolean filterDeclared,
                                     boolean partitionDeclared,
                                     List<String> issueCodes,
                                     List<String> recommendedActions) {
        if (!filterDeclared && !partitionDeclared) {
            issueCodes.add("INCREMENTAL_BOUNDARY_NOT_DECLARED");
            recommendedActions.add("增量同步建议声明 filterConfig 或 partitionConfig，用于表达时间字段、ID 边界或分片窗口");
        }
    }

    private String resolvePreviewStatus(List<String> issueCodes) {
        if (issueCodes.stream().anyMatch(this::isBlockingIssue)) {
            return BLOCKED;
        }
        if (issueCodes.isEmpty()) {
            return READY;
        }
        return NEEDS_REVIEW;
    }

    /**
     * 预览层的阻断问题。
     *
     * <p>注意：SCOPE_NOT_EXECUTABLE_BY_MINIMAL_RUN_ONCE_BRIDGE 不在这里阻断任务草稿，因为高级范围可以先保存和审批；
     * 它会在执行预检查和 worker bridge 阶段阻断真实读写。</p>
     */
    private boolean isBlockingIssue(String issueCode) {
        return "SYNC_MODE_MISSING".equals(issueCode)
                || "SYNC_MODE_UNSUPPORTED".equals(issueCode)
                || "UNKNOWN_CONNECTOR_TYPE".equals(issueCode)
                || "UNKNOWN_SYNC_MODE".equals(issueCode)
                || "SOURCE_CONNECTOR_NOT_FOUND".equals(issueCode)
                || "TARGET_CONNECTOR_NOT_FOUND".equals(issueCode)
                || "CONNECTOR_COMPATIBILITY_UNSUPPORTED".equals(issueCode)
                || "SOURCE_MODE_UNSUPPORTED".equals(issueCode)
                || "TARGET_MODE_UNSUPPORTED".equals(issueCode)
                || "SOURCE_MODE_NOT_SUPPORTED".equals(issueCode)
                || "TARGET_MODE_NOT_SUPPORTED".equals(issueCode)
                || "SOURCE_STREAMING_REQUIRED".equals(issueCode)
                || "TARGET_STREAMING_REQUIRED".equals(issueCode)
                || "SOURCE_OBJECT_NOT_DECLARED".equals(issueCode)
                || "TARGET_OBJECT_NOT_DECLARED".equals(issueCode)
                || "WRITE_STRATEGY_UNSUPPORTED".equals(issueCode)
                || "PRIMARY_KEY_NOT_DECLARED_FOR_CONFLICT_WRITE".equals(issueCode)
                || "INCREMENTAL_FIELD_NOT_DECLARED".equals(issueCode)
                || "SYNC_SCOPE_TYPE_UNSUPPORTED".equals(issueCode)
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

    private List<String> distinct(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}

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
import com.czh.datasmart.govern.datasync.support.SyncWriteStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 同步模板规划预览支撑组件。
 *
 * <p>本组件不执行同步、不访问源端或目标端、不解析真实字段映射内容，只根据模板已经保存的低敏配置事实生成
 * “下一步是否建议进入任务草稿/执行前预检”的报告。它的定位介于 validateTemplate 和真实 worker 之间：</p>
 * <p>1. validateTemplate 负责 fail-fast 的基础校验；</p>
 * <p>2. preview 负责把缺失配置、连接器兼容性、checkpoint 建议和性能/安全提醒以列表形式返回；</p>
 * <p>3. worker 负责真实读取、写入、checkpoint 和错误样本采集。</p>
 *
 * <p>低敏原则：只返回“配置是否声明”和“问题码/建议”，不返回 fieldMappingConfig、filterConfig、partitionConfig、
 * retryPolicy、timeoutPolicy 的原文，因为这些 JSON 将来可能包含业务字段名、过滤条件、时间窗口、分片规则甚至敏感描述。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncTemplatePlanningPreviewSupport {

    private static final String PAYLOAD_POLICY = "LOW_SENSITIVE_TEMPLATE_PLANNING_PREVIEW";
    private static final String READY = "READY";
    private static final String NEEDS_REVIEW = "NEEDS_REVIEW";
    private static final String BLOCKED = "BLOCKED";

    private final SyncConnectorCapabilityRegistry connectorCapabilityRegistry;

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
        SyncMode syncMode = resolveMode(template, issueCodes, recommendedActions);
        SyncWriteStrategy writeStrategy = resolveWriteStrategy(template, issueCodes, recommendedActions);
        SyncConnectorCompatibilityView compatibility = resolveCompatibility(template, syncMode, issueCodes, recommendedActions);

        boolean sourceObjectDeclared = hasText(template.getSourceObjectName());
        boolean targetObjectDeclared = hasText(template.getTargetObjectName());
        boolean primaryKeyDeclared = hasText(template.getPrimaryKeyField());
        boolean incrementalFieldDeclared = hasText(template.getIncrementalField());
        boolean fieldMappingDeclared = hasText(template.getFieldMappingConfig());
        boolean filterDeclared = hasText(template.getFilterConfig());
        boolean partitionDeclared = hasText(template.getPartitionConfig());
        boolean retryPolicyDeclared = hasText(template.getRetryPolicy());
        boolean timeoutPolicyDeclared = hasText(template.getTimeoutPolicy());
        evaluateConfigurationHints(syncMode, writeStrategy, compatibility, sourceObjectDeclared, targetObjectDeclared,
                primaryKeyDeclared, incrementalFieldDeclared, fieldMappingDeclared, filterDeclared, partitionDeclared,
                retryPolicyDeclared, timeoutPolicyDeclared, issueCodes, recommendedActions, performanceNotes, safetyNotes);

        String previewStatus = resolvePreviewStatus(issueCodes);
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
                sourceObjectDeclared,
                targetObjectDeclared,
                writeStrategy == null ? null : writeStrategy.name(),
                writeStrategy != null && writeStrategy.requiresConflictKey(),
                primaryKeyDeclared,
                incrementalFieldDeclared,
                previewStatus,
                !BLOCKED.equals(previewStatus),
                READY.equals(previewStatus),
                compatibility != null && compatibility.supported(),
                compatibility != null && compatibility.checkpointRequired(),
                fieldMappingDeclared,
                filterDeclared,
                partitionDeclared,
                retryPolicyDeclared,
                timeoutPolicyDeclared,
                List.copyOf(issueCodes),
                List.copyOf(recommendedActions),
                List.copyOf(performanceNotes),
                List.copyOf(safetyNotes),
                PAYLOAD_POLICY
        );
    }

    /**
     * 解析同步模式。
     *
     * <p>预览接口不直接抛出校验异常，而是把问题转换成 issueCodes，便于前端一次性展示多个缺口。
     * 资源不存在、无权限这类边界仍由上层 getTemplate 负责抛异常。</p>
     */
    private SyncMode resolveMode(SyncTemplate template, List<String> issueCodes, List<String> recommendedActions) {
        String syncMode = normalize(template.getSyncMode());
        if (syncMode == null) {
            issueCodes.add("SYNC_MODE_MISSING");
            recommendedActions.add("先为同步模板选择 FULL、INCREMENTAL_TIME、CDC_STREAMING 等平台支持的同步模式");
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
     * 解析写入策略并把未知策略压缩为低敏 issueCode。
     *
     * <p>预览接口的职责是“一次性列出配置问题”，因此这里不直接抛异常。真正创建任务或执行前校验仍会在
     * {@link SyncTemplateValidationSupport} 中 fail-fast。这样前端和 Agent 可以在一个响应里同时看到对象定位、
     * 写入策略、checkpoint、重试和超时等多类缺口。</p>
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
     * 根据同步模式和已声明配置生成规划建议。
     *
     * <p>这些判断当前是轻量启发式规则，目的是在真实执行前暴露常见缺口。它不解析配置 JSON 的正文，
     * 所以不会泄露 SQL、字段名、业务过滤条件或分片窗口。后续可以在这里继续接入安全 JSON Schema 校验，
     * 但仍应只返回低敏摘要。</p>
     */
    private void evaluateConfigurationHints(SyncMode syncMode,
                                            SyncWriteStrategy writeStrategy,
                                            SyncConnectorCompatibilityView compatibility,
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
        if (!sourceObjectDeclared) {
            issueCodes.add("SOURCE_OBJECT_NOT_DECLARED");
            recommendedActions.add("声明 sourceObjectName，真实执行器必须知道从哪个表、视图、topic 或逻辑资源读取");
        }
        if (!targetObjectDeclared) {
            issueCodes.add("TARGET_OBJECT_NOT_DECLARED");
            recommendedActions.add("声明 targetObjectName，真实执行器必须知道写入哪个目标对象");
        }
        if (writeStrategy != null && writeStrategy.requiresConflictKey() && !primaryKeyDeclared) {
            issueCodes.add("PRIMARY_KEY_NOT_DECLARED_FOR_CONFLICT_WRITE");
            recommendedActions.add(writeStrategy.name() + " 写入策略需要 primaryKeyField，用于目标端冲突判断和幂等写入");
        }
        if (writeStrategy != null && writeStrategy.isDestructiveRewrite()) {
            issueCodes.add("DESTRUCTIVE_WRITE_STRATEGY_REQUIRES_REVIEW");
            recommendedActions.add("OVERWRITE 属于覆盖式高风险写入，建议接入 permission-admin 审批、影响范围预估和回滚预案后再运行");
            safetyNotes.add("覆盖式写入不应由普通 worker 静默执行，应在执行前完成人审、审计和备份策略确认");
        }
        if ((syncMode == SyncMode.INCREMENTAL_TIME || syncMode == SyncMode.INCREMENTAL_ID) && !incrementalFieldDeclared) {
            issueCodes.add("INCREMENTAL_FIELD_NOT_DECLARED");
            recommendedActions.add("增量同步必须声明 incrementalField，用于 checkpoint 推进、断点续行和失败恢复");
        }
        if (!fieldMappingDeclared) {
            issueCodes.add("FIELD_MAPPING_NOT_DECLARED");
            recommendedActions.add("如果源端和目标端 schema 不完全一致，请补充 fieldMappingConfig；预览不会返回字段映射原文");
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
        safetyNotes.add("预览结果不代表真实执行已通过；执行前仍需要权限、审批、连接测试、worker 租约和 checkpoint 回调共同满足");
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
                || "INCREMENTAL_FIELD_NOT_DECLARED".equals(issueCode);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}

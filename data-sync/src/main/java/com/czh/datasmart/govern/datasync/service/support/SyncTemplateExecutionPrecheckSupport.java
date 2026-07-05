/**
 * @Author : Cui
 * @Date: 2026/07/05 23:50
 * @Description DataSmart Govern Backend - SyncTemplateExecutionPrecheckSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncConnectorCompatibilityView;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTemplateExecutionPrecheckResponse;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.support.SyncMode;
import com.czh.datasmart.govern.datasync.support.SyncTransferChannel;
import com.czh.datasmart.govern.datasync.support.SyncWriteStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 同步模板执行前预检查支撑组件。
 *
 * <p>本组件是 data-sync 执行闭环的“最后一道控制面闸门”。它不会读取源端数据、不会写目标端、不会执行 SQL，
 * 但会把当前模板是否能被现有 runner 安全执行判断清楚。这样 {@code runTask} 就不会把明显不可执行的任务
 * 推入 QUEUED，导致 worker 再失败、告警再抖动、用户再排障。</p>
 *
 * <p>预检查拆成两类判断：</p>
 * <p>1. 产品契约判断：同步范围、连接器组合、写入策略、字段映射是否合理；</p>
 * <p>2. 工程实现判断：当前最小 run-once bridge 是否真的支持该范围、该模式、该 checkpoint handoff。</p>
 *
 * <p>这也是商业化项目里非常重要的诚实边界：规划上支持的能力，不等于当前 runner 已经能执行。
 * 如果没有这个边界，就很容易把“可配置”误说成“可生产执行”。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncTemplateExecutionPrecheckSupport {

    public static final String READY_TO_EXECUTE = "READY_TO_EXECUTE";
    public static final String REQUIRES_APPROVAL = "REQUIRES_APPROVAL";
    public static final String NOT_SUPPORTED_BY_CURRENT_RUNNER = "NOT_SUPPORTED_BY_CURRENT_RUNNER";
    public static final String BLOCKED = "BLOCKED";

    private static final String PAYLOAD_POLICY = "LOW_SENSITIVE_TEMPLATE_EXECUTION_PRECHECK";

    private final SyncConnectorCapabilityRegistry connectorCapabilityRegistry;
    private final SyncTemplateScopeContractSupport scopeContractSupport;
    private final SyncFieldMappingExecutionContractSupport fieldMappingExecutionContractSupport;

    /**
     * 执行模板预检查。
     *
     * @param template 已通过租户/项目可见性校验的模板。
     * @return 低敏预检查报告。
     */
    public SyncTemplateExecutionPrecheckResponse precheck(SyncTemplate template) {
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
        SyncFieldMappingExecutionContract fieldMappingContract = fieldMappingExecutionContractSupport.parse(
                template.getFieldMappingConfig(), template.getPrimaryKeyField());
        issueCodes.addAll(fieldMappingContract.getIssueCodes());
        safetyNotes.addAll(fieldMappingContract.getWarnings());

        boolean connectorFactsComplete = hasText(template.getSourceConnectorType())
                && hasText(template.getTargetConnectorType())
                && syncMode != null;
        boolean connectorCompatibilitySupported = compatibility != null && compatibility.supported();
        boolean checkpointRequired = compatibility != null && compatibility.checkpointRequired();
        boolean checkpointHandoffSupported = !checkpointRequired;
        boolean fieldMappingRunnable = fieldMappingContract.directlyRunnableByMinimalBridge();
        boolean customSqlSafetyPassed = !scopeContract.customSqlScope()
                || !scopeContract.hasIssue("CUSTOM_SQL_RAW_SQL_UNSAFE");

        evaluateWriteStrategy(template, writeStrategy, issueCodes, recommendedActions, safetyNotes);
        evaluateRunnerBoundary(syncMode, scopeContract, fieldMappingRunnable, checkpointRequired,
                issueCodes, recommendedActions, performanceNotes, safetyNotes);

        List<String> distinctIssues = distinct(issueCodes);
        boolean scopeContractValid = !scopeContract.hasBlockingIssues();
        boolean executableByCurrentRunner = scopeContract.executableByMinimalBridge()
                && modeExecutableByMinimalBridge(syncMode)
                && checkpointHandoffSupported
                && fieldMappingRunnable
                && connectorCompatibilitySupported
                && !hasHardBlockingIssue(distinctIssues);
        boolean approvalRequired = scopeContract.requiresApproval()
                || writeStrategy != null && writeStrategy.isDestructiveRewrite();
        String status = resolveStatus(distinctIssues, executableByCurrentRunner, approvalRequired);

        return new SyncTemplateExecutionPrecheckResponse(
                template.getId(),
                template.getTenantId(),
                template.getProjectId(),
                template.getWorkspaceId(),
                normalize(template.getSyncMode()),
                transferChannel == null ? null : transferChannel.name(),
                SyncTransferChannelSupport.referenceRuntime(transferChannel),
                scopeContract.scopeType(),
                status,
                canCreateTaskDraft(syncMode, scopeContract, writeStrategy),
                READY_TO_EXECUTE.equals(status),
                connectorFactsComplete,
                connectorCompatibilitySupported,
                scopeContractValid,
                hasText(template.getFieldMappingConfig()),
                fieldMappingRunnable,
                scopeContract.objectMappingDeclared(),
                scopeContract.customSqlDeclared(),
                customSqlSafetyPassed,
                approvalRequired,
                executableByCurrentRunner,
                checkpointRequired,
                checkpointHandoffSupported,
                distinctIssues,
                distinct(recommendedActions),
                distinct(performanceNotes),
                distinct(safetyNotes),
                PAYLOAD_POLICY
        );
    }

    /**
     * 解析同步模式，错误转为 issueCode。
     */
    private SyncMode resolveMode(SyncTemplate template, List<String> issueCodes, List<String> recommendedActions) {
        String syncMode = normalize(template.getSyncMode());
        if (syncMode == null) {
            issueCodes.add("SYNC_MODE_MISSING");
            recommendedActions.add("执行前必须声明同步模式，例如 FULL、ONE_TIME_MIGRATION、INCREMENTAL_TIME 或 CUSTOM_SQL_QUERY");
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
     * 解析写入策略，错误转为 issueCode。
     */
    private SyncWriteStrategy resolveWriteStrategy(SyncTemplate template,
                                                   List<String> issueCodes,
                                                   List<String> recommendedActions) {
        try {
            return SyncWriteStrategy.fromValue(template.getWriteStrategy());
        } catch (IllegalArgumentException exception) {
            issueCodes.add("WRITE_STRATEGY_UNSUPPORTED");
            recommendedActions.add("将 writeStrategy 调整为 APPEND、UPSERT、INSERT_IGNORE、REPLACE 或 OVERWRITE");
            return null;
        }
    }

    /**
     * 执行连接器能力矩阵检查。
     */
    private SyncConnectorCompatibilityView resolveCompatibility(SyncTemplate template,
                                                               SyncMode syncMode,
                                                               List<String> issueCodes,
                                                               List<String> recommendedActions) {
        if (!hasText(template.getSourceConnectorType())
                || !hasText(template.getTargetConnectorType())
                || syncMode == null) {
            issueCodes.add("CONNECTOR_FACTS_INCOMPLETE");
            recommendedActions.add("执行前必须先补全源端/目标端 connector type；推荐通过 datasource-management 能力快照自动补齐");
            return null;
        }
        SyncConnectorCompatibilityView compatibility = connectorCapabilityRegistry.checkCompatibility(
                template.getSourceConnectorType(), template.getTargetConnectorType(), syncMode.name());
        if (!compatibility.supported()) {
            issueCodes.add("CONNECTOR_COMPATIBILITY_UNSUPPORTED");
        }
        issueCodes.addAll(compatibility.issueCodes());
        recommendedActions.addAll(compatibility.recommendedActions());
        return compatibility;
    }

    /**
     * 根据写入策略补充执行前硬性要求。
     */
    private void evaluateWriteStrategy(SyncTemplate template,
                                       SyncWriteStrategy writeStrategy,
                                       List<String> issueCodes,
                                       List<String> recommendedActions,
                                       List<String> safetyNotes) {
        if (writeStrategy == null) {
            return;
        }
        if (writeStrategy.requiresConflictKey() && !hasText(template.getPrimaryKeyField())) {
            issueCodes.add("PRIMARY_KEY_NOT_DECLARED_FOR_CONFLICT_WRITE");
            recommendedActions.add(writeStrategy.name() + " 写入策略执行前必须声明 primaryKeyField，避免目标端重复或无法幂等");
        }
        if (writeStrategy.isDestructiveRewrite()) {
            issueCodes.add("DESTRUCTIVE_WRITE_STRATEGY_REQUIRES_APPROVAL");
            recommendedActions.add("OVERWRITE 执行前必须完成审批、影响范围评估和回滚预案确认");
            safetyNotes.add("覆盖式写入属于高风险动作，当前预检查不会允许它静默进入普通 run-once bridge");
        }
    }

    /**
     * 补充当前工程实现边界。
     */
    private void evaluateRunnerBoundary(SyncMode syncMode,
                                        SyncTemplateScopeContract scopeContract,
                                        boolean fieldMappingRunnable,
                                        boolean checkpointRequired,
                                        List<String> issueCodes,
                                        List<String> recommendedActions,
                                        List<String> performanceNotes,
                                        List<String> safetyNotes) {
        if (!scopeContract.executableByMinimalBridge()) {
            issueCodes.add("SCOPE_NOT_EXECUTABLE_BY_MINIMAL_RUN_ONCE_BRIDGE");
            recommendedActions.add("当前范围可作为任务草稿和审批对象保存，但执行前需要专用多对象/全库/自定义 SQL runner");
        }
        if (!modeExecutableByMinimalBridge(syncMode)) {
            issueCodes.add("MODE_NOT_EXECUTABLE_BY_MINIMAL_RUN_ONCE_BRIDGE");
            recommendedActions.add("当前最小 run-once bridge 只安全支持 FULL 和 ONE_TIME_MIGRATION 的单对象小批量场景");
        }
        if (checkpointRequired) {
            issueCodes.add("CHECKPOINT_HANDOFF_NOT_IMPLEMENTED");
            recommendedActions.add("当前执行桥尚未完成 checkpoint 原始值安全交接，增量/回放/补数/CDC 场景不能直接入队执行");
        }
        if (!fieldMappingRunnable) {
            issueCodes.add("FIELD_MAPPING_CONTRACT_NOT_RUNNABLE_BY_MINIMAL_BRIDGE");
            recommendedActions.add("字段映射必须声明为最小 bridge 可执行的同名字段映射；字段改名、表达式、类型转换需要后续 transform runner");
        }
        performanceNotes.add("当前预检查不做真实行数估算、索引扫描、DDL 兼容比对或 explain；这些能力应在 metadata-aware runner 接入后补充");
        safetyNotes.add("预检查通过只表示控制面允许入队，不代表源端连接、目标端写入、网络、锁等待或下游容量一定成功");
    }

    /**
     * 当前最小 bridge 的模式边界。
     *
     * <p>FULL/ONE_TIME_MIGRATION 仍然只适合单批小表或演示级闭环；如果源端行数超过 fetchSize，底层 runner 会返回
     * endOfSource=false。由于 full 模式尚无稳定 offset/checkpoint 翻页语义，data-sync 不会伪造多批循环。</p>
     */
    private boolean modeExecutableByMinimalBridge(SyncMode syncMode) {
        return syncMode == SyncMode.FULL || syncMode == SyncMode.ONE_TIME_MIGRATION;
    }

    private String resolveStatus(List<String> issueCodes,
                                 boolean executableByCurrentRunner,
                                 boolean approvalRequired) {
        if (hasHardBlockingIssue(issueCodes)) {
            return BLOCKED;
        }
        if (!executableByCurrentRunner) {
            return NOT_SUPPORTED_BY_CURRENT_RUNNER;
        }
        if (approvalRequired) {
            return REQUIRES_APPROVAL;
        }
        return READY_TO_EXECUTE;
    }

    private boolean canCreateTaskDraft(SyncMode syncMode,
                                       SyncTemplateScopeContract scopeContract,
                                       SyncWriteStrategy writeStrategy) {
        return syncMode != null
                && writeStrategy != null
                && !scopeContract.hasBlockingIssues();
    }

    /**
     * 预检查硬阻断问题。
     *
     * <p>这里故意不把“当前 runner 不支持”纳入硬阻断，因为它对应 NOT_SUPPORTED_BY_CURRENT_RUNNER；
     * 这样用户能区分“配置错了”与“配置没错但当前执行器还未覆盖”。</p>
     */
    private boolean hasHardBlockingIssue(List<String> issueCodes) {
        return issueCodes.stream().anyMatch(issueCode ->
                "SYNC_MODE_MISSING".equals(issueCode)
                        || "SYNC_MODE_UNSUPPORTED".equals(issueCode)
                        || "WRITE_STRATEGY_UNSUPPORTED".equals(issueCode)
                        || "CONNECTOR_FACTS_INCOMPLETE".equals(issueCode)
                        || "CONNECTOR_COMPATIBILITY_UNSUPPORTED".equals(issueCode)
                        || "SOURCE_MODE_UNSUPPORTED".equals(issueCode)
                        || "TARGET_MODE_UNSUPPORTED".equals(issueCode)
                        || "SOURCE_MODE_NOT_SUPPORTED".equals(issueCode)
                        || "TARGET_MODE_NOT_SUPPORTED".equals(issueCode)
                        || "SOURCE_STREAMING_REQUIRED".equals(issueCode)
                        || "TARGET_STREAMING_REQUIRED".equals(issueCode)
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
                        || "CUSTOM_SQL_FIELD_MAPPING_REQUIRED".equals(issueCode)
                        || "PRIMARY_KEY_NOT_DECLARED_FOR_CONFLICT_WRITE".equals(issueCode));
    }

    private List<String> distinct(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private String normalize(String value) {
        return hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

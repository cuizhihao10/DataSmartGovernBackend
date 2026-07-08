/**
 * @Author : Cui
 * @Date: 2026/06/29 03:18
 * @Description DataSmart Govern Backend - SyncWorkerExecutionPlanSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.controller.dto.SyncConnectorCompatibilityView;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerExecutionPlanView;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.mapper.SyncTemplateMapper;
import com.czh.datasmart.govern.datasync.support.SyncExecutionState;
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
 * 同步 worker 执行计划生成器。
 *
 * <p>本组件把已经认领的 execution、任务和模板转换成 worker 可消费的低敏执行计划。
 * 它不生成 SQL、不返回字段映射原文、不连接数据源，只表达“当前执行是否允许继续”以及“缺哪些执行前事实”。</p>
 *
 * <p>为什么 worker plan 要知道 syncScopeType：过去 data-sync 主要按单表同步理解模板，如果不把范围语义下发到
 * worker 层，多表、整库、自定义 SQL 模板就可能在 claim 后继续被当作单表 runner 处理。现在 worker plan
 * 会显式携带范围类型，并在当前最小 bridge 不支持时 fail-closed。</p>
 */
@Component
public class SyncWorkerExecutionPlanSupport {

    private static final String PAYLOAD_POLICY = "LOW_SENSITIVE_WORKER_PLAN_METADATA_ONLY";

    private final SyncTemplateMapper templateMapper;
    private final SyncConnectorCapabilityRegistry connectorCapabilityRegistry;
    private final SyncTemplateScopeContractSupport scopeContractSupport;

    /**
     * 兼容旧测试的构造器。
     */
    public SyncWorkerExecutionPlanSupport(SyncTemplateMapper templateMapper,
                                          SyncConnectorCapabilityRegistry connectorCapabilityRegistry) {
        this(templateMapper, connectorCapabilityRegistry, new SyncTemplateScopeContractSupport());
    }

    /**
     * Spring 注入构造器。
     */
    @Autowired
    public SyncWorkerExecutionPlanSupport(SyncTemplateMapper templateMapper,
                                          SyncConnectorCapabilityRegistry connectorCapabilityRegistry,
                                          SyncTemplateScopeContractSupport scopeContractSupport) {
        this.templateMapper = templateMapper;
        this.connectorCapabilityRegistry = connectorCapabilityRegistry;
        this.scopeContractSupport = scopeContractSupport;
    }

    /**
     * 根据已经认领的 execution 与任务生成 worker 计划。
     *
     * @param execution 已经被 claim 的执行记录，通常应处于 RUNNING。
     * @param task execution 所属同步任务。
     * @return 低敏 worker 执行计划。
     */
    public SyncWorkerExecutionPlanView buildPlan(SyncExecution execution, SyncTask task) {
        if (execution == null || task == null) {
            return unavailablePlan(execution, task, "TASK_OR_EXECUTION_MISSING", "缺少任务或执行记录，worker 不应继续执行。");
        }

        SyncTemplate template = templateMapper.selectById(task.getTemplateId());
        if (template == null) {
            return unavailablePlan(execution, task, "TEMPLATE_NOT_FOUND", "模板不存在，worker 应调用 fail 回调并等待运营处理。");
        }

        SyncTemplateScopeContract scopeContract = scopeContractSupport.evaluate(template);
        PlanFacts facts = collectFacts(execution, template, scopeContract);
        SyncWriteStrategy writeStrategy = resolveWriteStrategy(template, facts.issueCodes());
        SyncConnectorCompatibilityView compatibility = resolveCompatibility(template, facts.issueCodes());
        SyncTransferChannel transferChannel = SyncTransferChannelSupport.resolve(template.getSyncMode());
        boolean connectorSupported = compatibility != null && compatibility.supported();
        if (compatibility != null) {
            facts.issueCodes().addAll(compatibility.issueCodes());
        }
        appendTemplateIssues(template, writeStrategy, compatibility, scopeContract, facts.issueCodes());

        List<String> distinctIssues = distinct(facts.issueCodes());
        String planStatus = resolvePlanStatus(distinctIssues, connectorSupported);
        facts.workerActions().addAll(workerActions(planStatus, compatibility));

        return new SyncWorkerExecutionPlanView(
                true,
                planStatus,
                execution.getTenantId(),
                execution.getProjectId(),
                execution.getWorkspaceId(),
                task.getId(),
                execution.getId(),
                execution.getExecutionNo(),
                execution.getExecutionState(),
                execution.getTriggerType(),
                execution.getExecutorId(),
                execution.getLeaseExpireTime(),
                template.getId(),
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
                scopeContract.requiresApproval(),
                scopeContract.executableByMinimalBridge(),
                hasText(template.getSourceObjectName()),
                hasText(template.getTargetObjectName()),
                writeStrategy == null ? null : writeStrategy.name(),
                writeStrategy != null && writeStrategy.requiresConflictKey(),
                hasText(template.getPrimaryKeyField()),
                hasText(template.getIncrementalField()),
                connectorSupported,
                compatibility == null ? null : compatibility.consistencyGoal(),
                compatibility != null && compatibility.checkpointRequired(),
                compatibility == null ? null : compatibility.retryPattern(),
                hasText(template.getFieldMappingConfig()),
                hasText(template.getObjectMappingConfig()),
                hasText(template.getCustomSqlConfig()),
                hasText(template.getFilterConfig()),
                hasText(template.getPartitionConfig()),
                hasText(template.getRetryPolicy()),
                hasText(template.getTimeoutPolicy()),
                distinctIssues,
                List.copyOf(facts.workerActions()),
                compatibility == null ? List.of() : compatibility.performanceNotes(),
                mergeSafetyNotes(compatibility, scopeContract),
                PAYLOAD_POLICY
        );
    }

    /**
     * 收集不需要访问真实数据源即可判断的基础事实。
     */
    private PlanFacts collectFacts(SyncExecution execution,
                                   SyncTemplate template,
                                   SyncTemplateScopeContract scopeContract) {
        List<String> issueCodes = new ArrayList<>();
        List<String> workerActions = new ArrayList<>();
        if (!SyncExecutionState.RUNNING.name().equals(execution.getExecutionState())) {
            issueCodes.add("EXECUTION_NOT_RUNNING");
        }
        if (Boolean.FALSE.equals(template.getEnabled())) {
            issueCodes.add("TEMPLATE_DISABLED");
        }
        if (!hasText(template.getSourceConnectorType()) || !hasText(template.getTargetConnectorType())) {
            issueCodes.add("CONNECTOR_FACTS_MISSING");
        }
        if (!hasText(template.getSyncMode())) {
            issueCodes.add("SYNC_MODE_MISSING");
        }
        issueCodes.addAll(scopeContract.issueCodes());
        if (scopeContract.singleObjectScope() && !hasText(template.getSourceObjectName())) {
            issueCodes.add("SOURCE_OBJECT_NOT_DECLARED");
        }
        if (scopeContract.singleObjectScope() && !hasText(template.getTargetObjectName())) {
            issueCodes.add("TARGET_OBJECT_NOT_DECLARED");
        }
        if (!hasText(template.getFieldMappingConfig())) {
            issueCodes.add("FIELD_MAPPING_NOT_DECLARED");
        }
        if (!hasText(template.getRetryPolicy())) {
            issueCodes.add("RETRY_POLICY_NOT_DECLARED");
        }
        if (!hasText(template.getTimeoutPolicy())) {
            issueCodes.add("TIMEOUT_POLICY_NOT_DECLARED");
        }
        return new PlanFacts(issueCodes, workerActions);
    }

    /**
     * 解析 worker 执行时必须理解的写入策略。
     */
    private SyncWriteStrategy resolveWriteStrategy(SyncTemplate template, List<String> issueCodes) {
        try {
            SyncWriteStrategy writeStrategy = SyncWriteStrategy.fromValueForMode(
                    template.getWriteStrategy(), template.getSyncMode());
            if (!hasText(template.getWriteStrategy())) {
                issueCodes.add(writeStrategy == SyncWriteStrategy.UPDATE
                        ? "WRITE_STRATEGY_DEFAULTED_TO_UPDATE_FOR_REALTIME"
                        : "WRITE_STRATEGY_DEFAULTED_TO_INSERT");
            }
            return writeStrategy;
        } catch (IllegalArgumentException exception) {
            issueCodes.add("WRITE_STRATEGY_UNSUPPORTED");
            return null;
        }
    }

    /**
     * 解析连接器兼容性。
     */
    private SyncConnectorCompatibilityView resolveCompatibility(SyncTemplate template, List<String> issueCodes) {
        if (!hasText(template.getSourceConnectorType())
                || !hasText(template.getTargetConnectorType())
                || !hasText(template.getSyncMode())) {
            return null;
        }
        try {
            return connectorCapabilityRegistry.checkCompatibility(
                    template.getSourceConnectorType(),
                    template.getTargetConnectorType(),
                    template.getSyncMode());
        } catch (PlatformBusinessException exception) {
            issueCodes.add("CONNECTOR_COMPATIBILITY_UNKNOWN");
            return null;
        }
    }

    /**
     * 根据连接器能力、范围契约和模板配置补充执行前问题码。
     */
    private void appendTemplateIssues(SyncTemplate template,
                                      SyncWriteStrategy writeStrategy,
                                      SyncConnectorCompatibilityView compatibility,
                                      SyncTemplateScopeContract scopeContract,
                                      List<String> issueCodes) {
        if (compatibility != null && !compatibility.supported()) {
            issueCodes.add("CONNECTOR_COMPATIBILITY_UNSUPPORTED");
        }
        if (writeStrategy != null && writeStrategy.requiresConflictKey() && !hasText(template.getPrimaryKeyField())) {
            issueCodes.add("PRIMARY_KEY_NOT_DECLARED_FOR_CONFLICT_WRITE");
        }
        SyncMode syncMode = resolveModeOrNull(template.getSyncMode());
        if ((syncMode == SyncMode.INCREMENTAL_TIME || syncMode == SyncMode.INCREMENTAL_ID)
                && !hasText(template.getIncrementalField())) {
            issueCodes.add("INCREMENTAL_FIELD_NOT_DECLARED");
        }
        if (writeStrategy != null && writeStrategy.isDestructiveRewrite()) {
            issueCodes.add("DESTRUCTIVE_WRITE_STRATEGY_REQUIRES_REVIEW");
        }
        if (!scopeContract.executableByMinimalBridge()) {
            issueCodes.add("SCOPE_NOT_EXECUTABLE_BY_MINIMAL_RUN_ONCE_BRIDGE");
        }
        if (compatibility != null && compatibility.checkpointRequired() && !hasText(template.getFilterConfig())) {
            issueCodes.add("CHECKPOINT_BOUNDARY_NOT_DECLARED");
        }
        if (isFullLikeMode(template.getSyncMode()) && !hasText(template.getPartitionConfig())) {
            issueCodes.add("PARTITION_PLAN_NOT_DECLARED");
        }
    }

    /**
     * 将 issueCode 折叠成 worker 可理解的计划状态。
     */
    private String resolvePlanStatus(List<String> issueCodes, boolean connectorSupported) {
        if (!connectorSupported || issueCodes.stream().anyMatch(this::isPlanBlockingIssue)) {
            return "BLOCKED";
        }
        return issueCodes.isEmpty() ? "READY_TO_RUN" : "READY_WITH_WARNINGS";
    }

    private boolean isPlanBlockingIssue(String issueCode) {
        return "TASK_OR_EXECUTION_MISSING".equals(issueCode)
                || "TEMPLATE_NOT_FOUND".equals(issueCode)
                || "TEMPLATE_DISABLED".equals(issueCode)
                || "EXECUTION_NOT_RUNNING".equals(issueCode)
                || "CONNECTOR_FACTS_MISSING".equals(issueCode)
                || "SYNC_MODE_MISSING".equals(issueCode)
                || "CONNECTOR_COMPATIBILITY_UNKNOWN".equals(issueCode)
                || "CONNECTOR_COMPATIBILITY_UNSUPPORTED".equals(issueCode)
                || "SOURCE_OBJECT_NOT_DECLARED".equals(issueCode)
                || "TARGET_OBJECT_NOT_DECLARED".equals(issueCode)
                || "WRITE_STRATEGY_UNSUPPORTED".equals(issueCode)
                || "PRIMARY_KEY_NOT_DECLARED_FOR_CONFLICT_WRITE".equals(issueCode)
                || "INCREMENTAL_FIELD_NOT_DECLARED".equals(issueCode)
                || "FIELD_MAPPING_NOT_DECLARED".equals(issueCode)
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
                || "SCOPE_NOT_EXECUTABLE_BY_MINIMAL_RUN_ONCE_BRIDGE".equals(issueCode);
    }

    /**
     * 给 worker 返回下一步动作建议。
     */
    private List<String> workerActions(String planStatus, SyncConnectorCompatibilityView compatibility) {
        if ("BLOCKED".equals(planStatus)) {
            return List.of(
                    "DO_NOT_READ_OR_WRITE_DATA",
                    "CALL_FAIL_EXECUTION_WITH_LOW_SENSITIVE_REASON",
                    "WAIT_FOR_OPERATOR_OR_TEMPLATE_FIX"
            );
        }
        List<String> actions = new ArrayList<>();
        actions.add("CLAIM_ALREADY_MARKED_RUNNING_DO_NOT_CALL_START");
        actions.add("SEND_HEARTBEAT_BEFORE_LEASE_EXPIRES");
        if (compatibility != null && compatibility.checkpointRequired()) {
            actions.add("WRITE_CHECKPOINT_AFTER_EACH_SAFE_BATCH");
        }
        actions.add("CALL_COMPLETE_ON_SUCCESS_OR_FAIL_ON_NON_RETRYABLE_ERROR");
        return List.copyOf(actions);
    }

    private SyncWorkerExecutionPlanView unavailablePlan(SyncExecution execution,
                                                       SyncTask task,
                                                       String issueCode,
                                                       String workerAction) {
        return new SyncWorkerExecutionPlanView(
                false,
                "BLOCKED",
                execution == null ? null : execution.getTenantId(),
                execution == null ? null : execution.getProjectId(),
                execution == null ? null : execution.getWorkspaceId(),
                task == null ? null : task.getId(),
                execution == null ? null : execution.getId(),
                execution == null ? null : execution.getExecutionNo(),
                execution == null ? null : execution.getExecutionState(),
                execution == null ? null : execution.getTriggerType(),
                execution == null ? null : execution.getExecutorId(),
                execution == null ? null : execution.getLeaseExpireTime(),
                task == null ? null : task.getTemplateId(),
                null,
                null,
                null,
                null,
                null,
                null,
                SyncTransferChannelSupport.referenceRuntime(null),
                null,
                false,
                false,
                false,
                0,
                false,
                false,
                false,
                false,
                null,
                false,
                false,
                false,
                false,
                null,
                false,
                null,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                List.of(issueCode),
                List.of("DO_NOT_READ_OR_WRITE_DATA", workerAction),
                List.of(),
                List.of("执行计划不可用时不允许 worker 猜测连接、SQL、字段映射或 checkpoint。"),
                PAYLOAD_POLICY
        );
    }

    private List<String> mergeSafetyNotes(SyncConnectorCompatibilityView compatibility,
                                          SyncTemplateScopeContract scopeContract) {
        List<String> notes = new ArrayList<>();
        if (compatibility != null) {
            notes.addAll(compatibility.safetyNotes());
        }
        notes.addAll(scopeContract.warnings());
        if (!scopeContract.executableByMinimalBridge()) {
            notes.add("当前范围不允许由最小 run-once bridge 执行，worker 必须 fail-closed。");
        }
        return distinct(notes);
    }

    private boolean isFullLikeMode(String syncMode) {
        String mode = normalize(syncMode);
        return "FULL".equals(mode) || "SCHEDULED_FULL".equals(mode) || "ONE_TIME_MIGRATION".equals(mode);
    }

    private SyncMode resolveModeOrNull(String syncMode) {
        String mode = normalize(syncMode);
        if (mode == null) {
            return null;
        }
        try {
            return SyncMode.valueOf(mode);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private List<String> distinct(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalize(String value) {
        return hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private record PlanFacts(List<String> issueCodes, List<String> workerActions) {
    }
}

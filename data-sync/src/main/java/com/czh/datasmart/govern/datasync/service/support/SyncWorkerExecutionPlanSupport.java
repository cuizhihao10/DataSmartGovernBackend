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
import com.czh.datasmart.govern.datasync.support.SyncWriteStrategy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 同步 worker 执行计划生成器。
 *
 * <p>本组件位于 data-sync 控制面和真实执行器之间，职责是把“数据库里的模板/任务/execution 状态”
 * 转换成“worker 可以理解的低敏执行计划”。它不是 JDBC 连接器，也不是 SQL 生成器，因此不会读取或返回
 * 字段映射正文、过滤条件正文、连接串、账号、密钥、样本数据、SQL、文件路径或内部 endpoint。</p>
 *
 * <p>为什么要独立出来，而不是直接放进 {@code DataSyncExecutorLeaseServiceImpl}：</p>
 * <p>1. 租约服务应该专注并发认领、heartbeat、defer 和过期恢复；</p>
 * <p>2. 执行计划会持续演进，例如接入 worker 发布状态、连接池容量、schema 版本、JSON Schema 配置校验；</p>
 * <p>3. 分离后可以让 claim 状态机保持稳定，同时让执行计划按商业化需求逐步增强。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncWorkerExecutionPlanSupport {

    private static final String PAYLOAD_POLICY = "LOW_SENSITIVE_WORKER_PLAN_METADATA_ONLY";

    private final SyncTemplateMapper templateMapper;
    private final SyncConnectorCapabilityRegistry connectorCapabilityRegistry;

    /**
     * 根据已经认领的 execution 与任务生成 worker 计划。
     *
     * <p>该方法通常在 claim 成功后调用。此时 execution 已经从 QUEUED 原子流转为 RUNNING，
     * 因此返回给 worker 的动作建议不会再要求调用 startExecution，而是直接进入读取、写入、heartbeat、
     * checkpoint、complete/fail 回调。这样可以避免 worker 重复调用 start 导致状态冲突。</p>
     *
     * @param execution 已经认领的执行记录。
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

        PlanFacts facts = collectFacts(execution, template);
        SyncWriteStrategy writeStrategy = resolveWriteStrategy(template, facts.issueCodes());
        SyncConnectorCompatibilityView compatibility = resolveCompatibility(template, facts.issueCodes());
        boolean connectorSupported = compatibility != null && compatibility.supported();
        if (compatibility != null) {
            facts.issueCodes().addAll(compatibility.issueCodes());
        }
        appendTemplateIssues(template, writeStrategy, compatibility, facts.issueCodes());

        String planStatus = resolvePlanStatus(facts.issueCodes(), connectorSupported);
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
                hasText(template.getFilterConfig()),
                hasText(template.getPartitionConfig()),
                hasText(template.getRetryPolicy()),
                hasText(template.getTimeoutPolicy()),
                List.copyOf(facts.issueCodes()),
                List.copyOf(facts.workerActions()),
                compatibility == null ? List.of() : compatibility.performanceNotes(),
                compatibility == null ? List.of() : compatibility.safetyNotes(),
                PAYLOAD_POLICY
        );
    }

    /**
     * 收集不需要读取连接器能力矩阵即可判断的基础事实。
     *
     * <p>这里特意只判断配置块是否存在，而不解析配置正文。原因是配置正文可能包含字段名、过滤表达式、
     * 分区键、业务日期边界或外部系统提示，直接暴露给 worker claim 响应会扩大敏感信息传播面。
     * 后续真实 connector runtime 可以在更严格的权限边界内读取并解析这些配置。</p>
     */
    private PlanFacts collectFacts(SyncExecution execution, SyncTemplate template) {
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
        if (!hasText(template.getSourceObjectName())) {
            issueCodes.add("SOURCE_OBJECT_NOT_DECLARED");
        }
        if (!hasText(template.getTargetObjectName())) {
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
     *
     * <p>空策略会兼容为 APPEND，但会留下 WRITE_STRATEGY_DEFAULTED_TO_APPEND 警告，提醒 worker 和运营台：
     * 默认追加写入虽然能跑通最小闭环，但在重试、回放和补数场景下更容易产生重复记录。未知策略则直接变成阻断问题，
     * 因为 worker 不能自行猜测写入语义。</p>
     */
    private SyncWriteStrategy resolveWriteStrategy(SyncTemplate template, List<String> issueCodes) {
        try {
            SyncWriteStrategy writeStrategy = SyncWriteStrategy.fromValue(template.getWriteStrategy());
            if (!hasText(template.getWriteStrategy())) {
                issueCodes.add("WRITE_STRATEGY_DEFAULTED_TO_APPEND");
            }
            return writeStrategy;
        } catch (IllegalArgumentException exception) {
            issueCodes.add("WRITE_STRATEGY_UNSUPPORTED");
            return null;
        }
    }

    /**
     * 解析连接器兼容性。
     *
     * <p>能力矩阵只接收连接器类型和同步模式，不接收 datasourceId 对应的真实连接配置。
     * 如果连接器类型或同步模式缺失，本方法不会抛出异常，而是把问题压缩成低敏 issueCode，
     * 由 planStatus 统一决定 worker 是否继续。</p>
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
     * 根据连接器能力与模板配置补充执行前问题码。
     *
     * <p>checkpoint 与增量边界是执行闭环的重点：如果能力矩阵认为该模式需要 checkpoint，
     * 但模板没有声明过滤/边界配置，worker 即使能读取数据，也很难保证断点续行和重试边界。
     * 因此这里给出显式 issueCode，帮助后续 runner 决定是否 fail-closed。</p>
     */
    private void appendTemplateIssues(SyncTemplate template,
                                      SyncWriteStrategy writeStrategy,
                                      SyncConnectorCompatibilityView compatibility,
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
        if (compatibility != null && compatibility.checkpointRequired() && !hasText(template.getFilterConfig())) {
            issueCodes.add("CHECKPOINT_BOUNDARY_NOT_DECLARED");
        }
        if (isFullLikeMode(template.getSyncMode()) && !hasText(template.getPartitionConfig())) {
            issueCodes.add("PARTITION_PLAN_NOT_DECLARED");
        }
    }

    /**
     * 将 issueCode 折叠成 worker 可理解的计划状态。
     *
     * <p>BLOCKED 表示 worker 不应读取或写入真实数据；READY_WITH_WARNINGS 表示可以进入执行，
     * 但应使用更保守的默认批大小、较短 heartbeat 和更严格的失败回调；READY_TO_RUN 表示控制面预检较完整。
     * 这里不直接返回“是否成功”，是为了给商用运营台留下更细的解释空间。</p>
     */
    private String resolvePlanStatus(List<String> issueCodes, boolean connectorSupported) {
        if (!connectorSupported
                || issueCodes.contains("TASK_OR_EXECUTION_MISSING")
                || issueCodes.contains("TEMPLATE_NOT_FOUND")
                || issueCodes.contains("TEMPLATE_DISABLED")
                || issueCodes.contains("EXECUTION_NOT_RUNNING")
                || issueCodes.contains("CONNECTOR_FACTS_MISSING")
                || issueCodes.contains("SYNC_MODE_MISSING")
                || issueCodes.contains("CONNECTOR_COMPATIBILITY_UNKNOWN")
                || issueCodes.contains("CONNECTOR_COMPATIBILITY_UNSUPPORTED")
                || issueCodes.contains("SOURCE_OBJECT_NOT_DECLARED")
                || issueCodes.contains("TARGET_OBJECT_NOT_DECLARED")
                || issueCodes.contains("WRITE_STRATEGY_UNSUPPORTED")
                || issueCodes.contains("PRIMARY_KEY_NOT_DECLARED_FOR_CONFLICT_WRITE")
                || issueCodes.contains("INCREMENTAL_FIELD_NOT_DECLARED")) {
            return "BLOCKED";
        }
        return issueCodes.isEmpty() ? "READY_TO_RUN" : "READY_WITH_WARNINGS";
    }

    /**
     * 给 worker 返回下一步动作建议。
     *
     * <p>这些动作只是协议提示，不是内部 URL，也不是强制让 worker 拼接接口路径。
     * 后续如果进入 SDK 化 worker，可以把这些动作映射为 SDK 方法，避免 worker 硬编码 REST 路由。</p>
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
                List.of(issueCode),
                List.of("DO_NOT_READ_OR_WRITE_DATA", workerAction),
                List.of(),
                List.of("执行计划不可用时不允许 worker 猜测连接、SQL、字段映射或 checkpoint。"),
                PAYLOAD_POLICY
        );
    }

    private boolean isFullLikeMode(String syncMode) {
        String mode = normalize(syncMode);
        return "FULL".equals(mode) || "ONE_TIME_MIGRATION".equals(mode);
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalize(String value) {
        return hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private record PlanFacts(List<String> issueCodes, List<String> workerActions) {
    }
}

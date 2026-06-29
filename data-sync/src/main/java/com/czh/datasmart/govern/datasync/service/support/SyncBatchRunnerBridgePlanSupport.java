/**
 * @Author : Cui
 * @Date: 2026/06/29 23:45
 * @Description DataSmart Govern Backend - SyncBatchRunnerBridgePlanSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerExecutionPlanView;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.support.SyncMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 最小批量执行器桥接计划生成器。
 *
 * <p>本组件是 data-sync 从“控制面可规划”走向“真实执行闭环”的收敛点。它不会直接执行 JDBC，
 * 也不会读取 datasource-management 的凭据或连接串，而是把现有模板、任务、execution、workerPlan 和字段映射解析结果
 * 合成为一个内部桥接计划，明确告诉后续 connector runtime：当前是否可以派发、应该按什么读取策略和写入策略执行、
 * 以及为什么被阻断。</p>
 *
 * <p>这一步的产品价值在于：</p>
 * <p>1. 避免 data-sync 继续只停留在预览和 claim 层，而没有真实执行入口；</p>
 * <p>2. 避免为了快速闭环把 datasource-management 内部 runner 直接拉进 data-sync，造成双控制面和跨模块强耦合；</p>
 * <p>3. 为后续 HTTP/gRPC/SDK batch runner 留出稳定契约，让执行器只负责受控读写，data-sync 仍负责状态机、checkpoint 和回调。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncBatchRunnerBridgePlanSupport {

    /**
     * 当前最小 bridge 仅承诺关系型 JDBC 批处理。
     *
     * <p>Kafka、文件、对象存储、REST API、MongoDB 等连接器仍在产品规划内，但它们的读取边界、checkpoint、错误样本和吞吐模型
     * 与 JDBC 表批处理不同。这里先 fail-closed，避免表同步 runner 被滥用到不匹配的连接器场景。</p>
     */
    private static final Set<String> MINIMAL_JDBC_CONNECTORS = Set.of("MYSQL", "POSTGRESQL", "SQL_SERVER");

    private final SyncFieldMappingExecutionContractSupport fieldMappingExecutionContractSupport;

    /**
     * 构建内部批量执行器桥接计划。
     *
     * @param execution 当前已认领的执行记录。正常情况下已由 lease 服务置为 RUNNING。
     * @param task execution 所属同步任务。
     * @param template 同步模板，提供对象定位、写入策略和字段映射配置。
     * @param workerPlan claim 后生成的低敏 worker 执行计划。
     * @return 内部桥接计划。dispatchable=false 时，调用方不应派发真实读写。
     */
    public SyncBatchRunnerBridgePlan buildPlan(SyncExecution execution,
                                               SyncTask task,
                                               SyncTemplate template,
                                               SyncWorkerExecutionPlanView workerPlan) {
        List<String> issueCodes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (execution == null || task == null || template == null || workerPlan == null) {
            issueCodes.add("BRIDGE_INPUT_CONTEXT_MISSING");
            return blockedPlan(execution, task, template, null, issueCodes, warnings);
        }

        issueCodes.addAll(workerPlan.issueCodes());
        if (!workerPlan.available() || "BLOCKED".equals(workerPlan.planStatus())) {
            issueCodes.add("WORKER_PLAN_BLOCKED");
        }
        if (!isMinimalJdbcConnector(template.getSourceConnectorType())
                || !isMinimalJdbcConnector(template.getTargetConnectorType())) {
            issueCodes.add("MINIMAL_JDBC_BATCH_BRIDGE_CONNECTOR_UNSUPPORTED");
        }
        if (!isMinimalJdbcBatchMode(template.getSyncMode())) {
            issueCodes.add("MINIMAL_JDBC_BATCH_BRIDGE_MODE_UNSUPPORTED");
        }
        if ("OVERWRITE".equals(normalize(template.getWriteStrategy()))) {
            issueCodes.add("DESTRUCTIVE_WRITE_STRATEGY_REQUIRES_APPROVED_BRIDGE_POLICY");
        }

        SyncFieldMappingExecutionContract fieldMappingContract =
                fieldMappingExecutionContractSupport.parse(template.getFieldMappingConfig(), template.getPrimaryKeyField());
        issueCodes.addAll(fieldMappingContract.getIssueCodes());
        warnings.addAll(fieldMappingContract.getWarnings());
        if (fieldMappingContract.isRequiresFieldRenameTransform()) {
            issueCodes.add("FIELD_RENAME_TRANSFORM_NOT_SUPPORTED_BY_MINIMAL_BRIDGE");
        }
        if (!fieldMappingContract.directlyRunnableByMinimalBridge()) {
            issueCodes.add("FIELD_MAPPING_CONTRACT_NOT_RUNNABLE_BY_MINIMAL_BRIDGE");
        }

        List<String> distinctIssues = distinct(issueCodes);
        List<String> distinctWarnings = distinct(warnings);
        if (!blockingIssues(distinctIssues).isEmpty()) {
            return blockedPlan(execution, task, template, fieldMappingContract, distinctIssues, distinctWarnings);
        }

        return new SyncBatchRunnerBridgePlan(
                true,
                "READY_TO_DISPATCH",
                execution.getTenantId(),
                execution.getProjectId(),
                execution.getWorkspaceId(),
                task.getId(),
                execution.getId(),
                template.getId(),
                template.getSourceDatasourceId(),
                template.getTargetDatasourceId(),
                normalize(template.getSourceConnectorType()),
                normalize(template.getTargetConnectorType()),
                normalize(template.getSyncMode()),
                readStrategy(template.getSyncMode()),
                normalizeOrDefault(template.getWriteStrategy(), "APPEND"),
                checkpointType(template.getSyncMode()),
                objectLocator(template.getSourceSchemaName(), template.getSourceObjectName()),
                objectLocator(template.getTargetSchemaName(), template.getTargetObjectName()),
                fieldMappingContract,
                template.getIncrementalField(),
                zeroIfNull(execution.getRecordsRead()),
                zeroIfNull(execution.getRecordsWritten()),
                zeroIfNull(execution.getFailedRecordCount()),
                distinctIssues,
                distinctWarnings,
                dispatchActions(workerPlan));
    }

    /**
     * 创建阻断计划。
     *
     * <p>阻断计划仍会尽量携带租户、项目、任务、execution 和模板 ID，方便上层 fail 回调或运营诊断定位；
     * 但不会要求调用方继续读取或写入真实数据。</p>
     */
    private SyncBatchRunnerBridgePlan blockedPlan(SyncExecution execution,
                                                  SyncTask task,
                                                  SyncTemplate template,
                                                  SyncFieldMappingExecutionContract fieldMappingContract,
                                                  List<String> issueCodes,
                                                  List<String> warnings) {
        return new SyncBatchRunnerBridgePlan(
                false,
                "BLOCKED",
                execution == null ? null : execution.getTenantId(),
                execution == null ? null : execution.getProjectId(),
                execution == null ? null : execution.getWorkspaceId(),
                task == null ? null : task.getId(),
                execution == null ? null : execution.getId(),
                template == null ? null : template.getId(),
                template == null ? null : template.getSourceDatasourceId(),
                template == null ? null : template.getTargetDatasourceId(),
                template == null ? null : normalize(template.getSourceConnectorType()),
                template == null ? null : normalize(template.getTargetConnectorType()),
                template == null ? null : normalize(template.getSyncMode()),
                template == null ? null : readStrategy(template.getSyncMode()),
                template == null ? null : normalizeOrDefault(template.getWriteStrategy(), "APPEND"),
                template == null ? null : checkpointType(template.getSyncMode()),
                template == null ? null : objectLocator(template.getSourceSchemaName(), template.getSourceObjectName()),
                template == null ? null : objectLocator(template.getTargetSchemaName(), template.getTargetObjectName()),
                fieldMappingContract,
                template == null ? null : template.getIncrementalField(),
                zeroIfNull(execution == null ? null : execution.getRecordsRead()),
                zeroIfNull(execution == null ? null : execution.getRecordsWritten()),
                zeroIfNull(execution == null ? null : execution.getFailedRecordCount()),
                distinct(issueCodes),
                distinct(warnings),
                List.of(
                        "DO_NOT_DISPATCH_BATCH_RUNNER",
                        "CALL_FAIL_EXECUTION_WITH_LOW_SENSITIVE_REASON_OR_DEFER_FOR_TEMPLATE_FIX",
                        "KEEP_FIELD_MAPPING_AND_CONNECTION_DETAILS_OUT_OF_EVENTS"
                ));
    }

    /**
     * 过滤出真正阻断最小 bridge 的问题码。
     *
     * <p>workerPlan 中某些 issueCode 是风险提示，例如缺少 retryPolicy、timeoutPolicy 或 partitionPlan，
     * 当前不直接阻断最小闭环；但连接器不支持、状态不对、对象缺失、字段映射不可运行、覆盖写入未审批等问题必须阻断。</p>
     */
    private List<String> blockingIssues(List<String> issueCodes) {
        return issueCodes.stream()
                .filter(issueCode -> !nonBlockingIssue(issueCode))
                .toList();
    }

    private boolean nonBlockingIssue(String issueCode) {
        return "RETRY_POLICY_NOT_DECLARED".equals(issueCode)
                || "TIMEOUT_POLICY_NOT_DECLARED".equals(issueCode)
                || "PARTITION_PLAN_NOT_DECLARED".equals(issueCode)
                || "WRITE_STRATEGY_DEFAULTED_TO_APPEND".equals(issueCode)
                || "CHECKPOINT_BOUNDARY_NOT_DECLARED".equals(issueCode);
    }

    private List<String> dispatchActions(SyncWorkerExecutionPlanView workerPlan) {
        List<String> actions = new ArrayList<>();
        actions.add("DISPATCH_TO_CONNECTOR_RUNTIME_RUN_ONCE");
        actions.add("SEND_HEARTBEAT_UNTIL_RUNNER_RESULT_RETURNS");
        if (workerPlan.checkpointRequired()) {
            actions.add("WRITE_CHECKPOINT_AFTER_SAFE_BATCH_RESULT");
        }
        actions.add("CALL_COMPLETE_OR_FAIL_CALLBACK_AFTER_RUNNER_RESULT");
        return List.copyOf(actions);
    }

    private boolean isMinimalJdbcConnector(String connectorType) {
        return MINIMAL_JDBC_CONNECTORS.contains(normalize(connectorType));
    }

    private boolean isMinimalJdbcBatchMode(String syncMode) {
        SyncMode mode = resolveMode(syncMode);
        return mode == SyncMode.FULL
                || mode == SyncMode.INCREMENTAL_TIME
                || mode == SyncMode.INCREMENTAL_ID
                || mode == SyncMode.SCHEDULED_BATCH
                || mode == SyncMode.ONE_TIME_MIGRATION
                || mode == SyncMode.REPLAY
                || mode == SyncMode.BACKFILL;
    }

    private String readStrategy(String syncMode) {
        SyncMode mode = resolveMode(syncMode);
        if (mode == null) {
            return "UNKNOWN";
        }
        return switch (mode) {
            case FULL, ONE_TIME_MIGRATION -> "FULL_OBJECT_SCAN";
            case INCREMENTAL_TIME -> "INCREMENTAL_TIME_WINDOW";
            case INCREMENTAL_ID -> "INCREMENTAL_ID_RANGE";
            case SCHEDULED_BATCH -> "SCHEDULED_BATCH_WINDOW";
            case REPLAY -> "REPLAY_FROM_CHECKPOINT";
            case BACKFILL -> "BACKFILL_RANGE";
            case CDC_STREAMING -> "STREAMING_OFFSET";
            case OFFLINE_IMPORT, OFFLINE_EXPORT -> "ARTIFACT_STAGE";
        };
    }

    private String checkpointType(String syncMode) {
        SyncMode mode = resolveMode(syncMode);
        if (mode == null) {
            return "UNKNOWN";
        }
        return switch (mode) {
            case FULL, ONE_TIME_MIGRATION -> "NONE_OR_FINAL_WATERMARK";
            case INCREMENTAL_TIME -> "TIME_FIELD";
            case INCREMENTAL_ID -> "ID_FIELD";
            case SCHEDULED_BATCH -> "BATCH_WINDOW";
            case REPLAY -> "CHECKPOINT_REF";
            case BACKFILL -> "BACKFILL_RANGE";
            case CDC_STREAMING -> "STREAMING_OFFSET";
            case OFFLINE_IMPORT, OFFLINE_EXPORT -> "ARTIFACT_STAGE";
        };
    }

    private String objectLocator(String schemaName, String objectName) {
        if (!hasText(objectName)) {
            return null;
        }
        if (!hasText(schemaName)) {
            return objectName.trim();
        }
        return schemaName.trim() + "." + objectName.trim();
    }

    private SyncMode resolveMode(String syncMode) {
        String normalized = normalize(syncMode);
        if (normalized == null) {
            return null;
        }
        try {
            return SyncMode.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private List<String> distinct(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private Long zeroIfNull(Long value) {
        return value == null ? 0L : value;
    }

    private String normalizeOrDefault(String value, String defaultValue) {
        String normalized = normalize(value);
        return normalized == null ? defaultValue : normalized;
    }

    private String normalize(String value) {
        return hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

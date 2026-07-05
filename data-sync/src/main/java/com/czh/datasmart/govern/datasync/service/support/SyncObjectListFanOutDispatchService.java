/**
 * @Author : Cui
 * @Date: 2026/07/05 16:19
 * @Description DataSmart Govern Backend - SyncObjectListFanOutDispatchService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCompleteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionFailRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerExecutionPlanView;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;
import com.czh.datasmart.govern.datasync.integration.datasource.runonce.DatasourceRunOnceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * OBJECT_LIST 多对象同步串行 fan-out 调度器。
 *
 * <p>本组件是 DataX-style 执行拓扑在当前项目里的第一段真实落地：DataX 的 Job 会被拆成多个 Task/Channel，
 * 每个 Channel 使用 Reader/Writer 完成一个受控数据搬运单元。本项目当前还没有完整 DataX 引擎和并发资源组，
 * 因此先采用“控制面拆对象、Java run-once 串行执行、data-sync 统一终态回写”的最小商业闭环。</p>
 *
 * <p>为什么先串行而不是直接并发：多表同步一旦并发，就要同时处理资源组、连接池配额、目标库锁冲突、失败半成功、
 * 断点恢复、脏数据隔离和 operator attention。当前阶段用户最关心的是“从选择多张表到真实同步完成”的闭环，
 * 串行 fan-out 能先把产品链路跑通，同时为后续 splitPk、TaskGroup 并发和资源组限流留下清晰扩展点。</p>
 *
 * <p>安全边界：</p>
 * <p>1. 只支持 OBJECT_LIST，不支持 SCHEMA_FULL/DATABASE_FULL 的运行时发现；</p>
 * <p>2. 只支持 FULL 与 ONE_TIME_MIGRATION，不支持增量 checkpoint、定时批量窗口或 CDC；</p>
 * <p>3. 每个子对象都会被转换为 SINGLE_OBJECT 模板后重新走 bridge plan 校验；</p>
 * <p>4. 任一对象失败即 fail 整个 execution，避免出现“部分成功但任务显示成功”的假闭环；</p>
 * <p>5. 普通结果只返回低敏状态和 issueCode，不暴露对象名、字段名、SQL、行样本或连接信息。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncObjectListFanOutDispatchService {

    private static final Set<String> SUPPORTED_SYNC_MODES = Set.of("FULL", "ONE_TIME_MIGRATION");
    private static final String OBJECT_LIST = "OBJECT_LIST";
    private static final String SINGLE_OBJECT = "SINGLE_OBJECT";

    private final SyncObjectMappingExecutionContractSupport objectMappingExecutionContractSupport;
    private final SyncBatchRunnerBridgePlanSupport bridgePlanSupport;
    private final SyncBatchRunOnceDispatchService runOnceDispatchService;
    private final SyncExecutionLifecycleSupport lifecycleSupport;
    private final DataSyncTaskManagementReceiptPublisher receiptPublisher;

    /**
     * 判断当前合同是否可以进入最小串行 fan-out。
     *
     * <p>该判断刻意比“合同是否需要专用 Runner”更窄：OBJECT_LIST 在产品上当然最终需要专用 Runner 和并发治理，
     * 但 FULL/ONE_TIME_MIGRATION 且显式 mappings 的场景，可以用当前 Java run-once 能力先做串行闭环。
     * 这样既不会把 SCHEMA_FULL/DATABASE_FULL 的发现逻辑草率放开，也不会把增量 checkpoint 原始水位问题提前引入。</p>
     */
    public boolean supports(SyncOfflineRunnerJobContract contract, SyncActorContext actorContext) {
        if (contract == null) {
            return false;
        }
        return contract.offlineChannel()
                && OBJECT_LIST.equals(normalize(contract.syncScopeType()))
                && SUPPORTED_SYNC_MODES.contains(normalize(contract.syncMode()))
                && !contract.checkpointRequired()
                && !firstText(contract.customSqlStatementPolicy(), "NOT_APPLICABLE").startsWith("CUSTOM_SQL")
                && !Boolean.TRUE.equals(actorContext == null ? null : actorContext.approvalRequired());
    }

    /**
     * 串行执行 OBJECT_LIST 中的每一个对象映射。
     *
     * @param execution 当前已被 worker claim 的 execution。
     * @param task 当前同步任务。
     * @param template 原始多对象模板。
     * @param workerPlan 原始 worker 计划。该计划可能因为 OBJECT_LIST 被标记 BLOCKED，本服务会为每个子对象生成单对象计划视图。
     * @param actorContext 当前服务账号或操作者上下文。
     * @param parentContract 原始离线 Runner 合同，用于结果中保留合同状态。
     * @return 低敏调度结果。
     */
    public SyncOfflineRunnerDispatchResult dispatchObjectList(SyncExecution execution,
                                                              SyncTask task,
                                                              SyncTemplate template,
                                                              SyncWorkerExecutionPlanView workerPlan,
                                                              SyncActorContext actorContext,
                                                              SyncOfflineRunnerJobContract parentContract) {
        SyncObjectMappingExecutionContract objectContract = objectMappingExecutionContractSupport.parse(template);
        if (!objectContract.executableBySerialFanOut()) {
            return failFanOut(task, execution, actorContext, parentContract,
                    false,
                    "OBJECT_LIST_MAPPING_CONTRACT_BLOCKED",
                    "OBJECT_LIST_MAPPING_CONTRACT_BLOCKED",
                    "OBJECT_LIST 多对象映射配置不可执行，本次执行未触发真实读写",
                    objectContract.issueCodes());
        }

        long totalRecordsRead = 0L;
        long totalRecordsWritten = 0L;
        long totalFailedRecordCount = 0L;
        boolean remoteCalled = false;
        List<String> accumulatedWarnings = new ArrayList<>();

        for (SyncObjectMappingExecutionItem item : objectContract.mappings()) {
            SyncTemplate childTemplate = singleObjectTemplate(template, item);
            SyncExecution childExecution = childExecutionView(execution);
            SyncWorkerExecutionPlanView childWorkerPlan = singleObjectWorkerPlan(workerPlan, item);
            SyncBatchRunnerBridgePlan childBridgePlan =
                    bridgePlanSupport.buildPlan(childExecution, task, childTemplate, childWorkerPlan);
            if (!childBridgePlan.isDispatchable()) {
                return failFanOut(task, execution, actorContext, parentContract,
                        remoteCalled,
                        "OBJECT_LIST_CHILD_BRIDGE_PLAN_BLOCKED",
                        "OBJECT_LIST_CHILD_BRIDGE_PLAN_BLOCKED",
                        "OBJECT_LIST 子对象桥接计划被阻断，本次执行按 fail-closed 终止；请先修复对象级字段映射或写入策略",
                        mergeIssueCodes(childBridgePlan.getIssueCodes(), item.warnings(), "OBJECT_LIST_CHILD_BRIDGE_PLAN_BLOCKED"));
            }

            SyncBatchRunOnceRemoteExecutionResult remoteResult =
                    runOnceDispatchService.executePreparedRunOnceRemoteOnly(childBridgePlan, childExecution, task, actorContext);
            remoteCalled = remoteCalled || remoteResult != null && remoteResult.remoteCalled();
            if (remoteResult == null || remoteResult.failed() || !remoteResult.completed()) {
                return failFanOut(task, execution, actorContext, parentContract,
                        remoteCalled,
                        "OBJECT_LIST_CHILD_RUN_ONCE_FAILED",
                        firstText(remoteResult == null ? null : remoteResult.errorCode(), "OBJECT_LIST_CHILD_RUN_ONCE_FAILED"),
                        "OBJECT_LIST 子对象 run-once 执行失败，本次多对象同步已停止，避免后续对象继续写入造成半成功不可解释状态",
                        mergeIssueCodes(remoteResult == null ? List.of() : remoteResult.issueCodes(),
                                item.warnings(),
                                "OBJECT_LIST_CHILD_RUN_ONCE_FAILED"));
            }
            totalRecordsRead += zeroIfNull(remoteResult.totalRecordsRead());
            totalRecordsWritten += zeroIfNull(remoteResult.totalRecordsWritten());
            totalFailedRecordCount += zeroIfNull(remoteResult.totalFailedRecordCount());
            accumulatedWarnings.addAll(item.warnings());
        }

        completeFanOut(task, execution, actorContext, totalRecordsRead, totalRecordsWritten, totalFailedRecordCount);
        return new SyncOfflineRunnerDispatchResult(
                remoteCalled,
                true,
                false,
                "OBJECT_LIST_SERIAL_FAN_OUT_COMPLETED",
                execution.getId(),
                "OBJECT_LIST_ALL_OBJECTS_COMPLETED",
                parentContract == null ? "OBJECT_LIST_SERIAL_FAN_OUT" : parentContract.contractStatus(),
                mergeIssueCodes(accumulatedWarnings, objectContract.warnings(), "OBJECT_LIST_SERIAL_FAN_OUT_COMPLETED"),
                SyncOfflineRunnerDispatchResult.PAYLOAD_POLICY
        );
    }

    /**
     * 根据一个对象映射构造临时 SINGLE_OBJECT 模板。
     *
     * <p>该对象不会入库，只用于生成子 bridge plan。这样可以最大化复用已经实现的字段映射、过滤条件、写入策略、
     * connector 能力和 checkpoint 类型校验逻辑，避免 fan-out 服务自己再实现一套“看起来像 bridge plan”的重复规则。</p>
     */
    private SyncTemplate singleObjectTemplate(SyncTemplate template, SyncObjectMappingExecutionItem item) {
        SyncTemplate child = new SyncTemplate();
        child.setId(template.getId());
        child.setTenantId(template.getTenantId());
        child.setProjectId(template.getProjectId());
        child.setWorkspaceId(template.getWorkspaceId());
        child.setName(template.getName());
        child.setDescription(template.getDescription());
        child.setSourceDatasourceId(template.getSourceDatasourceId());
        child.setTargetDatasourceId(template.getTargetDatasourceId());
        child.setSourceSchemaName(item.sourceSchemaName());
        child.setSourceObjectName(item.sourceObjectName());
        child.setTargetSchemaName(item.targetSchemaName());
        child.setTargetObjectName(item.targetObjectName());
        child.setSourceConnectorType(template.getSourceConnectorType());
        child.setTargetConnectorType(template.getTargetConnectorType());
        child.setSyncMode(template.getSyncMode());
        child.setSyncScopeType(SINGLE_OBJECT);
        child.setWriteStrategy(template.getWriteStrategy());
        child.setPrimaryKeyField(template.getPrimaryKeyField());
        child.setIncrementalField(template.getIncrementalField());
        child.setFieldMappingConfig(firstText(item.fieldMappingConfigOverride(), template.getFieldMappingConfig()));
        child.setFilterConfig(template.getFilterConfig());
        child.setPartitionConfig(null);
        child.setRetryPolicy(template.getRetryPolicy());
        child.setTimeoutPolicy(template.getTimeoutPolicy());
        child.setEnabled(template.getEnabled());
        child.setCreatedBy(template.getCreatedBy());
        child.setUpdatedBy(template.getUpdatedBy());
        child.setCreateTime(template.getCreateTime());
        child.setUpdateTime(template.getUpdateTime());
        return child;
    }

    /**
     * 构造子对象执行视图。
     *
     * <p>多对象最小闭环当前不支持对象级断点恢复，因此每个子对象都从 0 开始让 datasource-management 做稳定分页。
     * 如果复用父 execution 的 recordsRead 作为 offset，第二张表会错误跳过前 N 行。后续要做生产级恢复时，应把对象 ordinal、
     * 对象级 checkpointRef 和已处理计数持久化到专用 fan-out 执行表，而不是复用父 execution 的全局计数。</p>
     */
    private SyncExecution childExecutionView(SyncExecution execution) {
        SyncExecution child = new SyncExecution();
        child.setId(execution.getId());
        child.setTenantId(execution.getTenantId());
        child.setProjectId(execution.getProjectId());
        child.setWorkspaceId(execution.getWorkspaceId());
        child.setSyncTaskId(execution.getSyncTaskId());
        child.setExecutionNo(execution.getExecutionNo());
        child.setExecutionState(execution.getExecutionState());
        child.setTriggerType(execution.getTriggerType());
        child.setExecutorId(execution.getExecutorId());
        child.setLeaseExpireTime(execution.getLeaseExpireTime());
        child.setRecordsRead(0L);
        child.setRecordsWritten(0L);
        child.setFailedRecordCount(0L);
        child.setTriggeredBy(execution.getTriggeredBy());
        return child;
    }

    /**
     * 构造子对象 worker plan。
     *
     * <p>原始 workerPlan 对 OBJECT_LIST 会带有 “SCOPE_NOT_EXECUTABLE_BY_MINIMAL_RUN_ONCE_BRIDGE” 阻断码。
     * fan-out 已经把多对象拆成了单对象，因此子计划不能继承这个父级阻断码，否则每张子表都会被再次误判为多对象不可执行。</p>
     */
    private SyncWorkerExecutionPlanView singleObjectWorkerPlan(SyncWorkerExecutionPlanView workerPlan,
                                                               SyncObjectMappingExecutionItem item) {
        return new SyncWorkerExecutionPlanView(
                true,
                "READY_TO_RUN",
                workerPlan.tenantId(),
                workerPlan.projectId(),
                workerPlan.workspaceId(),
                workerPlan.syncTaskId(),
                workerPlan.executionId(),
                workerPlan.executionNo(),
                workerPlan.executionState(),
                workerPlan.triggerType(),
                workerPlan.executorId(),
                workerPlan.leaseExpireTime(),
                workerPlan.templateId(),
                workerPlan.sourceDatasourceId(),
                workerPlan.targetDatasourceId(),
                workerPlan.sourceConnectorType(),
                workerPlan.targetConnectorType(),
                workerPlan.syncMode(),
                workerPlan.transferChannel(),
                workerPlan.referenceRuntime(),
                SINGLE_OBJECT,
                true,
                false,
                false,
                1,
                false,
                true,
                true,
                true,
                workerPlan.writeStrategy(),
                workerPlan.writeStrategyRequiresConflictKey(),
                workerPlan.primaryKeyDeclared(),
                workerPlan.incrementalFieldDeclared(),
                workerPlan.connectorCompatibilitySupported(),
                workerPlan.consistencyGoal(),
                false,
                workerPlan.retryPattern(),
                true,
                false,
                false,
                workerPlan.filterDeclared(),
                false,
                workerPlan.retryPolicyDeclared(),
                workerPlan.timeoutPolicyDeclared(),
                sanitizedChildIssueCodes(workerPlan.issueCodes()),
                List.of("OBJECT_LIST_CHILD_PLAN_DISPATCH_TO_RUN_ONCE", "CALL_PARENT_FAN_OUT_COMPLETE_AFTER_ALL_OBJECTS"),
                workerPlan.performanceNotes(),
                mergeIssueCodes(workerPlan.safetyNotes(), item.warnings(), "OBJECT_LIST_CHILD_SCOPE_CREATED_BY_FAN_OUT"),
                workerPlan.payloadPolicy()
        );
    }

    private List<String> sanitizedChildIssueCodes(List<String> issueCodes) {
        if (issueCodes == null || issueCodes.isEmpty()) {
            return List.of();
        }
        return issueCodes.stream()
                .filter(issueCode -> !"SCOPE_NOT_EXECUTABLE_BY_MINIMAL_RUN_ONCE_BRIDGE".equals(issueCode))
                .filter(issueCode -> !"OBJECT_MAPPING_CONFIG_REQUIRED".equals(issueCode))
                .filter(issueCode -> !"OBJECT_MAPPING_EMPTY".equals(issueCode))
                .filter(issueCode -> !"OBJECT_MAPPING_TOO_LARGE".equals(issueCode))
                .filter(issueCode -> !"OBJECT_MAPPING_IDENTIFIER_UNSAFE".equals(issueCode))
                .toList();
    }

    private void completeFanOut(SyncTask task,
                                SyncExecution execution,
                                SyncActorContext actorContext,
                                long totalRecordsRead,
                                long totalRecordsWritten,
                                long totalFailedRecordCount) {
        SyncExecutionCompleteRequest request = new SyncExecutionCompleteRequest();
        request.setExecutorId(execution.getExecutorId());
        request.setRecordsRead(totalRecordsRead);
        request.setRecordsWritten(totalRecordsWritten);
        request.setCheckpointRef(null);
        request.setIdempotencyKey("object-list-fan-out-complete-" + execution.getId());
        lifecycleSupport.completeExecution(task, execution, request, actorContext);
        receiptPublisher.publishComplete(task, execution, actorContext,
                aggregateResponse(totalRecordsRead, totalRecordsWritten, totalFailedRecordCount));
    }

    private SyncOfflineRunnerDispatchResult failFanOut(SyncTask task,
                                                       SyncExecution execution,
                                                       SyncActorContext actorContext,
                                                       SyncOfflineRunnerJobContract parentContract,
                                                       boolean remoteCalled,
                                                       String dispatchStatus,
                                                       String errorCode,
                                                       String errorMessage,
                                                       List<String> issueCodes) {
        SyncExecutionFailRequest request = new SyncExecutionFailRequest();
        request.setExecutorId(execution.getExecutorId());
        request.setErrorType("OBJECT_LIST_SERIAL_FAN_OUT_FAILED");
        request.setErrorCode(errorCode);
        request.setErrorMessage(errorMessage);
        request.setSourceRecordKey(null);
        request.setTargetRecordKey(null);
        request.setSamplePayload(null);
        request.setRetryable(false);
        request.setIdempotencyKey("object-list-fan-out-fail-" + execution.getId() + "-" + errorCode);
        lifecycleSupport.failExecution(task, execution, request, actorContext);
        receiptPublisher.publishFailed(task, execution, actorContext, errorCode, issueCodes);
        return new SyncOfflineRunnerDispatchResult(
                remoteCalled,
                false,
                true,
                dispatchStatus,
                execution.getId(),
                null,
                parentContract == null ? "OBJECT_LIST_SERIAL_FAN_OUT" : parentContract.contractStatus(),
                mergeIssueCodes(issueCodes, parentContract == null ? List.of() : parentContract.issueCodes(), errorCode),
                SyncOfflineRunnerDispatchResult.PAYLOAD_POLICY
        );
    }

    private DatasourceRunOnceResponse aggregateResponse(long totalRecordsRead,
                                                        long totalRecordsWritten,
                                                        long totalFailedRecordCount) {
        DatasourceRunOnceResponse response = new DatasourceRunOnceResponse();
        response.setRunStatus("OBJECT_LIST_ALL_OBJECTS_COMPLETED");
        response.setBatchRecordsRead(totalRecordsRead);
        response.setBatchRecordsWritten(totalRecordsWritten);
        response.setBatchFailedRecordCount(totalFailedRecordCount);
        response.setTotalRecordsRead(totalRecordsRead);
        response.setTotalRecordsWritten(totalRecordsWritten);
        response.setTotalFailedRecordCount(totalFailedRecordCount);
        response.setEndOfSource(Boolean.TRUE);
        response.setFailed(Boolean.FALSE);
        response.setCompleteCallbackRecommended(Boolean.TRUE);
        response.setFailCallbackRecommended(Boolean.FALSE);
        response.setProgressCallbackRecommended(Boolean.FALSE);
        response.setCheckpointCandidateProduced(Boolean.FALSE);
        response.setPayloadPolicy("LOW_SENSITIVE_OBJECT_LIST_FAN_OUT_RESULT_NO_OBJECT_NAMES_NO_ROWS_NO_SQL");
        return response;
    }

    private List<String> mergeIssueCodes(List<String> first, List<String> second, String issueCode) {
        List<String> values = new ArrayList<>();
        values.addAll(first == null ? List.of() : first);
        values.addAll(second == null ? List.of() : second);
        if (hasText(issueCode)) {
            values.add(issueCode);
        }
        return new ArrayList<>(new LinkedHashSet<>(values));
    }

    private long zeroIfNull(Long value) {
        return value == null ? 0L : value;
    }

    private String firstText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private String normalize(String value) {
        return hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

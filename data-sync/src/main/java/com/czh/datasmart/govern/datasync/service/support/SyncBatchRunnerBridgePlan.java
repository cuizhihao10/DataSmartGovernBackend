/**
 * @Author : Cui
 * @Date: 2026/06/29 23:44
 * @Description DataSmart Govern Backend - SyncBatchRunnerBridgePlan.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import lombok.Getter;

import java.util.List;

/**
 * 同步批量执行器桥接计划。
 *
 * <p>该计划用于把 data-sync 的控制面对象转换为后续受控 connector runtime 可以消费的内部派发契约。
 * 它不是公开 API，不应该被前端、Agent runtime event、普通审计投影或日志直接展示，因为它包含对象定位和字段列表。</p>
 *
 * <p>为什么需要这个中间计划，而不是直接从 data-sync 调用 datasource-management 的 {@code SyncBatchExecutionRunner}：</p>
 * <p>1. data-sync 是任务/execution/checkpoint 的控制面所有者，必须负责何时 complete/fail、何时写 checkpoint；</p>
 * <p>2. datasource-management 当前 runner 内部还会更新自身模块的同步任务状态，直接调用会形成“双控制面”，导致状态写错表；</p>
 * <p>3. 桥接计划先固定低耦合契约，后续可以演进为 HTTP/gRPC/SDK 调用，让 connector runtime 只负责单批读写并返回低敏结果。</p>
 *
 * <p>和 {@link com.czh.datasmart.govern.datasync.controller.dto.SyncWorkerExecutionPlanView} 的区别：</p>
 * <p>workerPlan 是 claim 响应的一部分，只暴露低敏布尔事实；bridgePlan 是内部派发计划，可以携带对象名和字段名，
 * 因而只能在服务端受控链路中使用。</p>
 */
@Getter
public class SyncBatchRunnerBridgePlan {

    /**
     * 当前计划的敏感边界说明。
     */
    public static final String PAYLOAD_POLICY = "INTERNAL_BATCH_RUNNER_BRIDGE_PLAN_DO_NOT_EXPOSE";

    /**
     * 是否允许派发给最小 batch runner。
     *
     * <p>true 表示控制面、连接器类型、同步模式、字段映射和写入策略都满足当前最小闭环要求；
     * false 表示应 fail-closed，不能让 worker 猜测 SQL、字段或对象。</p>
     */
    private final boolean dispatchable;

    /**
     * 派发状态。
     *
     * <p>当前取值为 READY_TO_DISPATCH 或 BLOCKED。后续如果接入 worker 池、配额和维护窗口，
     * 可以扩展为 WAITING_CAPACITY、WAITING_APPROVAL、DEFERRED_BY_BACKPRESSURE 等状态。</p>
     */
    private final String dispatchStatus;

    /**
     * 租户 ID，用于执行器侧隔离、限流和指标标签。
     */
    private final Long tenantId;

    /**
     * 项目 ID，用于项目级配额、审计和执行归属。
     */
    private final Long projectId;

    /**
     * 工作空间 ID，用于空间级 worker 路由和协作隔离。
     */
    private final Long workspaceId;

    /**
     * 同步任务 ID。
     */
    private final Long syncTaskId;

    /**
     * 执行记录 ID，是 checkpoint、complete、fail 回调的核心锚点。
     */
    private final Long executionId;

    /**
     * 模板 ID，用于追溯当前派发计划来自哪份配置。
     */
    private final Long templateId;

    /**
     * 源数据源 ID。它只是 datasource-management 中的引用，不是连接地址。
     */
    private final Long sourceDatasourceId;

    /**
     * 目标数据源 ID。执行器后续会在自己的权限边界内读取连接配置。
     */
    private final Long targetDatasourceId;

    /**
     * 源端连接器类型，例如 MYSQL、POSTGRESQL、SQL_SERVER。
     */
    private final String sourceConnectorType;

    /**
     * 目标端连接器类型。
     */
    private final String targetConnectorType;

    /**
     * 同步模式，例如 FULL、INCREMENTAL_TIME、BACKFILL。
     */
    private final String syncMode;

    /**
     * 读取策略摘要。
     *
     * <p>该字段用于告诉 connector runtime 应按全量扫描、时间窗口、ID 范围、回放还是补数方式准备读取计划。</p>
     */
    private final String readStrategy;

    /**
     * 写入策略，例如 APPEND、UPSERT、INSERT_IGNORE、REPLACE。
     */
    private final String writeStrategy;

    /**
     * checkpoint 类型摘要。
     *
     * <p>该字段不包含 checkpoint 原始值，只说明本次执行应如何推进水位，例如 TIME_FIELD、ID_FIELD 或 BATCH_WINDOW。</p>
     */
    private final String checkpointType;

    /**
     * 源对象定位。
     *
     * <p>可能包含 schema/table 等业务模型信息，因此只能内部使用，不能进入公开响应。</p>
     */
    private final String sourceObjectLocator;

    /**
     * 目标对象定位。
     */
    private final String targetObjectLocator;

    /**
     * 字段映射内部执行契约。
     */
    private final SyncFieldMappingExecutionContract fieldMappingContract;

    /**
     * 过滤条件内部执行契约。
     *
     * <p>这些条件来自模板 filterConfig，并且已经被解析成安全字段名、标准化操作符和值。
     * 由于 value 可能包含业务范围信息，它只能进入 internal run-once 请求，最终由 datasource-management
     * 通过 PreparedStatement 绑定，不能进入普通响应、日志、审计摘要或 runtime event。</p>
     */
    private final List<SyncFilterExecutionCondition> filterConditions;

    /**
     * 离线 Runner 作业合同。
     *
     * <p>该字段是本阶段新增的“执行器调度面低敏合同”。它比普通 workerPlan 更接近未来专用 DataX-style Runner，
     * 但仍然不包含 SQL 正文、连接凭据、对象映射原文、字段映射原文、过滤条件、分区条件或 checkpoint 原始值。
     * 现有最小 run-once bridge 仍只读取本类中原有的对象定位和字段映射字段；后续真正接入专用 Runner 时，
     * 可以优先消费该合同判断是否需要对象 fan-out、调度窗口、checkpoint handoff、审批和低敏执行报告。</p>
     */
    private final SyncOfflineRunnerJobContract offlineRunnerContract;

    /**
     * 增量字段。
     *
     * <p>它是字段名而不是 checkpoint 值；真实水位值仍由 checkpoint 表或执行器回调维护。</p>
     */
    private final String incrementalField;

    /**
     * 历史已读记录数，用于 runner 恢复或诊断时理解已有进度。
     */
    private final Long previousRecordsRead;

    /**
     * 历史已写记录数。
     */
    private final Long previousRecordsWritten;

    /**
     * 历史失败记录数。
     */
    private final Long previousFailedRecordCount;

    /**
     * 阻断或需要关注的问题码。
     */
    private final List<String> issueCodes;

    /**
     * 非阻断提示。
     */
    private final List<String> warnings;

    /**
     * 后续动作建议。
     */
    private final List<String> nextActions;

    /**
     * 载荷策略说明。
     */
    private final String payloadPolicy;

    public SyncBatchRunnerBridgePlan(boolean dispatchable,
                                     String dispatchStatus,
                                     Long tenantId,
                                     Long projectId,
                                     Long workspaceId,
                                     Long syncTaskId,
                                     Long executionId,
                                     Long templateId,
                                     Long sourceDatasourceId,
                                     Long targetDatasourceId,
                                     String sourceConnectorType,
                                     String targetConnectorType,
                                     String syncMode,
                                     String readStrategy,
                                     String writeStrategy,
                                     String checkpointType,
                                     String sourceObjectLocator,
                                     String targetObjectLocator,
                                     SyncFieldMappingExecutionContract fieldMappingContract,
                                     List<SyncFilterExecutionCondition> filterConditions,
                                     SyncOfflineRunnerJobContract offlineRunnerContract,
                                     String incrementalField,
                                     Long previousRecordsRead,
                                     Long previousRecordsWritten,
                                     Long previousFailedRecordCount,
                                     List<String> issueCodes,
                                     List<String> warnings,
                                     List<String> nextActions) {
        this.dispatchable = dispatchable;
        this.dispatchStatus = dispatchStatus;
        this.tenantId = tenantId;
        this.projectId = projectId;
        this.workspaceId = workspaceId;
        this.syncTaskId = syncTaskId;
        this.executionId = executionId;
        this.templateId = templateId;
        this.sourceDatasourceId = sourceDatasourceId;
        this.targetDatasourceId = targetDatasourceId;
        this.sourceConnectorType = sourceConnectorType;
        this.targetConnectorType = targetConnectorType;
        this.syncMode = syncMode;
        this.readStrategy = readStrategy;
        this.writeStrategy = writeStrategy;
        this.checkpointType = checkpointType;
        this.sourceObjectLocator = sourceObjectLocator;
        this.targetObjectLocator = targetObjectLocator;
        this.fieldMappingContract = fieldMappingContract;
        this.filterConditions = filterConditions == null ? List.of() : List.copyOf(filterConditions);
        this.offlineRunnerContract = offlineRunnerContract;
        this.incrementalField = incrementalField;
        this.previousRecordsRead = previousRecordsRead;
        this.previousRecordsWritten = previousRecordsWritten;
        this.previousFailedRecordCount = previousFailedRecordCount;
        this.issueCodes = List.copyOf(issueCodes);
        this.warnings = List.copyOf(warnings);
        this.nextActions = List.copyOf(nextActions);
        this.payloadPolicy = PAYLOAD_POLICY;
    }
}

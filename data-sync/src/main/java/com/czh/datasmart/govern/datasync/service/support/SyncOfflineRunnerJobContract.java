/**
 * @Author : Cui
 * @Date: 2026/07/05 14:26
 * @Description DataSmart Govern Backend - SyncOfflineRunnerJobContract.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.util.List;

/**
 * DataX-style 离线 Runner 作业合同。
 *
 * <p>这个合同处在“控制面规划”和“真实执行器”之间：它比面向 UI 的 offline-job-plan 更接近执行器调度，
 * 但仍然坚持低敏边界，不包含 SQL 正文、statementRef 值、连接串、账号、密码、对象映射原文、字段映射原文、
 * 过滤条件、分区条件、样本数据或 checkpoint 原始值。</p>
 *
 * <p>它要解决的核心问题是：当 data-sync 以后真正接入 DataX-style Runner 时，Runner 入口不应该只收到
 * 一个 templateId 然后自己回查、猜测和拼装配置，而应该收到一份稳定合同，明确：</p>
 * <p>1. 当前任务属于哪条传输通道和哪类 Reader/Writer；</p>
 * <p>2. 是否需要审批、调度、checkpoint handoff、对象 fan-out 或专用 Runner；</p>
 * <p>3. 当前最小 run-once bridge 是否能端到端执行，还是只能停在预派发/阻断状态；</p>
 * <p>4. Runner 完成后应该按什么报告策略回写进度、checkpoint、错误样本和指标。</p>
 *
 * @param contractVersion 合同版本。Runner、Agent 和测试可以据此做兼容性判断。
 * @param contractStatus 合同状态，例如 MINIMAL_BRIDGE_END_TO_END_SUPPORTED、DEDICATED_OFFLINE_RUNNER_REQUIRED。
 * @param templateId 模板 ID。
 * @param tenantId 租户 ID。
 * @param projectId 项目 ID。
 * @param workspaceId 工作空间 ID。
 * @param syncTaskId 任务 ID。纯模板规划阶段可以为空。
 * @param executionId 执行记录 ID。任务尚未运行时可以为空。
 * @param sourceDatasourceId 源数据源 ID，只是内部引用，不含连接信息。
 * @param targetDatasourceId 目标数据源 ID。
 * @param sourceConnectorType 源端连接器类型低敏枚举。
 * @param targetConnectorType 目标端连接器类型低敏枚举。
 * @param syncMode 同步模式。
 * @param transferChannel 传输通道。离线合同只接受 OFFLINE，CDC 会被标记为实时通道。
 * @param referenceRuntime 参考执行架构，例如 DATAX_STYLE_OFFLINE_READER_WRITER_RUNNER。
 * @param syncScopeType 同步范围类型。
 * @param readerFamily Reader 家族。
 * @param writerFamily Writer 家族。
 * @param modeFamily 模式族。
 * @param writeStrategy 写入策略摘要。
 * @param runnerBoundary Runner 边界说明。
 * @param offlineChannel 是否为离线通道。
 * @param planReady 是否已经形成可解释计划。
 * @param approvalRequired 是否需要审批。
 * @param checkpointRequired 是否需要 checkpoint。
 * @param taskLevelScheduleRequired 是否需要任务层调度配置。
 * @param dedicatedOfflineRunnerRequired 是否需要专用离线 Runner。
 * @param minimalBridgeDispatchable 当前 bridge 预派发是否放行。它不等于完整端到端能力。
 * @param minimalBridgeEndToEndSupported 当前最小 run-once 是否能完整闭环。只有 FULL/ONE_TIME_MIGRATION 且无 checkpoint handoff 时才应为 true。
 * @param customSqlStatementPolicy 自定义 SQL 低敏策略，不含 SQL 正文或 statementRef 值。
 * @param fieldMappingDeclared 是否声明字段映射。
 * @param fieldMappingRunnableByMinimalBridge 字段映射是否可被当前最小 bridge 直接执行。
 * @param shardPlan 分片计划摘要。
 * @param reportContract 执行报告合同。
 * @param dataXJobExecutionContract DataX-style Job/TaskGroup/Channel/Reader/Writer 执行拓扑合同。
 *                                  它把“未来执行器如何拆作业、如何选择 Reader/Writer、如何管理通道和安全策略”
 *                                  固定成低敏结构，但仍不包含 SQL 正文、凭据、对象映射原文、字段映射原文、
 *                                  过滤条件原文、checkpoint 原始值或行样本。
 * @param issueCodes 问题码。
 * @param failClosedReasons fail-closed 原因。
 * @param recommendedActions 推荐动作。
 * @param safetyNotes 安全提示。
 * @param payloadPolicy 低敏载荷策略。
 */
public record SyncOfflineRunnerJobContract(
        String contractVersion,
        String contractStatus,
        Long templateId,
        Long tenantId,
        Long projectId,
        Long workspaceId,
        Long syncTaskId,
        Long executionId,
        Long sourceDatasourceId,
        Long targetDatasourceId,
        String sourceConnectorType,
        String targetConnectorType,
        String syncMode,
        String transferChannel,
        String referenceRuntime,
        String syncScopeType,
        String readerFamily,
        String writerFamily,
        String modeFamily,
        String writeStrategy,
        String runnerBoundary,
        boolean offlineChannel,
        boolean planReady,
        boolean approvalRequired,
        boolean checkpointRequired,
        boolean taskLevelScheduleRequired,
        boolean dedicatedOfflineRunnerRequired,
        boolean minimalBridgeDispatchable,
        boolean minimalBridgeEndToEndSupported,
        String customSqlStatementPolicy,
        boolean fieldMappingDeclared,
        boolean fieldMappingRunnableByMinimalBridge,
        SyncOfflineRunnerShardPlan shardPlan,
        SyncOfflineRunnerExecutionReport reportContract,
        SyncDataXJobExecutionContract dataXJobExecutionContract,
        List<String> issueCodes,
        List<String> failClosedReasons,
        List<String> recommendedActions,
        List<String> safetyNotes,
        String payloadPolicy
) {
}

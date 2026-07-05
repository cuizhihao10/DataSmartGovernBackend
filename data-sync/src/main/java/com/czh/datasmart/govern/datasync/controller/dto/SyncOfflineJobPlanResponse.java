/**
 * @Author : Cui
 * @Date: 2026/07/05 14:07
 * @Description DataSmart Govern Backend - SyncOfflineJobPlanResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.util.List;

/**
 * DataX 风格离线作业计划响应。
 *
 * <p>这个 DTO 面向前端、Agent 和运营台回答一个比 preview/precheck 更工程化的问题：
 * “如果这份同步模板未来交给专用离线 runner 执行，它应该被拆成怎样的 Reader/Writer 作业”。</p>
 *
 * <p>它与现有几个接口的边界如下：</p>
 * <p>1. validate：硬性校验模板字段是否合法，失败时直接抛业务异常；</p>
 * <p>2. preview：面向配置页面和 Agent 规划，告诉用户配置是否完整；</p>
 * <p>3. precheck：面向当前真实执行入口，回答现有最小 runner 能不能入队；</p>
 * <p>4. offline-job-plan：面向未来 DataX-style 离线执行器，描述低敏作业计划、审批要求、调度语义和 fail-closed 边界。</p>
 *
 * <p>低敏原则非常重要：该响应不返回 SQL 正文、连接串、账号、密码、objectMappingConfig 原文、fieldMappingConfig 原文、
 * filterConfig 原文、partitionConfig 原文、样本数据或 checkpoint 原始值。它只返回“声明了什么、需要什么、应该由谁执行”
 * 这类控制面事实，避免规划接口变成数据泄露通道。</p>
 *
 * @param templateId 模板 ID。
 * @param tenantId 租户 ID。
 * @param projectId 项目 ID。
 * @param workspaceId 工作空间 ID。
 * @param sourceDatasourceId 源数据源 ID，只是平台内部引用，不包含连接信息。
 * @param targetDatasourceId 目标数据源 ID，只是平台内部引用，不包含连接信息。
 * @param sourceConnectorType 源端连接器类型低敏枚举，例如 MYSQL、POSTGRESQL、OBJECT_STORAGE。
 * @param targetConnectorType 目标端连接器类型低敏枚举。
 * @param syncMode 同步模式，例如 FULL、SCHEDULED_BATCH、CUSTOM_SQL_QUERY、CDC_STREAMING。
 * @param transferChannel 顶层传输通道。离线计划只接受 OFFLINE；CDC_STREAMING 会被标记为非离线通道。
 * @param referenceRuntime 参考执行架构，例如 DATAX_STYLE_OFFLINE_READER_WRITER_RUNNER。
 * @param syncScopeType 同步范围，例如 SINGLE_OBJECT、OBJECT_LIST、SCHEMA_FULL、DATABASE_FULL、CUSTOM_SQL_QUERY。
 * @param offlineChannel 当前模板是否属于离线传输通道。
 * @param planStatus 计划状态：PLAN_READY、PLAN_READY_REQUIRES_APPROVAL、PLAN_READY_DEDICATED_RUNNER_REQUIRED、NOT_OFFLINE_CHANNEL、BLOCKED。
 * @param planReady 是否已经能形成低敏离线作业计划。它不等于当前最小 runner 已可执行。
 * @param canCreateTaskDraft 是否建议允许创建任务草稿。
 * @param executableByMinimalBridge 当前 data-sync -> datasource-management 最小 run-once bridge 是否可直接执行。
 * @param dedicatedOfflineRunnerRequired 是否需要专用 DataX-style 离线 runner，而不是当前最小 bridge。
 * @param readerFamily 低敏 Reader 家族，例如 JDBC_READER、FILE_READER、OBJECT_STORAGE_READER。
 * @param writerFamily 低敏 Writer 家族，例如 JDBC_WRITER、FILE_WRITER、OBJECT_STORAGE_WRITER。
 * @param modeFamily 模式族，用于 runner 选择执行模板，例如 FULL_OBJECT_SCAN、SCHEDULED_BATCH_WINDOW。
 * @param shardStrategy 分片策略摘要，例如 OBJECT_LEVEL_FAN_OUT、TIME_WINDOW_SHARD、ARTIFACT_CHUNK_SHARD。
 * @param scheduleSemantics 调度语义摘要，说明定时全量、定时批量和手动触发的区别。
 * @param sqlStatementPolicy 自定义 SQL 的 statementRef 策略，不返回 SQL 正文。
 * @param checkpointHandoffPolicy checkpoint 交接策略摘要，不返回 checkpoint 值。
 * @param approvalPolicy 审批策略摘要。
 * @param runnerBoundary 当前计划应该由最小 bridge 执行，还是必须交给专用离线 runner 或实时 CDC pipeline。
 * @param taskLevelScheduleRequired 是否必须在创建任务时提供 scheduleConfig。SCHEDULED_BATCH 必须为 true。
 * @param customSqlStatementRefDeclared 自定义 SQL 配置中是否声明 statementRef。
 * @param customSqlInlineSqlDeclared 自定义 SQL 配置中是否声明 inline SQL 正文；只暴露布尔值，不返回正文。
 * @param checkpointRequired 当前模式是否需要 checkpoint。
 * @param checkpointHandoffRequired 是否要求 runner 与 checkpoint 表/状态服务进行安全交接。
 * @param approvalRequired 是否需要审批或人工确认。
 * @param fieldMappingDeclared 是否声明字段映射配置。
 * @param fieldMappingRunnableByMinimalBridge 字段映射是否能被当前最小 bridge 直接执行。
 * @param objectMappingDeclared 是否声明对象映射配置。
 * @param selectedObjectCount 对象映射数量摘要，不返回对象名正文。
 * @param filterDeclared 是否声明过滤配置。
 * @param partitionDeclared 是否声明分区配置。
 * @param issueCodes 低敏问题码。
 * @param failClosedReasons fail-closed 原因。用于告诉调用方为什么不能被当前执行器直接运行。
 * @param recommendedActions 推荐动作。
 * @param performanceNotes 性能提示。
 * @param safetyNotes 安全治理提示。
 * @param payloadPolicy 低敏载荷策略。
 */
public record SyncOfflineJobPlanResponse(
        Long templateId,
        Long tenantId,
        Long projectId,
        Long workspaceId,
        Long sourceDatasourceId,
        Long targetDatasourceId,
        String sourceConnectorType,
        String targetConnectorType,
        String syncMode,
        String transferChannel,
        String referenceRuntime,
        String syncScopeType,
        boolean offlineChannel,
        String planStatus,
        boolean planReady,
        boolean canCreateTaskDraft,
        boolean executableByMinimalBridge,
        boolean dedicatedOfflineRunnerRequired,
        String readerFamily,
        String writerFamily,
        String modeFamily,
        String shardStrategy,
        String scheduleSemantics,
        String sqlStatementPolicy,
        String checkpointHandoffPolicy,
        String approvalPolicy,
        String runnerBoundary,
        boolean taskLevelScheduleRequired,
        boolean customSqlStatementRefDeclared,
        boolean customSqlInlineSqlDeclared,
        boolean checkpointRequired,
        boolean checkpointHandoffRequired,
        boolean approvalRequired,
        boolean fieldMappingDeclared,
        boolean fieldMappingRunnableByMinimalBridge,
        boolean objectMappingDeclared,
        int selectedObjectCount,
        boolean filterDeclared,
        boolean partitionDeclared,
        List<String> issueCodes,
        List<String> failClosedReasons,
        List<String> recommendedActions,
        List<String> performanceNotes,
        List<String> safetyNotes,
        String payloadPolicy
) {
}

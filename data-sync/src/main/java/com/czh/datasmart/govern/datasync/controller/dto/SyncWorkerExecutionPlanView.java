/**
 * @Author : Cui
 * @Date: 2026/06/29 03:18
 * @Description DataSmart Govern Backend - SyncWorkerExecutionPlanView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 同步执行器工作计划视图。
 *
 * <p>这个 DTO 是 data-sync 从“控制面已经有模板、任务、execution、lease”走向“真实 worker 可执行”的关键契约。
 * worker 在 claim 成功后，需要知道自己应该按什么同步模式执行、是否需要 checkpoint、当前连接器组合是否被产品能力矩阵允许、
 * 哪些配置块已经声明、遇到阻断时应该 fail 还是 defer。过去 claim 只返回 {@code SyncExecution} 和 {@code SyncTask}，
 * 这两个实体只能说明“谁认领了哪次执行”，不能说明“这次执行应该怎么跑”。</p>
 *
 * <p>为什么这里仍然叫 View，而不是 Command：</p>
 * <p>1. 它是低敏执行计划摘要，不携带 JDBC URL、账号、密码、SQL、字段映射正文、过滤条件正文或样本数据；</p>
 * <p>2. 真正的连接凭据、表名、字段映射细节后续应由受控 connector runtime 按 datasourceId 和权限策略再取；</p>
 * <p>3. 这样可以先稳定 worker 协议与状态机，不把“凭据解析、SQL 生成、批处理读写”一次性塞进 claim 接口。</p>
 *
 * @param available 当前是否生成了可消费计划；模板缺失等结构性问题会导致 false。
 * @param planStatus 计划状态：READY_TO_RUN、READY_WITH_WARNINGS、BLOCKED。
 * @param tenantId 租户 ID，用于 worker 本地日志、限流键和指标标签，不包含租户敏感信息。
 * @param projectId 项目 ID，用于项目级配额、并发隔离和执行指标聚合。
 * @param workspaceId 工作空间 ID，用于协作空间隔离和后续 workspace 级 worker 池路由。
 * @param syncTaskId 同步任务 ID。
 * @param executionId 本次执行记录 ID。
 * @param executionNo 同一任务下第几次执行，方便日志中表达“第 N 次运行”。
 * @param executionState 当前执行状态；claim 成功后通常已经是 RUNNING。
 * @param triggerType 触发类型，例如 MANUAL、SCHEDULED、REPLAY、BACKFILL。
 * @param executorId 当前租约持有者 ID；用于 worker 确认响应是否属于自己。
 * @param leaseExpireTime 当前租约过期时间，worker 应在该时间前 heartbeat。
 * @param templateId 关联模板 ID。
 * @param sourceDatasourceId 源数据源 ID；只是平台内部引用，不是连接信息。
 * @param targetDatasourceId 目标数据源 ID；只是平台内部引用，不是连接信息。
 * @param sourceConnectorType 源端连接器类型低敏枚举，例如 MYSQL、POSTGRESQL。
 * @param targetConnectorType 目标端连接器类型低敏枚举。
 * @param syncMode 同步模式，例如 FULL、INCREMENTAL_TIME、CDC_STREAMING。
 * @param sourceObjectDeclared 是否已经声明源端对象名称；workerPlan 不返回对象名正文，只告诉执行器配置是否具备。
 * @param targetObjectDeclared 是否已经声明目标端对象名称；缺失时 worker 不应猜测写入目标。
 * @param writeStrategy 写入策略，例如 APPEND、UPSERT、OVERWRITE；该枚举会影响幂等、冲突处理、回放和审批。
 * @param writeStrategyRequiresConflictKey 当前写入策略是否要求 primaryKeyField。
 * @param primaryKeyDeclared 是否声明主键或冲突字段；不返回字段名正文。
 * @param incrementalFieldDeclared 是否声明增量字段；增量同步缺失时应阻断真实执行。
 * @param connectorCompatibilitySupported 连接器组合与同步模式是否通过能力矩阵。
 * @param consistencyGoal 推荐一致性目标，例如 SNAPSHOT_BOUNDED、AT_LEAST_ONCE_DEDUP_AWARE。
 * @param checkpointRequired 当前同步模式是否建议或需要 checkpoint。
 * @param retryPattern 推荐重试模式，例如 SEGMENT_RETRY、WINDOW_RETRY、OFFSET_RECOVERY。
 * @param fieldMappingDeclared 是否声明字段映射配置；不返回配置正文。
 * @param filterDeclared 是否声明过滤或增量边界配置；不返回配置正文。
 * @param partitionDeclared 是否声明分区或并发配置；不返回配置正文。
 * @param retryPolicyDeclared 是否声明重试策略；不返回策略正文。
 * @param timeoutPolicyDeclared 是否声明超时策略；不返回策略正文。
 * @param issueCodes 低敏问题码，供 worker、Agent、运营台判断是否继续。
 * @param workerActions 给 worker 的下一步动作建议，不包含 SQL 或内部 endpoint。
 * @param performanceNotes 性能提示，例如大表分片、批量写入、offset 恢复等。
 * @param safetyNotes 安全治理提示，例如不要暴露样本、凭据、URL、路径等。
 * @param payloadPolicy 载荷策略，明确本响应只允许低敏控制面信息。
 */
public record SyncWorkerExecutionPlanView(
        boolean available,
        String planStatus,
        Long tenantId,
        Long projectId,
        Long workspaceId,
        Long syncTaskId,
        Long executionId,
        Long executionNo,
        String executionState,
        String triggerType,
        String executorId,
        LocalDateTime leaseExpireTime,
        Long templateId,
        Long sourceDatasourceId,
        Long targetDatasourceId,
        String sourceConnectorType,
        String targetConnectorType,
        String syncMode,
        boolean sourceObjectDeclared,
        boolean targetObjectDeclared,
        String writeStrategy,
        boolean writeStrategyRequiresConflictKey,
        boolean primaryKeyDeclared,
        boolean incrementalFieldDeclared,
        boolean connectorCompatibilitySupported,
        String consistencyGoal,
        boolean checkpointRequired,
        String retryPattern,
        boolean fieldMappingDeclared,
        boolean filterDeclared,
        boolean partitionDeclared,
        boolean retryPolicyDeclared,
        boolean timeoutPolicyDeclared,
        List<String> issueCodes,
        List<String> workerActions,
        List<String> performanceNotes,
        List<String> safetyNotes,
        String payloadPolicy
) {
}

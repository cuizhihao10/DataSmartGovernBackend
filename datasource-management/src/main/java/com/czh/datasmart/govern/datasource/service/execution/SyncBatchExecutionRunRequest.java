/**
 * @Author : Cui
 * @Date: 2026/06/20 16:25
 * @Description DataSmart Govern Backend - SyncBatchExecutionRunRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单批同步执行请求。
 *
 * <p>这个对象不是 Controller 入参，而是 datasource-management 内部 worker 执行层的调用契约。
 * 它把“执行准备输入”“执行器身份”“上一批累计统计”“当前 checkpoint 起点”集中在一起，
 * 交给 `SyncBatchExecutionRunner` 完成一次 read -> write -> progress/complete/fail 的小闭环。</p>
 *
 * <p>为什么要显式建这个请求对象，而不是让 Runner 直接接收一堆散参数：</p>
 * <p>1. 数据同步执行链路天然会变长，后续还会接入 outbox、worker receipt、分片编号、重试上下文；</p>
 * <p>2. 请求对象可以把每个字段的业务含义写清楚，避免后续维护时混淆“批次增量统计”和“execution 累计统计”；</p>
 * <p>3. Runner 仍保持内部服务边界，不把 SQL 模板、真实行数据、连接串、凭证暴露给普通 API。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncBatchExecutionRunRequest {

    /**
     * worker 执行准备请求。
     * 该请求包含低敏 executionPlan 和字段清单，Runner 会先交给准备服务转换为内部 read/write context。
     */
    private SyncBatchExecutionPreparationRequest preparationRequest;

    /**
     * 执行器或系统调用者 ID。
     * 该 ID 会被传入 progress/complete/fail 回写接口，用于权限校验、审计记录和责任追踪。
     */
    private Long actorId;

    /**
     * 执行器或系统调用者角色。
     * 常见值可能是 WORKER、OPERATOR、ADMIN 或后续专门的 SERVICE_ACCOUNT。
     * Runner 不绕开现有 `SyncTaskService` 的角色校验，避免内部执行链路成为权限后门。
     */
    private String actorRole;

    /**
     * 调用者所属租户。
     * 数据同步属于强租户隔离场景，进度和结果回写也必须携带租户上下文。
     */
    private Long actorTenantId;

    /**
     * 当前批次所属分片或分区。
     * 在单分片任务中可以为空；未来做分区并发、日期分区补数、Kafka partition 同步时，
     * 该字段会成为 checkpoint upsert 的维度之一。
     */
    private String shardOrPartition;

    /**
     * 当前批次读取的起始 checkpoint 值。
     * 它只会进入 `SyncBatchReadContext.parameterValues`，再由 PreparedStatement 绑定到查询参数；
     * 不允许拼接进 SQL 字符串，也不应该写入普通日志或低敏事件。
     */
    private Object checkpointValue;

    /**
     * 本次 Runner 启动前 execution 已累计读取记录数。
     * 现有 `SyncTaskService.reportProgress` 保存的是 execution 级累计值，而不是“本批增量值”。
     * 因此外层 worker 循环如果多次调用 Runner，需要把上一批累计值传回来，避免下一次 progress 覆盖为单批数量。
     */
    private Long previousRecordsRead;

    /**
     * 本次 Runner 启动前 execution 已累计写入记录数。
     * 与 `previousRecordsRead` 一样，它服务的是“多批次调用时仍能正确回写累计进度”的场景。
     */
    private Long previousRecordsWritten;

    /**
     * 本次 Runner 启动前 execution 已累计失败记录数。
     * 当前阶段还没有失败行样本存储和重放补偿，因此这里只维护低敏数量。
     */
    private Long previousFailedRecordCount;
}

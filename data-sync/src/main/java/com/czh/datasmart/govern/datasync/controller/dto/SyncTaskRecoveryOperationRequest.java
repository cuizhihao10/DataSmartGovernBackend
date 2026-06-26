/**
 * @Author : Cui
 * @Date: 2026/06/27 02:28
 * @Description DataSmart Govern Backend - SyncTaskRecoveryOperationRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import lombok.Data;

/**
 * 同步任务恢复类操作请求。
 *
 * <p>该请求同时服务 replay 与 backfill 两类“恢复/补数”动作：
 * 1. replay：从历史 execution 或 checkpoint 重新执行，用于失败恢复、下游重建、修复错误写入；
 * 2. backfill：按时间窗口、分区窗口或业务分片补齐历史缺口。
 *
 * <p>为什么不直接复用 `SyncTaskLifecycleOperationRequest`：
 * 普通 pause/resume/retry/cancel 只需要 reason；replay/backfill 还需要稳定表达来源 execution、来源 checkpoint、
 * 时间窗口和分片窗口。如果继续塞进普通生命周期请求，后续会出现大量“某个字段只在某个动作有效”的隐性约定。
 *
 * <p>低敏边界：
 * 这些字段会进入恢复计划表和审计摘要。调用方不得把 SQL、连接串、密码、token、prompt、样本数据、完整工具参数写入 reason 或 shardOrPartition。
 * 服务层会做基础敏感词兜底，但生产级 DLP 仍应在网关、前端和审计管道继续加强。
 */
@Data
public class SyncTaskRecoveryOperationRequest {

    /**
     * replay 来源 executionId。
     *
     * <p>为空时服务端默认使用任务的 lastExecutionId。
     * replay 通常围绕某一次历史执行进行，sourceExecutionId 能让审计员解释“这次回放是从哪次执行派生出来的”。
     */
    private Long sourceExecutionId;

    /**
     * replay 来源 checkpointId。
     *
     * <p>为空时服务端会尝试读取 sourceExecutionId 下最新 checkpoint；如果没有 checkpoint，则表示从该 execution 的起点回放。
     */
    private Long sourceCheckpointId;

    /**
     * backfill 窗口开始边界。
     *
     * <p>当前使用字符串是为了兼容不同连接器：MySQL 可用时间戳，Kafka 可用 offset 时间，文件可用目录日期。
     * 后续如果按 connectorType 做强 schema，可以再细分为 LocalDateTime、Long offset 或 JSON schema。
     */
    private String windowStart;

    /**
     * backfill 窗口结束边界。
     */
    private String windowEnd;

    /**
     * 分片或分区选择器。
     *
     * <p>例如日期分区、Kafka partition、文件目录分片或业务哈希桶。
     * 不允许写入 SQL where 片段、样本数据或连接器内部凭据。
     */
    private String shardOrPartition;

    /**
     * 操作原因。
     *
     * <p>原因会进入审计摘要，用于说明为什么发起 replay/backfill。
     */
    private String reason;
}

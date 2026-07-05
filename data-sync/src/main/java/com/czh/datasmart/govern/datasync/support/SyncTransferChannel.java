/**
 * @Author : Cui
 * @Date: 2026/07/05 14:05
 * @Description DataSmart Govern Backend - SyncTransferChannel.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.support;

/**
 * 数据传输大类。
 *
 * <p>该枚举用于把大量 syncMode 收敛到产品和工程都容易理解的两条执行路线：</p>
 * <p>1. {@link #OFFLINE}：离线传输，参考 DataX 这类批量同步框架的思想，核心是 Reader/Writer 插件、
 * 批量读取、批量写入、任务调度、分片、重试和运行报告；</p>
 * <p>2. {@link #REALTIME}：实时传输，参考 Kafka Connect + Debezium 这类 CDC 架构的思想，核心是 source connector
 * 捕获数据库变更、写入 Kafka topic、下游 sink 或消费者持续消费变更事件。</p>
 *
 * <p>为什么要单独抽象 transferChannel，而不是只看 syncMode：</p>
 * <p>syncMode 表达的是“用户选择的具体传输方式”，例如 FULL、SCHEDULED_BATCH、CUSTOM_SQL_QUERY、CDC_STREAMING；
 * transferChannel 表达的是“执行器应该走哪条技术路线”。这样后续接入真实 DataX-style runner 或 Debezium/Kafka Connect
 * pipeline 时，调度、预检、监控、告警和 Agent 规划都能复用同一个大类判断。</p>
 */
public enum SyncTransferChannel {

    /**
     * 离线传输。
     *
     * <p>覆盖全量传输、定时全量、定时批量、一次性迁移、SQL 查询结果传输、离线导入/导出、回放和补数。
     * 它不代表“只能手动执行”，定时任务同样属于离线传输；关键区别在于它是有边界的批处理作业，而不是持续消费变更流。</p>
     */
    OFFLINE,

    /**
     * 实时传输。
     *
     * <p>当前只对应 CDC/流式同步路线。它通常需要 binlog/WAL/change stream 权限、offset/checkpoint、
     * Kafka topic、消费组、反压、延迟监控和长期运行 worker 管理。</p>
     */
    REALTIME
}

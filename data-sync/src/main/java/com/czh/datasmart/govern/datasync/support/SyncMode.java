/**
 * @Author : Cui
 * @Date: 2026/05/07 21:26
 * @Description DataSmart Govern Backend - SyncMode.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.support;

/**
 * 数据同步模式。
 *
 * <p>同步模式决定“数据如何移动”和“系统如何判断完成”：
 * 1. 全量同步关注扫描完整源数据和目标覆盖/追加策略；
 * 2. 增量同步关注 checkpoint、边界条件和重复数据处理；
 * 3. CDC/流式同步关注低延迟、offset、顺序和消费幂等；
 * 4. 回放/补数关注历史区间、分区范围和可恢复性。
 *
 * <p>当前先把枚举固定下来，是为了让 API、表结构和状态机从第一天就围绕产品模式设计，
 * 而不是只为 MySQL 单表复制写一套临时字段。
 */
public enum SyncMode {

    /**
     * 全量同步：每次运行读取源端完整范围。
     */
    FULL,

    /**
     * 按时间字段增量同步：例如 update_time > last_checkpoint。
     */
    INCREMENTAL_TIME,

    /**
     * 按 ID 范围增量同步：例如 id > last_id 或分段 id range。
     */
    INCREMENTAL_ID,

    /**
     * CDC 类实时同步：面向 binlog、WAL、Kafka topic 等持续流。
     */
    CDC_STREAMING,

    /**
     * 定时批同步：强调调度周期和批处理窗口。
     */
    SCHEDULED_BATCH,

    /**
     * 一次性迁移：用于系统迁移、初始化导入或临时搬迁。
     */
    ONE_TIME_MIGRATION,

    /**
     * 从 checkpoint 回放：用于故障恢复、修复错误写入或重建下游。
     */
    REPLAY,

    /**
     * 按历史日期、分区或业务范围补数。
     */
    BACKFILL,

    /**
     * 离线导入：例如文件到表、对象存储到仓库。
     */
    OFFLINE_IMPORT,

    /**
     * 离线导出：例如表到文件、查询结果到对象存储。
     */
    OFFLINE_EXPORT
}

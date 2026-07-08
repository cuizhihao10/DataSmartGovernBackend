/**
 * @Author : Cui
 * @Date: 2026/07/09 18:45
 * @Description DataSmart Govern Backend - SyncExecutionLog.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 数据同步执行日志实体。
 *
 * <p>这张实体对应 {@code data_sync_execution_log}，它和 {@link SyncExecution} 的关系可以理解为：</p>
 * <p>1. {@code data_sync_execution} 是“当前状态快照”，适合任务列表、运行历史列表和状态统计；</p>
 * <p>2. {@code data_sync_execution_log} 是“阶段时间线”，适合查看某次执行过程中每一步发生了什么；</p>
 * <p>3. {@code data_sync_object_execution} 是“对象/分片账本”，适合失败对象重试、分片恢复和部分成功分析。</p>
 *
 * <p>为什么不只依赖普通应用日志：应用日志适合工程排障，但它分散在服务实例文件或容器 stdout 中，
 * 不适合做用户可见的产品能力。执行日志落表后，前端、Agent、运维诊断和后续告警都能按 taskId/executionId
 * 稳定查询同一份低敏事实。</p>
 *
 * <p>低敏边界：本表只保存阶段、状态、中文说明、计数、速度、对象/分片低敏标识和 traceId。
 * 禁止保存 JDBC URL、账号、密码、完整 SQL、where 原文、字段值、行样本、真实分片边界或内部 endpoint。</p>
 */
@Data
@TableName("data_sync_execution_log")
public class SyncExecutionLog {

    /**
     * 执行日志主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 租户 ID。
     *
     * <p>冗余租户 ID 是为了让运行日志可以独立做租户范围查询、保留期清理和审计导出，
     * 不必每次 join execution/task 表。</p>
     */
    private Long tenantId;

    /**
     * 项目 ID。
     */
    private Long projectId;

    /**
     * 历史兼容字段。
     *
     * <p>工作空间已从当前用户产品层级中退场，但历史 execution 仍可能带有 workspaceId。
     * 日志表继续冗余该字段，是为了兼容旧数据和旧查询，不在新页面中要求用户理解或填写。</p>
     */
    private Long workspaceId;

    /**
     * 同步任务 ID。
     */
    private Long syncTaskId;

    /**
     * 父级执行记录 ID。
     */
    private Long executionId;

    /**
     * 日志所属阶段。
     *
     * <p>例如 QUEUE、CLAIM、PRECHECK、PLAN、CHANNEL、OBJECT、SHARD、REMOTE_BATCH、CHECKPOINT、COMPLETE、FAILED。
     * 阶段是前端渲染时间线和运维聚合的一级分类。</p>
     */
    private String logStage;

    /**
     * 日志级别。
     *
     * <p>INFO 表示正常推进，WARN 表示可恢复或需要关注，ERROR 表示执行失败或 fail-closed。</p>
     */
    private String logLevel;

    /**
     * 事件类型。
     *
     * <p>事件类型比阶段更细，用来描述具体动作，例如 EXECUTION_QUEUED、WORKER_CLAIMED、
     * RUN_ONCE_BATCH_COMPLETED、OBJECT_SUCCEEDED。它适合后续做统计、筛选和指标转换。</p>
     */
    private String eventType;

    /**
     * 事件结果。
     *
     * <p>常用取值：STARTED、SUCCEEDED、FAILED、SKIPPED、PROGRESS、BLOCKED、RETRYING。</p>
     */
    private String eventStatus;

    /**
     * 面向用户的中文短消息。
     *
     * <p>该字段直接给前端展示，应写“任务已被执行器认领”“第 2 批已写入 500 行”这种人能读懂的话，
     * 不应只写内部状态码。</p>
     */
    private String message;

    /**
     * 低敏详情摘要。
     *
     * <p>用于补充数量、策略、错误建议等排障信息。长度受控，不保存原始 SQL、连接串、样本行或凭据。</p>
     */
    private String detailSummary;

    /**
     * 当前执行器 ID。
     *
     * <p>执行器 ID 属于低敏运维定位信息，可以帮助判断是哪一个 worker 实例处理了本次执行。</p>
     */
    private String executorId;

    /**
     * 工作单元类型，例如 OBJECT 或 PARTITION_SHARD。
     */
    private String workUnitType;

    /**
     * 对象/分片账本 ID。
     */
    private Long objectExecutionId;

    /**
     * 对象顺序号。
     *
     * <p>该字段能把运行日志和对象映射列表的“第几条配置”对应起来，方便用户定位是哪张表或哪个分片出错。</p>
     */
    private Integer objectOrdinal;

    /**
     * 分片低敏标识，例如 id-range-0000。
     *
     * <p>这里不保存真实范围边界，真实边界只在 internal run-once 请求里短暂流转。</p>
     */
    private String shardOrPartition;

    /**
     * 当前累计读取记录数。
     */
    private Long recordsRead;

    /**
     * 当前累计写入记录数。
     */
    private Long recordsWritten;

    /**
     * 当前累计失败记录数。
     */
    private Long failedRecordCount;

    /**
     * 已完成工作单元数量。
     */
    private Integer completedWorkUnits;

    /**
     * 成功工作单元数量。
     */
    private Integer succeededWorkUnits;

    /**
     * 失败工作单元数量。
     */
    private Integer failedWorkUnits;

    /**
     * 进度百分比。
     *
     * <p>当前对象/分片总数已知时可计算精确百分比；普通单表 run-once 如果无法提前知道总行数，
     * 可以为空或只在终态写 100。</p>
     */
    private BigDecimal progressPercent;

    /**
     * 同步速度，单位：行/秒。
     *
     * <p>该速度按当前 execution 开始时间到事件时间之间的累计写入量估算。它不是底层 JDBC 精确吞吐，
     * 但足以支撑运行历史页面展示“当前速度”和运维判断是否卡住。</p>
     */
    private BigDecimal speedRowsPerSecond;

    /**
     * 事件发生时间。
     */
    private LocalDateTime eventTime;

    /**
     * 链路追踪 ID。
     */
    private String traceId;

    /**
     * 载荷安全策略说明。
     */
    private String payloadPolicy;

    /**
     * 数据库记录创建时间。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}

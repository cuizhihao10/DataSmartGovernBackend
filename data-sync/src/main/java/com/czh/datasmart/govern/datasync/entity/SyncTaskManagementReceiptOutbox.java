/**
 * @Author : Cui
 * @Date: 2026/06/29 19:34
 * @Description DataSmart Govern Backend - SyncTaskManagementReceiptOutbox.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * data-sync 到 task-management execution receipt 的本地 outbox。
 *
 * <p>为什么需要这张表：</p>
 * <p>1. data-sync complete/fail 是本服务的领域事实，应该先可靠落在 data-sync 自己的数据库；</p>
 * <p>2. task-management receipt 是跨服务投影，可能因为网络抖动、task-management 重启、网关策略变化而短暂失败；</p>
 * <p>3. 如果只在失败时写日志，就无法保证任务中心最终能看到同步执行结果；</p>
 * <p>4. outbox 把“要投递什么、投递到什么状态、何时重试、何时死信”持久化下来，形成可恢复的最终一致链路。</p>
 *
 * <p>低敏边界：</p>
 * <p>本表只保存 receipt 请求镜像和投递控制字段，不保存 SQL、字段映射正文、过滤条件、连接串、凭据、
 * checkpoint 原始值、失败行样本、prompt、模型输出、内部 URL 或远端响应正文。</p>
 */
@Data
@TableName("data_sync_task_management_receipt_outbox")
public class SyncTaskManagementReceiptOutbox {

    /** 表内自增主键，用于分页、排序和条件更新。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 稳定幂等 ID，和 task-management receiptId 一致；同一 execution 的同一事件只能有一条 outbox。 */
    private String receiptId;

    /** 租户 ID，冗余自同步任务，用于租户隔离、调度筛选和后续清理。 */
    private Long tenantId;

    /** 项目 ID，冗余自同步任务，用于项目级运营视图和排障。 */
    private Long projectId;

    /** 工作空间 ID，冗余自同步任务，用于空间级隔离和运营筛选。 */
    private Long workspaceId;

    /** data-sync 同步任务 ID。 */
    private Long syncTaskId;

    /** data-sync execution ID。 */
    private Long syncExecutionId;

    /** receipt 事件类型，例如 COMPLETE、FAILED；后续可扩展 PROGRESS、CHECKPOINT。 */
    private String eventType;

    /** 来源服务，当前固定为 data-sync。 */
    private String sourceService;

    /** 投递状态：PENDING、DELIVERING、RETRY_WAIT、DELIVERED、DEAD_LETTER。 */
    private String outboxState;

    /** 当前已尝试投递次数；进入 DELIVERING 时递增。 */
    private Integer attemptCount;

    /** 最大尝试次数，创建记录时从配置快照写入，避免配置变化让历史记录语义飘移。 */
    private Integer maxAttemptCount;

    /** 下一次允许重试时间；为空表示可立即处理或不再重试。 */
    private LocalDateTime nextRetryAt;

    /** 最近一次开始投递时间，用于识别卡在 DELIVERING 的崩溃残留。 */
    private LocalDateTime lastAttemptAt;

    /** 成功投递并得到 task-management 确认的时间。 */
    private LocalDateTime deliveredAt;

    /** 进入 DEAD_LETTER 的时间。 */
    private LocalDateTime deadLetterAt;

    /** 最近一次失败错误码，只允许枚举或标准码，不保存异常正文。 */
    private String lastErrorCode;

    /** 最近一次失败摘要，只保存低敏短说明，不保存 URL、请求体、响应体、SQL 或样本。 */
    private String lastErrorSummary;

    /** 投递时使用的 actorId；后台补偿可使用系统服务账号。 */
    private Long actorId;

    /** 投递时使用的角色，通常为 SERVICE_ACCOUNT。 */
    private String actorRole;

    /** 链路追踪 ID，只保存低敏 trace 标识，不保存业务参数。 */
    private String traceId;

    /** 低敏 receipt 请求 JSON；字段集合与 TaskManagementExecutionReceiptRequest 对齐。 */
    private String payloadJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

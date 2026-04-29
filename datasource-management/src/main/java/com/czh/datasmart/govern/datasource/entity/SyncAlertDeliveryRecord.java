package com.czh.datasmart.govern.datasource.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @Author : Cui
 * @Date: 2026/4/20 23:05
 * @Description DataSmart Govern Backend - SyncAlertDeliveryRecord.java
 * @Version:1.0.0
 *
 * 治理告警投递记录实体。
 * 告警主表回答的是“当前这条告警整体是什么状态”，
 * 而投递记录表回答的是“这条告警在每一次通道投递时具体发生了什么”。
 *
 * 这张表重点服务于三个真实运维场景：
 * 1. 外部告警平台不稳定时，定位是哪一次投递、哪个通道、什么错误导致失败；
 * 2. 死信告警被人工重入队后，回看历史上到底尝试过多少次；
 * 3. 审计或运营复盘时，回答“系统是否真的尝试通知过，以及最后在哪一步卡住了”。
 */
@Data
@TableName("sync_alert_delivery_record")
public class SyncAlertDeliveryRecord {

    /**
     * 投递记录主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属租户。
     * 与告警主表保持同一租户归属，便于租户级查询和审计隔离。
     */
    private Long tenantId;

    /**
     * 关联的治理告警 ID。
     */
    private Long alertId;

    /**
     * 关联的同步任务 ID。
     */
    private Long syncTaskId;

    /**
     * 第几次投递尝试。
     */
    private Integer attemptNo;

    /**
     * 本次尝试所走的通道。
     */
    private String channel;

    /**
     * 本次尝试结果状态。
     * 这里通常会记录 SENT、FAILED、SKIPPED 这类单次结果。
     */
    private String deliveryStatus;

    /**
     * 实际目标地址摘要。
     * 例如 webhook URL、内部日志通道标识等。
     */
    private String targetEndpoint;

    /**
     * 是否为人工触发投递。
     */
    private Boolean manualDispatch;

    /**
     * 触发本次投递的操作者 ID。
     */
    private Long operatorId;

    /**
     * 触发本次投递的操作者角色。
     */
    private String operatorRole;

    /**
     * 通道返回摘要或系统响应摘要。
     */
    private String responseSummary;

    /**
     * 失败原因摘要。
     */
    private String errorSummary;

    /**
     * 投递开始时间。
     */
    private LocalDateTime startedAt;

    /**
     * 投递结束时间。
     */
    private LocalDateTime finishedAt;

    /**
     * 创建时间。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}

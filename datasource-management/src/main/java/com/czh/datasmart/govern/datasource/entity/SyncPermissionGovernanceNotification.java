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
 * @Date: 2026/4/24 22:40
 * @Description DataSmart Govern Backend - SyncPermissionGovernanceNotification.java
 * @Version:1.0.0
 *
 * 权限治理通知实体。
 * 它承载的是权限变更审批链路中的“待办提醒”和“结果通知”，不是泛用消息中心。
 *
 * 当前设计目标：
 * 1. 让审批待办和审批结果变成可查询、可阅读、可追踪的持久对象；
 * 2. 区分“按角色派发的待审批通知”和“按具体人派发的审批结果通知”；
 * 3. 为后续接入站内消息、IM、邮件时保留稳定的通知数据骨架。
 */
@Data
@TableName("sync_permission_governance_notification")
public class SyncPermissionGovernanceNotification {

    /**
     * 通知主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 目标租户范围。
     * 为空语义在库里统一转成 0，由服务层负责解释成平台全局。
     */
    private Long tenantId;

    /**
     * 关联的权限变更申请 ID。
     */
    private Long changeRequestId;

    /**
     * 通知类型。
     */
    private String notificationType;

    /**
     * 具体接收人 ID。
     * 对于“审批结果通知”这类点对点消息通常会有值。
     */
    private Long recipientActorId;

    /**
     * 接收人角色。
     * 对于“待审批通知”这类按角色派发的待办，这个字段是主要查询入口。
     */
    private String recipientActorRole;

    /**
     * 通知通道。
     */
    private String notificationChannel;

    /**
     * 通知状态。
     */
    private String notificationStatus;

    /**
     * 通知标题摘要。
     */
    private String summary;

    /**
     * 通知详情。
     */
    private String detail;

    /**
     * 下一次允许尝试投递的时间。
     * 当前第一版基本会立即投递，这个字段主要为后续失败重试留出扩展位。
     */
    private LocalDateTime nextDispatchAt;

    /**
     * 已投递尝试次数。
     */
    private Integer dispatchAttemptCount;

    /**
     * 最近一次投递时间。
     */
    private LocalDateTime dispatchedAt;

    /**
     * 最近一次投递失败摘要。
     */
    private String lastDispatchError;

    /**
     * 读消息的人 ID。
     * 目前主要用于角色待办被谁确认阅读的追踪。
     */
    private Long readBy;

    /**
     * 已读时间。
     */
    private LocalDateTime readAt;

    /**
     * 创建时间。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

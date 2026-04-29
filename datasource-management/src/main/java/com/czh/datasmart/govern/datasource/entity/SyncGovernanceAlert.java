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
 * @Description DataSmart Govern Backend - SyncGovernanceAlert.java
 * @Version:1.0.0
 *
 * 同步治理告警实体。
 * 这个对象不是简单技术日志，而是给运营、管理员和后续 observability 模块消费的治理对象。
 *
 * 当前它承担两层职责：
 * 1. 风险对象：记录为什么告警、影响哪个租户或任务、当前是否已确认或解决；
 * 2. 投递 outbox：记录这条告警是否已对外投递、失败了几次、当前被哪个调度实例认领。
 *
 * 把告警对象直接作为 outbox 项处理的好处是：
 * - 风险状态和投递状态天然统一，不需要额外维护“业务告警表 + outbox 表”的双写一致性；
 * - 多实例调度时可以直接围绕同一条告警对象做认领、续租、失败恢复；
 * - 后续如果要做更复杂的外部通知中心，也能从这个聚合根平滑演进。
 */
@Data
@TableName("sync_governance_alert")
public class SyncGovernanceAlert {

    /**
     * 告警主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 租户 ID。
     * 平台级告警允许为空，在库里通常仍会由服务层按平台语义解释。
     */
    private Long tenantId;

    /**
     * 关联的同步任务 ID。
     */
    private Long syncTaskId;

    /**
     * 告警类型。
     */
    private String alertType;

    /**
     * 告警严重级别。
     */
    private String severity;

    /**
     * 告警处理状态，例如 OPEN、ACKNOWLEDGED、RESOLVED。
     */
    private String alertStatus;

    /**
     * 外部投递状态，例如 PENDING、SENT、FAILED、SKIPPED、DEAD_LETTER。
     */
    private String deliveryStatus;

    /**
     * 最近一次投递时使用的通道。
     */
    private String deliveryChannel;

    /**
     * 告警去重键。
     * 相同问题在短时间内持续发生时，优先刷新同一条告警，而不是无限新增。
     */
    private String alertKey;

    /**
     * 告警摘要。
     */
    private String summary;

    /**
     * 告警详情。
     */
    private String detail;

    /**
     * 告警来源资源。
     */
    private String sourceResource;

    /**
     * 触发本次告警的动作。
     */
    private String triggeredByAction;

    /**
     * 首次发生时间。
     */
    private LocalDateTime firstOccurredAt;

    /**
     * 最近一次再次发生时间。
     */
    private LocalDateTime lastOccurredAt;

    /**
     * 同类告警累计发生次数。
     */
    private Integer occurrenceCount;

    /**
     * 确认人 ID。
     */
    private Long acknowledgedBy;

    /**
     * 确认时间。
     */
    private LocalDateTime acknowledgedAt;

    /**
     * 解决人 ID。
     */
    private Long resolvedBy;

    /**
     * 解决时间。
     */
    private LocalDateTime resolvedAt;

    /**
     * 最近一次投递时间。
     */
    private LocalDateTime lastDeliveryAt;

    /**
     * 下一次允许重试投递的时间。
     */
    private LocalDateTime nextDeliveryAttemptAt;

    /**
     * 已尝试投递次数。
     */
    private Integer deliveryAttemptCount;

    /**
     * 最近一次投递失败摘要。
     */
    private String lastDeliveryError;

    /**
     * 死信时间。
     */
    private LocalDateTime deadLetteredAt;

    /**
     * 死信原因摘要。
     */
    private String deadLetterReason;

    /**
     * 当前由哪个调度实例认领这条 outbox 告警。
     * 多实例场景下，这个字段用于避免重复投递。
     */
    private String dispatchLeaseOwner;

    /**
     * 当前认领租约的过期时间。
     * 如果实例崩溃，其他实例可以在租约过期后重新认领。
     */
    private LocalDateTime dispatchLeaseExpireAt;

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

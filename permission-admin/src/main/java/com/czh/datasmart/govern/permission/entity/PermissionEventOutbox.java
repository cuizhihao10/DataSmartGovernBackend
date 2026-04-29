/**
 * @Author : Cui
 * @Date: 2026/04/27 20:10
 * @Description DataSmart Govern Backend - PermissionEventOutbox.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 权限事件 outbox 实体。
 *
 * <p>outbox 表解决的是一个经典生产一致性问题：
 * 权限策略已经写入数据库，但 Kafka 事件发送失败怎么办？
 *
 * <p>如果直接在业务事务里调用 Kafka，可能出现：
 * 1. 数据库提交成功，Kafka 发送失败，gateway 缓存没有失效；
 * 2. Kafka 发送成功，数据库事务回滚，消费者看到了并不存在的策略变更；
 * 3. 网络抖动导致调用方不知道事件到底有没有发出去。
 *
 * <p>outbox 模式把“业务事实”和“待发布事件”放进同一个数据库事务：
 * 1. 策略变更成功时，同时插入 outbox 记录；
 * 2. 定时投递器扫描 PENDING/FAILED 事件并发送 Kafka；
 * 3. 发送成功标记 SENT，失败记录错误并设置下次重试时间；
 * 4. 即使服务重启，未发送事件仍留在数据库中，可以继续补偿。
 */
@Data
@TableName("permission_event_outbox")
public class PermissionEventOutbox {

    /**
     * outbox 主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 事件 ID。
     *
     * <p>来自平台事件契约，消费者可用它做幂等或排障。
     */
    private String eventId;

    /**
     * 事件类型，例如 ROUTE_POLICY_CREATED。
     */
    private String eventType;

    /**
     * Kafka topic。
     */
    private String topic;

    /**
     * Kafka key。
     *
     * <p>当前使用 tenantId 作为 key，让同一租户的权限事件尽量进入同一分区，降低乱序概率。
     */
    private String eventKey;

    /**
     * 事件载荷 JSON。
     */
    private String payloadJson;

    /**
     * 事件状态：PENDING、SENDING、SENT、FAILED、DEAD、IGNORED。
     *
     * <p>IGNORED 不是自动投递器产生的状态，而是管理员在确认事件不再需要投递后手工标记。
     * 引入它的原因是：商业系统不能把“坏消息已超过重试次数”和“人工确认不再处理”混成同一个 DEAD 状态，
     * 否则后续看板和告警无法区分系统故障与人工处置结果。
     */
    private String status;

    /**
     * 已尝试发送次数。
     */
    private Integer attemptCount;

    /**
     * 最大尝试次数。
     */
    private Integer maxAttempts;

    /**
     * 下次允许重试时间。
     */
    private LocalDateTime nextRetryTime;

    /**
     * 最近一次错误信息。
     */
    private String lastError;

    /**
     * 发送成功时间。
     */
    private LocalDateTime sentTime;

    /**
     * 事件所属租户。
     */
    private Long tenantId;

    /**
     * 事件关联资源类型。
     */
    private String resourceType;

    /**
     * 事件关联资源 ID。
     */
    private String resourceId;

    /**
     * 链路追踪 ID。
     */
    private String traceId;

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

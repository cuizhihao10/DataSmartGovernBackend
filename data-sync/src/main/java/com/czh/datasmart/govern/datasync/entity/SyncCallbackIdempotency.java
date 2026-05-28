/**
 * @Author : Cui
 * @Date: 2026/05/08 23:02
 * @Description DataSmart Govern Backend - SyncCallbackIdempotency.java
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
 * data-sync 执行器回调与租约动作幂等记录。
 *
 * <p>同步执行器常见的重试场景包括：
 * 1. 执行器 complete/fail 已经被服务端处理，但 HTTP 响应丢失；
 * 2. checkpoint 写入成功，但调用方超时后重复上报；
 * 3. heartbeat/defer 因网络抖动重复提交；
 * 4. 运维恢复或自动恢复任务被重复触发。
 *
 * <p>如果没有幂等表，重复请求可能造成状态重复推进、错误样本重复、checkpoint 膨胀、恢复动作反复执行。
 * 本表使用 `tenantId + action + scopeKey + idempotencyKey` 唯一约束作为并发裁决点，
 * 让多个服务实例同时收到同一请求时，也只有一个事务可以继续推进业务状态。
 */
@Data
@TableName("data_sync_callback_idempotency")
public class SyncCallbackIdempotency {

    /** 幂等记录主键，仅用于内部更新和排障定位。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租户 ID，避免不同租户的相同幂等键互相影响。 */
    private Long tenantId;

    /** 同步任务 ID；恢复类全局动作可为空。 */
    private Long syncTaskId;

    /** 执行记录 ID；恢复类全局动作可为空。 */
    private Long executionId;

    /**
     * 作用域键。
     *
     * <p>示例：`taskId:executionId` 保护单次执行回调；`RECOVERY` 保护恢复动作。
     * 之所以不用多个可空字段直接做唯一键，是因为 MySQL 唯一索引允许多个 NULL 组合重复。
     */
    private String scopeKey;

    /** 动作编码，例如 START、CHECKPOINT、COMPLETE、FAIL、HEARTBEAT、DEFER、RECOVER_EXPIRED_LEASE。 */
    private String action;

    /** 调用方生成的幂等键，同一次业务动作重试必须复用同一个键。 */
    private String idempotencyKey;

    /** 执行器 ID；系统恢复类动作可为空或使用 SERVICE_ACCOUNT。 */
    private String executorId;

    /** 请求摘要，保存短文本用于排障，不保存完整大结果、SQL、密钥或敏感样本。 */
    private String requestDigest;

    /** 幂等处理状态：PROCESSING、SUCCEEDED、FAILED。 */
    private String callbackState;

    /** 首次成功处理后的响应摘要，便于重复请求复用首次处理语义。 */
    private String responseSummary;

    /** 失败摘要，为后续独立失败审计预留。 */
    private String errorMessage;

    /** 首次看到该幂等键的时间。 */
    private LocalDateTime firstSeenTime;

    /** 最近一次看到该幂等键的时间。 */
    private LocalDateTime lastSeenTime;

    /** 创建时间。 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间。 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

/**
 * @Author : Cui
 * @Date: 2026/05/07 21:38
 * @Description DataSmart Govern Backend - SyncErrorSample.java
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
 * 同步错误样本实体。
 *
 * <p>真实同步任务失败时，运营人员不只需要“失败了”三个字，还需要知道失败记录长什么样、错误是否可重试、
 * 是源端读取失败还是目标端写入失败、是否需要人工修复字段映射或数据内容。
 */
@Data
@TableName("data_sync_error_sample")
public class SyncErrorSample {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    /** 项目 ID，冗余自任务，用于项目级错误样本筛选和质量复盘。 */
    private Long projectId;
    /** 工作空间 ID，冗余自任务，用于空间级错误治理看板。 */
    private Long workspaceId;
    private Long syncTaskId;
    private Long executionId;

    /** 错误类型，例如 SOURCE_READ_ERROR、TARGET_WRITE_ERROR、TYPE_CONVERSION_ERROR。 */
    private String errorType;

    /** 外部系统或内部标准错误码。 */
    private String errorCode;

    /** 错误摘要。长堆栈应进入日志或对象存储，不应无限塞入业务表。 */
    private String errorMessage;

    /** 源端记录定位，例如主键、文件行号、Kafka topic/partition/offset。 */
    private String sourceRecordKey;

    /** 目标端记录定位，例如目标主键或写入批次。 */
    private String targetRecordKey;

    /** 脱敏并截断后的样本载荷。 */
    private String samplePayload;

    /** 是否可重试。 */
    private Boolean retryable;

    /**
     * 修复状态。QUARANTINED 表示经用户确认后在后续任务重试中跳过该精确记录，
     * 它不会物理删除或修改源端数据。
     */
    private String resolutionStatus;

    /** 结构化修复动作，例如 QUARANTINE_FOR_RETRY。 */
    private String resolutionAction;

    /** 用户确认说明的 SHA-256 摘要；禁止保存模型原文、SQL 或样本正文。 */
    private String resolutionNoteDigest;

    /** 执行修复确认的真实用户 actorId。 */
    private Long resolvedBy;

    /** 修复状态最后更新时间。 */
    private LocalDateTime resolvedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}

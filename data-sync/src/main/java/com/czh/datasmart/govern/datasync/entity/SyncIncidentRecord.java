/**
 * @Author : Cui
 * @Date: 2026/05/08 22:22
 * @Description DataSmart Govern Backend - SyncIncidentRecord.java
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
 * 同步事故记录实体。
 *
 * <p>事故记录不是错误样本，也不是审计记录：
 * 1. 错误样本描述某条数据、某个批次或某次外部调用为什么失败；
 * 2. 审计记录描述谁在什么时候做了什么动作；
 * 3. 事故记录描述一个需要运营跟踪的问题单，例如连接器连续崩溃、目标库长期限流、字段映射策略错误。
 *
 * <p>当前先在 data-sync 内部落一张轻量表，未来可以对接企业工单系统、告警平台或统一 incident-center。
 */
@Data
@TableName("data_sync_incident_record")
public class SyncIncidentRecord {

    /** 事故记录主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租户 ID，用于多租户隔离和运营台筛选。 */
    private Long tenantId;

    /** 项目 ID，用于项目级事故工作台、SLA 统计和项目负责人可见性控制。 */
    private Long projectId;

    /** 工作空间 ID，用于空间级事故筛选和多团队运营协作。 */
    private Long workspaceId;

    /** 关联同步任务 ID。 */
    private Long syncTaskId;

    /** 关联最近执行记录 ID，可为空，取决于任务是否已经产生 execution。 */
    private Long executionId;

    /** 事故类型，例如 EXECUTOR_UNSTABLE、CONNECTOR_FAILURE、TARGET_THROTTLED、CONFIGURATION_ERROR。 */
    private String incidentType;

    /** 严重级别，例如 P1、P2、P3、P4。 */
    private String severity;

    /** 事故状态，例如 OPEN、ACKNOWLEDGED、RESOLVED。 */
    private String incidentStatus;

    /** 事故标题，适合列表展示和通知摘要。 */
    private String title;

    /** 事故描述，保存脱敏后的排障说明，不放密钥、完整 SQL 或大样本数据。 */
    private String description;

    /** 创建事故的操作者 ID。 */
    private Long operatorId;

    /** 创建事故的操作者角色。 */
    private String operatorRole;

    /** 当前事故负责人 ID，用于运营分派、SLA 跟踪和“我的事故”列表。 */
    private Long assignedOperatorId;

    /** 当前事故负责人角色，后续可扩展为运营组、值班组或外部工单队列。 */
    private String assignedOperatorRole;

    /** 解决摘要，当前创建时为空，后续 resolve 事故时再填写。 */
    private String resolutionSummary;

    /** 事故被确认接手的时间。 */
    private LocalDateTime acknowledgedAt;

    /** 事故被标记解决的时间。 */
    private LocalDateTime resolvedAt;

    /** 事故被关闭的时间。 */
    private LocalDateTime closedAt;

    /** 创建时间。 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间。 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

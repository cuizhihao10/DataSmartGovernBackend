/**
 * @Author : Cui
 * @Date: 2026/05/07 21:38
 * @Description DataSmart Govern Backend - SyncAuditRecord.java
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
 * 数据同步审计记录实体。
 *
 * <p>数据同步属于高治理风险操作，尤其是导出、跨系统写入、回放和补数。
 * 审计记录用于回答“谁在什么时候对哪个同步对象做了什么动作，以及结果如何”。
 */
@Data
@TableName("data_sync_audit_record")
public class SyncAuditRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;
    private Long projectId;
    private Long workspaceId;

    /**
     * 同步模板 ID。
     *
     * <p>模板创建、模板校验、模板禁用等动作不一定关联具体任务。
     * 如果没有 templateId，模板级审计只能把模板 ID 塞进 actionPayload，后续很难按模板、项目或空间稳定检索。
     */
    private Long templateId;

    private Long syncTaskId;
    private Long executionId;
    private String actionType;
    private Long actorId;
    private String actorRole;
    private String actionPayload;
    private String result;
    private String traceId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}

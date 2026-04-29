/**
 * @Author : Cui
 * @Date: 2026/04/25 23:00
 * @Description DataSmart Govern Backend - PermissionAuditRecord.java
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
 * 权限审计记录实体。
 *
 * <p>权限域的任何高风险变更都应留下审计记录。
 * 当前先保存“谁、在哪个租户、对哪个资源、执行了什么动作、结果如何、详情是什么”，
 * 后续可以和 platform-common 的 PlatformAuditEvent、Kafka 审计事件、observability 告警中心统一。
 */
@Data
@TableName("permission_audit_record")
public class PermissionAuditRecord {

    /**
     * 审计主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 链路追踪 ID。
     */
    private String traceId;

    /**
     * 租户 ID。
     */
    private Long tenantId;

    /**
     * 操作者 ID。
     */
    private Long actorId;

    /**
     * 操作者角色。
     */
    private String actorRole;

    /**
     * 资源类型。
     */
    private String resourceType;

    /**
     * 资源 ID。
     */
    private String resourceId;

    /**
     * 动作名称。
     */
    private String action;

    /**
     * 操作结果，例如 SUCCESS、FAILED、DENIED。
     */
    private String result;

    /**
     * 审计摘要。
     */
    private String summary;

    /**
     * 结构化详情 JSON。
     */
    private String detailJson;

    /**
     * 创建时间。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}

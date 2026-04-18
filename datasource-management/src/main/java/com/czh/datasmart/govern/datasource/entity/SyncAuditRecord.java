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
 * @Date: 2026/4/18 23:12
 * @Description DataSmart Govern Backend - SyncAuditRecord.java
 * @Version:1.0.0
 *
 * 同步审计记录实体。
 * 审计记录不是“可有可无的日志增强”，而是企业产品必须具备的治理基座。
 * 它主要服务于以下场景：
 * - 谁在什么时候创建或修改了同步配置；
 * - 谁审批了高风险同步任务；
 * - 谁做了强制取消、强制重试、优先级覆盖等管理员动作；
 * - 故障发生后，能否还原完整的人和系统操作轨迹。
 */
@Data
@TableName("sync_audit_record")
public class SyncAuditRecord {

    /**
     * 审计主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 租户标识。
     */
    private Long tenantId;

    /**
     * 关联任务 ID。
     */
    private Long syncTaskId;

    /**
     * 关联执行记录 ID。
     */
    private Long executionId;

    /**
     * 动作类型。
     */
    private String actionType;

    /**
     * 操作者 ID。
     */
    private Long actorId;

    /**
     * 操作者角色。
     */
    private String actorRole;

    /**
     * 动作载荷。
     * 当前阶段先保存为 JSON 字符串，便于快速落审计轨迹；
     * 后续如果审计中心独立，可以再做结构化事件模型。
     */
    private String actionPayload;

    /**
     * 创建时间。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}

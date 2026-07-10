/**
 * @Author : Cui
 * @Date: 2026/07/10 14:00
 * @Description DataSmart Govern Backend - PermissionTenant.java
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
 * 租户主数据实体。
 *
 * <p>租户代表使用 DataSmart 平台的一家公司，是应用、项目、数据源、同步任务和 Agent 数据的最高业务隔离边界。
 * 开租由平台超级管理员执行；暂停和关闭只改变生命周期状态并保留历史，不物理删除商业与审计事实。</p>
 */
@Data
@TableName("permission_tenant")
public class PermissionTenant {

    @TableId(value = "tenant_id", type = IdType.INPUT)
    private Long tenantId;

    private String tenantCode;

    private String tenantName;

    private String tenantType;

    private String planCode;

    private String status;

    private String defaultApplicationCode;

    private Long ownerActorId;

    private Long openedBy;

    private LocalDateTime openedAt;

    private String description;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

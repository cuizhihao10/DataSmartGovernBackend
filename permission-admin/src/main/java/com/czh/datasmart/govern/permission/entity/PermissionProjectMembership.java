/**
 * @Author : Cui
 * @Date: 2026/05/10 13:28
 * @Description DataSmart Govern Backend - PermissionProjectMembership.java
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
 * 用户项目授权关系实体。
 *
 * <p>数据范围策略中的 PROJECT 并不是一个抽象口号，它必须落到“某个用户到底能访问哪些项目”。
 * 该表保存 actor 与 project/workspace 的授权关系，是 permission-admin 物化 `${actorProjectIds}` 的基础。
 *
 * <p>为什么不把项目 ID 直接写死在数据范围策略里？
 * 1. 同一个角色下不同用户能访问的项目通常不同；
 * 2. 项目成员会随组织调整、项目交接、外包人员进出而变化；
 * 3. 数据范围策略应该描述“按项目收敛”，项目成员表负责回答“当前人有哪些项目”；
 * 4. 后续可以扩展项目角色、有效期、授权来源、审批单号和继承关系，而不用重写路由策略。
 */
@Data
@TableName("permission_project_membership")
public class PermissionProjectMembership {

    /**
     * 授权关系主键。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 租户 ID。
     *
     * <p>项目授权必须带租户边界，避免不同租户恰好使用相同 projectId 时发生权限串扰。
     */
    private Long tenantId;

    /**
     * 操作者 ID。
     *
     * <p>这里命名为 actorId 而不是 userId，是为了兼容后续服务账号、机器人账号或外部身份映射。
     */
    private Long actorId;

    /**
     * 项目 ID。
     *
     * <p>PROJECT 数据范围最终会把该字段集合透传给 data-sync，转换为 `project_id IN (...)`。
     */
    private Long projectId;

    /**
     * 工作空间 ID。
     *
     * <p>当前 data-sync 先按项目集合收敛，workspaceId 作为后续空间级权限和空间级看板的扩展字段保留。
     */
    private Long workspaceId;

    /**
     * 用户在项目内的角色。
     *
     * <p>例如 OWNER、MAINTAINER、VIEWER。当前先用于审计和未来扩展，后续可以让项目内角色影响审批、导出和事故处置。
     */
    private String projectRole;

    /**
     * 授权来源。
     *
     * <p>常见来源包括 MANUAL、IMPORT、IDP_GROUP、APPROVAL。记录来源有助于后续排查“为什么这个人能看到该项目”。
     */
    private String grantSource;

    /**
     * 是否启用。
     *
     * <p>成员离开项目时优先禁用而不是物理删除，可以保留历史审计线索。
     */
    private Boolean enabled;

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

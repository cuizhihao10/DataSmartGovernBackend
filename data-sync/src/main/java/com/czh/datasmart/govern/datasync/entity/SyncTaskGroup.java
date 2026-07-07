/**
 * @Author : Cui
 * @Date: 2026/07/07 22:25
 * @Description DataSmart Govern Backend - SyncTaskGroup.java
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
 * 同步任务分组持久化实体。
 *
 * <p>早期版本只把 groupCode/groupName 放在 data_sync_task 上，这适合快速完成列表过滤和导入导出，
 * 但无法支撑真实产品里的“分组树、创建分组、删除分组、默认分组、前端展开折叠、组级批量运营”等能力。
 * 本实体把“分组本身”升级为一个稳定资源，而任务表上的 groupCode/groupName 继续作为任务所属分组的快照字段，
 * 这样既能保留任务列表查询效率，也能让前端拥有清晰的分组菜单数据源。</p>
 *
 * <p>该表不保存任何源端/目标端连接信息、SQL、字段映射正文或样本数据，只表达任务运营组织关系。
 * 删除分组时也不会删除任务，而是把任务移动到默认分组，避免误操作影响正在运行或等待调度的同步任务。</p>
 */
@Data
@TableName("data_sync_task_group")
public class SyncTaskGroup {

    /**
     * 分组资源主键。
     *
     * <p>前端树形菜单可以用 id 做 React key，但跨环境导入导出、Agent 工具调用和 URL 参数仍应使用 groupCode，
     * 因为数据库自增 ID 在不同环境之间不稳定。</p>
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 租户 ID。
     *
     * <p>同一个 groupCode 在不同租户下代表完全不同的业务分组，所有查询、创建、删除都必须落在租户边界内。</p>
     */
    private Long tenantId;

    /**
     * 项目 ID。
     *
     * <p>项目维度用于承接 permission-admin 的 PROJECT 数据范围。为空表示租户级分组；如果当前调用方是
     * PROJECT 范围角色，服务层会要求写入明确的 projectId，避免绕过项目授权边界。</p>
     */
    private Long projectId;

    /**
     * 工作空间 ID。
     *
     * <p>工作空间用于前端看板、团队协作和多环境隔离。分组树按 tenant/project/workspace 三元范围组织，
     * 避免不同工作空间的同名分组互相污染。</p>
     */
    private Long workspaceId;

    /**
     * 父分组编码。
     *
     * <p>这里使用 parentGroupCode 而不是 parentId，是为了让导入导出、跨环境迁移和 Agent 工具调用保持稳定。
     * 当前实现采用 Java 内存构树，避免 PostgreSQL/MySQL 递归 SQL 差异带来的迁移成本。</p>
     */
    private String parentGroupCode;

    /**
     * 分组稳定编码。
     *
     * <p>groupCode 是分组的自然业务键，服务层会统一规范化为大写，并限制字符集。前端选择分组、创建任务、
     * 删除分组、Agent 批量操作都应传 groupCode，而不是传展示名称。</p>
     */
    private String groupCode;

    /**
     * 分组展示名称。
     *
     * <p>允许中文，用于左侧导航栏和中间分组菜单栏展示。展示名可以被运营人员调整，不作为唯一键。</p>
     */
    private String groupName;

    /**
     * 分组说明。
     *
     * <p>用于解释该分组的业务含义，例如“订单域离线同步”“客户迁移第三批”。说明会进入低敏运营视图，
     * 不应保存 SQL、连接串、密码、样本数据或完整字段映射。</p>
     */
    private String description;

    /**
     * 展示排序值。
     *
     * <p>前端可以按 displayOrder、groupName、updateTime 综合排序。默认分组固定为 0，业务分组通常从 100 开始，
     * 给后续拖拽排序预留空间。</p>
     */
    private Integer displayOrder;

    /**
     * 是否默认分组。
     *
     * <p>每个 tenant/project/workspace 范围内必须存在一个默认分组。创建任务时未传 groupCode 会进入默认分组；
     * 删除普通分组时，任务也会自动回落到默认分组。</p>
     */
    private Boolean defaultGroup;

    /**
     * 是否归档。
     *
     * <p>当前删除采用逻辑归档，便于保留审计证据。归档分组不会出现在默认树形菜单中，也不能被新任务选择。</p>
     */
    private Boolean archived;

    /**
     * 创建人 ID。
     */
    private Long createdBy;

    /**
     * 最近更新人 ID。
     */
    private Long updatedBy;

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

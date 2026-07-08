/**
 * @Author : Cui
 * @Date: 2026/07/08 23:10
 * @Description DataSmart Govern Backend - PermissionProject.java
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
 * 项目主数据实体。
 *
 * <p>当前产品层级已经收敛为“租户 -> 项目 -> 数据源/同步任务/质量规则/Agent 会话”。
 * 因此项目不再只是初始化脚本里的一条样例数据，而应该成为用户可创建、可切换、可授权、可审计的一等控制面资源。</p>
 *
 * <p>为什么仍然保留 applicationId：
 * DataSmart 平台未来可能在同一租户下同时提供 FlashSync、数据质量、资产目录、合规脱敏等多个产品入口。
 * application_id 在这里作为“项目所属产品应用”的内部绑定，不再要求普通用户在页面上理解或填写。
 * 前端只需要展示 projectName/projectCode，后端在创建时会自动选择当前租户的默认应用。</p>
 *
 * <p>为什么仍然存在 defaultWorkspaceId：
 * 数据库历史模型曾经存在 workspace 层级，Agent 内部工作区、旧执行事实和审计记录也可能还引用 workspace。
 * 新建项目不再设置该字段，正式页面也不展示该字段；保留列只是为了兼容迁移期数据，而不是继续把工作空间作为用户产品层级。</p>
 */
@Data
@TableName("permission_project")
public class PermissionProject {

    /**
     * 项目数字 ID。
     *
     * <p>该 ID 会被 gateway 注入为 X-DataSmart-Project-Id，也会写入 datasource_config.project_id、
     * data_sync_template.project_id、data_sync_task.project_id 等业务表，用于项目级数据隔离。</p>
     */
    @TableId(value = "project_id", type = IdType.INPUT)
    private Long projectId;

    /**
     * 租户 ID。
     *
     * <p>项目必须属于某个租户。即使两个租户都创建了同名项目，它们也应通过 tenantId 完全隔离。</p>
     */
    private Long tenantId;

    /**
     * 所属应用 ID。
     *
     * <p>这是后端内部产品应用绑定，普通用户不需要手填。创建项目时如果请求未指定 applicationId，
     * 服务层会读取当前租户默认可用应用，例如 FlashSync。</p>
     */
    private Long applicationId;

    /**
     * 项目稳定编码。
     *
     * <p>编码适合进入 URL、导入导出文件、审计日志、Agent 工具参数和脚本。为空创建时服务层会生成 PROJECT_{projectId}。</p>
     */
    private String projectCode;

    /**
     * 项目展示名称。
     *
     * <p>这是前端项目切换器、数据源列表、同步任务归属和审计页面最常用的用户可读名称。</p>
     */
    private String projectName;

    /**
     * 项目类型。
     *
     * <p>当前默认 DATA_GOVERNANCE。后续可扩展为 SANDBOX、PRODUCTION、CUSTOMER_DELIVERY 等，
     * 用于决定默认权限、容量额度、审批策略和同步任务执行边界。</p>
     */
    private String projectType;

    /**
     * 项目生命周期状态。
     *
     * <p>ACTIVE 表示可用，DISABLED 表示暂不可创建新资源，ARCHIVED 表示归档隐藏。
     * 当前第一版只实现创建和查询，后续编辑/下线/归档会继续复用该字段。</p>
     */
    private String status;

    /**
     * 历史默认工作空间 ID。
     *
     * <p>新建项目保持为空。它仅服务历史数据、旧执行事实和 Agent 内部兼容，不再作为页面层级展示。</p>
     */
    private Long defaultWorkspaceId;

    /**
     * 默认负责人 actorId。
     *
     * <p>项目创建后会给负责人自动写入 OWNER 成员关系，确保创建者能立刻在项目切换器中看到新项目。</p>
     */
    private Long ownerActorId;

    /**
     * 项目描述。
     *
     * <p>建议记录业务域、维护人、用途、数据敏感等级或迁移目标等低敏说明，便于后续审计和运营排障。</p>
     */
    private String description;

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

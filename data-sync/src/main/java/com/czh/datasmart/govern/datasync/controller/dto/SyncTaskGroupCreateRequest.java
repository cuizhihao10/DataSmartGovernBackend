/**
 * @Author : Cui
 * @Date: 2026/07/07 22:46
 * @Description DataSmart Govern Backend - SyncTaskGroupCreateRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建同步任务分组请求。
 *
 * <p>该 DTO 面向前端分组菜单栏中的“加号”入口，也面向后续 Agent 工具的 createTaskGroup 能力。
 * 分组不是单纯的 UI 分类，而是任务运营、批量导入导出、组级调度、告警聚合和权限审计都会引用的稳定资源，
 * 因此请求中必须携带稳定 groupCode，而不是只传一个展示名称。</p>
 */
@Data
public class SyncTaskGroupCreateRequest {

    /**
     * 分组所属租户。
     *
     * <p>为空时服务端按当前操作者上下文解析。非平台管理员/服务账号不能为其它租户创建分组。</p>
     */
    private Long tenantId;

    /**
     * 分组所属项目。
     *
     * <p>如果当前权限范围是 PROJECT，创建分组时必须明确 projectId，避免项目负责人创建租户级分组从而绕开项目边界。</p>
     */
    private Long projectId;

    /**
     * 分组所属工作空间。
     *
     * <p>工作空间为空表示项目级或租户级分组；有值时只在该工作空间菜单内展示和选择。</p>
     */
    private Long workspaceId;

    /**
     * 父分组编码。
     *
     * <p>为空表示一级分组；非空时父分组必须已经存在且不能是已归档分组。使用编码而不是父 ID，
     * 是为了导入导出、跨环境迁移和 Agent 工具调用时保持稳定。</p>
     */
    @Size(max = 64, message = "父分组编码不能超过 64 个字符")
    private String parentGroupCode;

    /**
     * 分组稳定编码。
     *
     * <p>服务端会统一转换为大写，并限制为字母、数字、下划线、短横线、点号和冒号。
     * 该编码用于创建任务时的 groupCode、删除分组时的路径参数，以及未来组级批量操作。</p>
     */
    @NotBlank(message = "分组编码不能为空")
    @Size(max = 64, message = "分组编码不能超过 64 个字符")
    private String groupCode;

    /**
     * 分组展示名称。
     *
     * <p>展示名称可以使用中文，前端左侧导航栏和中间分组菜单栏会展示该字段；如果为空，服务端使用 groupCode 兜底。</p>
     */
    @Size(max = 128, message = "分组名称不能超过 128 个字符")
    private String groupName;

    /**
     * 分组说明。
     *
     * <p>说明只保存低敏业务解释，例如“订单域每小时同步任务”。不要写连接串、SQL、密码、token 或样本数据。</p>
     */
    @Size(max = 500, message = "分组说明不能超过 500 个字符")
    private String description;

    /**
     * 展示排序。
     *
     * <p>前端可按 displayOrder 排序后再按名称兜底排序。为空时服务端默认使用 100。</p>
     */
    private Integer displayOrder;
}

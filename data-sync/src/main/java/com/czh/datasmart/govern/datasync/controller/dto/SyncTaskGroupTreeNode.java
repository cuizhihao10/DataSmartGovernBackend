/**
 * @Author : Cui
 * @Date: 2026/07/07 22:47
 * @Description DataSmart Govern Backend - SyncTaskGroupTreeNode.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 同步任务分组树节点。
 *
 * <p>该 DTO 专门服务前端左侧菜单导航栏和内容页中间分组菜单栏。前端可以用 {@link #children} 判断是否展示展开/折叠箭头，
 * 用 {@link #defaultGroup} 高亮默认分组，用任务计数字段展示分组徽标，用 {@link #legacyOnly} 提示历史任务中存在尚未显式创建的分组。</p>
 */
@Data
public class SyncTaskGroupTreeNode {

    /**
     * 分组资源 ID。
     *
     * <p>历史任务聚合出来但尚未创建分组资源的 legacy 节点可能没有 ID，前端此时应使用 groupCode 作为 key。</p>
     */
    private Long id;

    private Long tenantId;
    private Long projectId;
    private Long workspaceId;

    /**
     * 父分组编码。
     */
    private String parentGroupCode;

    /**
     * 分组稳定编码。
     */
    private String groupCode;

    /**
     * 分组展示名称。
     */
    private String groupName;

    /**
     * 分组说明。
     */
    private String description;

    /**
     * 展示排序。
     */
    private Integer displayOrder;

    /**
     * 是否默认分组。
     */
    private Boolean defaultGroup;

    /**
     * 是否仅由历史任务聚合出来。
     *
     * <p>legacyOnly=true 表示 data_sync_task 中存在 groupCode，但 data_sync_task_group 尚未有对应资源。
     * 这样能兼容旧版本数据，同时提醒后续可通过创建分组资源把它纳入正式菜单管理。</p>
     */
    private Boolean legacyOnly;

    private Long taskCount;
    private Long activeTaskCount;
    private Long scheduledTaskCount;
    private Long runningTaskCount;
    private Long failedTaskCount;
    private Long recycledTaskCount;
    private LocalDateTime lastUpdateTime;

    /**
     * 子分组。
     *
     * <p>前端展开/折叠只需要维护客户端 expandedKeys；后端始终返回完整树，避免每次展开节点都重新请求。</p>
     */
    private List<SyncTaskGroupTreeNode> children = new ArrayList<>();
}

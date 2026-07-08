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
     * <p>历史任务聚合出来但尚未创建分组资源的 legacy 节点可能没有 ID。旧版本前端通常会退化使用 groupCode 做 key，
     * 但 groupCode 只在同一个 tenant/project 展示作用域内唯一，跨项目查询时会发生 DEFAULT 等编码重复。
     * 新前端应优先使用 {@link #treeKey}，把 id 只作为资源详情或调试字段。</p>
     */
    private Long id;

    /**
     * 前端树节点稳定唯一键。
     *
     * <p>生成规则由后端统一维护，当前语义等价于 tenantId/projectId/groupCode 的组合键。
     * 它解决的是“多个项目都存在 DEFAULT 时，前端不能再只拿 groupCode 当 React/Vue tree key”的问题。
     * 该字段只用于展示树、选中态、展开折叠态和诊断定位；写接口仍继续传 groupCode 以及必要的租户/项目范围。</p>
     */
    private String treeKey;

    /**
     * 父节点稳定唯一键。
     *
     * <p>当节点存在 parentGroupCode 时，后端会在相同 tenant/project 展示作用域内生成父节点 treeKey。
     * 前端通常可以直接使用 children 渲染树，不需要 parentTreeKey；但在虚拟树、扁平化列表、拖拽排序或调试父子关系时，
     * 该字段可以避免前端重新拼接父节点作用域。</p>
     */
    private String parentTreeKey;

    private Long tenantId;
    private Long projectId;
    private Long workspaceId;

    /**
     * 分组作用域类型。
     *
     * <p>可能值包括 GLOBAL、TENANT、PROJECT。它说明同一个 groupCode 到底属于哪个业务边界。
     * 数据库里历史 workspace 分组会在后端读路径归一化到项目级展示，避免前端继续暴露工作空间层级。</p>
     */
    private String scopeType;

    /**
     * 分组作用域展示标签。
     *
     * <p>这是面向用户和调试的低敏说明，例如“项目级：租户 10 / 项目 101”。
     * 当前端选择不聚合多个 DEFAULT 节点时，可以把该字段作为 tooltip、次级说明或同名节点后缀来源。</p>
     */
    private String scopeLabel;

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
     *
     * <p>保留原始业务名称，兼容旧前端和导入导出逻辑。新前端实际渲染时应优先使用 {@link #displayName}：
     * 当同一批返回结果中存在多个“默认分组”时，displayName 会自动补充范围后缀，避免用户看到无法区分的重复菜单。</p>
     */
    private String groupName;

    /**
     * 前端推荐展示名称。
     *
     * <p>displayName 是后端已经结合重复检测和作用域语义处理过的名称。
     * 例如只返回一个 DEFAULT 时它仍是“默认分组”；如果同一批响应中跨项目返回多个 DEFAULT，
     * 它会变成“默认分组（项目 101）”“默认分组（项目 102）”这类可区分名称。</p>
     */
    private String displayName;

    /**
     * 前端推荐展示路径。
     *
     * <p>displayPath 用来说明节点在“作用域 + 分组树”中的完整位置，例如“项目级：租户 10 / 项目 101 / 默认分组”。
     * 它适合用于面包屑、下拉框选中项、搜索结果、Agent 工具确认语句和问题排查日志。</p>
     */
    private String displayPath;

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

    /**
     * 当前节点及全部子节点的任务总数。
     *
     * <p>taskCount 只表示“直接归属到当前 groupCode 的任务数”；subtreeTaskCount 表示“当前分组树分支的汇总任务数”。
     * 前端左侧“全部同步任务”数量应优先汇总根节点的 subtreeTaskCount，而不是使用当前任务列表分页 total，
     * 否则在搜索、状态筛选或分页后会把全局统计错误地显示成当前筛选结果。</p>
     */
    private Long subtreeTaskCount;

    /**
     * 当前节点及全部子节点的活跃任务数。
     */
    private Long subtreeActiveTaskCount;

    /**
     * 当前节点及全部子节点的等待调度任务数。
     */
    private Long subtreeScheduledTaskCount;

    /**
     * 当前节点及全部子节点的运行中任务数。
     */
    private Long subtreeRunningTaskCount;

    /**
     * 当前节点及全部子节点的失败或待人工介入任务数。
     */
    private Long subtreeFailedTaskCount;

    /**
     * 当前节点及全部子节点的回收站任务数。
     */
    private Long subtreeRecycledTaskCount;

    private LocalDateTime lastUpdateTime;

    /**
     * 子分组。
     *
     * <p>前端展开/折叠只需要维护客户端 expandedKeys；后端始终返回完整树，避免每次展开节点都重新请求。</p>
     */
    private List<SyncTaskGroupTreeNode> children = new ArrayList<>();
}

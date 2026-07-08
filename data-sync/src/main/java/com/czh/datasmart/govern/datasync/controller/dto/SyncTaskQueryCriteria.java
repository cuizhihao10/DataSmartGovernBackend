/**
 * @Author : Cui
 * @Date: 2026/05/07 21:28
 * @Description DataSmart Govern Backend - SyncTaskQueryCriteria.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

/**
 * 同步任务查询条件。
 *
 * <p>该 record 对应任务列表、回收站列表、Agent 工具查询和运营台筛选。字段设计上遵循两条原则：</p>
 * <p>1. tenant/project 先表达数据范围，真正是否可见仍由服务层结合 actorContext 和 permission-admin
 * 下沉的数据范围做二次收口，不能只相信前端传参；</p>
 * <p>2. template/owner/group/state/approval/trigger 是业务筛选维度，适合前端列表、导入导出校验和 Agent
 * “帮我找出订单域失败任务”这类自然语言查询落到结构化参数。</p>
 *
 * @param tenantId 请求希望查询的租户；普通用户不能用它扩大到其它租户
 * @param projectId 项目过滤条件；PROJECT 数据范围下还会叠加 authorizedProjectIds
 * @param workspaceId 历史兼容字段。用户侧任务列表、分组、回收站和导出接口已经不再接收 workspace 过滤；
 *                    该字段仅保留给旧导入导出文件、内部 worker 或历史调用点，新的 Controller 会统一传 null。
 * @param templateId 来源模板过滤条件
 * @param ownerId 负责人过滤条件
 * @param groupCode 任务分组编码过滤条件，服务层会规范化为大写稳定编码
 * @param currentState 任务主状态过滤条件，例如 SCHEDULED、RUNNING、RECYCLED
 * @param approvalState 审批状态过滤条件
 * @param triggerType 最近触发方式过滤条件，例如 MANUAL、SCHEDULED
 * @param current 当前页码
 * @param size 每页条数
 * @param keyword 列表搜索关键字，用于任务名称、分组、状态和运行模式的轻量模糊匹配
 */
public record SyncTaskQueryCriteria(
        Long tenantId,
        Long projectId,
        Long workspaceId,
        Long templateId,
        Long ownerId,
        String groupCode,
        String currentState,
        String approvalState,
        String triggerType,
        Long current,
        Long size,
        String keyword
) {
    public SyncTaskQueryCriteria(Long tenantId,
                                 Long projectId,
                                 Long workspaceId,
                                 Long templateId,
                                 Long ownerId,
                                 String groupCode,
                                 String currentState,
                                 String approvalState,
                                 String triggerType,
                                 Long current,
                                 Long size) {
        this(tenantId, projectId, workspaceId, templateId, ownerId, groupCode,
                currentState, approvalState, triggerType, current, size, null);
    }
}

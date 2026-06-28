/**
 * @Author : Cui
 * @Date: 2026/04/27 22:20
 * @Description DataSmart Govern Backend - TaskCreateRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.integration.task;

import lombok.Data;

/**
 * 调用 task-management 创建任务接口的本地请求模型。
 *
 * <p>字段与 task-management 的 CreateTaskRequest 对齐，但定义在 data-quality 模块内。
 * 这样可以避免两个微服务在编译期直接依赖对方内部 DTO。
 */
@Data
public class TaskCreateRequest {

    private String name;

    private String description;

    private String type;

    /**
     * task-management 创建阶段幂等键。
     *
     * <p>data-quality 不自己解释该键，也不会把它写入质量 payload；它只是把上游传入的低敏机器标识
     * 透传给 task-management，让任务中心在跨服务重试、补偿和并发提交时能够复用同一条任务。</p>
     *
     * <p>典型来源包括 Agent command idempotencyKey、外部工单号或批处理提交 ID。该字段不能包含
     * SQL、prompt、异常样本、工具参数正文、凭据、内部 URL 或模型输出。</p>
     */
    private String idempotencyKey;

    /**
     * 质量任务所属租户 ID。
     *
     * <p>该字段会映射到 task-management 的 task.tenant_id。
     * data-quality 自身的 payload 里也会保存 tenantId，但顶层字段更利于任务中心直接做列表过滤、
     * 队列公平调度、租户级配额和权限数据范围控制，避免每次都解析 JSON payload。
     */
    private Long tenantId;

    /**
     * 质量任务负责人 ID。
     *
     * <p>当前如果没有明确负责人，先使用任务管理集成配置中的服务账号 ID。
     * 后续质量规则补齐 owner 后，可以把规则负责人写入这里，让失败通知和待办归属更准确。
     */
    private Long ownerId;

    /**
     * 质量任务所属项目 ID。
     *
     * <p>当前质量规则模型还没有项目字段，因此先保留为空。
     * 后续数据资产、项目空间或治理工作区落地后，可以把规则所属项目同步到任务中心。
     */
    private Long projectId;

    private String params;

    private String priority;

    private Integer maxRetryCount;
}

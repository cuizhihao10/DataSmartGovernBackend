package com.czh.datasmart.govern.task.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/04/27 01:10
 * @Description DataSmart Govern Backend - TaskExecutionClaimRequest.java
 * @Version:1.0.0
 *
 * 执行器认领任务请求。
 *
 * <p>执行器不是直接把任务 ID 改成 RUNNING，而是向任务中心申请认领下一条可执行任务。
 * 任务中心负责按优先级、类型、状态和租约规则返回任务，这样才能在多执行器场景下避免重复执行。
 */
@Data
public class TaskExecutionClaimRequest {

    /**
     * 执行器实例 ID。
     *
     * <p>必须稳定且可排障，例如 worker-01、pod 名、agent-runtime 实例 ID。
     */
    @NotBlank(message = "执行器 ID 不能为空")
    private String executorId;

    /**
     * 执行器希望领取的任务类型。
     *
     * <p>为空表示领取任意类型；有值时只领取对应类型任务。
     */
    private String taskType;

    /**
     * 执行器希望认领的租户范围。
     *
     * <p>该字段服务于未来的“租户公平调度”和“租户级 worker 池”：
     * - 平台级 worker 可以不传，表示从所有允许范围内认领；
     * - 租户专属 worker 可以传 tenantId，只消费本租户任务；
     * - 当某个租户进入限流或维护窗口时，调度器也可以按 tenantId 暂停或迁移消费。
     *
     * <p>权限边界仍由服务层结合 actorContext 判断，不能仅依赖请求体。
     */
    @Min(value = 0, message = "租户 ID 不能小于 0")
    private Long tenantId;

    /**
     * 执行器希望认领的负责人范围。
     *
     * <p>常规 worker 通常不需要按负责人消费；但在人工补偿、VIP 客户保障、
     * 专属运维通道等场景中，按负责人或服务账号过滤可以减少误认领和资源争抢。
     */
    @Min(value = 0, message = "负责人 ID 不能小于 0")
    private Long ownerId;

    /**
     * 执行器希望认领的项目范围。
     *
     * <p>项目级认领为后续“项目专属执行器”“项目级并发配额”“项目维护窗口”
     * 预留调度入口。当前先作为轻量过滤条件落地，避免未来再改执行器协议。
     */
    @Min(value = 0, message = "项目 ID 不能小于 0")
    private Long projectId;

    /**
     * 租约秒数。
     *
     * <p>执行器需要在租约到期前持续心跳续租；如果长时间不续租，系统会认为执行器失联。
     */
    @Min(value = 10, message = "租约秒数不能小于 10")
    @Max(value = 3600, message = "租约秒数不能大于 3600")
    private Long leaseSeconds;
}

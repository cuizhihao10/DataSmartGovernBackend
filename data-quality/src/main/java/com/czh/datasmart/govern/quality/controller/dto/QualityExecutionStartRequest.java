/**
 * @Author : Cui
 * @Date: 2026/04/27 22:23
 * @Description DataSmart Govern Backend - QualityExecutionStartRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 质量检测执行器开始执行回调请求。
 *
 * <p>当 task-management 中的 `DATA_QUALITY_SCAN` 任务被某个质量执行器认领后，
 * 执行器应先调用本接口创建 `quality_check_execution` 记录。
 * 这样做的核心价值是把“任务中心的一次运行”和“质量域的一次检测动作”绑定起来：
 * 1. task-management 负责排队、认领、心跳、超时和重试；
 * 2. data-quality 负责规则快照、检测执行、报告和异常证据；
 * 3. 两边通过 taskId、taskRunId、executorId 建立可追踪关系。
 *
 * <p>注意：这里不把扫描计划拆成大量字段，而是允许传入快照 JSON。
 * 原因是扫描计划会随着关系型、Kafka、文件、API 等连接器不断演进，
 * 使用快照字段能保证历史执行可解释，同时避免 execution 表频繁改列。
 */
@Data
public class QualityExecutionStartRequest {

    /**
     * 质量规则 ID。
     *
     * <p>执行器虽然通常能从任务 payload 中拿到规则快照，但仍必须传 ruleId，
     * 因为 data-quality 需要校验规则是否存在、是否已删除、是否仍处于可执行状态。
     */
    @NotNull(message = "质量规则 ID 不能为空")
    private Long ruleId;

    /**
     * task-management 中的任务 ID。
     *
     * <p>这是跨模块追踪的主关联字段。质量运营人员看到一条质量执行记录后，
     * 后续可以通过 taskId 反查任务队列、重试次数、执行器心跳和运维干预日志。
     */
    @NotNull(message = "任务 ID 不能为空")
    private Long taskId;

    /**
     * task-management 中的单次执行记录 ID。
     *
     * <p>同一个任务可能因为失败、超时或人工重试产生多次 run。
     * taskRunId 用于精确绑定“第几次运行”产生了这份质量报告。
     */
    private Long taskRunId;

    /**
     * 执行器实例 ID。
     *
     * <p>真实生产环境通常会有多个执行器实例并发消费质量任务。
     * 保存 executorId 可以帮助排查某个实例是否频繁失败、扫描过慢或版本异常。
     */
    @NotBlank(message = "执行器 ID 不能为空")
    @Size(max = 128, message = "执行器 ID 长度不能超过 128 个字符")
    private String executorId;

    /**
     * 扫描计划快照。
     *
     * <p>建议传入任务 payload 中的 scanPlan JSON 字符串。
     * data-quality 不在这里重新生成计划，是为了保证报告解释的是“当时实际执行的计划”，
     * 而不是规则或配置被修改后的最新计划。
     */
    private String scanPlanSnapshot;

    /**
     * 开始执行时的补充说明。
     *
     * <p>例如执行器版本、连接器类型、分片号、调度原因等。
     */
    @Size(max = 1000, message = "执行说明长度不能超过 1000 个字符")
    private String message;
}

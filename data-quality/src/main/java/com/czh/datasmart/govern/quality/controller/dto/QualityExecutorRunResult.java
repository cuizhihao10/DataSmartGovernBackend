/**
 * @Author : Cui
 * @Date: 2026/04/28 19:52
 * @Description DataSmart Govern Backend - QualityExecutorRunResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 质量执行器 coordinator 单次运行结果。
 *
 * <p>该 DTO 不是质量检测报告，而是“执行器调度过程”的运行摘要。
 * 它用于手动触发 `/quality-rules/executor/coordinator/run-once` 后，告诉调用方：
 * 1. coordinator 是否启用；
 * 2. 有没有从 task-management 认领到任务；
 * 3. payload 是否解析成功；
 * 4. 是否创建了 data-quality execution；
 * 5. 最终是安全失败、未认领，还是因为配置关闭而跳过。
 *
 * <p>当前阶段真实扫描器尚未落地，所以该结果主要服务于本地联调、学习和执行闭环验证。
 */
@Data
public class QualityExecutorRunResult {

    /**
     * 本次 coordinator 是否处于启用状态。
     */
    private Boolean coordinatorEnabled;

    /**
     * 本次是否成功从 task-management 认领到任务。
     */
    private Boolean claimed;

    /**
     * task-management 任务 ID。
     */
    private Long taskId;

    /**
     * task-management 单次执行记录 ID。
     */
    private Long taskRunId;

    /**
     * data-quality 质量执行记录 ID。
     *
     * <p>只有 payload 解析成功且 data-quality start 回调成功后才会有值。
     */
    private Long qualityExecutionId;

    /**
     * 执行器实例 ID。
     */
    private String executorId;

    /**
     * payload schema 版本。
     */
    private String payloadSchemaVersion;

    /**
     * 扫描目标类型。
     */
    private String targetType;

    /**
     * 扫描策略。
     */
    private String scanStrategy;

    /**
     * 是否已向 task-management 发送过心跳。
     */
    private Boolean heartbeatSent;

    /**
     * task-management 是否已被标记为终态。
     */
    private Boolean taskFinalized;

    /**
     * data-quality execution 是否已被标记为终态。
     */
    private Boolean qualityExecutionFinalized;

    /**
     * 本次真实扫描生成的质量报告 ID。
     *
     * <p>只有执行器成功跑完 metric SQL，并调用 data-quality 成功回写报告后才会有值。
     */
    private Long reportId;

    /**
     * 本次扫描得到的实际观测值。
     */
    private BigDecimal measuredValue;

    /**
     * 本次扫描样本量。
     */
    private Integer sampleSize;

    /**
     * 本次扫描异常数量。
     */
    private Integer exceptionCount;

    /**
     * 本次运行结果编码。
     *
     * <p>建议值包括：
     * - DISABLED：执行器或集成开关关闭；
     * - NO_TASK：当前没有可认领任务；
     * - RELATIONAL_SCAN_SUCCEEDED：关系型受控扫描成功；
     * - UNSUPPORTED_SCAN：任务结构合法但当前扫描器尚不支持；
     * - FAILED_TO_PROCESS：处理链路出现真实异常；
     * - THROTTLED_DEFERRED：执行器并发护栏触发，任务已延迟回队列；
     * - THROTTLED_DEAD_LETTER：执行器并发护栏反复触发，task-management 已把任务移入死信状态。
     */
    private String outcome;

    /**
     * 面向运营和联调人员的结果说明。
     */
    private String message;

    /**
     * 失败原因摘要。
     */
    private String errorMessage;

    /**
     * 本次 coordinator 运行时间。
     */
    private LocalDateTime runTime;
}

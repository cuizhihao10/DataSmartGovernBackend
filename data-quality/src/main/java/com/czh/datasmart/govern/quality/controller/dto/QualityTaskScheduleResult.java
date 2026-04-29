/**
 * @Author : Cui
 * @Date: 2026/04/27 22:20
 * @Description DataSmart Govern Backend - QualityTaskScheduleResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.controller.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 质量检测任务提交结果。
 *
 * <p>它把两个层面的结果放在一起：
 * 1. scanPlan：本次准备如何扫描；
 * 2. taskId/taskStatus：是否已经进入 task-management 队列。
 *
 * <p>这样即使任务提交失败，调用方也能看到生成的计划和失败原因，便于调整参数或重试提交。
 */
@Data
public class QualityTaskScheduleResult {

    /**
     * 规则 ID。
     */
    private Long ruleId;

    /**
     * 任务是否已经提交到 task-management。
     */
    private Boolean submitted;

    /**
     * 是否为预演模式。
     */
    private Boolean dryRun;

    /**
     * task-management 返回的任务 ID。
     */
    private Long taskId;

    /**
     * task-management 返回的任务状态。
     */
    private String taskStatus;

    /**
     * task-management 返回的任务类型。
     */
    private String taskType;

    /**
     * 本次生成的扫描计划。
     */
    private QualityScanPlan scanPlan;

    /**
     * 结果说明。
     */
    private String message;

    /**
     * 提示或风险说明。
     */
    private List<String> warnings = new ArrayList<>();

    /**
     * 提交时间。
     */
    private LocalDateTime scheduledTime;
}

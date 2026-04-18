package com.czh.datasmart.govern.task.controller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:12
 * @Description DataSmart Govern Backend - CreateTaskRequest.java
 * @Version:1.0.0
 *
 * 创建任务请求体。
 * 这个对象是“外部请求契约”的一部分，作用不是承载数据库结构，
 * 而是描述创建任务时调用方允许提交哪些信息。
 *
 * 将 DTO 和实体分开，是后端分层里很重要的基本功：
 * 1. DTO 面向接口契约，控制输入边界。
 * 2. Entity 面向持久化结构，反映数据库表设计。
 * 3. 两者分离后，未来数据库字段变化不必强迫 API 一起变化。
 */
@Data
public class CreateTaskRequest {

    /**
     * 任务名称。
     * 这是任务中心列表页最核心的可读标识，因此要求非空。
     */
    @NotBlank(message = "任务名称不能为空")
    private String name;

    /**
     * 任务描述。
     * 用于补充任务背景、执行目标和业务说明，可为空。
     */
    private String description;

    /**
     * 任务类型。
     * 当前阶段先用字符串保持灵活，便于快速接入不同任务来源。
     * 后续当任务类型稳定后，可以升级为更明确的枚举或任务模板体系。
     */
    @NotBlank(message = "任务类型不能为空")
    private String type;

    /**
     * 任务参数。
     * 当前先使用 JSON 字符串承载可变结构，
     * 这样在项目早期可以减少频繁调整表结构和 DTO 的成本。
     */
    private String params;

    /**
     * 任务优先级。
     * 允许调用方传入 HIGH / MEDIUM / LOW，不传时服务层会自动归一为默认值。
     */
    private String priority;

    /**
     * 最大重试次数。
     * 如果调用方不传，服务层会按默认重试策略补齐。
     */
    @Min(value = 0, message = "最大重试次数不能小于 0")
    private Integer maxRetryCount;
}

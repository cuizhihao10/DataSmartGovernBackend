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
     * 请求方期望绑定的租户 ID。
     *
     * <p>注意：这个字段不是最终可信来源。真实生产环境里，租户 ID 应优先来自 gateway
     * 写入的 `X-DataSmart-Tenant-Id`，因为前端请求体可以被篡改。
     * 保留请求体字段的原因是服务账号、批量导入、历史脚本和本地联调有时需要代表某个租户创建任务。
     * 服务层会根据操作者角色决定是否采纳该值，避免普通用户跨租户创建任务。
     */
    @Min(value = 0, message = "租户 ID 不能小于 0")
    private Long tenantId;

    /**
     * 任务负责人 ID。
     *
     * <p>不传时服务层会优先使用当前操作者 ID 作为负责人。
     * 对商业化任务平台来说，负责人字段会直接影响“我的任务”、待办提醒、SLA 责任归属、
     * 失败任务通知和审计复盘，因此需要在创建任务时就写入，而不是只放在日志里。
     */
    @Min(value = 0, message = "负责人 ID 不能小于 0")
    private Long ownerId;

    /**
     * 项目 ID。
     *
     * <p>项目维度用于租户内部的二级隔离和运营视图，例如一个客户租户下可能同时有零售风控项目、
     * 数据仓库治理项目、AI Agent 自动化项目。任务绑定项目后，后续可以继续演进项目级权限、
     * 项目级队列配额、项目级看板和项目级成本统计。
     */
    @Min(value = 0, message = "项目 ID 不能小于 0")
    private Long projectId;

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

    /**
     * 最大连续延迟回队列次数。
     *
     * <p>该字段用于执行器背压场景，例如 worker 并发满、租户配额满或数据源配额满。
     * 如果任务连续 defer 超过该上限，任务中心会把它放入 DEAD_LETTER 并要求运营人员关注，
     * 而不是让它无限自动回到可认领队列。
     */
    @Min(value = 0, message = "最大连续延迟次数不能小于 0")
    private Integer maxDeferCount;
}

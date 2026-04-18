package com.czh.datasmart.govern.task.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:14
 * @Description DataSmart Govern Backend - Task.java
 * @Version:1.0.0
 *
 * 任务主表实体。
 * 这张表承载的是任务的“当前快照”，也就是系统此刻如何看待这一条任务：
 * - 当前状态是什么。
 * - 当前进度是多少。
 * - 是否还可以继续重试。
 * - 最近一次结果或失败原因是什么。
 *
 * 学习任务中心类系统时，一个非常重要的建模思想是：
 * - 主表负责保存“当前态”。
 * - 日志表负责保存“变化轨迹”。
 * 这样列表和详情查询会很高效，而追踪与审计也不会丢失历史过程。
 */
@Data
@TableName("task")
public class Task {

    /**
     * 主键 ID。
     * 当前使用数据库自增，足够支撑现阶段单库模式。
     * 后续如果演进到分布式 ID，也可以在这一层调整，不影响上层业务方法签名。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 任务名称。
     * 更偏向给人阅读，是任务列表中最直观的识别信息。
     */
    private String name;

    /**
     * 任务描述。
     * 用于补充任务背景、目标或执行说明。
     */
    private String description;

    /**
     * 任务类型。
     * 当前使用字符串，是为了在项目早期保持对不同任务来源的兼容性。
     */
    private String type;

    /**
     * 当前任务状态。
     * 取值约束由 TaskStatus 统一定义，Service 层负责维护合法流转。
     */
    private String status;

    /**
     * 任务参数。
     * 当前先保存为 JSON 字符串，使任务载荷能够快速适配不同业务场景。
     */
    private String params;

    /**
     * 当前执行进度，范围通常为 0-100。
     */
    private Integer progress;

    /**
     * 断点信息。
     * 主要用于暂停恢复、失败复盘和后续断点续跑能力。
     */
    private String checkpoint;

    /**
     * 任务优先级。
     * 当前只是基础调度语义，后续可以与真正的调度队列和资源优先策略结合。
     */
    private String priority;

    /**
     * 已重试次数。
     * 每次 retry 成功触发时递增，用于控制恢复次数边界。
     */
    private Integer retryCount;

    /**
     * 最大允许重试次数。
     * 这个字段的存在是为了防止任务在异常场景下无限重试，造成资源浪费或错误放大。
     */
    private Integer maxRetryCount;

    /**
     * 创建时间。
     * 使用 MyBatis-Plus 自动填充，减少样板代码。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间。
     * 只要任务快照发生变化，就应该刷新这个时间。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 开始执行时间。
     * 只有当任务真正进入 RUNNING 后才会有值。
     */
    private LocalDateTime startTime;

    /**
     * 执行结束时间。
     * 任务成功、失败或取消时通常会写入。
     */
    private LocalDateTime endTime;

    /**
     * 结果摘要或失败原因。
     * 它是主表里的“最新结果快照”，方便快速展示。
     * 更详细的变化细节则会写入执行日志表。
     */
    private String result;
}

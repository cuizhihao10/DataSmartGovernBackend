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
     * 租户 ID。
     *
     * <p>这是 task-management 从“单租户演示任务列表”走向“企业级多租户任务平台”的关键字段。
     * 后续 permission-admin 的数据范围策略下沉到业务模块时，最基础的判断就是：
     * 平台管理员可以跨租户查看，租户管理员、运营、审计和普通用户默认只能看到自己租户的数据。
     *
     * <p>该字段当前允许为空，是为了兼容项目早期已经创建的历史任务和本地联调脚本；
     * 新创建任务会优先从 gateway 透传的可信租户上下文中解析，服务账号批量提交时也可以显式指定目标租户。
     */
    private Long tenantId;

    /**
     * 负责人 ID。
     *
     * <p>负责人通常表示“谁拥有或主要关注这条任务”，它不一定等同于创建人：
     * - 普通用户创建的任务默认负责人是自己；
     * - 项目负责人可以代表项目创建任务，并把负责人设置给团队成员；
     * - 服务账号创建的质量检测、同步、AI Agent 任务可以把负责人设置为规则 owner 或项目 owner。
     *
     * <p>该字段为后续普通用户“只看我的任务”、项目负责人“看本项目任务”、运营人员“按负责人排障”提供查询基础。
     */
    private Long ownerId;

    /**
     * 项目 ID。
     *
     * <p>真实商业产品里，一个租户内部往往还有多个项目、业务线或工作空间。
     * 如果任务只绑定租户，不绑定项目，那么当租户规模变大后，任务列表、SLA 看板、队列积压治理都会变得很粗。
     * 保留 projectId 可以支持后续：
     * - 项目级任务看板；
     * - 项目级并发配额；
     * - 项目负责人审批；
     * - 项目级数据范围策略；
     * - 项目维度成本核算和审计导出。
     */
    private Long projectId;

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
     * 当前连续延迟回队列次数。
     *
     * <p>该字段专门用于 DEFERRED 背压场景，和 retryCount 不是同一个概念：
     * - retryCount 统计业务失败后的恢复尝试；
     * - deferCount 统计任务被执行器认领后，因为容量、配额或限流原因主动退避的次数。
     *
     * <p>为什么要放在任务主表：
     * 列表页和运营后台需要快速识别“是否有任务一直被退避”，如果只从日志表实时聚合会比较重。
     * 主表保存当前连续计数，历史细节仍然通过 task_execution_log 和 task_execution_run 追踪。
     */
    private Integer deferCount;

    /**
     * 最大允许连续延迟回队列次数。
     *
     * <p>达到上限后任务会进入 DEAD_LETTER，并设置 attentionRequired=true。
     * 这样可以阻止任务无限自动回队列，避免容量不足时形成“认领 -> 退避 -> 再认领”的隐性循环。
     */
    private Integer maxDeferCount;

    /**
     * 当前执行记录 ID。
     *
     * <p>当任务被执行器认领后，会创建一条 task_execution_run，并把该 run ID 回写到任务主表。
     * 这样任务详情页可以快速定位“当前正在跑的是哪一次执行”。
     */
    private Long currentExecutionRunId;

    /**
     * 当前执行器 ID。
     *
     * <p>用于展示和租约校验。执行器心跳时必须和该字段一致，避免其他实例误续租。
     */
    private String currentExecutorId;

    /**
     * 最近一次入队时间。
     *
     * <p>当前 PENDING 暂时等价于可调度队列，queuedTime 用于后续计算排队时长和队列积压。
     */
    private LocalDateTime queuedTime;

    /**
     * 最近一次执行器心跳时间。
     */
    private LocalDateTime heartbeatTime;

    /**
     * 当前执行租约过期时间。
     *
     * <p>如果超过这个时间没有收到心跳，系统可以判定执行器失联，并触发超时恢复。
     */
    private LocalDateTime leaseExpireTime;

    /**
     * 是否需要运营人员关注。
     *
     * <p>例如执行器心跳超时、重试次数耗尽、长时间排队，都可以把该字段置为 true。
     */
    private Boolean attentionRequired;

    /**
     * 任务执行超时时间，单位秒。
     *
     * <p>当前先用于默认租约和未来执行超时策略。不同任务类型后续可以配置不同默认值。
     */
    private Integer timeoutSeconds;

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

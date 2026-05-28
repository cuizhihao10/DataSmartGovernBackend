/**
 * @Author : Cui
 * @Date: 2026/04/30 00:18
 * @Description DataSmart Govern Backend - TaskQueueInspectionRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 任务队列运营视图查询条件。
 *
 * <p>该 DTO 服务于 `GET /tasks/operations/queue`。
 * 它与普通 `/tasks` 列表的定位不同：
 * - 普通列表更像业务对象查询，关注“有哪些任务”；
 * - 队列运营视图关注“哪些任务正在影响调度健康”，例如延迟回队列、死信、需要人工关注、排队过久。
 *
 * <p>为什么要单独建 DTO：
 * 1. 运维查询条件会越来越多，如果都散落在 Controller 的 @RequestParam 上，可读性会快速下降；
 * 2. DTO 可以把字段用途、时间格式、默认值和边界写清楚；
 * 3. 后续如果前端要复用这些条件构建运营控制台，也能直接对照这个契约。
 */
@Data
public class TaskQueueInspectionRequest {

    /**
     * 当前页码，从 1 开始。
     */
    @Min(value = 1, message = "页码不能小于 1")
    private Integer current = 1;

    /**
     * 每页数量。
     *
     * <p>运营视图可能被频繁刷新，因此限制最大 200，避免一次查询拉出过多任务影响数据库。
     */
    @Min(value = 1, message = "每页数量不能小于 1")
    @Max(value = 200, message = "每页数量不能超过 200")
    private Integer size = 20;

    /**
     * 任务状态过滤。
     *
     * <p>常用值包括 PENDING、RUNNING、DEFERRED、DEAD_LETTER、FAILED、PAUSED。
     * 如果不传，服务层默认只查询非成功/非取消的运营相关任务。
     */
    private String status;

    /**
     * 任务类型过滤。
     *
     * <p>例如 DATA_QUALITY_SCAN、DATA_SYNC、AGENT_JOB。
     * 该字段有助于运营人员区分是质量检测、同步任务还是未来 Agent Runtime 任务在积压。
     */
    private String type;

    /**
     * 租户过滤条件。
     *
     * <p>运维队列不是全局随便看的列表。平台管理员可以用该字段查看指定租户积压；
     * 租户管理员、运营、审计等角色即使传入其他 tenantId，服务层也会继续叠加当前操作者租户范围，
     * 从而保证“请求参数不能扩大权限，只能缩小查询范围”。
     */
    @Min(value = 0, message = "租户 ID 不能小于 0")
    private Long tenantId;

    /**
     * 负责人过滤条件。
     *
     * <p>用于排查某个用户或服务账号名下是否存在大量失败、延迟、死信任务。
     * 后续接入 notification-center 后，也可以用负责人维度计算待办和告警归属。
     */
    @Min(value = 0, message = "负责人 ID 不能小于 0")
    private Long ownerId;

    /**
     * 项目过滤条件。
     *
     * <p>项目维度适合真实企业租户内部的二级运营：例如只查看某个数据治理项目的任务积压，
     * 或比较不同项目的失败率、排队时长和资源消耗。
     */
    @Min(value = 0, message = "项目 ID 不能小于 0")
    private Long projectId;

    /**
     * 优先级过滤。
     *
     * <p>例如 HIGH、MEDIUM、LOW。真实生产排障时，HIGH 优先级任务长时间排队通常比 LOW 更需要关注。
     */
    private String priority;

    /**
     * 是否只看需要人工关注的任务。
     *
     * <p>DEAD_LETTER、租约超时、连续失败、长时间排队等场景都可以把 attentionRequired 置为 true。
     */
    private Boolean attentionRequired;

    /**
     * 当前执行器 ID。
     *
     * <p>用于排查某个 worker、Pod 或 Agent Runtime 实例是否持有过多 RUNNING 任务，
     * 或某个实例是否频繁把任务推入 DEFERRED/DEAD_LETTER。
     */
    private String currentExecutorId;

    /**
     * 最小连续退避次数。
     *
     * <p>例如传 3 可以筛出已经连续被执行器退避至少 3 次的任务，
     * 这类任务通常意味着容量、配额或下游依赖持续异常。
     */
    @Min(value = 0, message = "最小连续退避次数不能小于 0")
    private Integer deferCountAtLeast;

    /**
     * 只查询 queuedTime 早于该时间的任务。
     *
     * <p>时间格式建议使用 ISO 日期时间，例如 `2026-04-30T00:00:00`。
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime queuedBefore;

    /**
     * 只查询 queuedTime 晚于该时间的任务。
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime queuedAfter;

    /**
     * 查询排队时长已经超过多少秒的任务。
     *
     * <p>例如传 600 表示查询 queuedTime 距当前时间超过 10 分钟的任务。
     * 它比 queuedBefore 更适合运营台上的“排队超过 N 分钟”快捷筛选。
     */
    @Min(value = 1, message = "排队时长阈值不能小于 1 秒")
    private Long queuedOlderThanSeconds;

    /**
     * 是否包含明确终态任务。
     *
     * <p>默认 false。运营队列视图通常不关心 SUCCESS/CANCELLED，
     * 但事故复盘时可以打开该开关，把所有状态都纳入查询。
     */
    private Boolean includeTerminal;
}

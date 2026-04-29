package com.czh.datasmart.govern.task.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionClaimRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionClaimResult;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionHeartbeatRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskLeaseRecoveryResult;
import com.czh.datasmart.govern.task.controller.dto.TaskQueueInspectionRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskQueueItemView;
import com.czh.datasmart.govern.task.controller.dto.TaskQueueSummaryResponse;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.entity.TaskExecutionRun;
import com.czh.datasmart.govern.task.entity.TaskExecutionLog;

import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:14
 * @Description DataSmart Govern Backend - TaskService.java
 * @Version:1.0.0
 *
 * 任务服务接口。
 * 这一层的意义不是简单罗列 CRUD，而是把任务生命周期中“对外可操作的业务动作”显式表达出来。
 *
 * 也就是说，这个接口不是围绕数据库操作命名，
 * 而是围绕业务语义命名：
 * - createTask 表示登记任务。
 * - startTask 表示启动执行。
 * - retryTask 表示重新进入下一轮执行。
 * - listExecutionLogs 表示查询任务轨迹。
 *
 * 这样的接口设计更接近领域服务，也更容易让学习者理解“任务模块到底提供了哪些能力”。
 */
public interface TaskService extends IService<Task> {

    /**
     * 创建任务。
     */
    Task createTask(String name, String description, String type, String params, String priority,
                    Integer maxRetryCount, Integer maxDeferCount);

    /**
     * 启动任务。
     */
    boolean startTask(Long taskId);

    /**
     * 暂停任务。
     */
    boolean pauseTask(Long taskId);

    /**
     * 恢复任务。
     */
    boolean resumeTask(Long taskId);

    /**
     * 取消任务。
     */
    boolean cancelTask(Long taskId);

    /**
     * 重试任务。
     */
    Task retryTask(Long taskId);

    /**
     * 管理员强制暂停任务。
     *
     * <p>普通暂停只适用于 RUNNING；强制暂停还允许把 PENDING 任务暂停，防止它继续被调度。
     * 这类动作必须携带操作者上下文和原因，便于后续审计与事故复盘。
     */
    Task forcePauseTask(Long taskId, String reason, TaskActorContext actorContext);

    /**
     * 管理员恢复任务。
     *
     * <p>为了避免“恢复接口直接伪造运行中状态”，当前恢复会把 PAUSED 任务放回 PENDING，
     * 等待调度器或执行器重新启动。
     */
    Task forceResumeTask(Long taskId, String reason, TaskActorContext actorContext);

    /**
     * 管理员强制取消任务。
     *
     * <p>用于事故处置、下游依赖故障、客户撤销需求等场景。
     */
    Task forceCancelTask(Long taskId, String reason, TaskActorContext actorContext);

    /**
     * 管理员强制重试任务。
     *
     * <p>相比普通 retry，该方法可以在需要时忽略最大重试次数上限，但必须留下操作者和原因。
     */
    Task forceRetryTask(Long taskId, String reason, Boolean ignoreRetryLimit, TaskActorContext actorContext);

    /**
     * 管理员覆盖任务优先级。
     *
     * <p>优先级会影响调度公平性和 SLA，因此独立成显式动作，并写入执行日志。
     */
    Task overridePriority(Long taskId, String priority, String reason, TaskActorContext actorContext);

    /**
     * 查询任务队列运营视图。
     *
     * <p>该方法不是普通 CRUD 列表，而是面向运营排障的队列健康查询：
     * - 查看哪些任务仍在等待调度；
     * - 查看哪些任务因为容量不足进入 DEFERRED；
     * - 查看哪些任务已经进入 DEAD_LETTER；
     * - 查看哪些任务被标记为 attentionRequired；
     * - 按排队时长、执行器、任务类型、优先级、退避次数快速定位风险。
     *
     * <p>把该逻辑放在 Service 层，是为了让 Controller 只负责 HTTP 契约，
     * 而查询默认状态、分页上限、排序策略等业务规则由任务领域服务统一管理。
     */
    IPage<Task> inspectQueue(TaskQueueInspectionRequest request);

    /**
     * 查询任务队列运营项视图。
     *
     * <p>与 inspectQueue 返回原始 Task 不同，该方法会补充排队时长、延迟剩余时间、租约剩余时间、
     * 风险原因和推荐处置动作，适合运营工作台直接展示。
     */
    IPage<TaskQueueItemView> inspectQueueItems(TaskQueueInspectionRequest request);

    /**
     * 查询任务队列运营汇总。
     *
     * <p>列表接口回答“具体有哪些任务”，汇总接口回答“队列整体是否健康”。
     * 该方法会复用运营队列过滤语义，返回状态分布、关注任务数、死信任务数、最老排队时间等指标，
     * 为后续运营大盘、告警规则和 SLA 报表提供基础数据。
     */
    TaskQueueSummaryResponse summarizeQueue(TaskQueueInspectionRequest request);

    /**
     * 更新任务进度。
     */
    boolean updateProgress(Long taskId, Integer progress, String checkpoint);

    /**
     * 标记任务完成。
     */
    boolean completeTask(Long taskId, String result);

    /**
     * 标记任务失败。
     */
    boolean failTask(Long taskId, String errorMessage);

    /**
     * 执行器主动延迟任务并放回可认领队列。
     *
     * <p>该动作只允许 RUNNING -> DEFERRED。
     * 它面向容量保护和背压控制场景，例如执行器本实例并发已满、租户配额触顶、单数据源并发触顶、
     * 下游临时限流等。与 failTask 不同，deferTask 不应该消耗业务失败语义，也不应该把任务标记为需要人工关注；
     * 它只是结束当前 run，并把任务设置为稍后可再次认领。
     *
     * @param taskId 任务 ID。
     * @param reason 延迟原因，会写入执行日志和执行 run。
     * @param delaySeconds 延迟秒数，服务层会做默认值和上下限保护。
     * @return 是否处理成功。
     */
    boolean deferTask(Long taskId, String reason, Integer delaySeconds);

    /**
     * 查询任务执行日志。
     */
    List<TaskExecutionLog> listExecutionLogs(Long taskId);

    /**
     * 执行器认领下一条可执行任务。
     */
    TaskExecutionClaimResult claimNextTask(TaskExecutionClaimRequest request, TaskActorContext actorContext);

    /**
     * 执行器心跳续租。
     */
    TaskExecutionRun heartbeatExecution(Long runId, TaskExecutionHeartbeatRequest request, TaskActorContext actorContext);

    /**
     * 恢复执行器租约超时的任务。
     */
    TaskLeaseRecoveryResult recoverTimedOutExecutions(Integer limit, TaskActorContext actorContext);

    /**
     * 查询任务执行记录。
     */
    List<TaskExecutionRun> listExecutionRuns(Long taskId);
}

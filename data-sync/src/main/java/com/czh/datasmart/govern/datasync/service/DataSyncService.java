/**
 * @Author : Cui
 * @Date: 2026/05/07 21:31
 * @Description DataSmart Govern Backend - DataSyncService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service;

import com.czh.datasmart.govern.common.api.PlatformPageResponse;
import com.czh.datasmart.govern.datasync.controller.dto.CreateSyncTaskRequest;
import com.czh.datasmart.govern.datasync.controller.dto.CreateSyncTemplateRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncAuditQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncCheckpointQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirtyRecordReplayRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirtyRecordReplayResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncErrorSampleQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCheckpointRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCompleteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionFailRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionStartRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncOfflineJobPlanResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectExecutionQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectExecutionView;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncObjectRetryResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskCloneRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskLifecycleOperationRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskOperationResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskRecoveryOperationRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTemplateExecutionPrecheckResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTemplatePlanningPreviewResponse;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTemplateQueryCriteria;
import com.czh.datasmart.govern.datasync.entity.SyncAuditRecord;
import com.czh.datasmart.govern.datasync.entity.SyncCheckpoint;
import com.czh.datasmart.govern.datasync.entity.SyncErrorSample;
import com.czh.datasmart.govern.datasync.entity.SyncExecution;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.entity.SyncTemplate;

/**
 * 数据同步服务契约。
 *
 * <p>第一版服务只覆盖“模板定义”和“任务定义/入队”。
 * 真实执行引擎、checkpoint、失败样本和数据搬运 worker 会在后续独立扩展，避免第一步就把执行细节塞进服务实现。
 */
public interface DataSyncService {

    SyncTemplate createTemplate(CreateSyncTemplateRequest request, SyncActorContext actorContext);

    PlatformPageResponse<SyncTemplate> pageTemplates(SyncTemplateQueryCriteria criteria, SyncActorContext actorContext);

    SyncTemplate getTemplate(Long id, SyncActorContext actorContext);

    SyncTaskOperationResult validateTemplate(Long id, SyncActorContext actorContext);

    /**
     * 生成同步模板规划预览。
     *
     * <p>预览只返回低敏配置健康结果，不执行同步、不读取源端数据、不返回字段映射原文、过滤条件、SQL 或样本。
     * 它用于帮助用户、Agent 或运营人员理解“这个模板是否适合进入任务草稿/执行前预检”。</p>
     */
    SyncTemplatePlanningPreviewResponse previewTemplate(Long id, SyncActorContext actorContext);

    /**
     * 生成同步模板执行前预检查。
     *
     * <p>预检查比 preview 更接近真实运行入口：它会判断当前模板是否能被现有 runner 入队执行，
     * 并把“配置非法”“需要审批”“当前 runner 暂不支持”区分开。该方法仍然不读取源端数据、不写目标端、
     * 不执行 SQL，只返回低敏状态、问题码和建议。</p>
     */
    SyncTemplateExecutionPrecheckResponse precheckTemplate(Long id, SyncActorContext actorContext);

    /**
     * 生成 DataX 风格离线作业计划。
     *
     * <p>离线作业计划用于帮助 UI、Agent、审批流和运维人员理解“这份模板如果交给专用离线 runner，
     * 应该使用哪类 Reader/Writer、需要什么分片/调度/checkpoint/审批能力”。它不是执行入口：
     * 不创建任务、不入队、不连接源端、不执行 SQL、不返回 objectMapping/fieldMapping/filter/SQL 原文。</p>
     */
    SyncOfflineJobPlanResponse buildOfflineJobPlan(Long id, SyncActorContext actorContext);

    SyncTask createTask(CreateSyncTaskRequest request, SyncActorContext actorContext);

    PlatformPageResponse<SyncTask> pageTasks(SyncTaskQueryCriteria criteria, SyncActorContext actorContext);

    SyncTask getTask(Long id, SyncActorContext actorContext);

    SyncTaskOperationResult runTask(Long id, SyncActorContext actorContext);

    /**
     * 手工调度同步任务立即执行一次。
     *
     * <p>该动作和 runTask 的底层效果都是创建 MANUAL execution，但产品语义更清晰：
     * runTask 是通用运行入口，manualDispatchTask 对应用户在调度台点击“立即调度一次”。
     * 定时任务在手工调度完成后仍应回到 SCHEDULED 等待下一次计划触发。</p>
     */
    SyncTaskOperationResult manualDispatchTask(Long id, SyncActorContext actorContext);

    /**
     * 暂停同步任务。
     *
     * <p>暂停属于可恢复的生命周期控制动作，通常用于维护窗口、下游限流、字段映射待确认等场景。
     * 当前契约只暴露普通用户/项目负责人入口，不包含管理员强制暂停或批量暂停能力。
     */
    SyncTaskOperationResult pauseTask(Long id, SyncTaskLifecycleOperationRequest request, SyncActorContext actorContext);

    /**
     * 恢复已暂停同步任务，并重新创建待执行 execution。
     */
    SyncTaskOperationResult resumeTask(Long id, SyncTaskLifecycleOperationRequest request, SyncActorContext actorContext);

    /**
     * 从失败或部分成功状态发起普通重试。
     *
     * <p>人工介入任务的重跑走 attention 专用服务，避免绕过运营确认。
     */
    SyncTaskOperationResult retryTask(Long id, SyncTaskLifecycleOperationRequest request, SyncActorContext actorContext);

    /**
     * 取消同步任务。
     *
     * <p>取消会终止任务继续执行的业务意图；已经完成的执行历史不会被篡改。
     */
    SyncTaskOperationResult cancelTask(Long id, SyncTaskLifecycleOperationRequest request, SyncActorContext actorContext);

    /**
     * 手工结束正在排队、运行、重试或暂停中的同步任务。
     *
     * <p>手工结束会写入 execution 控制信号，后续 worker 心跳或写入型回调都会停止，适合人工止血和客户要求立即结束的场景。</p>
     */
    SyncTaskOperationResult manualTerminateTask(Long id, SyncTaskLifecycleOperationRequest request, SyncActorContext actorContext);

    /**
     * 下线同步任务。
     *
     * <p>下线会关闭自动调度并清空 nextFireTime，是删除进回收站之前的强制前置动作。</p>
     */
    SyncTaskOperationResult offlineTask(Long id, SyncTaskLifecycleOperationRequest request, SyncActorContext actorContext);

    /**
     * 删除任务到回收站。
     *
     * <p>只有已下线任务可以进入回收站；回收站内任务仍可查看详情和克隆，但不能运行或调度。</p>
     */
    SyncTaskOperationResult recycleTask(Long id, SyncTaskLifecycleOperationRequest request, SyncActorContext actorContext);

    /**
     * 从回收站彻底删除任务。
     *
     * <p>当前采用逻辑 DELETED，保留 execution、checkpoint、错误样本和审计证据的历史归属。</p>
     */
    SyncTaskOperationResult hardDeleteTask(Long id, SyncTaskLifecycleOperationRequest request, SyncActorContext actorContext);

    /**
     * 克隆同步任务。
     *
     * <p>克隆只复制任务定义字段，不复制执行历史；新任务默认进入 DRAFT，可选择立即手工执行一次。</p>
     */
    SyncTaskOperationResult cloneTask(Long id, SyncTaskCloneRequest request, SyncActorContext actorContext);

    /**
     * 从历史 execution 或 checkpoint 发起同步回放。
     *
     * <p>回放不是普通 retry：
     * retry 表达“同一个失败任务再跑一次”，replay 表达“基于某次历史执行或断点重新派生一次恢复性执行”。
     * 因此请求需要携带 sourceExecutionId/sourceCheckpointId，服务层会创建新的 QUEUED execution 和恢复计划，
     * 后续 worker 再根据 executionId 读取恢复计划执行真实数据搬运。
     */
    SyncTaskOperationResult replayTask(Long id, SyncTaskRecoveryOperationRequest request, SyncActorContext actorContext);

    /**
     * 按时间窗口、分区窗口或业务分片发起历史补数。
     *
     * <p>补数用于处理历史缺口、晚到数据、客户指定时间段重刷或分区级修复。
     * 当前服务只保存低敏恢复计划，不在 API 层接收 SQL、样本数据、连接串或完整工具参数。
     */
    SyncTaskOperationResult backfillTask(Long id, SyncTaskRecoveryOperationRequest request, SyncActorContext actorContext);

    SyncExecution startExecution(Long taskId, Long executionId, SyncExecutionStartRequest request, SyncActorContext actorContext);

    SyncCheckpoint writeCheckpoint(Long taskId, Long executionId, SyncExecutionCheckpointRequest request, SyncActorContext actorContext);

    SyncExecution completeExecution(Long taskId, Long executionId, SyncExecutionCompleteRequest request, SyncActorContext actorContext);

    SyncErrorSample failExecution(Long taskId, Long executionId, SyncExecutionFailRequest request, SyncActorContext actorContext);

    PlatformPageResponse<SyncExecution> pageExecutions(SyncExecutionQueryCriteria criteria, SyncActorContext actorContext);

    /**
     * 查询某次 execution 内部的对象级执行账本。
     *
     * <p>该接口是 OBJECT_LIST 部分成功闭环的运维入口：用户或运营人员能看到哪些对象成功、哪些失败、
     * 尝试次数、低敏错误码和计数信息，然后再决定是否走对象级选择性重试。</p>
     */
    PlatformPageResponse<SyncObjectExecutionView> pageObjectExecutions(SyncObjectExecutionQueryCriteria criteria,
                                                                       SyncActorContext actorContext);

    /**
     * 对某次 execution 下的 FAILED 对象发起选择性重试。
     *
     * <p>该动作不会直接搬运数据，而是重置失败对象账本并重新排队父 execution。真实执行仍由 worker 认领后完成，
     * 因此它和现有租约、回调、审计、可观测链路保持一致。</p>
     */
    SyncObjectRetryResult retryObjectExecutions(Long taskId,
                                                Long executionId,
                                                SyncObjectRetryRequest request,
                                                SyncActorContext actorContext);

    PlatformPageResponse<SyncCheckpoint> pageCheckpoints(SyncCheckpointQueryCriteria criteria, SyncActorContext actorContext);

    PlatformPageResponse<SyncErrorSample> pageErrorSamples(SyncErrorSampleQueryCriteria criteria, SyncActorContext actorContext);

    /**
     * 基于结构化错误样本创建脏数据修复重放计划。
     *
     * <p>该方法不是直接在 HTTP 线程里改写目标表，而是创建新的 REPLAY execution 和恢复计划。
     * 真正的数据重放仍由 worker 按租约、checkpoint、模板配置、连接器能力和幂等策略执行。</p>
     */
    SyncDirtyRecordReplayResult replayDirtyRecords(Long taskId,
                                                   SyncDirtyRecordReplayRequest request,
                                                   SyncActorContext actorContext);

    PlatformPageResponse<SyncAuditRecord> pageAuditRecords(SyncAuditQueryCriteria criteria, SyncActorContext actorContext);
}

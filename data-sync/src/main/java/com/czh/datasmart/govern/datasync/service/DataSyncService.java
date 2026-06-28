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
import com.czh.datasmart.govern.datasync.controller.dto.SyncErrorSampleQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCheckpointRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionCompleteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionFailRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncExecutionStartRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskLifecycleOperationRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskOperationResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskQueryCriteria;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskRecoveryOperationRequest;
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

    SyncTask createTask(CreateSyncTaskRequest request, SyncActorContext actorContext);

    PlatformPageResponse<SyncTask> pageTasks(SyncTaskQueryCriteria criteria, SyncActorContext actorContext);

    SyncTask getTask(Long id, SyncActorContext actorContext);

    SyncTaskOperationResult runTask(Long id, SyncActorContext actorContext);

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

    PlatformPageResponse<SyncCheckpoint> pageCheckpoints(SyncCheckpointQueryCriteria criteria, SyncActorContext actorContext);

    PlatformPageResponse<SyncErrorSample> pageErrorSamples(SyncErrorSampleQueryCriteria criteria, SyncActorContext actorContext);

    PlatformPageResponse<SyncAuditRecord> pageAuditRecords(SyncAuditQueryCriteria criteria, SyncActorContext actorContext);
}

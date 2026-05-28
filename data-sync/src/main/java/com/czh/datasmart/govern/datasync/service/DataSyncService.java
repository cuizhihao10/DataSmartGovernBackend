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
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskOperationResult;
import com.czh.datasmart.govern.datasync.controller.dto.SyncTaskQueryCriteria;
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

    SyncTask createTask(CreateSyncTaskRequest request, SyncActorContext actorContext);

    PlatformPageResponse<SyncTask> pageTasks(SyncTaskQueryCriteria criteria, SyncActorContext actorContext);

    SyncTask getTask(Long id, SyncActorContext actorContext);

    SyncTaskOperationResult runTask(Long id, SyncActorContext actorContext);

    SyncExecution startExecution(Long taskId, Long executionId, SyncExecutionStartRequest request, SyncActorContext actorContext);

    SyncCheckpoint writeCheckpoint(Long taskId, Long executionId, SyncExecutionCheckpointRequest request, SyncActorContext actorContext);

    SyncExecution completeExecution(Long taskId, Long executionId, SyncExecutionCompleteRequest request, SyncActorContext actorContext);

    SyncErrorSample failExecution(Long taskId, Long executionId, SyncExecutionFailRequest request, SyncActorContext actorContext);

    PlatformPageResponse<SyncExecution> pageExecutions(SyncExecutionQueryCriteria criteria, SyncActorContext actorContext);

    PlatformPageResponse<SyncCheckpoint> pageCheckpoints(SyncCheckpointQueryCriteria criteria, SyncActorContext actorContext);

    PlatformPageResponse<SyncErrorSample> pageErrorSamples(SyncErrorSampleQueryCriteria criteria, SyncActorContext actorContext);

    PlatformPageResponse<SyncAuditRecord> pageAuditRecords(SyncAuditQueryCriteria criteria, SyncActorContext actorContext);
}

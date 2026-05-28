/**
 * @Author : Cui
 * @Date: 2026/05/25 00:04
 * @Description DataSmart Govern Backend - TaskDraftService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.czh.datasmart.govern.task.controller.dto.CreateTaskDraftRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import com.czh.datasmart.govern.task.controller.dto.UpdateTaskDraftRequest;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.entity.TaskDraft;

/**
 * 任务草稿服务接口。
 *
 * <p>该接口把草稿作为独立领域对象暴露，而不是混入 TaskService。
 * 这样调用方可以清楚地区分：</p>
 * <p>1. TaskDraftService：管理还未进入调度队列的任务意图；</p>
 * <p>2. TaskService：管理已经成为真实任务的执行生命周期。</p>
 */
public interface TaskDraftService extends IService<TaskDraft> {

    TaskDraft createDraft(CreateTaskDraftRequest request, TaskActorContext actorContext);

    TaskDraft updateDraft(Long draftId, UpdateTaskDraftRequest request, TaskActorContext actorContext);

    TaskDraft submitDraft(Long draftId, String comment, TaskActorContext actorContext);

    TaskDraft approveDraft(Long draftId, String comment, TaskActorContext actorContext);

    TaskDraft rejectDraft(Long draftId, String comment, TaskActorContext actorContext);

    Task convertApprovedDraft(Long draftId, String comment, TaskActorContext actorContext);

    TaskDraft getDraftDetail(Long draftId, TaskActorContext actorContext);

    IPage<TaskDraft> listDrafts(Integer current, Integer size, String status, String type,
                                Long tenantId, Long ownerId, Long projectId, TaskActorContext actorContext);
}

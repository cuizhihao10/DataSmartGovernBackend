/**
 * @Author : Cui
 * @Date: 2026/05/25 00:07
 * @Description DataSmart Govern Backend - TaskDraftServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.czh.datasmart.govern.task.controller.dto.CreateTaskDraftRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import com.czh.datasmart.govern.task.controller.dto.UpdateTaskDraftRequest;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.entity.TaskDraft;
import com.czh.datasmart.govern.task.mapper.TaskDraftMapper;
import com.czh.datasmart.govern.task.service.TaskDraftService;
import com.czh.datasmart.govern.task.service.support.TaskDraftLifecycleSupport;
import com.czh.datasmart.govern.task.service.support.TaskDraftScopeSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务草稿服务实现门面。
 *
 * <p>和 TaskServiceImpl 一样，这里保持薄门面，只声明事务边界、分页查询和委托关系。
 * 草稿状态流转放在 TaskDraftLifecycleSupport，数据范围规则放在 TaskDraftScopeSupport。</p>
 */
@Service
@RequiredArgsConstructor
public class TaskDraftServiceImpl extends ServiceImpl<TaskDraftMapper, TaskDraft> implements TaskDraftService {

    private final TaskDraftLifecycleSupport lifecycleSupport;
    private final TaskDraftScopeSupport scopeSupport;

    @Override
    @Transactional
    public TaskDraft createDraft(CreateTaskDraftRequest request, TaskActorContext actorContext) {
        return lifecycleSupport.createDraft(request, actorContext);
    }

    @Override
    @Transactional
    public TaskDraft updateDraft(Long draftId, UpdateTaskDraftRequest request, TaskActorContext actorContext) {
        return lifecycleSupport.updateDraft(draftId, request, actorContext);
    }

    @Override
    @Transactional
    public TaskDraft submitDraft(Long draftId, String comment, TaskActorContext actorContext) {
        return lifecycleSupport.submitDraft(draftId, comment, actorContext);
    }

    @Override
    @Transactional
    public TaskDraft approveDraft(Long draftId, String comment, TaskActorContext actorContext) {
        return lifecycleSupport.approveDraft(draftId, comment, actorContext);
    }

    @Override
    @Transactional
    public TaskDraft rejectDraft(Long draftId, String comment, TaskActorContext actorContext) {
        return lifecycleSupport.rejectDraft(draftId, comment, actorContext);
    }

    @Override
    @Transactional
    public Task convertApprovedDraft(Long draftId, String comment, TaskActorContext actorContext) {
        return lifecycleSupport.convertApprovedDraft(draftId, comment, actorContext);
    }

    @Override
    public TaskDraft getDraftDetail(Long draftId, TaskActorContext actorContext) {
        return lifecycleSupport.requireVisibleDraft(draftId, actorContext);
    }

    @Override
    public IPage<TaskDraft> listDrafts(Integer current, Integer size, String status, String type,
                                       Long tenantId, Long ownerId, Long projectId,
                                       TaskActorContext actorContext) {
        LambdaQueryWrapper<TaskDraft> wrapper = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            wrapper.eq(TaskDraft::getStatus, status);
        }
        if (type != null && !type.isBlank()) {
            wrapper.eq(TaskDraft::getType, type);
        }
        scopeSupport.applyListScope(wrapper, tenantId, ownerId, projectId, actorContext);
        wrapper.orderByDesc(TaskDraft::getCreateTime);

        int safeCurrent = Math.max(1, current == null ? 1 : current);
        int safeSize = Math.max(1, Math.min(size == null ? 10 : size, 200));
        return page(new Page<>(safeCurrent, safeSize), wrapper);
    }
}

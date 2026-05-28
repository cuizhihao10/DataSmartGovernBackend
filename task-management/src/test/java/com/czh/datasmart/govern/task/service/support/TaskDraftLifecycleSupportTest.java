/**
 * @Author : Cui
 * @Date: 2026/05/25 00:12
 * @Description DataSmart Govern Backend - TaskDraftLifecycleSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.support;

import com.czh.datasmart.govern.task.controller.dto.CreateTaskDraftRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.entity.TaskDraft;
import com.czh.datasmart.govern.task.mapper.TaskDraftMapper;
import com.czh.datasmart.govern.task.mapper.TaskMapper;
import com.czh.datasmart.govern.task.support.TaskDraftStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务草稿生命周期测试。
 *
 * <p>这些用例固定草稿和真实任务之间的安全边界：
 * 草稿可以创建、提交、审批，但只有 APPROVED 草稿才能转换为真实 PENDING 任务。
 * 这能防止 Agent 或模板系统绕过审批直接把任务送入执行队列。</p>
 */
@ExtendWith(MockitoExtension.class)
class TaskDraftLifecycleSupportTest {

    @Mock
    private TaskDraftMapper taskDraftMapper;

    @Mock
    private TaskMapper taskMapper;

    @Mock
    private TaskLifecycleSupport taskLifecycleSupport;

    @InjectMocks
    private TaskDraftScopeSupport scopeSupport;

    private final TaskDraftParamValidationSupport paramValidationSupport =
            new TaskDraftParamValidationSupport(new ObjectMapper());

    @Test
    void createDraftShouldResolveProjectScopeAndStayDraftOnly() {
        TaskDraftLifecycleSupport support = support();
        CreateTaskDraftRequest request = new CreateTaskDraftRequest();
        request.setName("订单质量扫描草稿");
        request.setType("DATA_QUALITY_SCAN");
        request.setProjectId(20L);
        request.setPriority("high");
        request.setSourceType("AGENT");
        request.setSourceRef("audit-task-draft");
        request.setParams("{\"ruleIds\":[1,2]}");

        TaskDraft draft = support.createDraft(request, projectOwner());

        assertEquals(TaskDraftStatus.DRAFT, draft.getStatus());
        assertEquals(10L, draft.getTenantId());
        assertEquals(20L, draft.getProjectId());
        assertEquals(1001L, draft.getOwnerId());
        assertEquals("HIGH", draft.getPriority());
        verify(taskDraftMapper).insert(draft);
    }

    @Test
    void convertApprovedDraftShouldCreateRealTaskAndMarkDraftConverted() {
        TaskDraftLifecycleSupport support = support();
        TaskDraft draft = approvedDraft();
        when(taskDraftMapper.selectById(1L)).thenReturn(draft);
        when(taskDraftMapper.markConverting(1L, TaskDraftStatus.APPROVED, TaskDraftStatus.CONVERTING)).thenReturn(1);
        Task createdTask = new Task();
        createdTask.setId(9001L);
        when(taskLifecycleSupport.createTask(
                eq("订单质量扫描草稿"),
                any(),
                eq("DATA_QUALITY_SCAN"),
                eq("{\"ruleIds\":[1,2]}"),
                eq("MEDIUM"),
                eq(3),
                eq(20),
                eq(10L),
                eq(1001L),
                eq(20L),
                eq(projectOwner())
        )).thenReturn(createdTask);
        when(taskDraftMapper.markConverted(1L, 9001L, TaskDraftStatus.CONVERTING, TaskDraftStatus.CONVERTED))
                .thenReturn(1);

        Task task = support.convertApprovedDraft(1L, "审批后创建真实任务", projectOwner());

        assertEquals(9001L, task.getId());
        verify(taskDraftMapper).markConverting(1L, TaskDraftStatus.APPROVED, TaskDraftStatus.CONVERTING);
        verify(taskDraftMapper).markConverted(1L, 9001L, TaskDraftStatus.CONVERTING, TaskDraftStatus.CONVERTED);
    }

    @Test
    void convertAlreadyConvertedDraftShouldReturnExistingTaskWithoutCreatingDuplicate() {
        TaskDraftLifecycleSupport support = support();
        TaskDraft draft = approvedDraft();
        draft.setStatus(TaskDraftStatus.CONVERTED);
        draft.setConvertedTaskId(9001L);
        Task existingTask = new Task();
        existingTask.setId(9001L);
        when(taskDraftMapper.selectById(1L)).thenReturn(draft);
        when(taskMapper.selectById(9001L)).thenReturn(existingTask);

        Task task = support.convertApprovedDraft(1L, "重复转换请求", projectOwner());

        assertEquals(9001L, task.getId());
        verify(taskMapper).selectById(9001L);
    }

    @Test
    void convertApprovedDraftShouldBlockWhenAnotherTransactionIsConverting() {
        TaskDraftLifecycleSupport support = support();
        TaskDraft draft = approvedDraft();
        TaskDraft convertingDraft = approvedDraft();
        convertingDraft.setStatus(TaskDraftStatus.CONVERTING);
        when(taskDraftMapper.selectById(1L)).thenReturn(draft, convertingDraft);
        when(taskDraftMapper.markConverting(1L, TaskDraftStatus.APPROVED, TaskDraftStatus.CONVERTING)).thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> support.convertApprovedDraft(1L, "并发转换", projectOwner()));
    }

    @Test
    void convertRejectedDraftShouldBeBlocked() {
        TaskDraftLifecycleSupport support = support();
        TaskDraft draft = approvedDraft();
        draft.setStatus(TaskDraftStatus.REJECTED);
        when(taskDraftMapper.selectById(1L)).thenReturn(draft);

        assertThrows(IllegalStateException.class,
                () -> support.convertApprovedDraft(1L, "不允许转换", projectOwner()));
    }

    @Test
    void createDataQualityDraftShouldRejectMissingExecutableParams() {
        TaskDraftLifecycleSupport support = support();
        CreateTaskDraftRequest request = new CreateTaskDraftRequest();
        request.setName("缺少规则的质量扫描草稿");
        request.setType("DATA_QUALITY_SCAN");
        request.setProjectId(20L);
        request.setParams("{\"objective\":\"只写了目标但没有规则或扫描计划\"}");

        assertThrows(IllegalArgumentException.class, () -> support.createDraft(request, projectOwner()));
    }

    @Test
    void createDataSyncDraftShouldAcceptTemplateBasedParams() {
        TaskDraftLifecycleSupport support = support();
        CreateTaskDraftRequest request = new CreateTaskDraftRequest();
        request.setName("订单同步草稿");
        request.setType("DATA_SYNC");
        request.setProjectId(20L);
        request.setParams("{\"syncTemplateId\":88}");

        TaskDraft draft = support.createDraft(request, projectOwner());

        assertEquals("DATA_SYNC", draft.getType());
        verify(taskDraftMapper).insert(draft);
    }

    private TaskDraftLifecycleSupport support() {
        return new TaskDraftLifecycleSupport(taskDraftMapper, taskMapper, scopeSupport,
                taskLifecycleSupport, paramValidationSupport);
    }

    private TaskDraft approvedDraft() {
        TaskDraft draft = new TaskDraft();
        draft.setId(1L);
        draft.setName("订单质量扫描草稿");
        draft.setDescription("由 Agent 生成的质量扫描任务草稿");
        draft.setType("DATA_QUALITY_SCAN");
        draft.setTenantId(10L);
        draft.setOwnerId(1001L);
        draft.setProjectId(20L);
        draft.setStatus(TaskDraftStatus.APPROVED);
        draft.setParams("{\"ruleIds\":[1,2]}");
        draft.setPriority("MEDIUM");
        draft.setMaxRetryCount(3);
        draft.setMaxDeferCount(20);
        draft.setSourceType("AGENT");
        draft.setSourceRef("audit-task-draft");
        return draft;
    }

    private TaskActorContext projectOwner() {
        return new TaskActorContext(10L, 1001L, "PROJECT_OWNER", "trace-draft", "PROJECT", List.of(20L));
    }
}

/**
 * @Author : Cui
 * @Date: 2026/05/25 00:06
 * @Description DataSmart Govern Backend - TaskDraftLifecycleSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.support;

import com.czh.datasmart.govern.task.controller.dto.CreateTaskDraftRequest;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import com.czh.datasmart.govern.task.controller.dto.UpdateTaskDraftRequest;
import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.entity.TaskDraft;
import com.czh.datasmart.govern.task.mapper.TaskDraftMapper;
import com.czh.datasmart.govern.task.mapper.TaskMapper;
import com.czh.datasmart.govern.task.support.TaskDraftStatus;
import com.czh.datasmart.govern.task.support.TaskPriority;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.NoSuchElementException;

/**
 * 任务草稿生命周期支持组件。
 *
 * <p>该组件负责维护草稿状态流转，不负责真实任务运行状态。
 * 草稿状态主线为：DRAFT -> PENDING_APPROVAL -> APPROVED/REJECTED -> CONVERTED。
 * 只有 APPROVED 草稿才允许转换成真实 task，这一点是 Agent 自动化与生产执行队列之间的安全阀。</p>
 */
@Component
@RequiredArgsConstructor
public class TaskDraftLifecycleSupport {

    private final TaskDraftMapper taskDraftMapper;
    private final TaskMapper taskMapper;
    private final TaskDraftScopeSupport scopeSupport;
    private final TaskLifecycleSupport taskLifecycleSupport;
    private final TaskDraftParamValidationSupport paramValidationSupport;

    public TaskDraft createDraft(CreateTaskDraftRequest request, TaskActorContext actorContext) {
        LocalDateTime now = LocalDateTime.now();
        TaskDraft draft = new TaskDraft();
        draft.setName(requiredText(request.getName(), "草稿名称不能为空"));
        draft.setDescription(request.getDescription());
        draft.setType(requiredText(request.getType(), "任务类型不能为空"));
        draft.setTenantId(scopeSupport.resolveTenantIdForCreate(request.getTenantId(), actorContext));
        draft.setOwnerId(scopeSupport.resolveOwnerIdForCreate(request.getOwnerId(), actorContext));
        draft.setProjectId(scopeSupport.resolveProjectIdForCreate(request.getProjectId(), actorContext));
        draft.setStatus(TaskDraftStatus.DRAFT);
        draft.setParams(request.getParams());
        paramValidationSupport.validate(draft.getType(), draft.getParams(), "创建任务草稿");
        draft.setPriority(TaskPriority.normalize(request.getPriority()));
        draft.setMaxRetryCount(request.getMaxRetryCount() == null ? 3 : request.getMaxRetryCount());
        draft.setMaxDeferCount(request.getMaxDeferCount() == null ? 20 : request.getMaxDeferCount());
        draft.setSourceType(defaultText(request.getSourceType(), "MANUAL"));
        draft.setSourceRef(request.getSourceRef());
        draft.setCreatedBy(actorContext == null ? null : actorContext.actorId());
        draft.setCreateTime(now);
        draft.setUpdateTime(now);
        taskDraftMapper.insert(draft);
        return draft;
    }

    public TaskDraft updateDraft(Long draftId, UpdateTaskDraftRequest request, TaskActorContext actorContext) {
        TaskDraft draft = requireDraft(draftId);
        scopeSupport.validateDraftInActorScope(draft, actorContext, "更新任务草稿");
        ensureEditable(draft);
        if (request.getName() != null && !request.getName().isBlank()) {
            draft.setName(request.getName().trim());
        }
        if (request.getDescription() != null) {
            draft.setDescription(request.getDescription());
        }
        if (request.getType() != null && !request.getType().isBlank()) {
            draft.setType(request.getType().trim());
        }
        if (request.getProjectId() != null) {
            draft.setProjectId(scopeSupport.resolveProjectIdForCreate(request.getProjectId(), actorContext));
        }
        if (request.getOwnerId() != null) {
            draft.setOwnerId(scopeSupport.resolveOwnerIdForCreate(request.getOwnerId(), actorContext));
        }
        if (request.getParams() != null) {
            draft.setParams(request.getParams());
        }
        if (request.getPriority() != null) {
            draft.setPriority(TaskPriority.normalize(request.getPriority()));
        }
        if (request.getMaxRetryCount() != null) {
            draft.setMaxRetryCount(request.getMaxRetryCount());
        }
        if (request.getMaxDeferCount() != null) {
            draft.setMaxDeferCount(request.getMaxDeferCount());
        }
        paramValidationSupport.validate(draft.getType(), draft.getParams(), "更新任务草稿");
        draft.setUpdateTime(LocalDateTime.now());
        taskDraftMapper.updateById(draft);
        return draft;
    }

    public TaskDraft submitDraft(Long draftId, String comment, TaskActorContext actorContext) {
        TaskDraft draft = requireDraft(draftId);
        scopeSupport.validateDraftInActorScope(draft, actorContext, "提交任务草稿审批");
        if (!TaskDraftStatus.DRAFT.equals(draft.getStatus()) && !TaskDraftStatus.REJECTED.equals(draft.getStatus())) {
            throw new IllegalStateException("只有 DRAFT 或 REJECTED 草稿才能提交审批");
        }
        paramValidationSupport.validate(draft.getType(), draft.getParams(), "提交任务草稿审批");
        draft.setStatus(TaskDraftStatus.PENDING_APPROVAL);
        draft.setSubmittedBy(actorContext == null ? null : actorContext.actorId());
        draft.setApprovalComment(comment);
        draft.setSubmitTime(LocalDateTime.now());
        draft.setUpdateTime(LocalDateTime.now());
        taskDraftMapper.updateById(draft);
        return draft;
    }

    public TaskDraft approveDraft(Long draftId, String comment, TaskActorContext actorContext) {
        TaskDraft draft = requireDraft(draftId);
        scopeSupport.validateDraftInActorScope(draft, actorContext, "审批通过任务草稿");
        ensureStatus(draft, TaskDraftStatus.PENDING_APPROVAL, "只有待审批草稿才能审批通过");
        draft.setStatus(TaskDraftStatus.APPROVED);
        draft.setApprovedBy(actorContext == null ? null : actorContext.actorId());
        draft.setApprovalComment(comment);
        draft.setApprovalTime(LocalDateTime.now());
        draft.setUpdateTime(LocalDateTime.now());
        taskDraftMapper.updateById(draft);
        return draft;
    }

    public TaskDraft rejectDraft(Long draftId, String comment, TaskActorContext actorContext) {
        TaskDraft draft = requireDraft(draftId);
        scopeSupport.validateDraftInActorScope(draft, actorContext, "拒绝任务草稿");
        ensureStatus(draft, TaskDraftStatus.PENDING_APPROVAL, "只有待审批草稿才能被拒绝");
        draft.setStatus(TaskDraftStatus.REJECTED);
        draft.setApprovedBy(actorContext == null ? null : actorContext.actorId());
        draft.setApprovalComment(comment);
        draft.setApprovalTime(LocalDateTime.now());
        draft.setUpdateTime(LocalDateTime.now());
        taskDraftMapper.updateById(draft);
        return draft;
    }

    public Task convertApprovedDraft(Long draftId, String comment, TaskActorContext actorContext) {
        TaskDraft draft = requireDraft(draftId);
        scopeSupport.validateDraftInActorScope(draft, actorContext, "转换任务草稿");
        if (TaskDraftStatus.CONVERTED.equals(draft.getStatus()) && draft.getConvertedTaskId() != null) {
            return requireConvertedTask(draft);
        }
        ensureStatus(draft, TaskDraftStatus.APPROVED, "只有 APPROVED 草稿才能转换为真实任务");
        paramValidationSupport.validate(draft.getType(), draft.getParams(), "转换任务草稿");

        /*
         * 转换真实任务是草稿生命周期里风险最高的一步：
         * - 前端可能因为网络抖动重复点击；
         * - Agent Runtime 可能因为超时重试；
         * - 网关或调用方可能重放同一个请求。
         *
         * 如果这里仍然采用“先 select 判断 APPROVED，再 createTask，再 update 草稿”的普通写法，
         * 两个并发事务都可能在 select 时看到 APPROVED，最终创建出两条真实任务。
         *
         * 因此先用数据库条件更新抢占 APPROVED -> CONVERTING。
         * 只有抢占成功的事务才继续创建真实任务；抢占失败说明草稿状态已被其他事务改变，
         * 当前请求必须重新读取草稿并按已有状态做幂等返回或安全拒绝。
         */
        int claimed = taskDraftMapper.markConverting(draftId, TaskDraftStatus.APPROVED, TaskDraftStatus.CONVERTING);
        if (claimed == 0) {
            return handleConversionRace(draftId);
        }

        Task task = taskLifecycleSupport.createTask(
                draft.getName(),
                conversionDescription(draft, comment),
                draft.getType(),
                draft.getParams(),
                draft.getPriority(),
                draft.getMaxRetryCount(),
                draft.getMaxDeferCount(),
                draft.getTenantId(),
                draft.getOwnerId(),
                draft.getProjectId(),
                actorContext
        );
        int converted = taskDraftMapper.markConverted(draftId, task.getId(),
                TaskDraftStatus.CONVERTING, TaskDraftStatus.CONVERTED);
        if (converted == 0) {
            throw new IllegalStateException("任务草稿转换状态异常，真实任务已创建但草稿未能标记 CONVERTED，draftId="
                    + draftId + ", taskId=" + task.getId());
        }
        return task;
    }

    public TaskDraft requireVisibleDraft(Long draftId, TaskActorContext actorContext) {
        TaskDraft draft = requireDraft(draftId);
        scopeSupport.validateDraftInActorScope(draft, actorContext, "查询任务草稿详情");
        return draft;
    }

    private TaskDraft requireDraft(Long draftId) {
        TaskDraft draft = taskDraftMapper.selectById(draftId);
        if (draft == null) {
            throw new NoSuchElementException("任务草稿不存在: " + draftId);
        }
        return draft;
    }

    /**
     * 处理草稿转换并发竞争。
     *
     * <p>条件更新失败后必须重新读取草稿，因为失败原因可能不同：</p>
     * <p>1. 如果草稿已是 CONVERTED 且有 convertedTaskId，说明重复请求已经可以幂等返回真实任务；</p>
     * <p>2. 如果草稿是 CONVERTING，说明另一个事务正在转换，当前请求不能再创建任务；</p>
     * <p>3. 其他状态说明审批被撤回、拒绝或草稿被修改，应按状态机拒绝。</p>
     */
    private Task handleConversionRace(Long draftId) {
        TaskDraft latest = requireDraft(draftId);
        if (TaskDraftStatus.CONVERTED.equals(latest.getStatus()) && latest.getConvertedTaskId() != null) {
            return requireConvertedTask(latest);
        }
        if (TaskDraftStatus.CONVERTING.equals(latest.getStatus())) {
            throw new IllegalStateException("任务草稿正在转换真实任务，请稍后查询转换结果，draftId=" + draftId);
        }
        throw new IllegalStateException("任务草稿状态已变化，不能转换真实任务，draftId="
                + draftId + ", status=" + latest.getStatus());
    }

    /**
     * 读取已转换出的真实任务。
     *
     * <p>重复转换请求返回同一条真实任务，是幂等语义的重要部分。
     * 如果草稿声称已经转换但真实任务不存在，说明数据库一致性被破坏，需要直接抛错给运营排查。</p>
     */
    private Task requireConvertedTask(TaskDraft draft) {
        Task task = taskMapper.selectById(draft.getConvertedTaskId());
        if (task == null) {
            throw new IllegalStateException("任务草稿已标记 CONVERTED，但真实任务不存在，draftId="
                    + draft.getId() + ", convertedTaskId=" + draft.getConvertedTaskId());
        }
        return task;
    }

    private void ensureEditable(TaskDraft draft) {
        if (!TaskDraftStatus.DRAFT.equals(draft.getStatus()) && !TaskDraftStatus.REJECTED.equals(draft.getStatus())) {
            throw new IllegalStateException("只有 DRAFT 或 REJECTED 草稿才能编辑");
        }
    }

    private void ensureStatus(TaskDraft draft, String expectedStatus, String message) {
        if (!expectedStatus.equals(draft.getStatus())) {
            throw new IllegalStateException(message);
        }
    }

    private String conversionDescription(TaskDraft draft, String comment) {
        String base = defaultText(draft.getDescription(), "任务由草稿审批转换生成。");
        return base + "\n\n草稿来源=" + defaultText(draft.getSourceType(), "UNKNOWN")
                + ", sourceRef=" + defaultText(draft.getSourceRef(), "N/A")
                + ", convertComment=" + defaultText(comment, "无");
    }

    private String requiredText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String defaultText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}

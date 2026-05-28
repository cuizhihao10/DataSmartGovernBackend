package com.czh.datasmart.govern.task.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.task.controller.dto.TaskExecutionCallbackContext;
import com.czh.datasmart.govern.task.entity.TaskCallbackIdempotency;
import com.czh.datasmart.govern.task.mapper.TaskCallbackIdempotencyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * @Author : Cui
 * @Date: 2026/05/07 21:14
 * @Description DataSmart Govern Backend - TaskCallbackIdempotencySupport.java
 * @Version:1.0.0
 *
 * 任务执行器回调幂等支持组件。
 *
 * <p>这个组件专门负责“一个执行器回调是否已经被处理过”的判断和记录。
 * 之所以从 TaskLifecycleSupport 中拆出来，是因为幂等属于横切可靠性能力，
 * 它和“任务状态如何从 RUNNING 变成 SUCCESS/FAILED/DEFERRED”不是同一类职责。
 * 拆分后生命周期状态机只需要表达业务流转，幂等组件则集中处理唯一键、重复请求、状态摘要和后续审计扩展。</p>
 *
 * <p>实现原则：
 * 1. 先插入 PROCESSING 记录，插入成功才继续执行业务状态变更；
 * 2. 如果插入时发生唯一键冲突，说明相同 taskId + action + idempotencyKey 已经出现过，当前请求按重复回调处理；
 * 3. 业务状态变更成功后，把记录标记为 SUCCEEDED；
 * 4. 当前方法默认参与上层事务。也就是说，如果后续业务变更失败，PROCESSING 记录会一起回滚，
 *    避免形成“幂等键已经占用，但任务实际没有推进”的悬挂状态。</p>
 */
@Component
@RequiredArgsConstructor
public class TaskCallbackIdempotencySupport {

    /**
     * 表示当前请求已经占用幂等键，业务处理尚在进行中。
     */
    private static final String STATE_PROCESSING = "PROCESSING";

    /**
     * 表示当前幂等键对应的业务动作已经成功完成。
     */
    private static final String STATE_SUCCEEDED = "SUCCEEDED";

    /**
     * 表示当前幂等键对应的业务动作处理失败。
     *
     * <p>当前事务模型下失败通常会回滚，所以该状态主要为未来“独立事务记录失败尝试”预留。</p>
     */
    private static final String STATE_FAILED = "FAILED";

    /**
     * 文本摘要最大长度。
     *
     * <p>数据库字段使用 VARCHAR(1000)，这里统一截断，避免异常堆栈、结果 JSON 或 checkpoint 过长导致写库失败。</p>
     */
    private static final int MAX_SUMMARY_LENGTH = 1000;

    private final TaskCallbackIdempotencyMapper idempotencyMapper;

    /**
     * 尝试登记一次执行器回调。
     *
     * @param taskId 任务 ID，幂等保护的业务归属。
     * @param action 回调动作，例如 PROGRESS、COMPLETE、FAIL、DEFER。
     * @param callbackContext 执行器回调上下文，必须包含 runId、executorId、idempotencyKey。
     * @param requestDigest 请求摘要，用于审计和排障，不参与唯一性判断。
     * @return true 表示这是重复回调，上层可以直接返回成功；false 表示首次回调，上层应该继续推进业务状态。
     */
    public boolean isDuplicateCallback(Long taskId,
                                       String action,
                                       TaskExecutionCallbackContext callbackContext,
                                       String requestDigest) {
        /*
         * 幂等登记依赖 idempotencyKey。这里保持“缺少键不直接判重”的行为，
         * 因为 TaskLifecycleSupport 后续会执行更明确的参数校验并返回业务错误。
         */
        if (!hasRequiredIdempotencyInput(taskId, action, callbackContext)) {
            return false;
        }

        LocalDateTime now = LocalDateTime.now();
        TaskCallbackIdempotency record = new TaskCallbackIdempotency();
        record.setTaskId(taskId);
        record.setAction(action);
        record.setIdempotencyKey(callbackContext.idempotencyKey().trim());
        record.setRunId(callbackContext.runId());
        record.setExecutorId(callbackContext.executorId().trim());
        record.setRequestDigest(truncate(requestDigest));
        record.setCallbackState(STATE_PROCESSING);
        record.setFirstSeenTime(now);
        record.setLastSeenTime(now);
        record.setCreateTime(now);
        record.setUpdateTime(now);

        try {
            idempotencyMapper.insert(record);
            return false;
        } catch (DuplicateKeyException ex) {
            /*
             * 数据库唯一索引是并发场景下最可靠的裁判。
             * 捕获唯一键冲突后，我们只刷新 lastSeenTime，让运营人员能看到重复请求仍在发生，
             * 但不再让该请求继续修改任务状态。
             */
            touchDuplicateRecord(taskId, action, callbackContext.idempotencyKey());
            return true;
        }
    }

    /**
     * 将当前幂等记录标记为成功。
     *
     * @param taskId 任务 ID。
     * @param action 回调动作。
     * @param callbackContext 回调上下文。
     * @param responseSummary 业务处理结果摘要，例如 SUCCESS、FAILED、DEFERRED。
     */
    public void markSucceeded(Long taskId,
                              String action,
                              TaskExecutionCallbackContext callbackContext,
                              String responseSummary) {
        updateState(taskId, action, callbackContext, STATE_SUCCEEDED, responseSummary, null);
    }

    /**
     * 将当前幂等记录标记为失败。
     *
     * <p>当前阶段多数失败会随上层事务回滚，本方法暂时作为未来独立事务失败审计的扩展点。
     * 预留该方法可以让后续改造不影响 TaskLifecycleSupport 的业务流程结构。</p>
     */
    public void markFailed(Long taskId,
                           String action,
                           TaskExecutionCallbackContext callbackContext,
                           String errorMessage) {
        updateState(taskId, action, callbackContext, STATE_FAILED, null, errorMessage);
    }

    private void updateState(Long taskId,
                             String action,
                             TaskExecutionCallbackContext callbackContext,
                             String callbackState,
                             String responseSummary,
                             String errorMessage) {
        if (!hasRequiredIdempotencyInput(taskId, action, callbackContext)) {
            return;
        }
        TaskCallbackIdempotency record = findRecord(taskId, action, callbackContext.idempotencyKey());
        if (record == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        record.setCallbackState(callbackState);
        record.setResponseSummary(truncate(responseSummary));
        record.setErrorMessage(truncate(errorMessage));
        record.setLastSeenTime(now);
        record.setUpdateTime(now);
        idempotencyMapper.updateById(record);
    }

    private void touchDuplicateRecord(Long taskId, String action, String idempotencyKey) {
        TaskCallbackIdempotency record = findRecord(taskId, action, idempotencyKey);
        if (record == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        record.setLastSeenTime(now);
        record.setUpdateTime(now);
        idempotencyMapper.updateById(record);
    }

    private TaskCallbackIdempotency findRecord(Long taskId, String action, String idempotencyKey) {
        return idempotencyMapper.selectOne(new LambdaQueryWrapper<TaskCallbackIdempotency>()
                .eq(TaskCallbackIdempotency::getTaskId, taskId)
                .eq(TaskCallbackIdempotency::getAction, action)
                .eq(TaskCallbackIdempotency::getIdempotencyKey, idempotencyKey.trim())
                .last("LIMIT 1"));
    }

    private boolean hasRequiredIdempotencyInput(Long taskId,
                                                String action,
                                                TaskExecutionCallbackContext callbackContext) {
        return taskId != null
                && action != null
                && !action.isBlank()
                && callbackContext != null
                && callbackContext.idempotencyKey() != null
                && !callbackContext.idempotencyKey().isBlank()
                && callbackContext.runId() != null
                && callbackContext.executorId() != null
                && !callbackContext.executorId().isBlank();
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= MAX_SUMMARY_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_SUMMARY_LENGTH);
    }
}

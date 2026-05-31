/**
 * @Author : Cui
 * @Date: 2026/05/08 23:04
 * @Description DataSmart Govern Backend - SyncCallbackIdempotencySupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.datasync.entity.SyncCallbackIdempotency;
import com.czh.datasmart.govern.datasync.mapper.SyncCallbackIdempotencyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * data-sync 执行器回调与租约动作幂等支撑组件。
 *
 * <p>实现原则：
 * 1. 首次请求先插入 PROCESSING 记录，插入成功才继续执行业务状态变更；
 * 2. 唯一键冲突说明同一业务动作已经出现过，当前请求按重复请求处理，不再推进状态；
 * 3. 业务处理成功后把记录标记为 SUCCEEDED；
 * 4. 当前组件默认参与上层事务，业务失败时 PROCESSING 记录会随事务回滚，避免幂等键被错误占用。
 */
@Component
@RequiredArgsConstructor
public class SyncCallbackIdempotencySupport {

    private static final String STATE_PROCESSING = "PROCESSING";
    private static final String STATE_SUCCEEDED = "SUCCEEDED";
    private static final String STATE_FAILED = "FAILED";
    private static final int MAX_SUMMARY_LENGTH = 1000;

    private final SyncCallbackIdempotencyMapper idempotencyMapper;

    /**
     * 尝试登记一次幂等动作。
     *
     * @return true 表示重复请求，上层应直接返回当前状态；false 表示首次请求，上层继续执行业务。
     */
    public boolean isDuplicate(Long tenantId,
                               Long syncTaskId,
                               Long executionId,
                               String action,
                               String scopeKey,
                               String idempotencyKey,
                               String executorId,
                               String requestDigest) {
        if (!hasRequiredInput(tenantId, action, scopeKey, idempotencyKey)) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        SyncCallbackIdempotency record = new SyncCallbackIdempotency();
        record.setTenantId(tenantId);
        record.setSyncTaskId(syncTaskId);
        record.setExecutionId(executionId);
        record.setScopeKey(scopeKey.trim());
        record.setAction(action.trim().toUpperCase());
        record.setIdempotencyKey(idempotencyKey.trim());
        record.setExecutorId(trimToNull(executorId));
        record.setRequestDigest(truncate(requestDigest));
        record.setCallbackState(STATE_PROCESSING);
        record.setFirstSeenTime(now);
        record.setLastSeenTime(now);
        record.setCreateTime(now);
        record.setUpdateTime(now);
        try {
            idempotencyMapper.insert(record);
            return false;
        } catch (DuplicateKeyException exception) {
            touchDuplicate(tenantId, action, scopeKey, idempotencyKey);
            return true;
        }
    }

    /**
     * 标记幂等动作已成功。
     */
    public void markSucceeded(Long tenantId,
                              String action,
                              String scopeKey,
                              String idempotencyKey,
                              String responseSummary) {
        updateState(tenantId, action, scopeKey, idempotencyKey, STATE_SUCCEEDED, responseSummary, null);
    }

    /**
     * 标记幂等动作失败。
     *
     * <p>当前事务模型下失败通常会回滚，本方法主要为后续独立失败审计预留。
     */
    public void markFailed(Long tenantId,
                           String action,
                           String scopeKey,
                           String idempotencyKey,
                           String errorMessage) {
        updateState(tenantId, action, scopeKey, idempotencyKey, STATE_FAILED, null, errorMessage);
    }

    private void touchDuplicate(Long tenantId, String action, String scopeKey, String idempotencyKey) {
        SyncCallbackIdempotency record = findRecord(tenantId, action, scopeKey, idempotencyKey);
        if (record == null) {
            return;
        }
        record.setLastSeenTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        idempotencyMapper.updateById(record);
    }

    private void updateState(Long tenantId,
                             String action,
                             String scopeKey,
                             String idempotencyKey,
                             String state,
                             String responseSummary,
                             String errorMessage) {
        if (!hasRequiredInput(tenantId, action, scopeKey, idempotencyKey)) {
            return;
        }
        SyncCallbackIdempotency record = findRecord(tenantId, action, scopeKey, idempotencyKey);
        if (record == null) {
            return;
        }
        record.setCallbackState(state);
        record.setResponseSummary(truncate(responseSummary));
        record.setErrorMessage(truncate(errorMessage));
        record.setLastSeenTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        idempotencyMapper.updateById(record);
    }

    /**
     * 查询指定幂等键的当前记录。
     *
     * <p>早期该方法仅作为组件内部工具方法存在；Agent 触发 data-sync 的场景需要在命中重复请求时读取首次成功响应摘要，
     * 以便 worker 重试不会因为“响应丢失”而重新创建同步任务。这里选择暴露只读查询，而不是让上层直接访问 Mapper，
     * 是为了继续把 action 规范化、scopeKey 裁剪和唯一键语义集中在幂等支撑组件内。</p>
     */
    public SyncCallbackIdempotency findRecord(Long tenantId, String action, String scopeKey, String idempotencyKey) {
        return idempotencyMapper.selectOne(new LambdaQueryWrapper<SyncCallbackIdempotency>()
                .eq(SyncCallbackIdempotency::getTenantId, tenantId)
                .eq(SyncCallbackIdempotency::getAction, action.trim().toUpperCase())
                .eq(SyncCallbackIdempotency::getScopeKey, scopeKey.trim())
                .eq(SyncCallbackIdempotency::getIdempotencyKey, idempotencyKey.trim())
                .last("LIMIT 1"));
    }

    private boolean hasRequiredInput(Long tenantId, String action, String scopeKey, String idempotencyKey) {
        return tenantId != null
                && action != null
                && !action.isBlank()
                && scopeKey != null
                && !scopeKey.isBlank()
                && idempotencyKey != null
                && !idempotencyKey.isBlank();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

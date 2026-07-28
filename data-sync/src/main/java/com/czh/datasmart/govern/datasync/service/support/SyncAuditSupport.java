/**
 * @Author : Cui
 * @Date: 2026/05/07 21:40
 * @Description DataSmart Govern Backend - SyncAuditSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.entity.SyncAuditRecord;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncAuditRecordMapper;
import com.czh.datasmart.govern.datasync.mapper.SyncTaskMapper;
import com.czh.datasmart.govern.datasync.support.SyncAuditActionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 数据同步审计支撑组件。
 *
 * <p>审计写入从 ServiceImpl 中拆出，是为了让业务方法只表达“保存任务、运行任务”的主流程。
 * 后续如果审计要扩展到 Kafka、OpenSearch、归档库或统一 audit-center，只需要改这个组件。
 */
@Component
@RequiredArgsConstructor
public class SyncAuditSupport {

    private final SyncAuditRecordMapper auditRecordMapper;
    private final SyncTaskMapper taskMapper;

    /**
     * 保存同步审计记录。
     *
     * @param tenantId 租户 ID
     * @param syncTaskId 任务 ID
     * @param executionId 执行记录 ID，可为空
     * @param actionType 审计动作类型
     * @param actorContext 操作者上下文
     * @param payload 动作载荷摘要，建议只保存脱敏摘要，不保存大对象和密钥
     */
    public void saveAudit(Long tenantId,
                          Long syncTaskId,
                          Long executionId,
                          SyncAuditActionType actionType,
                          SyncActorContext actorContext,
                          String payload) {
        SyncAuditRecord record = new SyncAuditRecord();
        record.setTenantId(tenantId);
        record.setSyncTaskId(syncTaskId);
        record.setExecutionId(executionId);
        fillProjectScope(record);
        record.setActionType(actionType.name());
        record.setActorId(actorContext == null ? null : actorContext.actorId());
        record.setActorRole(actorContext == null ? null : actorContext.actorRole());
        record.setActionPayload(payload);
        record.setResult("SUCCESS");
        record.setTraceId(actorContext == null ? null : actorContext.traceId());
        record.setCreateTime(LocalDateTime.now());
        auditRecordMapper.insert(record);
    }

    /**
     * 为审计记录补齐项目/工作空间范围。
     *
     * <p>审计记录的价值不只是“留一条日志”，而是未来能回答：
     * 1. 某个项目在一段时间内发生了哪些同步变更；
     * 2. 某个项目的失败重跑、人工介入、事故关闭是否符合治理要求；
     * 3. 审计员是否可以按项目导出证据，而不是只能按租户导出大而全的审计流水。
     *
     * <p>多数 data-sync 审计动作都关联 syncTaskId，因此这里在审计支撑组件内部根据任务回填 projectId/workspaceId。
     * 这样调用方仍然只需要传业务最关心的 taskId/executionId，不必在每个业务方法里重复写一遍项目字段。
     *
     * <p>如果 syncTaskId 为空，只保留租户级审计范围，不尝试猜测任务归属。
     */
    private void fillProjectScope(SyncAuditRecord record) {
        if (record.getSyncTaskId() == null) {
            return;
        }
        SyncTask task = taskMapper.selectById(record.getSyncTaskId());
        if (task == null) {
            return;
        }
        record.setProjectId(task.getProjectId());
        record.setWorkspaceId(task.getWorkspaceId());
    }
}

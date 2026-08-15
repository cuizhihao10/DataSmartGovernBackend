/**
 * @Author : Cui
 * @Date: 2026/08/15
 * @Description DataSmart Govern Backend - SyncAgentExecutionCorrelationSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.AgentSyncTaskExecuteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirectAgentInvocationContext;
import com.czh.datasmart.govern.datasync.entity.SyncAgentExecutionCorrelation;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.mapper.SyncAgentExecutionCorrelationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 管理 Agent 调用与同步 execution 的最小关联事实。
 *
 * <p>这个 support 只负责“记录身份”和“查询身份”，不参与任务状态变更。记录放在 Agent 执行入口的同一事务内，
 * 这样成功返回给上游时，execution 和跨域关联要么一起提交，要么一起回滚。</p>
 */
@Service
@RequiredArgsConstructor
public class SyncAgentExecutionCorrelationSupport {

    private final SyncAgentExecutionCorrelationMapper mapper;

    /**
     * 在 Agent 工具成功创建 execution 后写入低敏跨域关联。
     *
     * <p>请求重放时数据库唯一键负责最终裁决；这里不更新首次写入的 command/session/run/audit，避免后来的
     * 非同一请求覆盖原始审计链。缺少 executionId 或必需身份时直接跳过，普通手工执行不会被伪造为 Agent 执行。</p>
     *
     * @param request Agent 内部执行请求
     * @param syncExecutionId 已经入队的 execution ID
     */
    public void record(AgentSyncTaskExecuteRequest request, Long syncExecutionId) {
        if (request == null || syncExecutionId == null
                || blank(request.getCommandId()) || blank(request.getSessionId())
                || blank(request.getRunId()) || blank(request.getAuditId())
                || request.getTenantId() == null || request.getSyncTaskId() == null) {
            return;
        }
        SyncAgentExecutionCorrelation record = new SyncAgentExecutionCorrelation();
        record.setTenantId(request.getTenantId());
        record.setProjectId(request.getProjectId());
        record.setSyncTaskId(request.getSyncTaskId());
        record.setSyncExecutionId(syncExecutionId);
        record.setCommandId(request.getCommandId().trim());
        record.setEntryMode("ASYNC_AGENT_COMMAND");
        record.setSessionId(request.getSessionId().trim());
        record.setRunId(request.getRunId().trim());
        record.setAuditId(request.getAuditId().trim());
        record.setTraceId(trim(request.getTraceId()));
        mapper.insertIfAbsent(record);
    }

    /**
     * 记录同步 Agent 工具直接调用产生的 execution。
     *
     * <p>该入口没有初始 Kafka/HTTP command outbox，commandId 必须保持 null；写一个虚构 ID 会让运维图错误地
     * 声称发生过异步投递。Controller 已完成服务身份校验，本方法仍要求任务范围与 invocation ID 完整，
     * 并把关联写入调用方事务。</p>
     */
    public void recordDirect(SyncTask task,
                             Long syncExecutionId,
                             SyncActorContext actorContext,
                             SyncDirectAgentInvocationContext invocation) {
        if (task == null || task.getId() == null || task.getTenantId() == null || syncExecutionId == null
                || invocation == null || blank(invocation.sessionId()) || blank(invocation.runId())
                || blank(invocation.auditId())) {
            return;
        }
        SyncAgentExecutionCorrelation record = new SyncAgentExecutionCorrelation();
        record.setTenantId(task.getTenantId());
        record.setProjectId(task.getProjectId());
        record.setSyncTaskId(task.getId());
        record.setSyncExecutionId(syncExecutionId);
        record.setCommandId(null);
        record.setEntryMode("DIRECT_AGENT_TOOL");
        record.setSessionId(invocation.sessionId().trim());
        record.setRunId(invocation.runId().trim());
        record.setAuditId(invocation.auditId().trim());
        record.setTraceId(trim(invocation.traceId() == null && actorContext != null
                ? actorContext.traceId() : invocation.traceId()));
        mapper.insertIfAbsent(record);
    }

    /** 查询根 execution 对应的 Agent 身份；查询失败返回 null 由图投影为未关联。 */
    public SyncAgentExecutionCorrelation findLatest(Long tenantId, Long taskId, Long executionId) {
        if (tenantId == null || taskId == null || executionId == null) {
            return null;
        }
        return mapper.selectLatestByExecution(tenantId, taskId, executionId);
    }

    private String trim(String value) {
        return blank(value) ? null : value.trim();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}

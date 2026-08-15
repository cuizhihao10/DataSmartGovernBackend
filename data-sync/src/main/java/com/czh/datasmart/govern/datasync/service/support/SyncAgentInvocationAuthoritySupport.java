/**
 * @Author : Cui
 * @Date: 2026/08/15
 * @Description DataSmart Govern Backend - SyncAgentInvocationAuthoritySupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.controller.dto.AgentSyncTaskExecuteRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirectAgentInvocationContext;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.czh.datasmart.govern.datasync.integration.agent.AgentRuntimeAuditObservation;
import com.czh.datasmart.govern.datasync.integration.agent.HttpAgentRuntimeAuditObservationClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 用 Agent Runtime 权威审计复核 data-sync 收到的跨服务关联身份。
 *
 * <p>共享令牌只证明调用服务持有部署凭证，不能证明请求体中的 tenant/project/session/run/audit 组合真实存在。
 * 本类通过受控只读接口读取 Java 审计，并逐项比对任务归属。任何来源不可用、记录缺失或字段不一致都会
 * fail-closed，防止平台服务账号把错误会话关联到别的租户执行。</p>
 */
@Service
@RequiredArgsConstructor
public class SyncAgentInvocationAuthoritySupport {

    private final HttpAgentRuntimeAuditObservationClient auditClient;

    /** 复核 task-management 异步 data-sync.execute 命令的权威身份。 */
    public void verifyAsync(SyncTask task,
                            AgentSyncTaskExecuteRequest request,
                            SyncActorContext actorContext) {
        if (task == null || request == null
                || !Objects.equals(task.getTenantId(), request.getTenantId())
                || request.getProjectId() != null && !Objects.equals(task.getProjectId(), request.getProjectId())
                || request.getWorkspaceId() != null && !Objects.equals(task.getWorkspaceId(), request.getWorkspaceId())) {
            throw forbidden("异步 Agent 请求范围与同步任务归属不一致");
        }
        verify(task, request.getSessionId(), request.getRunId(), request.getAuditId(),
                "data-sync.execute", actorContext);
    }

    /** 复核 Agent Runtime 同步 sync.task.run 工具的权威身份。 */
    public void verifyDirect(SyncTask task,
                             SyncDirectAgentInvocationContext invocation,
                             SyncActorContext actorContext) {
        if (invocation == null) {
            return;
        }
        verify(task, invocation.sessionId(), invocation.runId(), invocation.auditId(),
                "sync.task.run", actorContext);
    }

    /**
     * 执行精确归属比对。
     *
     * <p>observe 的 HTTP 路由本身已经按 session 恢复原用户并做对象级读取校验，这里继续比对响应字段，
     * 形成双层防护。不能只看 auditId，因为不同环境、导入数据或未来分片都可能出现错误关联。</p>
     */
    private void verify(SyncTask task,
                        String sessionId,
                        String runId,
                        String auditId,
                        String expectedToolCode,
                        SyncActorContext actorContext) {
        AgentRuntimeAuditObservation audit = auditClient.observe(sessionId, runId, auditId, actorContext);
        boolean matches = audit.available() && audit.found()
                && Objects.equals(audit.auditId(), auditId)
                && Objects.equals(audit.sessionId(), sessionId)
                && Objects.equals(audit.runId(), runId)
                && expectedToolCode.equalsIgnoreCase(text(audit.toolCode()))
                && Objects.equals(audit.tenantId(), task.getTenantId())
                && Objects.equals(audit.projectId(), task.getProjectId());
        if (!matches) {
            throw forbidden("Agent 工具审计不存在、不可用或与同步任务归属不一致");
        }
    }

    private PlatformBusinessException forbidden(String message) {
        return new PlatformBusinessException(PlatformErrorCode.FORBIDDEN, message);
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }
}

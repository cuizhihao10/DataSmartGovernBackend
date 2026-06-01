/**
 * @Author : Cui
 * @Date: 2026/06/01 22:21
 * @Description DataSmart Govern Backend - AgentRunToolDagConfirmationQueryService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution.confirmation;

import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagConfirmationQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentRunToolDagConfirmationView;
import com.czh.datasmart.govern.agent.service.runtime.AgentRuntimeEventQueryAccessContext;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * DAG selected-node 确认记录只读查询服务。
 *
 * <p>该服务不创建确认、不撤销确认、不重放 outbox，也不修改任何状态。
 * 它的职责是把确认事实安全地暴露给审计台、运维台、网关诊断和未来管理员补偿台。
 * 这样 selected-node 入箱能力就不再只是内部 Service 的副作用，而是可被产品界面和审计流程复核的 durable action 证据。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentRunToolDagConfirmationQueryService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final AgentRunToolDagConfirmationStore confirmationStore;
    private final AgentRunToolDagConfirmationAccessSupport accessSupport;

    /**
     * 查询某个 Run 下的确认记录。
     *
     * <p>该接口按 runId 从仓储取出有限数量记录，再做三层安全处理：
     * 1. 校验记录的 sessionId 必须和 URL 一致，防止调用方只凭 runId 读到跨会话记录；
     * 2. 按 SELF/PROJECT/TENANT/PLATFORM 数据范围过滤；
     * 3. 转换为安全 DTO，不暴露工具参数或原始 payload。</p>
     *
     * @param sessionId URL 中的会话 ID
     * @param runId URL 中的运行 ID
     * @param limit 调用方期望返回的最大条数，服务端会做默认值和上限保护
     * @param accessContext 当前访问主体和数据范围上下文
     * @return 只读确认记录列表
     */
    public AgentRunToolDagConfirmationQueryResponse listByRun(String sessionId,
                                                               String runId,
                                                               Integer limit,
                                                               AgentRuntimeEventQueryAccessContext accessContext) {
        String normalizedSessionId = requireText(sessionId, "sessionId");
        String normalizedRunId = requireText(runId, "runId");
        int normalizedLimit = normalizeLimit(limit);
        List<AgentRunToolDagConfirmationView> views = confirmationStore.listByRun(normalizedRunId, normalizedLimit).stream()
                .filter(record -> normalizedSessionId.equals(record.sessionId()))
                .filter(record -> accessSupport.canRead(record, accessContext))
                .map(this::toView)
                .toList();
        return new AgentRunToolDagConfirmationQueryResponse(normalizedLimit, views.size(), views);
    }

    /**
     * 查询单条确认记录详情。
     *
     * <p>详情查询比列表查询更适合跳转场景，例如用户从 command outbox、runtime event 或审批卡片点击 confirmationId。
     * 如果记录不存在返回 404；如果记录存在但 sessionId/runId 不匹配，则返回 400，说明调用方 URL 与证据链不一致；
     * 如果记录存在但越权，则返回 403，防止通过枚举 confirmationId 探测其他租户或项目的确认事实。</p>
     */
    public AgentRunToolDagConfirmationView getByConfirmationId(String sessionId,
                                                               String runId,
                                                               String confirmationId,
                                                               AgentRuntimeEventQueryAccessContext accessContext) {
        String normalizedSessionId = requireText(sessionId, "sessionId");
        String normalizedRunId = requireText(runId, "runId");
        String normalizedConfirmationId = requireText(confirmationId, "confirmationId");
        AgentRunToolDagConfirmationRecord record = confirmationStore.findByConfirmationId(normalizedConfirmationId)
                .orElseThrow(() -> new PlatformBusinessException(PlatformErrorCode.NOT_FOUND,
                        "DAG selected-node 确认记录不存在: " + normalizedConfirmationId));
        if (!normalizedSessionId.equals(record.sessionId()) || !normalizedRunId.equals(record.runId())) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "confirmationId 与当前 sessionId/runId 不匹配，拒绝跨上下文读取确认事实");
        }
        accessSupport.assertCanRead(record, accessContext);
        return toView(record);
    }

    private AgentRunToolDagConfirmationView toView(AgentRunToolDagConfirmationRecord record) {
        return new AgentRunToolDagConfirmationView(
                record.confirmationId(),
                record.sessionId(),
                record.runId(),
                record.selectionFingerprint(),
                record.selectedNodeIds(),
                record.selectedAuditIds(),
                record.policyVersions(),
                record.delegationEvidence(),
                record.outboxIds(),
                record.commandIds(),
                record.tenantId(),
                record.projectId(),
                record.workspaceId(),
                record.actorId(),
                record.traceId(),
                record.confirmed(),
                record.status(),
                record.expiresAt(),
                record.createdAt(),
                record.updatedAt()
        );
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "DAG selected-node 确认查询缺少必填字段: " + fieldName);
        }
        return value.trim();
    }
}

/**
 * @Author : Cui
 * @Date: 2026/05/28 18:00
 * @Description DataSmart Govern Backend - AgentToolExecutionEventOutboxQueryService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.outbox;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionEventOutboxQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionEventOutboxRecordView;
import com.czh.datasmart.govern.agent.event.outbox.AgentToolExecutionEventOutboxDiagnostics;
import com.czh.datasmart.govern.agent.event.outbox.AgentToolExecutionEventOutboxRecord;
import com.czh.datasmart.govern.agent.event.outbox.AgentToolExecutionEventOutboxStatus;
import com.czh.datasmart.govern.agent.event.outbox.AgentToolExecutionEventOutboxStore;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agent 工具执行事件 outbox 查询服务。
 *
 * <p>Controller 不直接操作 store，而是通过该服务统一处理参数归一化、状态枚举解析和响应视图转换。
 * 这样后续加入权限过滤、租户隔离、payload 审计授权或数据库分页时，不需要让 Controller 膨胀。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolExecutionEventOutboxQueryService {

    private final AgentToolExecutionEventOutboxStore outboxStore;

    /**
     * 查询 outbox 记录。
     *
     * @param runId 可选 runId；为空时查询当前 JVM 热窗口内的全局记录。
     * @param status 可选 outbox 状态；大小写不敏感，例如 pending/PENDING。
     * @param limit 单次返回上限，默认 100，最大 1000。
     */
    public AgentToolExecutionEventOutboxQueryResponse query(String runId, String status, Integer limit) {
        AgentToolExecutionEventOutboxStatus parsedStatus = parseStatus(status);
        int normalizedLimit = normalizeLimit(limit);
        List<AgentToolExecutionEventOutboxRecord> records = outboxStore.list(
                normalizeText(runId),
                parsedStatus,
                normalizedLimit
        );
        List<AgentToolExecutionEventOutboxRecordView> views = records.stream()
                .map(AgentToolExecutionEventOutboxRecordView::from)
                .toList();
        return new AgentToolExecutionEventOutboxQueryResponse(
                normalizeText(runId),
                parsedStatus == null ? null : parsedStatus.name(),
                normalizedLimit,
                views.size(),
                views
        );
    }

    /**
     * 返回 outbox 诊断摘要。
     */
    public AgentToolExecutionEventOutboxDiagnostics diagnostics() {
        return outboxStore.diagnostics();
    }

    private AgentToolExecutionEventOutboxStatus parseStatus(String status) {
        String normalized = normalizeText(status);
        if (normalized == null) {
            return null;
        }
        try {
            return AgentToolExecutionEventOutboxStatus.valueOf(normalized.toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.BAD_REQUEST,
                    "不支持的 outbox 状态: " + status
            );
        }
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 100;
        }
        return Math.min(limit, 1000);
    }

    private String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}

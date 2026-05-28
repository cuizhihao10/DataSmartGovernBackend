/**
 * @Author : Cui
 * @Date: 2026/05/27 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeEventProjectionQueryService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentRuntimeEventConsumerProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventConsumerDiagnosticsView;
import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventProjectionQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventProjectionView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agent runtime event 投影查询服务。
 *
 * <p>该服务位于 controller 与 projection store 之间，负责把内部投影记录转换成 API 视图。
 * 不让 controller 直接依赖 store，有两个好处：
 * 1. 后续从内存仓储迁移到 MySQL/ClickHouse 时，controller API 不需要变化；
 * 2. 诊断信息可以在这里聚合配置、统计和投影大小，避免把多个底层对象暴露给 Web 层。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentRuntimeEventProjectionQueryService {

    private final AgentRuntimeEventProjectionStore projectionStore;
    private final AgentRuntimeEventConsumerStats consumerStats;
    private final AgentRuntimeEventConsumerProperties properties;
    private final AgentRuntimeEventProjectionAccessSupport accessSupport;
    private final AgentRuntimeEventVisibilitySupport visibilitySupport;

    /**
     * 查询 Agent runtime event 投影。
     *
     * <p>当前返回的是内存热窗口中的事件。生产上如果要支持审计追溯，应切换为持久化仓储，
     * 并在查询条件中继续增加时间范围、分页游标、排序方向和导出权限。</p>
     */
    public AgentRuntimeEventProjectionQueryResponse query(AgentRuntimeEventProjectionQuery query) {
        return buildResponse(query, projectionStore.query(query));
    }

    /**
     * 按访问上下文查询 Agent runtime event 投影。
     *
     * <p>这是面向 HTTP API 的推荐入口。gateway + permission-admin 已经判断“当前角色能否访问事件查询路由”，
     * 但事件明细仍然必须在服务层继续按租户、本人、项目集合收口。这样即使未来某个入口绕过前端、直接调用
     * agent-runtime 服务，也不会因为只做了入口授权而暴露全量事件。</p>
     */
    public AgentRuntimeEventProjectionQueryResponse query(AgentRuntimeEventProjectionQuery query,
                                                          AgentRuntimeEventQueryAccessContext accessContext) {
        AgentRuntimeEventProjectionQuery scopedQuery = accessSupport.restrict(query, accessContext);
        /*
         * HTTP 查询入口的安全链路分三层：
         * 1. gateway/permission-admin 判断“是否允许访问 runtime event 查询路由”；
         * 2. AccessSupport 把 SELF/PROJECT/TENANT/PLATFORM 数据范围落到查询条件；
         * 3. VisibilitySupport 再按角色过滤事件类型并对敏感字段脱敏。
         *
         * 这样可以避免一个常见生产风险：用户确实有权查看某个项目的 Agent 事件，
         * 但不代表他应该看到 prompt、token、SQL、原始 payload 或异常堆栈。
         */
        List<AgentRuntimeEventProjectionRecord> visibleRecords = visibilitySupport.filterVisibleRecords(
                projectionStore.query(scopedQuery),
                accessContext
        );
        return buildResponse(scopedQuery, visibleRecords, accessContext);
    }

    private AgentRuntimeEventProjectionQueryResponse buildResponse(AgentRuntimeEventProjectionQuery query,
                                                                   List<AgentRuntimeEventProjectionRecord> records) {
        return buildResponse(query, records, null);
    }

    private AgentRuntimeEventProjectionQueryResponse buildResponse(AgentRuntimeEventProjectionQuery query,
                                                                   List<AgentRuntimeEventProjectionRecord> records,
                                                                   AgentRuntimeEventQueryAccessContext accessContext) {
        List<AgentRuntimeEventProjectionView> events = records.stream()
                .map(record -> visibilitySupport.maskForAccess(record, accessContext))
                .map(this::toView)
                .toList();
        return new AgentRuntimeEventProjectionQueryResponse(
                query.normalizedLimit(),
                events.size(),
                events
        );
    }

    /**
     * 生成 consumer 诊断视图。
     *
     * <p>该诊断不是健康检查，也不 ping Kafka broker。它回答的是控制面自身状态：
     * listener 是否配置为启动、目标 topic/group 是什么、当前内存投影有多少、消费结果计数如何。
     * Kafka broker 连通性、consumer lag 和分区状态后续应由 observability 模块或 Kafka Exporter 承接。</p>
     */
    public AgentRuntimeEventConsumerDiagnosticsView diagnostics() {
        AgentRuntimeEventConsumerStatsSnapshot stats = consumerStats.snapshot();
        return new AgentRuntimeEventConsumerDiagnosticsView(
                properties.isEnabled(),
                properties.getTopic(),
                properties.getGroupId(),
                properties.getMaxEventsPerRun(),
                properties.getMaxTotalEvents(),
                projectionStore.size(),
                stats.acceptedCount(),
                stats.duplicateCount(),
                stats.rejectedCount(),
                stats.malformedJsonCount(),
                stats.unsupportedSchemaCount(),
                stats.missingEventTypeCount(),
                stats.missingCorrelationIdCount(),
                stats.emptyPayloadCount(),
                stats.averageProcessingMillis(),
                stats.lastAcceptedAt(),
                stats.lastDuplicateAt(),
                stats.lastRejectedAt(),
                stats.lastRejectedReason()
        );
    }

    private AgentRuntimeEventProjectionView toView(AgentRuntimeEventProjectionRecord record) {
        return new AgentRuntimeEventProjectionView(
                record.identityKey(),
                record.schemaVersion(),
                record.source(),
                record.eventType(),
                record.stage(),
                record.message(),
                record.severity(),
                record.tenantId(),
                record.projectId(),
                record.actorId(),
                record.requestId(),
                record.runId(),
                record.sessionId(),
                record.sequence(),
                record.createdAt(),
                record.publishedAt(),
                record.consumedAt(),
                record.attributes()
        );
    }
}

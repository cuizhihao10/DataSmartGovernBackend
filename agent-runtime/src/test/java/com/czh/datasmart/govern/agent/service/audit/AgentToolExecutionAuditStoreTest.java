/**
 * @Author : Cui
 * @Date: 2026/05/28 23:42
 * @Description DataSmart Govern Backend - AgentToolExecutionAuditStoreTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.audit;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionDecisionRequest;
import com.czh.datasmart.govern.agent.event.AgentToolExecutionEventPublisher;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionMode;
import com.czh.datasmart.govern.agent.model.AgentToolExecutionState;
import com.czh.datasmart.govern.agent.model.AgentToolRiskLevel;
import com.czh.datasmart.govern.agent.service.AgentToolExecutionAuditService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具执行审计仓储端口测试。
 *
 * <p>这组测试不是为了证明 ConcurrentHashMap 能工作，而是为了固定服务层与仓储端口之间的可靠性契约。
 * 后续把 {@link AgentToolExecutionAuditStore} 替换为 MySQL/MyBatis/JDBC 实现时，必须继续满足这些语义：
 * 状态先保存，再发布事件；如果保存失败，事件不能对外发布。</p>
 */
class AgentToolExecutionAuditStoreTest {

    @Test
    void stateTransitionShouldPersistBeforePublishingEvent() {
        AgentToolExecutionAuditRecord record = auditRecord("atea-store-approve", AgentToolExecutionState.WAITING_APPROVAL);
        List<String> sequence = new ArrayList<>();
        TrackingAuditStore auditStore = new TrackingAuditStore(sequence);
        auditStore.save(record);
        sequence.clear();
        AgentToolExecutionEventPublisher publisher = (previousState, currentRecord) ->
                sequence.add("publish:" + previousState.name() + "->" + currentRecord.getState().name());
        AgentToolExecutionAuditService service = new AgentToolExecutionAuditService(auditStore, publisher);

        service.approve(
                record.getSessionId(),
                record.getRunId(),
                record.getAuditId(),
                new AgentToolExecutionDecisionRequest("owner-001", "允许继续执行受控工具")
        );

        assertEquals(List.of("save:PLANNED", "publish:WAITING_APPROVAL->PLANNED"), sequence);
        assertEquals(AgentToolExecutionState.PLANNED, auditStore.findById(record.getAuditId()).orElseThrow().getState());
    }

    @Test
    void persistenceFailureShouldStopEventPublishing() {
        AgentToolExecutionAuditRecord record = auditRecord("atea-store-failure", AgentToolExecutionState.WAITING_APPROVAL);
        FailingSaveAuditStore auditStore = new FailingSaveAuditStore(record);
        List<String> publishedEvents = new ArrayList<>();
        AgentToolExecutionEventPublisher publisher = (previousState, currentRecord) ->
                publishedEvents.add(currentRecord.getAuditId());
        AgentToolExecutionAuditService service = new AgentToolExecutionAuditService(auditStore, publisher);

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> service.reject(
                record.getSessionId(),
                record.getRunId(),
                record.getAuditId(),
                new AgentToolExecutionDecisionRequest("owner-001", "保存失败时不能继续广播事件")
        ));

        assertTrue(exception.getMessage().contains("模拟审计仓储不可用"));
        assertTrue(publishedEvents.isEmpty());
    }

    private AgentToolExecutionAuditRecord auditRecord(String auditId, AgentToolExecutionState state) {
        return new AgentToolExecutionAuditRecord(
                auditId,
                "session-store-001",
                "run-store-001",
                "binding-store-001",
                "task.draft.persist",
                "TASK_MANAGEMENT",
                "task-management",
                "/task-drafts",
                null,
                10L,
                20L,
                30L,
                "actor-store",
                AgentToolRiskLevel.HIGH.name(),
                AgentToolExecutionMode.APPROVAL_REQUIRED.name(),
                true,
                false,
                false,
                List.of("CREATE"),
                "模型计划保存任务草稿，需要先由人工确认。",
                Map.of("objective", "生成数据质量任务草稿"),
                Map.of("memoryWritePolicy", "EPISODIC"),
                Map.of("missingRequiredFields", List.of()),
                state,
                "trace-store-001",
                "工具计划等待控制面推进。",
                LocalDateTime.of(2026, 5, 28, 23, 42)
        );
    }

    /**
     * 测试用仓储。
     *
     * <p>这里记录 save 调用顺序，是为了验证服务层不会先发布事件再保存状态。
     * 如果未来 MySQL 实现也通过相同端口注入，那么这个顺序就是事务 outbox 设计的前置条件。</p>
     */
    private static class TrackingAuditStore implements AgentToolExecutionAuditStore {

        private final List<String> sequence;
        private final Map<String, AgentToolExecutionAuditRecord> records = new LinkedHashMap<>();

        private TrackingAuditStore(List<String> sequence) {
            this.sequence = sequence;
        }

        @Override
        public void save(AgentToolExecutionAuditRecord audit) {
            sequence.add("save:" + audit.getState().name());
            records.put(audit.getAuditId(), audit);
        }

        @Override
        public Optional<AgentToolExecutionAuditRecord> findById(String auditId) {
            return Optional.ofNullable(records.get(auditId));
        }

        @Override
        public List<AgentToolExecutionAuditRecord> list(String sessionId, String runId) {
            return records.values().stream()
                    .filter(item -> sessionId == null || sessionId.equals(item.getSessionId()))
                    .filter(item -> runId == null || runId.equals(item.getRunId()))
                    .sorted(Comparator.comparing(AgentToolExecutionAuditRecord::getCreateTime))
                    .toList();
        }
    }

    /**
     * 模拟数据库保存失败的仓储。
     *
     * <p>保存失败时不能继续发布事件，否则 Python Runtime、Gateway WebSocket 或审计中心会看到一个系统事实库里并不存在的状态。
     * 这类“幽灵事件”在生产环境很难排查，因此这里必须用单测固定下来。</p>
     */
    private static class FailingSaveAuditStore implements AgentToolExecutionAuditStore {

        private final AgentToolExecutionAuditRecord record;

        private FailingSaveAuditStore(AgentToolExecutionAuditRecord record) {
            this.record = record;
        }

        @Override
        public void save(AgentToolExecutionAuditRecord audit) {
            throw new IllegalStateException("模拟审计仓储不可用");
        }

        @Override
        public Optional<AgentToolExecutionAuditRecord> findById(String auditId) {
            return record.getAuditId().equals(auditId) ? Optional.of(record) : Optional.empty();
        }

        @Override
        public List<AgentToolExecutionAuditRecord> list(String sessionId, String runId) {
            return List.of(record);
        }
    }
}

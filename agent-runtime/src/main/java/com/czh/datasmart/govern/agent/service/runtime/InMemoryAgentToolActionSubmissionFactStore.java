/**
 * @Author : Cui
 * @Date: 2026/06/28 21:45
 * @Description DataSmart Govern Backend - InMemoryAgentToolActionSubmissionFactStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版受控工具提交事实仓储。
 *
 * <p>它用于本地学习、单元测试和未启用 MySQL 的开发环境。它能表达“同一 JVM 内不要重复提交”
 * 的语义，但不能跨实例共享，也不能在服务重启后保留事实。生产环境如果开启真实 submit，
 * 应切换到 MySQL store，并确保下游 data-quality/task-management 也具备稳定幂等键。</p>
 */
@Component
@ConditionalOnExpression(
        "'${datasmart.agent-runtime.tool-action-submissions.store:memory}'.equalsIgnoreCase('memory')"
)
public class InMemoryAgentToolActionSubmissionFactStore implements AgentToolActionSubmissionFactStore {

    /**
     * 以 commandId 为主索引保存提交事实。
     *
     * <p>虽然 record 自身还有 submissionIdentityKey，但 commandId 是业务幂等语义的核心。
     * 使用 commandId 作为 Map key 能让 find/start/save 都围绕同一维度收口。</p>
     */
    private final Map<String, AgentToolActionSubmissionFactRecord> records = new ConcurrentHashMap<>();

    @Override
    public Optional<AgentToolActionSubmissionFactRecord> findByCommandId(String commandId) {
        return Optional.ofNullable(records.get(safeCommandId(commandId)));
    }

    @Override
    public synchronized AgentToolActionSubmissionFactStartResult start(
            AgentToolActionSubmissionFactRecord candidate) {
        AgentToolActionSubmissionFactRecord existing = records.get(candidate.commandId());
        if (existing != null) {
            return new AgentToolActionSubmissionFactStartResult(false, existing);
        }
        records.put(candidate.commandId(), candidate);
        return new AgentToolActionSubmissionFactStartResult(true, candidate);
    }

    @Override
    public synchronized AgentToolActionSubmissionFactRecord save(AgentToolActionSubmissionFactRecord record) {
        records.put(record.commandId(), record);
        return record;
    }

    private String safeCommandId(String commandId) {
        return commandId == null ? "" : commandId.trim();
    }
}

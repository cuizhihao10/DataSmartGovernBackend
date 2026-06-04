/**
 * @Author : Cui
 * @Date: 2026/06/04 19:16
 * @Description DataSmart Govern Backend - AgentRuntimeEventVisibilitySupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.config.AgentRuntimeEventConsumerProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventProjectionQueryResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventProjectionView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Agent runtime event 可见性策略测试。
 *
 * <p>Java 控制面和 Python Runtime 都会提供 runtime event replay/query 能力。两侧策略必须同构：
 * 普通用户可以看到关键进度事件，避免界面看起来“什么都没发生”；但属性细节必须脱敏，避免把 Skill code、
 * 权限事实来源、工具参数或上下文细节直接暴露给不具备治理权限的终端用户。</p>
 */
class AgentRuntimeEventVisibilitySupportTest {

    @Test
    void basicUserShouldSeeSkillVisibilityProgressButAttributesAreMasked() {
        InMemoryAgentRuntimeEventProjectionStore store = new InMemoryAgentRuntimeEventProjectionStore(10, 100);
        store.append(skillVisibilityEvent());
        AgentRuntimeEventProjectionQueryService queryService = new AgentRuntimeEventProjectionQueryService(
                store,
                new AgentRuntimeEventConsumerStats(),
                properties(),
                new AgentRuntimeEventProjectionAccessSupport(),
                new AgentRuntimeEventVisibilitySupport(),
                new AgentRuntimeEventDisplaySupport()
        );

        AgentRuntimeEventProjectionQueryResponse response = queryService.query(
                new AgentRuntimeEventProjectionQuery(null, null, null, null,
                        "run-basic-skill", null, null, null, 10),
                ordinaryUserContext()
        );

        assertEquals(1, response.totalMatched());
        AgentRuntimeEventProjectionView event = response.events().getFirst();
        assertEquals("事件详情已按当前角色权限脱敏", event.message());
        assertEquals(AgentRuntimeEventVisibilitySupport.MASKED_VALUE, event.attributes().get("visibleSkillCount"));
        assertEquals(AgentRuntimeEventVisibilitySupport.MASKED_VALUE, event.attributes().get("visibleSkillCodes"));
        assertEquals("BASIC", event.attributes().get(AgentRuntimeEventVisibilitySupport.VISIBILITY_LEVEL_ATTRIBUTE));
        assertEquals("SKILL_VISIBILITY", event.display().category());
        assertEquals("SUMMARY_MASKED", event.display().status());
    }

    private AgentRuntimeEventProjectionRecord skillVisibilityEvent() {
        Instant timestamp = Instant.parse("2026-06-04T11:00:00Z");
        return new AgentRuntimeEventProjectionRecord(
                "skill-visibility-basic",
                "agent-runtime-event.v1",
                "python-ai-runtime",
                "skill_visibility_snapshot_recorded",
                "record_skill_visibility_snapshot",
                "已记录本轮会话级 Skill 可见性快照。",
                "info",
                "10",
                "20",
                "1001",
                "request-basic-skill",
                "run-basic-skill",
                "session-basic-skill",
                1L,
                timestamp,
                timestamp,
                timestamp,
                Map.of(
                        "visibleSkillCount", 2,
                        "hiddenSkillCount", 0,
                        "visibleSkillCodes", List.of("datasource.profiling", "quality.rule.design"),
                        "permissionFactSource", "trusted-control-plane"
                )
        );
    }

    private AgentRuntimeEventQueryAccessContext ordinaryUserContext() {
        return new AgentRuntimeEventQueryAccessContext(
                10L,
                1001L,
                "ORDINARY_USER",
                "trace-basic-visibility-test",
                "SELF",
                List.of()
        );
    }

    private AgentRuntimeEventConsumerProperties properties() {
        AgentRuntimeEventConsumerProperties properties = new AgentRuntimeEventConsumerProperties();
        properties.setEnabled(false);
        properties.setTopic("datasmart.agent-runtime.events");
        properties.setGroupId("datasmart-agent-runtime-control-plane");
        properties.setMaxEventsPerRun(1000);
        properties.setMaxTotalEvents(10000);
        return properties;
    }
}

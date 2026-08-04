/**
 * @Author : Cui
 * @Date: 2026/08/04 00:00
 * @Description DataSmart Govern Backend - AgentSessionStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.session;

import java.util.List;
import java.util.Optional;

/**
 * Agent 会话聚合仓储端口。
 *
 * <p>会话、Run、消息、工具绑定和委托事实共同构成一个安全边界，不能分别落在互不一致的内存 Map 中。
 * 生产环境使用 PostgreSQL 实现，本地轻量测试仍可使用内存实现。</p>
 */
public interface AgentSessionStore {

    void save(AgentSessionRecord session);

    Optional<AgentSessionRecord> findById(String sessionId);

    List<AgentSessionRecord> list(Long tenantId,
                                  Long projectId,
                                  String actorId,
                                  boolean archived,
                                  int limit);

    default List<AgentSessionRecord> list(Long tenantId, Long projectId, String actorId) {
        return list(tenantId, projectId, actorId, false, 100);
    }
}

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

    /**
     * 原子保存一个完整会话聚合。
     *
     * <p>实现必须保证 session 主记录、delegation、tool bindings、runs 和 messages 要么整体成功、要么整体回滚，
     * 否则恢复后可能出现“页面能看到 Run，但执行时找不到委托”这类安全与一致性问题。</p>
     *
     * @param session 已完成领域校验的会话聚合
     */
    void save(AgentSessionRecord session);

    /**
     * 原子追加一条用户可见会话消息，而不覆盖同一会话中已经由其他调用链写入的 Run 或工具事实。
     *
     * <p>Agent 的确认后续跑存在一个重要并发边界：Java 先把当前 Run 终态交给 Python，Python 随后会回调
     * Java 创建下一 Run，最后原 Java 请求再记录二轮助手回复。如果最后一步继续调用 {@link #save(AgentSessionRecord)}
     * 保存调用 Python 之前读取的旧聚合，就会把 Python 刚创建的新 Run 当成“快照中不存在的子记录”删除。
     * 因此，追加对话消息必须是独立的增量写操作，不能通过整聚合替换来模拟。</p>
     *
     * <p>实现必须同时更新会话的 {@code lastMessageAt/updateTime}，并保证消息插入与时间更新处于同一原子边界。
     * 返回 {@code false} 表示会话已经不存在，调用方应停止返回任何后续 Run 引用，避免浏览器拿到悬空操作入口。</p>
     *
     * @param sessionId 消息所属的稳定会话 ID
     * @param message 已完成低敏治理、可以向用户展示的对话消息
     * @return 成功写入或幂等确认消息已存在时返回 true；会话不存在或参数无效时返回 false
     */
    boolean appendConversationMessage(String sessionId, AgentConversationMessageRecord message);

    /**
     * 按稳定 ID 重建完整会话聚合。
     *
     * @param sessionId 会话 ID
     * @return 找到时返回包含子事实的完整聚合，否则返回空 Optional
     */
    Optional<AgentSessionRecord> findById(String sessionId);

    /**
     * 按所有者边界查询历史会话。
     *
     * <p>仓储负责把 tenant/project/actor 条件下沉到数据库并限制返回数量；服务层仍需再次执行对象可见性过滤，
     * 形成纵深防御。结果按置顶优先、最近更新时间倒序返回。</p>
     *
     * @param tenantId 租户过滤条件
     * @param projectId 项目过滤条件
     * @param actorId 用户过滤条件
     * @param archived true 查询已归档，false 查询未归档
     * @param limit 最大返回数，实现还应应用平台硬上限
     * @return 满足边界的会话聚合列表
     */
    List<AgentSessionRecord> list(Long tenantId,
                                  Long projectId,
                                  String actorId,
                                  boolean archived,
                                  int limit);

    /**
     * 为旧调用方保留的未归档会话查询快捷方法。
     *
     * <p>默认最多返回 100 条，防止旧接口在迁移 JDBC 后意外执行无上限全表加载。</p>
     */
    default List<AgentSessionRecord> list(Long tenantId, Long projectId, String actorId) {
        return list(tenantId, projectId, actorId, false, 100);
    }
}

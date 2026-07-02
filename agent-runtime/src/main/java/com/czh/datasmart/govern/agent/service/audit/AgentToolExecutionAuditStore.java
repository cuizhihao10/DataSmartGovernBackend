/**
 * @Author : Cui
 * @Date: 2026/05/28 23:20
 * @Description DataSmart Govern Backend - AgentToolExecutionAuditStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.audit;

import java.util.List;
import java.util.Optional;

/**
 * Agent 工具执行审计仓储端口。
 *
 * <p>这个接口是 agent-runtime 从“演示级内存状态机”走向“生产级可恢复状态机”的关键边界。
 * 在 Codex、Claude Code 这类工具型 Agent 中，工具调用不是一次临时方法调用，而是一条需要被审批、执行、
 * 查询、回放、诊断和审计的业务事实链。因此工具审计记录最终必须进入可持久化、可索引、可恢复的系统事实库。</p>
 *
 * <p>为什么先抽象端口，而不是立刻把 {@link AgentToolExecutionAuditMemoryStore} 改成 MySQL：
 * 1. 当前本地开发环境并不总是启动 MySQL，直接强制依赖数据库会降低学习和联调效率；
 * 2. 审计记录和 outbox 后续应共享同一个数据库事务边界，需要先把业务服务从具体内存实现中解耦出来；
 * 3. 后续无论采用 MyBatis-Plus、JDBC、事件库还是独立审计中心，服务层都不应该感知具体存储技术；
 * 4. 端口可以让测试构造“保存失败”“保存顺序”等场景，提前固定生产级可靠性语义。</p>
 *
 * <p>当前接口保持非常克制，只覆盖 agent-runtime 已经真实使用的能力：
 * 批量创建、单条保存、按主键查询、按 session/run 查询。不要在第一阶段加入复杂分页、全文搜索或归档能力，
 * 避免还没有真实运营数据时就把仓储契约设计得过重。</p>
 */
public interface AgentToolExecutionAuditStore {

    /**
     * 保存单条工具执行审计记录。
     *
     * <p>该方法既用于新增，也用于状态流转后的覆盖更新。
     * 对内存实现来说，它只是一次 Map 覆盖；对 PostgreSQL/JDBC 实现来说，它应被映射为 INSERT ... ON CONFLICT DO UPDATE
     * 或明确的 INSERT/UPDATE 流程，并且需要保证 update_time、状态、审批信息、执行时间、结果摘要等字段被同步。</p>
     *
     * @param audit 工具执行审计记录，包含租户、项目、会话、运行、工具、状态、审批和执行摘要。
     */
    void save(AgentToolExecutionAuditRecord audit);

    /**
     * 批量保存工具执行审计记录。
     *
     * <p>一次 Agent Run 往往会产生多个工具计划，批量保存可以让未来数据库实现使用批处理或事务提交。
     * 默认实现逐条调用 {@link #save(AgentToolExecutionAuditRecord)}，这样简单实现只需要实现单条保存即可。</p>
     *
     * @param audits 同一次或多次 Agent Run 生成的审计记录集合。
     */
    default void saveAll(List<AgentToolExecutionAuditRecord> audits) {
        audits.forEach(this::save);
    }

    /**
     * 按审计 ID 查询单条记录。
     *
     * <p>auditId 是工具执行审计链路的稳定业务主键。Python Runtime、前端详情页、人工审批页和审计中心都会用它定位
     * “某一次工具计划/执行”到底处于什么状态。</p>
     *
     * @param auditId 工具执行审计 ID。
     * @return 找到时返回审计记录；不存在时返回空 Optional。
     */
    Optional<AgentToolExecutionAuditRecord> findById(String auditId);

    /**
     * 按 Agent 会话和运行查询工具审计记录列表。
     *
     * <p>sessionId/runId 都允许为空，是为了保留早期诊断查询能力；生产管理后台最终应叠加租户、项目、角色和分页条件，
     * 避免跨租户扫描或一次性加载过多历史数据。</p>
     *
     * @param sessionId Agent 会话 ID，可为空。
     * @param runId Agent Run ID，可为空。
     * @return 按创建时间升序排列的工具审计记录。
     */
    List<AgentToolExecutionAuditRecord> list(String sessionId, String runId);
}

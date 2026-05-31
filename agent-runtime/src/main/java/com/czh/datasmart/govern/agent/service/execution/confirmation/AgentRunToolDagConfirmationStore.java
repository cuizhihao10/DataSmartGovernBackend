/**
 * @Author : Cui
 * @Date: 2026/06/01 14:23
 * @Description DataSmart Govern Backend - AgentRunToolDagConfirmationStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution.confirmation;

import java.util.List;
import java.util.Optional;

/**
 * DAG selected-node 确认记录仓储协议。
 *
 * <p>上层服务只依赖该接口，不关心记录最终落在内存、MySQL、Redis 还是审计中心。
 * 这能避免 selected-node 执行治理继续膨胀成一个巨大 Impl 文件，也让后续生产级持久化成为可替换增量。</p>
 */
public interface AgentRunToolDagConfirmationStore {

    /**
     * 幂等保存确认记录。
     *
     * <p>confirmationId 由 sessionId、runId、selectionFingerprint 和 selectedAuditIds 稳定派生。
     * 因此用户刷新页面、网关超时重试或 Python Runtime 重放同一次确认时，应返回首次保存的记录，
     * 而不是制造多条“看起来像多次确认”的审计事实。</p>
     *
     * @param record 服务端已经完成 dry-run 指纹校验和 outbox 入箱后构造的确认事实。
     * @return 首次写入或已存在的同一 confirmationId 记录。
     */
    AgentRunToolDagConfirmationRecord saveIfAbsent(AgentRunToolDagConfirmationRecord record);

    /**
     * 按确认 ID 查询单条记录，供测试、诊断接口和未来审计台使用。
     */
    Optional<AgentRunToolDagConfirmationRecord> findByConfirmationId(String confirmationId);

    /**
     * 查询某个 run 的确认历史。
     *
     * <p>后续前端审批时间线、管理员补偿台和审计导出都会围绕 runId 展示确认历史。
     * 这里先提供 limit，避免在尚未设计分页游标时出现全量扫描式接口。</p>
     */
    List<AgentRunToolDagConfirmationRecord> listByRun(String runId, int limit);
}

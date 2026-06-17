/**
 * @Author : Cui
 * @Date: 2026/06/17 00:00
 * @Description DataSmart Govern Backend - AgentToolActionWorkerReceiptIndexStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.util.List;

/**
 * 受控工具动作 worker/dry-run receipt 专用索引仓储协议。
 *
 * <p>该接口是 memory -> MySQL/审计中心 的替换点。当前内存实现用于本地学习、单实例联调和单元测试；
 * 后续商业化版本应提供 MySQL durable store，并在 commandId、tenantId、projectId、runId、sessionId、
 * replaySequence 上建立组合索引，避免恢复预检扫描通用 runtime event 热窗口。</p>
 */
public interface AgentToolActionWorkerReceiptIndexStore {

    /**
     * 幂等写入一条低敏 receipt 索引。
     *
     * @param record 从 runtime event projection 白名单字段物化出的 receipt 索引记录。
     * @return true 表示首次写入，false 表示同一 eventIdentityKey 已存在并按幂等重复处理。
     */
    boolean upsert(AgentToolActionWorkerReceiptIndexRecord record);

    /**
     * 按 commandId 和数据范围查询 receipt。
     *
     * <p>仓储实现必须执行 tenant/project/actor/run/session/authorizedProjectIds 收口。
     * 不能把所有 command 记录先暴露给上层再过滤，否则未来远程/数据库实现容易产生越权观察窗口。</p>
     */
    List<AgentToolActionWorkerReceiptIndexRecord> queryByCommandId(AgentToolActionWorkerReceiptIndexQuery query);

    /**
     * 当前索引记录数量，用于测试、诊断接口和后续 Micrometer 指标。
     */
    int size();
}

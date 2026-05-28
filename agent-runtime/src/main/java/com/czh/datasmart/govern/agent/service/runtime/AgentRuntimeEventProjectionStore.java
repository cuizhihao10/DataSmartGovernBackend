/**
 * @Author : Cui
 * @Date: 2026/05/27 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeEventProjectionStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.util.List;
import java.util.Optional;

/**
 * Agent runtime event 控制面投影仓储协议。
 *
 * <p>这里先定义接口，再提供内存实现，是为了明确后续迁移方向：
 * - 当前阶段：内存实现便于 Kafka consumer 单元测试和本地联调；
 * - 商业化阶段：替换为 MySQL/ClickHouse/Elastic/OpenSearch/审计中心时，Consumer Service 不需要重写；
 * - 高并发阶段：可以按 tenantId/runId 分片或冷热分层，但外层仍使用同一组方法。</p>
 */
public interface AgentRuntimeEventProjectionStore {

    /**
     * 追加一条事件投影。
     *
     * @param record 已解析、已生成幂等键的事件投影。
     * @return true 表示新写入；false 表示 identityKey 已存在，本次属于重复消费。
     */
    boolean append(AgentRuntimeEventProjectionRecord record);

    /**
     * 按幂等键查询事件。
     *
     * <p>主要用于测试、排障和未来人工补偿页面定位“某条 Kafka 消息是否已经被控制面接收”。</p>
     */
    Optional<AgentRuntimeEventProjectionRecord> findByIdentityKey(String identityKey);

    /**
     * 查询某个 run 的事件列表。
     *
     * <p>第一版只按 runId 聚合。后续如果 Python 与 Java 的 runId 还没有完全对齐，也可以扩展
     * sessionId/requestId 查询，支持从 UI 会话或 HTTP 请求角度定位事件。</p>
     */
    List<AgentRuntimeEventProjectionRecord> listByRunId(String runId);

    /**
     * 按多维条件查询事件投影。
     *
     * <p>该方法面向控制台、排障页和审计页。相比 `listByRunId`，它支持 tenant/project/actor/session/request
     * 等维度，能够覆盖更多真实产品场景。当前内存实现会在 JVM 内扫描，后续数据库实现应下沉为索引查询。</p>
     */
    List<AgentRuntimeEventProjectionRecord> query(AgentRuntimeEventProjectionQuery query);

    /**
     * 当前投影总量，用于单元测试、诊断接口和后续指标化。
     */
    int size();
}

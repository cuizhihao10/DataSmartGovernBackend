/**
 * @Author : Cui
 * @Date: 2026/06/04 20:07
 * @Description DataSmart Govern Backend - AgentSkillVisibilitySnapshotIndexStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.util.List;

/**
 * Skill 可见性快照专用索引仓储协议。
 *
 * <p>为什么需要从通用 `AgentRuntimeEventProjectionStore` 中拆出来：</p>
 * <p>1. 通用 projection 是事件热窗口，服务所有 runtime event；Skill 可见性是产品语义明确的审计事实；</p>
 * <p>2. 后续商业化报表会按租户、项目、角色、权限来源、Manifest 指纹、隐藏状态分布做查询，
 * 如果一直扫描自由 attributes，会让报表和前端强耦合事件内部结构；</p>
 * <p>3. 专用索引端口可以先用内存实现，后续替换为 MySQL/ClickHouse/OpenSearch 时不影响 consumer 和 controller。</p>
 *
 * <p>安全边界：该索引仍然只存 `SKILL_VISIBILITY_SNAPSHOT_RECORDED` 的低敏聚合事实，不应扩展为保存 prompt、
 * SQL、工具参数、完整权限清单、样本数据或长期记忆正文。如果未来要做敏感详情排障，应走受控审计详情接口。</p>
 */
public interface AgentSkillVisibilitySnapshotIndexStore {

    /**
     * 追加一条 Skill 可见性快照投影。
     *
     * @param record 已由 runtime event consumer 解析并写入通用投影的记录。
     * @return true 表示首次进入专用索引；false 表示重复、非 Skill 可见性事件或被索引策略拒绝。
     */
    boolean append(AgentRuntimeEventProjectionRecord record);

    /**
     * 按控制面查询条件读取 Skill 可见性快照。
     *
     * <p>查询对象继续复用 `AgentRuntimeEventProjectionQuery`，是为了保持 controller、权限收口和 replay 游标语义一致。
     * 专用实现必须固定只返回 Skill 可见性快照，不能把 `eventType` 放开成通用事件查询。</p>
     */
    List<AgentRuntimeEventProjectionRecord> query(AgentRuntimeEventProjectionQuery query);

    /**
     * 当前索引中保存的快照数量。
     *
     * <p>该值后续可进入诊断接口或 Prometheus 指标，用来判断专用索引是否正常物化。</p>
     */
    int size();
}

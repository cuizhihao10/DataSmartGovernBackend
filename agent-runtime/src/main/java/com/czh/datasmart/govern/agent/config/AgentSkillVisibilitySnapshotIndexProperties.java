/**
 * @Author : Cui
 * @Date: 2026/06/04 20:05
 * @Description DataSmart Govern Backend - AgentSkillVisibilitySnapshotIndexProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Skill 可见性快照专用索引配置。
 *
 * <p>通用 runtime event projection 是“所有 Agent 事件的热窗口”，适合 replay、timeline 和临时排障；
 * Skill 可见性快照则是更稳定的产品事实：前端治理卡片、Skill Marketplace 统计、租户能力包灰度、
 * 权限策略漂移排查都需要长期按 Skill 可见性语义查询它。因此这里先单独抽出索引配置，为后续 MySQL、
 * ClickHouse、OpenSearch 或审计中心实现预留边界。</p>
 *
 * <p>当前默认仍是内存索引，原因不是把内存当生产最终形态，而是为了保持本地学习和单元测试低成本：
 * 1. 不要求开发者启动 MySQL/ClickHouse 才能验证 Agent Runtime；
 * 2. 不在事件契约还快速演进时过早固化表结构；
 * 3. 先让 consumer、查询服务、DTO 和权限收口都围绕“专用索引”工作，后续替换 store 即可。</p>
 */
@Data
@ConfigurationProperties(prefix = "datasmart.agent-runtime.runtime-events.skill-visibility-index")
public class AgentSkillVisibilitySnapshotIndexProperties {

    /**
     * 是否启用 Skill 可见性快照专用索引。
     *
     * <p>默认 true，是因为它只在 JVM 内保存低敏快照，不依赖外部数据库；如果排查问题时需要完全退回到
     * 通用 runtime event projection，可以设置为 false。关闭后查询服务会自动 fallback 到通用投影仓储。</p>
     */
    private boolean enabled = true;

    /**
     * 单个 run 在内存索引中最多保留多少条 Skill 可见性快照。
     *
     * <p>正常一次 `/agent/plans` 通常只会产生一条快照；但多轮推理、重试、二轮工具反馈或未来会话内能力
     * 刷新都可能产生多条快照。这里保留 per-run 上限，是为了避免异常循环把某个 run 的快照无限堆积。</p>
     */
    private int maxSnapshotsPerRun = 1000;

    /**
     * 当前 JVM 内所有 Skill 可见性快照的总上限。
     *
     * <p>该值控制内存热索引规模。生产环境如果需要跨实例、跨重启、长期审计，应切换到持久化索引，
     * 而不是无限放大这个内存窗口。</p>
     */
    private int maxTotalSnapshots = 10000;
}

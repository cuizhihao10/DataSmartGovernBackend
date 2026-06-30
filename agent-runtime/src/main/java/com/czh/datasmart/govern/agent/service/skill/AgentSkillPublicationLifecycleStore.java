/**
 * @Author : Cui
 * @Date: 2026/06/30 23:13
 * @Description DataSmart Govern Backend - AgentSkillPublicationLifecycleStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.skill;

import java.util.List;
import java.util.Optional;

/**
 * Skill 发布生命周期仓储端口。
 *
 * <p>当前默认实现是内存仓储，用于本地学习、单测和最小闭环验证；但 service 只依赖该接口。
 * 后续补 MySQL/JDBC 实现时，可以把发布单、状态流转、审核记录和 outbox 写入同一个控制面事实库，
 * 而不需要重写 controller 或状态机规则。</p>
 *
 * <p>安全边界：任何实现都只能保存 {@link AgentSkillPublicationRecord} 中定义的低敏元数据。
 * 不允许为了“方便排查”把 prompt、SQL、工具参数、样本数据、模型输出、凭据或内部 endpoint 塞进发布表。</p>
 */
public interface AgentSkillPublicationLifecycleStore {

    /**
     * 保存或覆盖发布单记录。
     *
     * <p>内存实现会直接替换同 publicationId 的记录；数据库实现应使用乐观锁或状态条件更新，
     * 避免并发审核时出现 DRAFT 直接被两个管理员推进到不同终态。</p>
     */
    AgentSkillPublicationRecord save(AgentSkillPublicationRecord record);

    /**
     * 按发布单 ID 查询。
     */
    Optional<AgentSkillPublicationRecord> findById(String publicationId);

    /**
     * 按 Skill 编码与版本查询。
     *
     * <p>同一个租户/项目内 skillCode + version 应唯一。版本不可重复发布，避免运行时和审计侧无法判断
     * 某个 contentFingerprint 对应哪份能力。</p>
     */
    Optional<AgentSkillPublicationRecord> findBySkillCodeAndVersion(String tenantId,
                                                                     String projectId,
                                                                     String skillCode,
                                                                     String version);

    /**
     * 查询发布单列表。
     */
    List<AgentSkillPublicationRecord> query(AgentSkillPublicationLifecycleQuery query);
}

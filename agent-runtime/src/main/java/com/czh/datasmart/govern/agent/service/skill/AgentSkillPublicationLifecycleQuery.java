/**
 * @Author : Cui
 * @Date: 2026/06/30 23:12
 * @Description DataSmart Govern Backend - AgentSkillPublicationLifecycleQuery.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.skill;

/**
 * Skill 发布生命周期查询条件。
 *
 * <p>该对象由 controller/service 组装后传给 store，避免仓储层直接理解 HTTP 参数。
 * 查询字段全部是低敏控制面字段，适合后续迁移到 MySQL、OpenSearch 或管理台分页查询。</p>
 *
 * @param tenantId 租户收口条件；生产环境应优先来自 gateway 可信 Header
 * @param projectId 项目收口条件；PROJECT 数据范围下必须继续叠加授权项目集合
 * @param skillCode Skill 编码过滤
 * @param domain 治理域过滤，例如 DATA_QUALITY、TASK_MANAGEMENT
 * @param status 发布生命周期状态过滤
 * @param limit 返回数量上限，避免管理台或诊断接口一次扫出过多历史
 */
public record AgentSkillPublicationLifecycleQuery(
        String tenantId,
        String projectId,
        String skillCode,
        String domain,
        AgentSkillPublicationLifecycleStatus status,
        int limit
) {

    public int normalizedLimit() {
        if (limit <= 0) {
            return 50;
        }
        return Math.min(limit, 200);
    }
}

/**
 * @Author : Cui
 * @Date: 2026/05/23 21:37
 * @Description DataSmart Govern Backend - AgentSkillGovernanceDescriptorView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * Agent Skill 治理描述。
 *
 * <p>Skill 是比工具更高一层的能力包，因此它同样需要治理信息。
 * Python Runtime 可以根据这些字段判断是否需要审批、是否必须带租户/项目上下文，
 * Java 控制面可以把这些字段写入审批单或审计流水。
 *
 * @param enabled Skill 是否启用
 * @param riskLevel 风险等级
 * @param approvalPolicy 审批策略
 * @param tenantScoped 是否租户内可见
 * @param projectScoped 是否项目内可见
 * @param auditRequired 是否必须写审计
 */
public record AgentSkillGovernanceDescriptorView(Boolean enabled,
                                                 String riskLevel,
                                                 String approvalPolicy,
                                                 Boolean tenantScoped,
                                                 Boolean projectScoped,
                                                 Boolean auditRequired) {
}

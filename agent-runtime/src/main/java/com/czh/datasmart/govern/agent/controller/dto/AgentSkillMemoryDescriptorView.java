/**
 * @Author : Cui
 * @Date: 2026/05/23 21:37
 * @Description DataSmart Govern Backend - AgentSkillMemoryDescriptorView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * Agent Skill 记忆策略描述。
 *
 * <p>Skill 通常会依赖多类记忆，例如质量规则设计需要语义记忆和历史异常情节记忆。
 * 该视图让 Python Runtime 在选择 Skill 后，能知道应该优先检索哪些记忆，以及生成产物默认可保留多久。
 *
 * @param memoryDependencies Skill 依赖的记忆类型
 * @param defaultMemoryScope 默认记忆范围，例如 SESSION、PROJECT、TENANT
 * @param retentionDays 默认保留天数
 */
public record AgentSkillMemoryDescriptorView(List<String> memoryDependencies,
                                             String defaultMemoryScope,
                                             Integer retentionDays) {
}

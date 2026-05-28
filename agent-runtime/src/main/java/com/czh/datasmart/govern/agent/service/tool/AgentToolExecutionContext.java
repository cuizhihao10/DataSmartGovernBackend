/**
 * @Author : Cui
 * @Date: 2026/05/14 19:12
 * @Description DataSmart Govern Backend - AgentToolExecutionContext.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;

import java.util.Map;

/**
 * Agent 工具执行上下文。
 *
 * <p>该上下文是“工具适配器”和“Agent 控制面”之间的边界对象。
 * 适配器不需要知道 SessionService、Controller、审批接口等上层细节，只需要从上下文中读取：
 * 当前会话、当前 Run、当前工具审计记录、Run 变量和 traceId。
 *
 * <p>保持这个对象清晰非常重要：
 * 1. 后续工具越来越多时，每个工具都可以复用同一套上下文；
 * 2. 工具执行时天然携带租户、项目、工作空间和操作者信息；
 * 3. 未来迁移到 Kafka 异步执行时，也可以把该上下文序列化成工具执行命令。
 */
public record AgentToolExecutionContext(AgentSessionRecord session,
                                        AgentRunRecord run,
                                        AgentToolExecutionAuditRecord audit,
                                        Map<String, Object> variables,
                                        String traceId) {
}

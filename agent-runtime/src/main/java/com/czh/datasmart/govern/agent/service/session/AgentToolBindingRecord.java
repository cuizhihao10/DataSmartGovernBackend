/**
 * @Author : Cui
 * @Date: 2026/05/13 22:48
 * @Description DataSmart Govern Backend - AgentToolBindingRecord.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.session;

import com.czh.datasmart.govern.agent.model.AgentToolBindingStatus;
import com.czh.datasmart.govern.agent.model.AgentToolType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 内部工具绑定记录。
 *
 * <p>当前记录保存在内存中，后续可以一对一迁移为数据库表或 Redis Hash。
 * 这里没有直接复用 Controller DTO，是为了保持“外部 API 契约”和“内部运行时状态”解耦：
 * API 可以演进展示字段，内部记录则服务于状态机、并发控制和审计写入。
 */
public record AgentToolBindingRecord(String bindingId,
                                     String toolCode,
                                     AgentToolType toolType,
                                     String displayName,
                                     String targetService,
                                     String targetEndpoint,
                                     Long targetResourceId,
                                     Boolean readOnly,
                                     String riskLevel,
                                     String executionMode,
                                     Boolean requiresApproval,
                                     Boolean idempotent,
                                     AgentToolBindingStatus status,
                                     List<String> allowedActions,
                                     LocalDateTime createTime) {
}

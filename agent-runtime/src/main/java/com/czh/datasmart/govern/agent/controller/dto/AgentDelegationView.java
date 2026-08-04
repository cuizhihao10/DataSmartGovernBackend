/**
 * @Author : Cui
 * @Date: 2026/08/04 00:00
 * @Description DataSmart Govern Backend - AgentDelegationView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 面向用户和审计页面的低敏 Agent 委托范围。 */
public record AgentDelegationView(String delegationId,
                                  String agentId,
                                  String userActorId,
                                  Long tenantId,
                                  Long projectId,
                                  List<String> toolCodes,
                                  List<String> actions,
                                  List<String> resourceScopes,
                                  String status,
                                  LocalDateTime issuedAt,
                                  LocalDateTime expiresAt,
                                  LocalDateTime revokedAt) {
}

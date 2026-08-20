/**
 * @Author : Cui
 * @Date: 2026/08/21 10:00
 * @Description DataSmart Govern Backend - GraphFactApprovalService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service;

import com.czh.datasmart.govern.permission.controller.dto.GraphFactApprovalEvaluateRequest;
import com.czh.datasmart.govern.permission.controller.dto.GraphFactApprovalRegisterRequest;
import com.czh.datasmart.govern.permission.controller.dto.GraphFactApprovalRegisterResponse;
import com.czh.datasmart.govern.permission.controller.dto.AgentToolActionApprovalFactEvaluationView;

/**
 * 业务图事实审批服务。
 *
 * <p>该服务把通用 Agent 动作审批事实与 GraphRAG 事实包绑定起来，但不负责写 Neo4j。
 * Neo4j 写入由 Kafka 事件 consumer 负责，因而审批服务不会因为图数据库短暂不可用而丢失审批证据。</p>
 */
public interface GraphFactApprovalService {

    /** 登记图事实候选，并在 APPROVED 时写入 Kafka outbox。 */
    GraphFactApprovalRegisterResponse register(GraphFactApprovalRegisterRequest request);

    /** 由摄取 consumer 回查当前审批事实。 */
    AgentToolActionApprovalFactEvaluationView evaluate(GraphFactApprovalEvaluateRequest request);
}

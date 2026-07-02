/**
 * @Author : Cui
 * @Date: 2026/06/11 23:20
 * @Description DataSmart Govern Backend - AgentToolActionApprovalFactStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.support;

import java.util.Optional;

/**
 * Agent 受控工具动作审批事实仓储端口。
 *
 * <p>task-management 不应该直接访问 permission-admin 数据库，permission-admin 的服务实现也不应该把审批事实
 * 固定死在内存 Map。抽出 store 端口后，当前阶段可以使用内存实现，本地联调简单；生产阶段可以替换为
 * PostgreSQL + Redis cache + outbox 审计，而不影响 controller/service/task-management 客户端契约。</p>
 */
public interface AgentToolActionApprovalFactStore {

    /**
     * 保存或覆盖审批事实。
     *
     * @param record 审批事实记录。
     * @return 保存后的记录。
     */
    AgentToolActionApprovalFactRecord save(AgentToolActionApprovalFactRecord record);

    /**
     * 按审批事实 ID 查询。
     *
     * @param approvalFactId 审批事实 ID。
     * @return 命中的审批事实。
     */
    Optional<AgentToolActionApprovalFactRecord> findById(String approvalFactId);
}

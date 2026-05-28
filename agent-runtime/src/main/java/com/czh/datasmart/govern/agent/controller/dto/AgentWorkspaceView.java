/**
 * @Author : Cui
 * @Date: 2026/05/13 22:43
 * @Description DataSmart Govern Backend - AgentWorkspaceView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * Agent 工作空间视图。
 *
 * <p>当前工作空间只生成逻辑键，不立即创建 Redis、MinIO、Chroma 或 Neo4j 资源。
 * 这样可以先把隔离语义固化下来，后续再把 workspaceKey 作为缓存、文件、向量库、知识图谱子图的命名空间前缀。
 */
public record AgentWorkspaceView(String workspaceKey,
                                 String isolationLevel,
                                 Long tenantId,
                                 Long projectId,
                                 Long workspaceId,
                                 String sessionId) {
}

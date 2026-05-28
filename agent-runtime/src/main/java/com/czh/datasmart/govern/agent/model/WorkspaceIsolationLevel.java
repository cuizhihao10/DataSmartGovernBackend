/**
 * @Author : Cui
 * @Date: 2026/05/13 22:40
 * @Description DataSmart Govern Backend - WorkspaceIsolationLevel.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.model;

/**
 * Agent 工作空间隔离级别。
 *
 * <p>工作空间隔离是类 OpenClaw 智能体架构的关键概念：
 * Agent 不应该在一个全局上下文里随意访问所有项目资产，而应该被限制在租户、项目、工作空间、会话四层边界内。
 * 当前版本只记录隔离级别，后续可以将它下沉为 Redis Key 前缀、MinIO 目录、向量库 collection、Neo4j 子图或临时文件目录。
 */
public enum WorkspaceIsolationLevel {

    /**
     * 租户级隔离。
     * 适合租户管理员做全租户治理分析，但需要更强审计和审批。
     */
    TENANT,

    /**
     * 项目级隔离。
     * 这是当前推荐默认值，和前面已落地的 PROJECT 数据范围保持一致。
     */
    PROJECT,

    /**
     * 工作空间级隔离。
     * 适合项目内多团队、多主题、多实验空间并行的场景，粒度更细。
     */
    WORKSPACE,

    /**
     * 会话级隔离。
     * 适合临时、安全要求高或不可复用上下文的 Agent 运行，每个会话拥有独立临时空间。
     */
    SESSION
}

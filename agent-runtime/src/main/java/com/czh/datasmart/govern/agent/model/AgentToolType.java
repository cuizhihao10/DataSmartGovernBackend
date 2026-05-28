/**
 * @Author : Cui
 * @Date: 2026/05/13 22:40
 * @Description DataSmart Govern Backend - AgentToolType.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.model;

/**
 * Agent 可调用工具类型。
 *
 * <p>这里先抽象“业务工具类别”，而不是直接绑定某个 Java 类或 HTTP URL。
 * 原因是 DataSmart Govern 的 Agent 后续会跨多个微服务工作：数据源、质量、同步任务、权限、资产、合规等。
 * 统一工具类型可以让智能网关先做权限、审计、风险分级，再决定真正调用哪个服务。
 */
public enum AgentToolType {

    /**
     * 数据源元数据工具。
     * 典型能力：查看数据源能力画像、表结构、字段、采样统计、连接健康状态。
     */
    DATASOURCE_METADATA,

    /**
     * 数据质量工具。
     * 典型能力：生成质量规则、查询质量报告、聚合异常样本、推荐清洗方案。
     */
    DATA_QUALITY,

    /**
     * 同步任务工具。
     * 典型能力：创建同步任务、检查执行历史、分析失败原因、规划重试或补数。
     */
    DATA_SYNC,

    /**
     * 任务中心工具。
     * 典型能力：创建平台任务、查询队列健康、认领执行、更新进度、处理死信。
     */
    TASK_MANAGEMENT,

    /**
     * 权限与成员工具。
     * 典型能力：解释为什么某个用户能访问某个项目、查询成员授权、生成权限排障建议。
     */
    PERMISSION_ADMIN,

    /**
     * 知识检索工具。
     * 典型能力：RAG/GraphRAG 检索产品文档、数据治理知识库、历史事故复盘。
     */
    KNOWLEDGE_RETRIEVAL,

    /**
     * 只读分析工具。
     * 典型能力：受控只读 SQL、只读统计、只读数据画像。该类型默认应谨慎处理敏感数据。
     */
    READONLY_ANALYTICS
}

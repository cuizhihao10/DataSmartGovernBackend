/**
 * @Author : Cui
 * @Date: 2026/05/13 22:18
 * @Description DataSmart Govern Backend - ModelCapability.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.model;

/**
 * 模型能力枚举。
 *
 * <p>不要把所有 AI 调用都抽象成“问一个大模型”。
 * 企业级智能体平台至少需要区分推理、代码、多模态、向量化和重排能力，
 * 否则后续 RAG、数据质量规则生成、SQL 解释、截图理解、文档理解都会被迫绑定到同一个模型。
 */
public enum ModelCapability {

    /**
     * 通用对话与推理能力。
     */
    CHAT,

    /**
     * 代码、SQL、DSL、配置生成和解释能力。
     */
    CODE,

    /**
     * 图像、截图、表格图片、文档截图等多模态理解能力。
     */
    MULTIMODAL,

    /**
     * 向量化能力，用于 RAG、相似表字段检索、知识库召回。
     */
    EMBEDDING,

    /**
     * 重排能力，用于对初召回文档、表、字段、规则候选进行二次排序。
     */
    RERANK
}

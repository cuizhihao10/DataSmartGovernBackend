/**
 * @Author : Cui
 * @Date: 2026/05/13 22:18
 * @Description DataSmart Govern Backend - ModelWorkloadType.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.model;

import java.util.Locale;

/**
 * 模型工作负载类型。
 *
 * <p>工作负载是模型路由的核心输入。
 * 同样是一次“生成”，数据治理问答、SQL 解释、规则生成、Agent 规划、代码生成需要的模型能力和成本完全不同。
 * 通过工作负载路由，可以让平台在商业部署时按场景选择不同模型、不同超时、不同成本策略。
 */
public enum ModelWorkloadType {
    /**
     * Agent 主推理与任务规划。
     */
    AGENT_REASONING,

    /**
     * 数据治理问答、指标解释、规则说明。
     */
    GOVERNANCE_QA,

    /**
     * SQL、脚本、配置、连接器模板等代码型生成。
     */
    CODE_GENERATION,

    /**
     * 文档、表结构、质量规则、知识库条目的向量化。
     */
    EMBEDDING,

    /**
     * 对召回结果进行重排。
     */
    RERANK,

    /**
     * 截图、文档图像、表格图片等多模态理解。
     */
    MULTIMODAL_UNDERSTANDING;

    /**
     * 将外部字符串安全转换为工作负载类型。
     *
     * <p>Controller 层允许前端传小写、中划线等形式，但服务内部必须使用稳定枚举，
     * 这样配置、审计和模型路由不会因为大小写不同产生分裂。
     */
    public static ModelWorkloadType fromValue(String value) {
        if (value == null || value.isBlank()) {
            return AGENT_REASONING;
        }
        return ModelWorkloadType.valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
    }
}

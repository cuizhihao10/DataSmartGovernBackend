/**
 * @Author : Cui
 * @Date: 2026/07/02 02:30
 * @Description DataSmart Govern Backend - AgentToolInputFieldProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import lombok.Data;

/**
 * Agent 工具输入字段的轻量 schema 配置。
 *
 * <p>工具目录需要向 Java 控制面、Python Runtime、审批台和审计链路解释每个参数的名称、类型、来源与
 * 敏感性。当前使用轻量字段模型而不是直接引入完整 JSON Schema，是为了让常见 string/number/object
 * 参数保持清晰；未来需要数组元素约束、oneOf/anyOf 或跨字段条件时，可以在工具 schema 适配层升级，
 * 而不把复杂校验规则直接塞回 {@link AgentRuntimeProperties}。
 *
 * <p>本对象只描述参数元数据，不保存真实参数值。SQL、文件路径、凭据、样本主键等敏感值不得写入
 * Spring 配置或日志；运行时仍由参数校验、审批和沙箱策略处理具体值。
 */
@Data
public class AgentToolInputFieldProperties {

    /**
     * 稳定字段名，应与工具调用 payload 的 key 一致。
     */
    private String name;

    /**
     * 字段类型，例如 string、number、boolean、object 或 array。
     */
    private String type = "string";

    /**
     * 是否必须在进入可执行状态前完成解析。
     */
    private Boolean required = false;

    /**
     * 面向规划器、审批人和维护者的业务说明。
     */
    private String description;

    /**
     * 用于学习和配置审查的低敏示例值，禁止放入真实凭据或客户数据。
     */
    private String example;

    /**
     * 是否属于需要脱敏展示、审批保护或禁止普通日志记录的字段。
     */
    private Boolean sensitive = false;

    /**
     * 参数解析方式。
     *
     * <p>支持 USER_REQUIRED、CAN_FILL_FROM_CONTEXT、SYSTEM_INJECTED 和 DERIVED。该值决定规划器应向
     * 用户澄清、从授权上下文补齐、由可信控制面注入，还是根据其他参数推导。
     */
    private String resolution = "USER_REQUIRED";
}

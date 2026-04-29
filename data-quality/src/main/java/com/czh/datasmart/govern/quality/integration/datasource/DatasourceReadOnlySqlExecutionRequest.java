/**
 * @Author : Cui
 * @Date: 2026/04/28 21:06
 * @Description DataSmart Govern Backend - DatasourceReadOnlySqlExecutionRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.integration.datasource;

import lombok.Data;

/**
 * datasource-management 受控只读 SQL 执行请求的本地合同模型。
 *
 * <p>data-quality 不直接引用 datasource-management 的 Java 类，是为了保持微服务编译期解耦。
 * 这个 DTO 只保留当前质量执行器真正需要发送的字段：
 * SQL、行数限制、超时、执行目的和服务账号角色。
 */
@Data
public class DatasourceReadOnlySqlExecutionRequest {

    /**
     * 由 data-quality SQL 模板构建器生成的只读 SQL。
     */
    private String sql;

    /**
     * 最大返回行数。
     */
    private Integer maxRows;

    /**
     * 查询超时秒数。
     */
    private Integer queryTimeoutSeconds;

    /**
     * 执行目的。
     *
     * <p>建议使用稳定编码，例如 QUALITY_METRIC_SCAN、QUALITY_ANOMALY_SAMPLE。
     * 后续 datasource-management 做审计时，可以按 purpose 聚合统计风险和成本。
     */
    private String purpose;

    /**
     * 操作者角色。
     *
     * <p>当前由配置写入 SERVICE_ACCOUNT；未来应由服务间认证上下文自动生成。
     */
    private String actorRole;
}

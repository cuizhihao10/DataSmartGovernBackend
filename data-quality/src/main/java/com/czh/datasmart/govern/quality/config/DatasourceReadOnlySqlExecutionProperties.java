/**
 * @Author : Cui
 * @Date: 2026/04/28 21:05
 * @Description DataSmart Govern Backend - DatasourceReadOnlySqlExecutionProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * datasource-management 受控只读 SQL 执行客户端配置。
 *
 * <p>这个配置专门服务于 data-quality 的真实扫描执行器：
 * data-quality 负责生成“质量语义正确”的 SQL，datasource-management 负责真正连接源库并施加安全边界。
 *
 * <p>为什么不复用 datasource-metadata-validation 配置：
 * 1. 元数据校验只读取表和字段结构，风险相对较低；
 * 2. 只读 SQL 执行会读取真实业务值、统计值和异常样本，风险更高；
 * 3. 两类能力在生产环境里的开关、fail-open 策略、超时和审计要求可能完全不同。
 *
 * <p>当前先使用 HTTP 直连地址。后续商业化部署时，建议统一迁移到 gateway 内网地址、
 * 服务发现地址或带服务账号签名的服务间调用通道。
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.quality.datasource-read-only-sql")
public class DatasourceReadOnlySqlExecutionProperties {

    /**
     * 是否启用对 datasource-management 只读 SQL 执行接口的调用。
     *
     * <p>如果这里关闭，coordinator 会把支持关系型扫描的任务也明确标记为失败，
     * 而不是绕过 datasource-management 自己连接源库。
     */
    private boolean enabled = true;

    /**
     * datasource-management 服务基础地址。
     */
    private String baseUrl = "http://localhost:8082";

    /**
     * 远程执行失败时是否 fail-open。
     *
     * <p>只读 SQL 执行不建议在生产环境 fail-open。
     * 因为一旦源库不可用、鉴权失败或执行异常，继续生成成功报告会污染质量大盘和审计证据。
     */
    private boolean failOpen = false;

    /**
     * 服务账号所属租户 ID。
     *
     * 当前本地联调使用 0 表示平台级系统调用；生产环境应由调度上下文或服务账号绑定关系提供。
     */
    private Long actorTenantId = 0L;

    /**
     * 服务账号操作者 ID。
     */
    private Long actorId = 0L;

    /**
     * 执行只读 SQL 时传给 datasource-management 的操作者角色。
     *
     * <p>datasource-management 当前允许 SERVICE_ACCOUNT 执行受控只读查询。
     * 后续应替换成真正的服务账号签名身份，而不是仅靠请求体里的角色字段。
     */
    private String actorRole = "SERVICE_ACCOUNT";

    /**
     * 操作者类型。
     */
    private String actorType = "SERVICE_ACCOUNT";

    /**
     * 来源服务。
     */
    private String sourceService = "data-quality";

    /**
     * metric SQL 的默认最大返回行数。
     *
     * <p>质量指标 SQL 正常只返回一行，保留这个配置是为了避免异常 SQL 返回过多结果。
     */
    private Integer metricMaxRows = 1;

    /**
     * metric SQL 的默认超时秒数。
     */
    private Integer metricQueryTimeoutSeconds = 30;

    /**
     * 异常样本 SQL 的默认最大返回行数。
     *
     * <p>最终还会和扫描计划里的 anomalySampleLimit 取较小值，避免一次回写过多异常明细。
     */
    private Integer anomalySampleMaxRows = 100;

    /**
     * 异常样本 SQL 的默认超时秒数。
     */
    private Integer anomalySampleQueryTimeoutSeconds = 30;
}

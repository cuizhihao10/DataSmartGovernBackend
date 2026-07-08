/**
 * @Author : Cui
 * @Date: 2026/07/08 16:42
 * @Description DataSmart Govern Backend - DatasourceReadOnlySqlRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.sql;

import lombok.Getter;
import lombok.Setter;

/**
 * datasource-management 只读 SQL 请求镜像。
 *
 * <p>该类刻意不直接依赖 datasource-management 的 DTO。微服务之间应该通过稳定的 HTTP JSON 契约交互，
 * 而不是让 data-sync 编译期绑定 datasource-management 内部类。这样 datasource-management 后续拆包、
 * 灰度或独立发布时，不会迫使 data-sync 跟着重新编译。</p>
 */
@Getter
@Setter
public class DatasourceReadOnlySqlRequest {

    /**
     * 待探测的 SQL。
     */
    private String sql;

    /**
     * 最大返回行数。创建向导只需要列元数据，通常固定为 1。
     */
    private Integer maxRows;

    /**
     * JDBC Statement 查询超时秒数。
     */
    private Integer queryTimeoutSeconds;

    /**
     * 调用目的，用于 datasource-management 审计。
     */
    private String purpose;

    /**
     * 操作者租户 ID。
     */
    private Long actorTenantId;

    /**
     * 操作者 ID。
     */
    private Long actorId;

    /**
     * 操作者角色。服务间调用通常使用 SERVICE_ACCOUNT，并由 Header 再次覆盖。
     */
    private String actorRole;

    /**
     * 操作者类型。创建向导经 data-sync 转发时属于服务账号代理用户操作。
     */
    private String actorType;

    /**
     * 来源服务，固定为 data-sync 或配置中的服务名。
     */
    private String sourceService;

    /**
     * 链路追踪 ID。
     */
    private String traceId;
}

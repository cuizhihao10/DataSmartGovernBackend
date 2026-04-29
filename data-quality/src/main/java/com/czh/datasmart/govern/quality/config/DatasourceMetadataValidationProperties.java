/**
 * @Author : Cui
 * @Date: 2026/04/27 22:00
 * @Description DataSmart Govern Backend - DatasourceMetadataValidationProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 数据源元数据校验配置。
 *
 * <p>质量规则的关系型目标最终需要通过 datasource-management 确认：
 * 数据源是否存在、表是否存在、字段是否存在、当前主体是否有权限查看元数据。
 *
 * <p>但在本地开发、单模块调试或 CI 编译阶段，datasource-management 不一定已经启动。
 * 因此这里把远程校验做成可配置能力：
 * 1. enabled=false 时，只执行结构性校验，不发起远程调用；
 * 2. enabled=true 时，通过 HTTP 调用 datasource-management 的元数据发现接口；
 * 3. failOpen=true 时，远程服务不可用不会阻止规则启用，但会给出风险提示；
 * 4. failOpen=false 时，远程校验失败会阻止目标被判定为可用，更适合生产环境。
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.quality.datasource-metadata-validation")
public class DatasourceMetadataValidationProperties {

    /**
     * 是否启用远程元数据校验。
     */
    private boolean enabled = false;

    /**
     * datasource-management 服务基础地址。
     *
     * <p>本地直连可使用 http://localhost:8082；生产环境可以改为网关地址或服务发现地址。
     */
    private String baseUrl = "http://localhost:8082";

    /**
     * 远程调用失败时是否放行。
     *
     * <p>开发环境建议 true，避免一个服务未启动影响质量模块调试；
     * 生产环境建议 false，避免目标未确认的规则进入调度执行。
     */
    private boolean failOpen = true;

    /**
     * 发起元数据探查时使用的系统操作者 ID。
     */
    private Long actorId = 0L;

    /**
     * 发起元数据探查时使用的系统操作者角色。
     */
    private String actorRole = "SYSTEM";

    /**
     * 发起元数据探查时使用的租户 ID。
     */
    private Long actorTenantId = 0L;

    /**
     * 每次只探查目标表，最大返回 1 张表即可满足存在性校验。
     */
    private Integer maxTables = 1;

    /**
     * 字段级规则需要返回字段清单，限制字段数可以保护超宽表场景。
     */
    private Integer maxColumnsPerTable = 500;
}

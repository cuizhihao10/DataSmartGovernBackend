package com.czh.datasmart.govern.datasource.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @Author : Cui
 * @Date: 2026/04/28 20:12
 * @Description DataSmart Govern Backend - ReadOnlySqlExecutionProperties.java
 * @Version:1.0.0
 *
 * 受控只读 SQL 执行配置。
 *
 * 这个配置类服务于 datasource-management 的“安全读代理”能力：
 * - data-quality、data-asset、profile 等后续模块不应该直接保存或使用外部数据源密码；
 * - 它们应该把只读扫描请求交给 datasource-management，由数据源模块统一做权限、只读校验、超时、行数上限和审计扩展；
 * - 这样可以避免每个业务模块各自实现一套连接外部库的逻辑，降低凭据扩散、越权查询和性能事故风险。
 *
 * 当前版本先做同步、短查询、少量结果返回，定位是“质量检测/字段画像/样本诊断的执行通道”，
 * 不是面向终端用户的大规模导出工具，也不是通用 SQL 工作台。
 */
@Data
@Component
@ConfigurationProperties(prefix = "datasmart.datasource.read-only-sql")
public class ReadOnlySqlExecutionProperties {

    /**
     * 是否启用受控只读 SQL 执行入口。
     *
     * 生产环境中，这个开关建议和网关鉴权、审计日志、租户策略、敏感字段脱敏一起启用。
     * 如果客户环境暂时没有完成这些配套能力，可以先关闭该开关，只保留接口契约和代码能力。
     */
    private Boolean enabled = true;

    /**
     * 调用方没有显式传入 maxRows 时使用的默认返回行数。
     *
     * 这里保持较小默认值，是因为质量检测和字段画像通常只需要统计结果或少量异常样本，
     * 不应该通过这个接口把业务明细批量搬出源库。
     */
    private Integer defaultMaxRows = 100;

    /**
     * 平台允许的绝对最大返回行数。
     *
     * 即便调用方传入更大的 maxRows，也会被压回这个上限。
     * 这是保护源库、网络、JVM 内存和接口响应时间的最后一道硬边界。
     */
    private Integer absoluteMaxRows = 1000;

    /**
     * 调用方没有显式传入 queryTimeoutSeconds 时使用的默认 SQL 超时秒数。
     *
     * JDBC 的 queryTimeout 由驱动具体实现，不同数据库可能存在细节差异；
     * 但把超时策略集中到这里，至少可以形成统一的产品配置面。
     */
    private Integer defaultQueryTimeoutSeconds = 10;

    /**
     * 平台允许的绝对最大 SQL 超时秒数。
     *
     * 受控只读查询不是离线批处理任务，因此不能让一个 HTTP 请求长期占用连接和线程。
     * 后续如果要支持长时间扫描，应改为 task-management 异步任务，而不是放宽这个同步接口。
     */
    private Integer absoluteQueryTimeoutSeconds = 60;
}

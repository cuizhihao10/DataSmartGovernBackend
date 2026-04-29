package com.czh.datasmart.govern.datasource.controller.dto;

import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/04/28 20:12
 * @Description DataSmart Govern Backend - ReadOnlySqlExecutionRequest.java
 * @Version:1.0.0
 *
 * 受控只读 SQL 执行请求。
 *
 * 这个对象不是给“任意 SQL 编辑器”使用的，而是给平台内部模块描述一次短平快的只读查询：
 * - data-quality 可以用它执行质量规则生成的统计 SQL 或异常样本 SQL；
 * - data-asset 可以用它做字段画像、枚举值预览、空值比例探查；
 * - 运维或管理员可以在排障时通过同一套权限边界做有限诊断。
 *
 * 当前已经开始支持从 `X-DataSmart-*` 平台 Header 注入可信上下文。
 * 为了兼容早期服务间调用，actorRole 仍然保留在请求体中；
 * 但如果 Header 中存在 actorRole，Controller 会优先使用 Header 值覆盖请求体值。
 */
@Data
public class ReadOnlySqlExecutionRequest {

    /**
     * 待执行 SQL。
     *
     * 当前仅允许单条 SELECT 语句，不允许 DDL、DML、存储过程、注释和多语句。
     * 这样做不是为了限制产品想象力，而是为了先把跨模块数据读取能力放在可控安全边界内。
     */
    private String sql;

    /**
     * 最大返回行数。
     *
     * 调用方可以根据场景缩小结果集，例如质量异常样本只取 20 行；
     * 但最终值一定会被服务端配置的 absoluteMaxRows 再次限制，避免客户端绕过保护。
     */
    private Integer maxRows;

    /**
     * 查询超时秒数。
     *
     * 这是单次 JDBC Statement 的执行超时，不等同于 HTTP 网关超时。
     * 生产环境通常还需要在 gateway、线程池、连接池、数据库侧共同配置超时，形成多层保护。
     */
    private Integer queryTimeoutSeconds;

    /**
     * 调用目的。
     *
     * 例如 QUALITY_COMPLETENESS_SCAN、QUALITY_UNIQUENESS_SAMPLE、DATA_ASSET_PROFILE。
     * 当前先返回到响应中并用于学习说明；后续接审计表时，它会成为排查“谁为什么查了什么”的重要字段。
     */
    private String purpose;

    /**
     * 操作者租户 ID。
     *
     * 该字段优先由 Controller 从 `X-DataSmart-Tenant-Id` 写入。
     * 审计查询和后续租户配额会依赖它区分不同客户或组织空间。
     */
    private Long actorTenantId;

    /**
     * 操作者 ID。
     *
     * 人工访问时代表用户 ID；服务间访问时可代表服务账号 ID。
     */
    private Long actorId;

    /**
     * 操作者角色。
     *
     * 当前最小可用版本仍用角色完成本地权限判断。
     * 后续应升级为“角色 + 数据范围 + 服务账号签名 + 审批策略”的综合鉴权。
     */
    private String actorRole;

    /**
     * 操作者类型。
     *
     * 例如 USER、SERVICE_ACCOUNT、AGENT。当前主要用于审计区分人工调用和系统调用。
     */
    private String actorType;

    /**
     * 来源服务。
     *
     * 例如 data-quality、data-asset、gateway。审计人员可以据此判断访问来自哪个平台模块。
     */
    private String sourceService;

    /**
     * 链路追踪 ID。
     *
     * 由 gateway 或服务间客户端生成，用于把一次质量任务、只读 SQL 执行和后续报告串起来。
     */
    private String traceId;
}

/**
 * @Author : Cui
 * @Date: 2026/04/25 22:30
 * @Description DataSmart Govern Backend - PlatformContextHeaders.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.common.context;

/**
 * 平台上下文 HTTP Header 常量。
 *
 * gateway 后续应该负责生成、校验和透传这些 Header；各业务微服务只消费这些上下文，不应该各自发明不同名称。
 * 统一 Header 的意义在于：
 * 1. 网关、服务、日志、审计、指标可以使用同一套字段关联请求；
 * 2. 服务间调用时可以稳定传递租户、操作者、traceId；
 * 3. 后续接入 OpenTelemetry 或集中式审计时，不需要逐模块做字段适配。
 */
public final class PlatformContextHeaders {

    public static final String TRACE_ID = "X-DataSmart-Trace-Id";
    public static final String TENANT_ID = "X-DataSmart-Tenant-Id";
    public static final String ACTOR_ID = "X-DataSmart-Actor-Id";
    public static final String ACTOR_ROLE = "X-DataSmart-Actor-Role";
    public static final String ACTOR_TYPE = "X-DataSmart-Actor-Type";
    public static final String SOURCE_SERVICE = "X-DataSmart-Source-Service";
    public static final String WORKSPACE_ID = "X-DataSmart-Workspace-Id";
    public static final String REQUEST_SOURCE = "X-DataSmart-Request-Source";

    /**
     * 权限中心判定出的数据范围级别。
     *
     * <p>该 Header 通常由 gateway 根据 permission-admin 的判定结果写入，下游业务服务只消费它。
     * 典型取值包括 SELF、PROJECT、TENANT、PLATFORM。
     */
    public static final String DATA_SCOPE_LEVEL = "X-DataSmart-Data-Scope-Level";

    /**
     * 权限中心返回的数据范围表达式。
     *
     * <p>表达式描述“为什么是这个范围”或“未来应该如何精确过滤”，例如 owner_id=${actorId}。
     * gateway 不解析表达式，业务服务也不能直接拼接到 SQL；正确做法是把它翻译成安全的查询构造器条件。
     */
    public static final String DATA_SCOPE_EXPRESSION = "X-DataSmart-Data-Scope-Expression";

    /**
     * 权限中心物化后的项目授权集合。
     *
     * <p>PROJECT 数据范围如果只下发 `project_id IN ${actorProjectIds}` 这种表达式，下游服务仍然不知道
     * `${actorProjectIds}` 到底是多少。该 Header 用逗号分隔的项目 ID 列表承载 permission-admin 已经计算好的授权快照，
     * 例如 `101,102,205`。
     *
     * <p>安全边界：该 Header 必须由 gateway 在调用 permission-admin 后写入；业务服务不能相信外部客户端直传的值。
     */
    public static final String AUTHORIZED_PROJECT_IDS = "X-DataSmart-Authorized-Project-Ids";

    /**
     * 当前访问是否需要审批。
     *
     * <p>查询链路可以先把它作为上下文透传和审计字段；高风险写操作后续可以用它进入审批流。
     */
    public static final String APPROVAL_REQUIRED = "X-DataSmart-Approval-Required";

    private PlatformContextHeaders() {
        throw new UnsupportedOperationException("PlatformContextHeaders 是常量类，不允许实例化");
    }
}

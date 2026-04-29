package com.czh.datasmart.govern.datasource.support;

/**
 * @Author : Cui
 * @Date: 2026/04/25 00:00
 * @Description DataSmart Govern Backend - SyncAdminRoutePolicy.java
 * @Version:1.0.0
 *
 * 本地同步治理路由策略定义。
 *
 * 这个枚举把“后端管理路由”与“它保护的资源动作”显式绑定在一起。
 * 在完整 permission-admin 模块尚未独立成型之前，datasource-management 先用这层枚举承载本地权限语义，
 * 让菜单、路由、资源、动作、数据范围之间有一个可读、可审计、可迁移的桥接模型。
 *
 * 每个枚举项都回答五个问题：
 * 1. 这个入口使用什么 HTTP 方法；
 * 2. 这个入口的路径语义是什么；
 * 3. 它保护的是哪个资源；
 * 4. 访问它需要哪个动作权限；
 * 5. 它推荐挂在哪个菜单、默认使用什么数据范围。
 */
public enum SyncAdminRoutePolicy {
    DATASOURCE_METADATA_DISCOVERY("POST", "/datasources/{id}/metadata/discover",
            SyncPermissionResource.DATASOURCE_METADATA, SyncPermissionAction.VIEW_STRUCTURE,
            SyncAdminMenu.DATASOURCE_EXPLORER, SyncDataScopeLevel.TENANT,
            "探查数据源结构、字段、索引和样本预览能力"),
    DATASOURCE_READ_ONLY_SQL_EXECUTION("POST", "/datasources/{id}/sql/read-only/execute",
            SyncPermissionResource.DATASOURCE_READONLY_QUERY, SyncPermissionAction.EXECUTE_READ_ONLY_QUERY,
            SyncAdminMenu.DATASOURCE_EXPLORER, SyncDataScopeLevel.TENANT,
            "通过数据源服务代理执行受控只读 SQL，服务质量扫描、字段画像和有限诊断场景"),
    DATASOURCE_READ_ONLY_SQL_AUDIT_QUERY("GET", "/datasources/sql/read-only/audits",
            SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.VIEW_POLICY,
            SyncAdminMenu.SYNC_PERMISSION_CENTER, SyncDataScopeLevel.TENANT,
            "查询受控只读 SQL 执行审计，支撑合规追踪、源库访问排障和运营复盘"),
    SYNC_TEMPLATE_MANAGEMENT("POST", "/sync/templates",
            SyncPermissionResource.SYNC_TEMPLATE, SyncPermissionAction.MANAGE,
            SyncAdminMenu.SYNC_TEMPLATE_CENTER, SyncDataScopeLevel.OWNED,
            "创建或更新同步模板，属于配置治理入口"),
    SYNC_TEMPLATE_VALIDATION("POST", "/sync/templates/{id}/validate",
            SyncPermissionResource.SYNC_TEMPLATE, SyncPermissionAction.MANAGE,
            SyncAdminMenu.SYNC_TEMPLATE_CENTER, SyncDataScopeLevel.OWNED,
            "执行模板智能校验，提前发现执行风险"),
    SYNC_TASK_OPERATION("POST", "/sync/tasks/{id}/operate",
            SyncPermissionResource.SYNC_TASK, SyncPermissionAction.OPERATE_OWNED,
            SyncAdminMenu.SYNC_TASK_CENTER, SyncDataScopeLevel.OWNED,
            "普通任务生命周期操作入口，默认面向 owned 任务"),
    SYNC_APPROVAL_OPERATION("POST", "/sync/tasks/{id}/approve",
            SyncPermissionResource.SYNC_APPROVAL, SyncPermissionAction.APPROVE,
            SyncAdminMenu.SYNC_APPROVAL_CENTER, SyncDataScopeLevel.TENANT,
            "审批待审核同步任务"),
    SYNC_QUEUE_HEALTH("GET", "/sync/admin/tasks/queue-health",
            SyncPermissionResource.SYNC_QUEUE, SyncPermissionAction.VIEW_QUEUE_HEALTH,
            SyncAdminMenu.SYNC_QUEUE_CENTER, SyncDataScopeLevel.TENANT,
            "查看队列健康、积压与压力快照"),
    SYNC_QUEUE_AGING_SCAN("POST", "/sync/admin/tasks/queue-aging-scan",
            SyncPermissionResource.SYNC_QUEUE, SyncPermissionAction.SCAN_QUEUE_AGING,
            SyncAdminMenu.SYNC_QUEUE_CENTER, SyncDataScopeLevel.TENANT,
            "执行队列老化扫描并生成治理结果"),
    SYNC_ALERT_QUERY("GET", "/sync/admin/alerts",
            SyncPermissionResource.SYNC_ALERT, SyncPermissionAction.VIEW_ALERT,
            SyncAdminMenu.SYNC_ALERT_CENTER, SyncDataScopeLevel.TENANT,
            "查看治理告警列表"),
    SYNC_ALERT_DELIVERY_QUERY("GET", "/sync/admin/alerts/{id}/deliveries",
            SyncPermissionResource.SYNC_ALERT_DELIVERY, SyncPermissionAction.VIEW_ALERT,
            SyncAdminMenu.SYNC_ALERT_CENTER, SyncDataScopeLevel.TENANT,
            "查看治理告警的每次投递明细"),
    SYNC_ALERT_DISPATCH("POST", "/sync/admin/alerts/{id}/dispatch",
            SyncPermissionResource.SYNC_ALERT, SyncPermissionAction.DISPATCH_ALERT,
            SyncAdminMenu.SYNC_ALERT_CENTER, SyncDataScopeLevel.TENANT,
            "手动补投治理告警"),
    SYNC_ALERT_RETRYABLE_DISPATCH("POST", "/sync/admin/alerts/dispatch-retryable",
            SyncPermissionResource.SYNC_ALERT, SyncPermissionAction.DISPATCH_ALERT,
            SyncAdminMenu.SYNC_ALERT_CENTER, SyncDataScopeLevel.TENANT,
            "批量扫描并补投可重试告警"),
    SYNC_ALERT_OUTBOX_HEALTH("GET", "/sync/admin/alerts/outbox-health",
            SyncPermissionResource.SYNC_ALERT, SyncPermissionAction.VIEW_ALERT,
            SyncAdminMenu.SYNC_ALERT_CENTER, SyncDataScopeLevel.TENANT,
            "查看治理告警 outbox 健康快照，包括积压、失败、死信和租约状态"),
    SYNC_ALERT_OUTBOX_LEASE_RECOVERY("POST", "/sync/admin/alerts/recover-expired-leases",
            SyncPermissionResource.SYNC_ALERT, SyncPermissionAction.DISPATCH_ALERT,
            SyncAdminMenu.SYNC_ALERT_CENTER, SyncDataScopeLevel.TENANT,
            "释放治理告警 outbox 的过期投递租约，避免宕机实例长期占用告警"),
    SYNC_PERMISSION_POLICY_VIEW("GET", "/sync/admin/permissions/snapshot",
            SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.VIEW_POLICY,
            SyncAdminMenu.SYNC_PERMISSION_CENTER, SyncDataScopeLevel.TENANT,
            "查看本地权限、菜单、路由和数据范围快照"),
    SYNC_PERMISSION_BINDING_QUERY("GET", "/sync/admin/permissions/bindings",
            SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.VIEW_POLICY,
            SyncAdminMenu.SYNC_PERMISSION_CENTER, SyncDataScopeLevel.TENANT,
            "查看数据库里的角色权限绑定记录"),
    SYNC_PERMISSION_BINDING_REPLACE("POST", "/sync/admin/permissions/bindings/replace",
            SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.MANAGE_POLICY,
            SyncAdminMenu.SYNC_PERMISSION_CENTER, SyncDataScopeLevel.TENANT,
            "批量替换角色在当前作用域下的权限绑定"),
    SYNC_PERMISSION_CHANGE_REQUEST_SUBMIT("POST", "/sync/admin/permissions/binding-change-requests",
            SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.MANAGE_POLICY,
            SyncAdminMenu.SYNC_PERMISSION_CENTER, SyncDataScopeLevel.TENANT,
            "提交权限绑定变更申请，先申请再审批再执行"),
    SYNC_PERMISSION_CHANGE_REQUEST_QUERY("GET", "/sync/admin/permissions/binding-change-requests",
            SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.VIEW_POLICY,
            SyncAdminMenu.SYNC_PERMISSION_CENTER, SyncDataScopeLevel.TENANT,
            "查询权限绑定变更申请列表"),
    SYNC_PERMISSION_CHANGE_REQUEST_APPROVE("POST", "/sync/admin/permissions/binding-change-requests/{id}/approve",
            SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.APPROVE,
            SyncAdminMenu.SYNC_PERMISSION_CENTER, SyncDataScopeLevel.TENANT,
            "审批权限绑定变更申请并在通过后执行落库"),
    SYNC_PERMISSION_APPROVAL_DELEGATE_RULE_CREATE("POST", "/sync/admin/permissions/approval-delegate-rules",
            SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.MANAGE_POLICY,
            SyncAdminMenu.SYNC_PERMISSION_CENTER, SyncDataScopeLevel.TENANT,
            "创建权限审批委托规则，支持值班轮转和代班治理"),
    SYNC_PERMISSION_APPROVAL_DELEGATE_RULE_QUERY("GET", "/sync/admin/permissions/approval-delegate-rules",
            SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.VIEW_POLICY,
            SyncAdminMenu.SYNC_PERMISSION_CENTER, SyncDataScopeLevel.TENANT,
            "查询权限审批委托规则列表"),
    SYNC_PERMISSION_APPROVAL_DELEGATE_RULE_DISABLE("POST", "/sync/admin/permissions/approval-delegate-rules/{id}/disable",
            SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.MANAGE_POLICY,
            SyncAdminMenu.SYNC_PERMISSION_CENTER, SyncDataScopeLevel.TENANT,
            "禁用权限审批委托规则，保留历史审计轨迹"),
    SYNC_PERMISSION_NOTIFICATION_QUERY("GET", "/sync/admin/permissions/notifications",
            SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.VIEW_POLICY,
            SyncAdminMenu.SYNC_PERMISSION_CENTER, SyncDataScopeLevel.TENANT,
            "查询权限治理通知列表，包括待审批提醒和审批结果通知"),
    SYNC_PERMISSION_NOTIFICATION_READ("POST", "/sync/admin/permissions/notifications/{id}/read",
            SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.VIEW_POLICY,
            SyncAdminMenu.SYNC_PERMISSION_CENTER, SyncDataScopeLevel.TENANT,
            "将权限治理通知标记为已读"),
    SYNC_PERMISSION_NOTIFICATION_REMINDER_SCAN("POST", "/sync/admin/permissions/notifications/scan-reminders",
            SyncPermissionResource.SYNC_PERMISSION_POLICY, SyncPermissionAction.MANAGE_POLICY,
            SyncAdminMenu.SYNC_PERMISSION_CENTER, SyncDataScopeLevel.TENANT,
            "扫描超时待审批申请单并生成普通提醒或升级提醒通知");

    private final String httpMethod;
    private final String path;
    private final SyncPermissionResource resource;
    private final SyncPermissionAction action;
    private final SyncAdminMenu menu;
    private final SyncDataScopeLevel recommendedScope;
    private final String description;

    SyncAdminRoutePolicy(String httpMethod,
                         String path,
                         SyncPermissionResource resource,
                         SyncPermissionAction action,
                         SyncAdminMenu menu,
                         SyncDataScopeLevel recommendedScope,
                         String description) {
        this.httpMethod = httpMethod;
        this.path = path;
        this.resource = resource;
        this.action = action;
        this.menu = menu;
        this.recommendedScope = recommendedScope;
        this.description = description;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public String getPath() {
        return path;
    }

    public SyncPermissionResource getResource() {
        return resource;
    }

    public SyncPermissionAction getAction() {
        return action;
    }

    public SyncAdminMenu getMenu() {
        return menu;
    }

    public SyncDataScopeLevel getRecommendedScope() {
        return recommendedScope;
    }

    public String getDescription() {
        return description;
    }
}

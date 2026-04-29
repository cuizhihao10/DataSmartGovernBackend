package com.czh.datasmart.govern.datasource.support;

/**
 * @Author : Cui
 * @Date: 2026/4/20 23:03
 * @Description DataSmart Govern Backend - SyncAdminMenu.java
 * @Version:1.0.0
 *
 * 本地同步治理菜单定义。
 * permission-admin PRD 明确提到菜单可见性不应只是前端问题，因此这里先在后端沉淀一份
 * “当前模块有哪些运营/治理菜单，以及它们大致对应什么业务能力”的策略枚举。
 *
 * 这份枚举当前服务于两类目标：
 * 1. 向前端或后续网关策略暴露稳定的菜单编码；
 * 2. 让学习和排障时可以从代码直接看到“某个角色理论上能看到哪些治理面板”。
 */
public enum SyncAdminMenu {
    DATASOURCE_EXPLORER("datasource:explorer", "数据源与元数据", "/datasources", "查看数据源、能力画像和元数据发现结果"),
    SYNC_TEMPLATE_CENTER("sync:template-center", "同步模板中心", "/sync/templates", "管理同步模板、字段映射和执行前校验"),
    SYNC_TASK_CENTER("sync:task-center", "同步任务中心", "/sync/tasks", "查看和操作租户内的同步任务"),
    SYNC_APPROVAL_CENTER("sync:approval-center", "审批中心", "/sync/tasks", "处理需要人工审批的同步任务"),
    SYNC_QUEUE_CENTER("sync:queue-center", "队列治理中心", "/sync/admin/tasks", "查看队列健康、老化和容量治理结果"),
    SYNC_ALERT_CENTER("sync:alert-center", "治理告警中心", "/sync/admin/alerts", "查看、确认、解决、补投治理告警"),
    SYNC_PERMISSION_CENTER("sync:permission-center", "权限策略中心", "/sync/admin/permissions", "查看本地资源、菜单、路由和数据范围策略"),
    SYNC_AUDIT_CENTER("sync:audit-center", "审计中心", "/sync/tasks", "查看治理动作和同步执行的审计轨迹");

    private final String code;
    private final String title;
    private final String routePath;
    private final String description;

    SyncAdminMenu(String code, String title, String routePath, String description) {
        this.code = code;
        this.title = title;
        this.routePath = routePath;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public String getRoutePath() {
        return routePath;
    }

    public String getDescription() {
        return description;
    }
}

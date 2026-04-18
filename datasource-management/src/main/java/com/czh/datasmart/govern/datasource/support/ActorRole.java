package com.czh.datasmart.govern.datasource.support;

import java.util.Arrays;
import java.util.EnumSet;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:12
 * @Description DataSmart Govern Backend - ActorRole.java
 * @Version:1.0.0
 *
 * 操作人角色枚举。
 * 当前项目还没有把完整权限中心真正接进来，但为了让任务审批、管理员强制动作、审计记录具备真实产品语义，
 * 这里先把参考文档里的主要角色固化下来，作为后续鉴权系统接入前的控制面抽象。
 */
public enum ActorRole {
    ORDINARY_USER,
    PROJECT_OWNER,
    OPERATOR,
    AUDITOR,
    TENANT_ADMINISTRATOR,
    PLATFORM_ADMINISTRATOR,
    SERVICE_ACCOUNT;

    private static final EnumSet<ActorRole> ADMIN_ROLES =
            EnumSet.of(TENANT_ADMINISTRATOR, PLATFORM_ADMINISTRATOR);

    private static final EnumSet<ActorRole> APPROVAL_ROLES =
            EnumSet.of(OPERATOR, TENANT_ADMINISTRATOR, PLATFORM_ADMINISTRATOR);

    /**
     * 允许做元数据发现的角色集合。
     * 元数据发现虽然不一定直接暴露业务数据，但会暴露库表结构，因此不应完全开放给普通用户。
     */
    private static final EnumSet<ActorRole> METADATA_DISCOVERY_ROLES =
            EnumSet.of(PROJECT_OWNER, OPERATOR, AUDITOR, TENANT_ADMINISTRATOR,
                    PLATFORM_ADMINISTRATOR, SERVICE_ACCOUNT);

    /**
     * 允许查看样本数据的角色集合。
     * 样本预览比“只看结构”更敏感，因此这里故意收得更严。
     */
    private static final EnumSet<ActorRole> SAMPLE_PREVIEW_ROLES =
            EnumSet.of(OPERATOR, AUDITOR, TENANT_ADMINISTRATOR, PLATFORM_ADMINISTRATOR);

    /**
     * 允许管理同步模板的角色集合。
     */
    private static final EnumSet<ActorRole> TEMPLATE_MANAGEMENT_ROLES =
            EnumSet.of(PROJECT_OWNER, OPERATOR, TENANT_ADMINISTRATOR, PLATFORM_ADMINISTRATOR);

    public boolean canApprove() {
        return APPROVAL_ROLES.contains(this);
    }

    public boolean canForceOverride() {
        return ADMIN_ROLES.contains(this);
    }

    public boolean canDiscoverMetadata() {
        return METADATA_DISCOVERY_ROLES.contains(this);
    }

    public boolean canPreviewSampleRows() {
        return SAMPLE_PREVIEW_ROLES.contains(this);
    }

    public boolean canManageTemplates() {
        return TEMPLATE_MANAGEMENT_ROLES.contains(this);
    }

    public static ActorRole fromValue(String value) {
        return Arrays.stream(values())
                .filter(item -> item.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的角色类型: " + value));
    }
}

package com.czh.datasmart.govern.datasource.controller.dto;

import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/4/20 23:04
 * @Description DataSmart Govern Backend - SyncRoutePolicyView.java
 * @Version:1.0.0
 *
 * 路由权限策略视图。
 * 这里返回的不只是一个路径字符串，而是把受保护资源、动作和建议数据范围一起带回去，
 * 便于后续网关、前端菜单控制和统一权限中心做策略对齐。
 */
@Data
public class SyncRoutePolicyView {

    /**
     * HTTP 方法。
     */
    private String httpMethod;

    /**
     * 路径模板。
     */
    private String path;

    /**
     * 后端受保护资源编码。
     */
    private String resource;

    /**
     * 当前路由要求的动作编码。
     */
    private String action;

    /**
     * 推荐归属菜单编码。
     */
    private String menuCode;

    /**
     * 推荐数据范围级别。
     */
    private String recommendedScope;

    /**
     * 当前角色是否可访问。
     */
    private Boolean accessible;

    /**
     * 路由用途说明。
     */
    private String description;
}

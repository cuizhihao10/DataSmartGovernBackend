package com.czh.datasmart.govern.datasource.controller.dto;

import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/4/20 23:04
 * @Description DataSmart Govern Backend - SyncMenuPolicyView.java
 * @Version:1.0.0
 *
 * 菜单策略视图。
 * 用于把本地 permission-admin 对齐结果返回给前端或运维端，
 * 让调用方直接看到“菜单编码、展示标题、入口路径、是否可见”的治理快照。
 */
@Data
public class SyncMenuPolicyView {

    /**
     * 菜单编码。
     */
    private String menuCode;

    /**
     * 菜单标题。
     */
    private String menuTitle;

    /**
     * 建议跳转路径。
     */
    private String routePath;

    /**
     * 菜单业务说明。
     */
    private String description;

    /**
     * 当前角色是否可见。
     */
    private Boolean visible;
}

package com.czh.datasmart.govern.datasource.controller.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/4/24 21:46
 * @Description DataSmart Govern Backend - SyncPermissionBindingReplaceRequest.java
 * @Version:1.0.0
 *
 * 权限绑定批量替换请求。
 * 这里采用“按角色 + 绑定类型整体替换”的语义，而不是一次只改一条。
 *
 * 这样设计的原因：
 * 1. 菜单、路由、动作这类权限通常天然是一个集合，前端管理界面也更容易按整组提交。
 * 2. 角色权限治理最常见的操作不是改单条，而是“把某角色这一组配置换成一套新的”。
 * 3. 这种方式更适合后续接审批、审计和灰度，因为一次变更的意图更清晰。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SyncPermissionBindingReplaceRequest extends SyncActionRequest {

    /**
     * 目标租户。
     * 为空时表示平台级全局绑定，仅平台管理员允许操作。
     */
    private Long targetTenantId;

    /**
     * 被修改绑定的目标角色。
     */
    @NotBlank(message = "targetRole 不能为空")
    private String targetRole;

    /**
     * 绑定类型。
     * 例如 `MENU`、`ROUTE`、`DATA_SCOPE`。
     */
    @NotBlank(message = "bindingType 不能为空")
    private String bindingType;

    /**
     * 本轮替换后的目标绑定值列表。
     * 允许为空列表，表示“清空当前作用域下此类型的数据库绑定”，让解析逻辑回退到全局绑定或配置默认值。
     */
    private List<String> bindingValues = new ArrayList<>();

    /**
     * 新建绑定默认优先级。
     */
    private Integer priority = 100;

    /**
     * 绑定来源。
     * 第一版默认使用 `MANUAL`，后续可以扩展为导入、同步或系统初始化等来源。
     */
    private String bindingSource = "MANUAL";
}

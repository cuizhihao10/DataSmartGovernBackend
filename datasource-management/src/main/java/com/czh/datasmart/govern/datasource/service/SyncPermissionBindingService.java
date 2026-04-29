package com.czh.datasmart.govern.datasource.service;

import com.czh.datasmart.govern.datasource.controller.dto.SyncPermissionBindingReplaceRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPermissionBindingReplaceResult;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPermissionBindingView;
import com.czh.datasmart.govern.datasource.support.SyncPermissionBindingType;

import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/4/24 21:46
 * @Description DataSmart Govern Backend - SyncPermissionBindingService.java
 * @Version:1.0.0
 *
 * 权限绑定治理服务。
 * 这一层负责把“数据库中的绑定记录”解释成“可用于权限快照和管理界面的治理语义”。
 *
 * 这里的职责与 `SyncPermissionPolicyService` 的区别是：
 * 1. `SyncPermissionPolicyService` 更偏最终展示和解释结果；
 * 2. `SyncPermissionBindingService` 更偏绑定对象本身的治理、读取和优先级解析。
 */
public interface SyncPermissionBindingService {

    /**
     * 查询某个角色在某个作用域下的绑定记录。
     *
     * @param actorId 发起查询的操作人
     * @param actorRole 发起查询的角色
     * @param actorTenantId 发起查询者所属租户
     * @param targetTenantId 目标租户，为空表示平台全局
     * @param targetRole 被查询的目标角色
     * @param bindingType 绑定类型，可为空表示查询全部类型
     * @param includeDisabled 是否同时查询已停用绑定
     * @return 可用于管理界面展示的绑定视图列表
     */
    List<SyncPermissionBindingView> listBindings(Long actorId,
                                                 String actorRole,
                                                 Long actorTenantId,
                                                 Long targetTenantId,
                                                 String targetRole,
                                                 String bindingType,
                                                 Boolean includeDisabled);

    /**
     * 批量替换一组绑定。
     * 这是当前阶段最贴近后台权限配置界面的写入语义。
     */
    SyncPermissionBindingReplaceResult replaceBindings(SyncPermissionBindingReplaceRequest request);

    /**
     * 解析某种绑定类型在当前目标租户和目标角色下的“生效值”。
     * 当前优先级顺序是：
     * 1. 租户级数据库绑定
     * 2. 平台全局数据库绑定
     * 3. 配置文件绑定
     * 4. 代码默认推导
     *
     * 本方法只负责数据库层的前两级解析，后两级由上层快照服务继续兜底。
     */
    List<String> resolveEffectiveBindingValues(Long targetTenantId,
                                               String targetRole,
                                               SyncPermissionBindingType bindingType);
}

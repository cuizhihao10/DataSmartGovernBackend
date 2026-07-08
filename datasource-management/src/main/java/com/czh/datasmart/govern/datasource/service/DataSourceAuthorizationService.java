/**
 * @Author : Cui
 * @Date: 2026/07/09 01:07
 * @Description DataSmart Govern Backend - DataSourceAuthorizationService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.datasource.controller.dto.DataSourceAuthorizationView;
import com.czh.datasmart.govern.datasource.controller.dto.GrantDataSourceAuthorizationRequest;
import com.czh.datasmart.govern.datasource.controller.dto.RevokeDataSourceAuthorizationRequest;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.service.support.DatasourceAuthorizationActorContext;
import com.czh.datasmart.govern.datasource.support.DataSourceAuthorizationAction;

import java.util.List;

/**
 * 数据源实例级授权服务。
 *
 * <p>该服务把“授权给其他用户”从页面按钮变成后端可审计、可撤销、可参与资源可见性判断的业务能力。
 * 它不替代 gateway/permission-admin 的路由级 RBAC，而是在已经通过路由鉴权后，继续回答“当前 actor 是否被授权访问这一条具体数据源”。</p>
 */
public interface DataSourceAuthorizationService {

    /**
     * 分页查看某个数据源的授权清单。
     *
     * @param datasource 当前数据源主记录，调用方已经完成项目范围校验。
     * @param page 分页对象。
     * @param subjectType 可选主体类型过滤。
     * @param status 可选授权状态过滤。
     * @return 授权展示视图分页。
     */
    IPage<DataSourceAuthorizationView> pageAuthorizations(DataSourceConfig datasource,
                                                          Page<?> page,
                                                          String subjectType,
                                                          String status);

    /**
     * 授予或更新某个主体对数据源的访问权。
     *
     * <p>同一 datasourceId + subjectType + subjectId 在 ACTIVE 状态下只能存在一条记录。
     * 重复授权会更新动作集合、过期时间和授权说明，而不是创建多条互相冲突的有效授权。</p>
     */
    DataSourceAuthorizationView grantAuthorization(DataSourceConfig datasource,
                                                   GrantDataSourceAuthorizationRequest request,
                                                   DatasourceAuthorizationActorContext actorContext);

    /**
     * 撤销某条授权。
     */
    DataSourceAuthorizationView revokeAuthorization(DataSourceConfig datasource,
                                                    Long authorizationId,
                                                    RevokeDataSourceAuthorizationRequest request,
                                                    DatasourceAuthorizationActorContext actorContext);

    /**
     * 查找当前 actor 在某个项目内通过实例级授权可访问的数据源 ID。
     *
     * <p>列表接口会把“项目范围内天然可见的数据源”和“授权给当前用户的数据源”做并集，
     * 因此该方法只返回授权账本贡献的 ID 集合，不负责项目成员权限本身。</p>
     */
    List<Long> findAuthorizedDatasourceIds(Long tenantId,
                                           Long projectId,
                                           DatasourceAuthorizationActorContext actorContext,
                                           DataSourceAuthorizationAction requiredAction);

    /**
     * 判断当前 actor 是否具备某条数据源的实例级授权。
     */
    boolean hasActiveAuthorization(Long datasourceId,
                                   DatasourceAuthorizationActorContext actorContext,
                                   DataSourceAuthorizationAction requiredAction);
}

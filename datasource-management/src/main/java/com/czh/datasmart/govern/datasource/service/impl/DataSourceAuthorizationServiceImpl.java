/**
 * @Author : Cui
 * @Date: 2026/07/09 01:07
 * @Description DataSmart Govern Backend - DataSourceAuthorizationServiceImpl.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.datasource.controller.dto.DataSourceAuthorizationView;
import com.czh.datasmart.govern.datasource.controller.dto.GrantDataSourceAuthorizationRequest;
import com.czh.datasmart.govern.datasource.controller.dto.RevokeDataSourceAuthorizationRequest;
import com.czh.datasmart.govern.datasource.entity.DataSourceAuthorization;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.mapper.DataSourceAuthorizationMapper;
import com.czh.datasmart.govern.datasource.service.DataSourceAuthorizationService;
import com.czh.datasmart.govern.datasource.service.support.DatasourceAuthorizationActorContext;
import com.czh.datasmart.govern.datasource.support.DataSourceAuthorizationAction;
import com.czh.datasmart.govern.datasource.support.DataSourceAuthorizationStatus;
import com.czh.datasmart.govern.datasource.support.DataSourceAuthorizationSubjectType;
import com.czh.datasmart.govern.datasource.support.DataSourceStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 数据源授权服务实现。
 *
 * <p>这里刻意保持授权逻辑显式、分步骤，而不是压缩成一段复杂 SQL。
 * 原因是资源授权属于安全边界：主体匹配、动作包含关系、过期时间、撤销状态、审计字段这些规则都应该能被阅读和复盘。
 * 后续如果授权量变大，可以把 subject 匹配和 action 匹配下沉到更精细的索引或物化视图，但业务语义仍应以本类为准。</p>
 */
@Service
@RequiredArgsConstructor
public class DataSourceAuthorizationServiceImpl implements DataSourceAuthorizationService {

    /**
     * 授权账本 Mapper。
     */
    private final DataSourceAuthorizationMapper authorizationMapper;

    @Override
    public IPage<DataSourceAuthorizationView> pageAuthorizations(DataSourceConfig datasource,
                                                                 Page<?> page,
                                                                 String subjectType,
                                                                 String status) {
        LambdaQueryWrapper<DataSourceAuthorization> wrapper = new LambdaQueryWrapper<DataSourceAuthorization>()
                .eq(DataSourceAuthorization::getDatasourceId, datasource.getId())
                .eq(DataSourceAuthorization::getTenantId, datasource.getTenantId())
                .eq(DataSourceAuthorization::getProjectId, datasource.getProjectId())
                .orderByDesc(DataSourceAuthorization::getId);
        if (hasText(subjectType)) {
            wrapper.eq(DataSourceAuthorization::getSubjectType,
                    DataSourceAuthorizationSubjectType.fromValue(subjectType).name());
        }
        if (hasText(status)) {
            wrapper.eq(DataSourceAuthorization::getStatus, normalizeStatus(status));
        }
        IPage<DataSourceAuthorization> entityPage = authorizationMapper.selectPage(
                new Page<>(page.getCurrent(), page.getSize()), wrapper);
        return entityPage.convert(item -> DataSourceAuthorizationView.from(item, datasource));
    }

    @Override
    @Transactional
    public DataSourceAuthorizationView grantAuthorization(DataSourceConfig datasource,
                                                          GrantDataSourceAuthorizationRequest request,
                                                          DatasourceAuthorizationActorContext actorContext) {
        ensureDatasourceCanBeAuthorized(datasource);
        DataSourceAuthorizationSubjectType subjectType =
                DataSourceAuthorizationSubjectType.fromValue(request.getSubjectType());
        String subjectId = trimRequired(request.getSubjectId(), "授权主体 ID 不能为空");
        String authorizedActions = DataSourceAuthorizationAction.normalizeToCsv(request.getAuthorizedActions());
        LocalDateTime now = LocalDateTime.now();
        if (request.getExpireTime() != null && !request.getExpireTime().isAfter(now)) {
            throw new IllegalArgumentException("授权过期时间必须晚于当前时间");
        }

        /*
         * 幂等授权策略：
         * - 如果同一主体已经拥有 ACTIVE 授权，就更新原记录，避免前端重复点击产生多条有效授权；
         * - 如果历史授权已经 REVOKED，则保留历史记录，新建一条 ACTIVE 授权，方便审计看到“撤销后重新授权”的事实。
         */
        DataSourceAuthorization authorization = findActiveAuthorization(
                datasource.getId(), subjectType.name(), subjectId);
        if (authorization == null) {
            authorization = new DataSourceAuthorization();
            authorization.setDatasourceId(datasource.getId());
            authorization.setTenantId(datasource.getTenantId());
            authorization.setProjectId(datasource.getProjectId());
            authorization.setWorkspaceId(datasource.getWorkspaceId());
            authorization.setSubjectType(subjectType.name());
            authorization.setSubjectId(subjectId);
            authorization.setCreateTime(now);
        }

        authorization.setSubjectName(trimToNull(request.getSubjectName()));
        authorization.setSubjectRole(trimToNull(request.getSubjectRole()));
        authorization.setAuthorizedActions(authorizedActions);
        authorization.setGrantSource(hasText(request.getGrantSource())
                ? request.getGrantSource().trim().toUpperCase(Locale.ROOT)
                : "UI_MANUAL");
        authorization.setStatus(DataSourceAuthorizationStatus.ACTIVE);
        authorization.setGrantReason(trimToNull(request.getGrantReason()));
        authorization.setExpireTime(request.getExpireTime());
        authorization.setGrantedByActorId(actorContext == null ? null : trimToNull(actorContext.actorId()));
        authorization.setGrantedByActorRole(actorContext == null ? null : trimToNull(actorContext.actorRole()));
        authorization.setGrantedTime(now);
        authorization.setRevokedByActorId(null);
        authorization.setRevokedByActorRole(null);
        authorization.setRevokeReason(null);
        authorization.setRevokedTime(null);
        authorization.setUpdateTime(now);

        if (authorization.getId() == null) {
            authorizationMapper.insert(authorization);
        } else {
            authorizationMapper.updateById(authorization);
        }
        return DataSourceAuthorizationView.from(authorization, datasource);
    }

    @Override
    @Transactional
    public DataSourceAuthorizationView revokeAuthorization(DataSourceConfig datasource,
                                                           Long authorizationId,
                                                           RevokeDataSourceAuthorizationRequest request,
                                                           DatasourceAuthorizationActorContext actorContext) {
        DataSourceAuthorization authorization = authorizationMapper.selectById(authorizationId);
        if (authorization == null || !datasource.getId().equals(authorization.getDatasourceId())) {
            throw new IllegalArgumentException("数据源授权记录不存在或不属于当前数据源，authorizationId=" + authorizationId);
        }
        if (DataSourceAuthorizationStatus.REVOKED.equals(authorization.getStatus())) {
            return DataSourceAuthorizationView.from(authorization, datasource);
        }
        LocalDateTime now = LocalDateTime.now();
        authorization.setStatus(DataSourceAuthorizationStatus.REVOKED);
        authorization.setRevokedByActorId(actorContext == null ? null : trimToNull(actorContext.actorId()));
        authorization.setRevokedByActorRole(actorContext == null ? null : trimToNull(actorContext.actorRole()));
        authorization.setRevokeReason(request == null ? null : trimToNull(request.getRevokeReason()));
        authorization.setRevokedTime(now);
        authorization.setUpdateTime(now);
        authorizationMapper.updateById(authorization);
        return DataSourceAuthorizationView.from(authorization, datasource);
    }

    @Override
    public List<Long> findAuthorizedDatasourceIds(Long tenantId,
                                                  Long projectId,
                                                  DatasourceAuthorizationActorContext actorContext,
                                                  DataSourceAuthorizationAction requiredAction) {
        if (tenantId == null || projectId == null || actorContext == null || !actorContext.hasIdentitySignal()) {
            return List.of();
        }
        return selectCandidateAuthorizations(tenantId, projectId, null, actorContext).stream()
                .filter(item -> isAuthorizationCurrentlyEffective(item, requiredAction))
                .map(DataSourceAuthorization::getDatasourceId)
                .distinct()
                .toList();
    }

    @Override
    public boolean hasActiveAuthorization(Long datasourceId,
                                          DatasourceAuthorizationActorContext actorContext,
                                          DataSourceAuthorizationAction requiredAction) {
        if (datasourceId == null || actorContext == null || !actorContext.hasIdentitySignal()) {
            return false;
        }
        return selectCandidateAuthorizations(null, null, datasourceId, actorContext).stream()
                .anyMatch(item -> isAuthorizationCurrentlyEffective(item, requiredAction));
    }

    /**
     * 查找同一主体当前生效的授权。
     */
    private DataSourceAuthorization findActiveAuthorization(Long datasourceId, String subjectType, String subjectId) {
        LambdaQueryWrapper<DataSourceAuthorization> wrapper = new LambdaQueryWrapper<DataSourceAuthorization>()
                .eq(DataSourceAuthorization::getDatasourceId, datasourceId)
                .eq(DataSourceAuthorization::getSubjectType, subjectType)
                .eq(DataSourceAuthorization::getSubjectId, subjectId)
                .eq(DataSourceAuthorization::getStatus, DataSourceAuthorizationStatus.ACTIVE)
                .last("LIMIT 1");
        return authorizationMapper.selectOne(wrapper);
    }

    /**
     * 查询与当前 actor 可能匹配的授权候选。
     *
     * <p>这里先在 SQL 中按主体类型和主体 ID/角色缩小范围，再在 Java 中判断动作包含关系和过期时间。
     * 这样既不会把全表授权都拉到内存，也能让动作包含关系保持在枚举方法中统一解释。</p>
     */
    private List<DataSourceAuthorization> selectCandidateAuthorizations(Long tenantId,
                                                                        Long projectId,
                                                                        Long datasourceId,
                                                                        DatasourceAuthorizationActorContext actorContext) {
        return authorizationMapper.selectAuthorizationCandidates(
                tenantId,
                projectId,
                datasourceId,
                trimToNull(actorContext.actorId()),
                trimToNull(actorContext.actorRole()),
                actorContext.isServiceAccount());
    }

    /**
     * 判断授权是否仍然有效并满足必需动作。
     */
    private boolean isAuthorizationCurrentlyEffective(DataSourceAuthorization authorization,
                                                      DataSourceAuthorizationAction requiredAction) {
        if (authorization == null || !DataSourceAuthorizationStatus.ACTIVE.equals(authorization.getStatus())) {
            return false;
        }
        if (authorization.getExpireTime() != null && !authorization.getExpireTime().isAfter(LocalDateTime.now())) {
            return false;
        }
        return DataSourceAuthorizationAction.includes(authorization.getAuthorizedActions(), requiredAction);
    }

    /**
     * 确认目标数据源仍可被授权。
     */
    private void ensureDatasourceCanBeAuthorized(DataSourceConfig datasource) {
        if (datasource == null) {
            throw new IllegalArgumentException("数据源不能为空");
        }
        if (DataSourceStatus.DELETED.equals(datasource.getStatus())) {
            throw new IllegalStateException("已删除的数据源不能继续授权，datasourceId=" + datasource.getId());
        }
    }

    private String normalizeStatus(String status) {
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!DataSourceAuthorizationStatus.ACTIVE.equals(normalized)
                && !DataSourceAuthorizationStatus.REVOKED.equals(normalized)) {
            throw new IllegalArgumentException("不支持的数据源授权状态: " + status + "，可选值为 ACTIVE、REVOKED");
        }
        return normalized;
    }

    private String trimRequired(String value, String message) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

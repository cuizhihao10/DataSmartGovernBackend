/**
 * @Author : Cui
 * @Date: 2026/07/09 01:07
 * @Description DataSmart Govern Backend - DataSourceAuthorizationView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.controller.dto;

import com.czh.datasmart.govern.datasource.entity.DataSourceAuthorization;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据源授权展示视图。
 *
 * <p>授权实体本身不包含数据源名称和类型，但前端授权弹窗需要展示“这条授权属于哪个数据源”。
 * 因此这里组合 datasource_config 的低敏字段与 datasource_authorization 的授权字段，避免前端再额外发起一次详情查询。</p>
 */
@Data
@Builder
public class DataSourceAuthorizationView {

    private Long id;
    private Long datasourceId;
    private String datasourceName;
    private String datasourceType;
    private Long tenantId;
    private Long projectId;
    private String subjectType;
    private String subjectId;
    private String subjectName;
    private String subjectRole;
    private String authorizedActions;
    private String grantSource;
    private String status;
    private String grantReason;
    private LocalDateTime expireTime;
    private String grantedByActorId;
    private String grantedByActorRole;
    private LocalDateTime grantedTime;
    private String revokedByActorId;
    private String revokedByActorRole;
    private String revokeReason;
    private LocalDateTime revokedTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /**
     * 从实体和数据源主记录组装前端展示视图。
     */
    public static DataSourceAuthorizationView from(DataSourceAuthorization authorization, DataSourceConfig datasource) {
        return DataSourceAuthorizationView.builder()
                .id(authorization.getId())
                .datasourceId(authorization.getDatasourceId())
                .datasourceName(datasource == null ? null : datasource.getName())
                .datasourceType(datasource == null ? null : datasource.getType())
                .tenantId(authorization.getTenantId())
                .projectId(authorization.getProjectId())
                .subjectType(authorization.getSubjectType())
                .subjectId(authorization.getSubjectId())
                .subjectName(authorization.getSubjectName())
                .subjectRole(authorization.getSubjectRole())
                .authorizedActions(authorization.getAuthorizedActions())
                .grantSource(authorization.getGrantSource())
                .status(authorization.getStatus())
                .grantReason(authorization.getGrantReason())
                .expireTime(authorization.getExpireTime())
                .grantedByActorId(authorization.getGrantedByActorId())
                .grantedByActorRole(authorization.getGrantedByActorRole())
                .grantedTime(authorization.getGrantedTime())
                .revokedByActorId(authorization.getRevokedByActorId())
                .revokedByActorRole(authorization.getRevokedByActorRole())
                .revokeReason(authorization.getRevokeReason())
                .revokedTime(authorization.getRevokedTime())
                .createTime(authorization.getCreateTime())
                .updateTime(authorization.getUpdateTime())
                .build();
    }
}

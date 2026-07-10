/**
 * @Author : Cui
 * @Date: 2026/07/10 18:40
 * @Description DataSmart Govern Backend - DataSourceAuthorizationServiceImplTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.impl;

import com.czh.datasmart.govern.datasource.entity.DataSourceAuthorization;
import com.czh.datasmart.govern.datasource.mapper.DataSourceAuthorizationMapper;
import com.czh.datasmart.govern.datasource.service.support.DatasourceAuthorizationActorContext;
import com.czh.datasmart.govern.datasource.support.DataSourceAuthorizationAction;
import com.czh.datasmart.govern.datasource.support.DataSourceAuthorizationStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据源实例授权匹配测试。
 *
 * <p>该测试保护“项目成员不自动获得数据源，但显式 ACL 必须立即生效”的协作边界。
 * Mapper 负责筛出 USER/ROLE/SERVICE_ACCOUNT 候选，服务层继续判断动作包含关系和过期时间。</p>
 */
class DataSourceAuthorizationServiceImplTest {

    @Test
    void userAuthorizationShouldExposeDatasourceWhenRequiredActionIsIncluded() {
        DataSourceAuthorizationMapper mapper = mock(DataSourceAuthorizationMapper.class);
        DataSourceAuthorizationServiceImpl service = new DataSourceAuthorizationServiceImpl(mapper);
        DatasourceAuthorizationActorContext actor =
                new DatasourceAuthorizationActorContext("1004", "ORDINARY_USER", "USER");
        DataSourceAuthorization authorization = authorization(24L, "VIEW,USE,MANAGE", null);
        when(mapper.selectAuthorizationCandidates(10L, 101L, null,
                "1004", "ORDINARY_USER", false)).thenReturn(List.of(authorization));

        List<Long> datasourceIds = service.findAuthorizedDatasourceIds(
                10L, 101L, actor, DataSourceAuthorizationAction.VIEW);

        assertThat(datasourceIds).containsExactly(24L);
        verify(mapper).selectAuthorizationCandidates(10L, 101L, null,
                "1004", "ORDINARY_USER", false);
    }

    @Test
    void expiredAuthorizationShouldNotExposeDatasource() {
        DataSourceAuthorizationMapper mapper = mock(DataSourceAuthorizationMapper.class);
        DataSourceAuthorizationServiceImpl service = new DataSourceAuthorizationServiceImpl(mapper);
        DatasourceAuthorizationActorContext actor =
                new DatasourceAuthorizationActorContext("1004", "ORDINARY_USER", "USER");
        when(mapper.selectAuthorizationCandidates(10L, 101L, null,
                "1004", "ORDINARY_USER", false))
                .thenReturn(List.of(authorization(24L, "VIEW", LocalDateTime.now().minusMinutes(1))));

        List<Long> datasourceIds = service.findAuthorizedDatasourceIds(
                10L, 101L, actor, DataSourceAuthorizationAction.VIEW);

        assertThat(datasourceIds).isEmpty();
    }

    private DataSourceAuthorization authorization(Long datasourceId,
                                                   String actions,
                                                   LocalDateTime expireTime) {
        DataSourceAuthorization authorization = new DataSourceAuthorization();
        authorization.setId(3L);
        authorization.setDatasourceId(datasourceId);
        authorization.setTenantId(10L);
        authorization.setProjectId(101L);
        authorization.setSubjectType("USER");
        authorization.setSubjectId("1004");
        authorization.setAuthorizedActions(actions);
        authorization.setStatus(DataSourceAuthorizationStatus.ACTIVE);
        authorization.setExpireTime(expireTime);
        return authorization;
    }
}

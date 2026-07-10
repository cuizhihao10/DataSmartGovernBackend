/**
 * @Author : Cui
 * @Date: 2026/07/10
 * @Description DataSmart Govern Backend - PermissionIdentityDisplaySupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.service.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.czh.datasmart.govern.permission.entity.PermissionIdentityUser;
import com.czh.datasmart.govern.permission.mapper.PermissionIdentityUserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PermissionIdentityDisplaySupportTest {

    @Test
    void resolvesDistinctHistoricalUsernamesWithoutExposingMissingActors() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(), "identity-display-support-test");
        assistant.setCurrentNamespace("identity-display-support-test");
        TableInfoHelper.initTableInfo(assistant, PermissionIdentityUser.class);
        PermissionIdentityUserMapper mapper = mock(PermissionIdentityUserMapper.class);
        PermissionIdentityUser disabledHistoricalIdentity = new PermissionIdentityUser();
        disabledHistoricalIdentity.setActorId(1001L);
        disabledHistoricalIdentity.setUsername("project-owner");
        disabledHistoricalIdentity.setStatus("DISABLED");
        when(mapper.selectList(any())).thenReturn(List.of(disabledHistoricalIdentity));

        PermissionIdentityDisplaySupport support = new PermissionIdentityDisplaySupport(mapper);

        assertThat(support.usernames(List.of(1001L, 1001L, 9999L)))
                .containsEntry(1001L, "project-owner")
                .doesNotContainKey(9999L);
    }
}

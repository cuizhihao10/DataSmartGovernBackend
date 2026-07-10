/**
 * @Author : Cui
 * @Date: 2026/07/08 18:29
 * @Description DataSmart Govern Backend - DataSourceManagementControllerTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.controller;

import com.czh.datasmart.govern.datasource.controller.dto.CreateDataSourceRequest;
import com.czh.datasmart.govern.datasource.controller.dto.UpdateDataSourceRequest;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.service.DataSourceAuthorizationService;
import com.czh.datasmart.govern.datasource.service.DataSourceManagementService;
import com.czh.datasmart.govern.datasource.service.support.DataSourceCredentialCipherSupport;
import com.czh.datasmart.govern.datasource.service.support.DatasourceProjectScopeSupport;
import com.czh.datasmart.govern.datasource.support.DataSourceAuthorizationAction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据源 Controller 项目上下文回归测试。
 *
 * <p>本测试不启动 Spring MVC，也不连接数据库，只直接调用 Controller 方法并捕获传给 Service 的参数。
 * 这样能非常精确地保护“创建数据源归属来自 gateway 项目 Header，workspace 不再写入”的接口合同。</p>
 */
class DataSourceManagementControllerTest {

    /**
     * 只有租户/项目 Header、没有 workspace Header 时，应按当前项目创建数据源并写入 workspace=null。
     *
     * <p>这条用例对应前端项目切换后的真实创建链路：
     * 1. gateway 已经把外部项目选择校验后重建为可信 {@code X-DataSmart-Project-Id}；
     * 2. datasource-management 不再要求用户填写租户、项目、工作空间 ID；
     * 3. 即使旧请求体还带 workspaceId，Controller 也必须忽略它，避免资源落入不可见旧工作空间。</p>
     */
    @Test
    void createDataSourceShouldUseProjectHeaderAndWriteNullWorkspace() {
        DataSourceManagementService service = mock(DataSourceManagementService.class);
        DataSourceAuthorizationService authorizationService = mock(DataSourceAuthorizationService.class);
        DataSourceManagementController controller = new DataSourceManagementController(
                service,
                authorizationService,
                new DatasourceProjectScopeSupport(),
                mock(DataSourceCredentialCipherSupport.class)
        );
        CreateDataSourceRequest request = new CreateDataSourceRequest();
        request.setName("订单库源端");
        request.setType("MYSQL");
        request.setUsagePurpose("SOURCE");
        request.setJdbcUrl("jdbc:mysql://localhost:3306/order_db");
        request.setUsername("readonly_user");
        request.setPassword("DataSmart@123");
        request.setDescription("用于 FlashSync 创建任务时选择的源端数据源");
        request.setWorkspaceId(10001L);
        DataSourceConfig saved = new DataSourceConfig();
        saved.setTenantId(10L);
        saved.setProjectId(205L);
        saved.setWorkspaceId(null);
        saved.setName("订单库源端");
        when(service.createDataSource(
                eq(10L),
                eq(205L),
                isNull(),
                eq(1002L),
                eq(1002L),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        )).thenReturn(saved);

        controller.createDataSource(request, 10L, 205L, "1002", null, null, null);

        ArgumentCaptor<Long> workspaceCaptor = ArgumentCaptor.forClass(Long.class);
        verify(service).createDataSource(
                eq(10L),
                eq(205L),
                workspaceCaptor.capture(),
                eq(1002L),
                eq(1002L),
                eq("订单库源端"),
                eq("MYSQL"),
                eq("jdbc:mysql://localhost:3306/order_db"),
                eq("readonly_user"),
                eq("DataSmart@123"),
                eq("用于 FlashSync 创建任务时选择的源端数据源"),
                eq("SOURCE")
        );
        assertThat(workspaceCaptor.getValue()).isNull();
    }

    @Test
    void datasourceOwnerShouldEditWithReaderProjectRole() {
        DataSourceManagementService service = mock(DataSourceManagementService.class);
        DataSourceAuthorizationService authorizationService = mock(DataSourceAuthorizationService.class);
        DataSourceManagementController controller = new DataSourceManagementController(
                service,
                authorizationService,
                new DatasourceProjectScopeSupport(),
                mock(DataSourceCredentialCipherSupport.class)
        );
        DataSourceConfig saved = activeDatasource(88L, 205L, 1002L);
        when(service.getById(88L)).thenReturn(saved);
        when(service.updateDataSource(eq(88L), any(), any(), any(), any(), any(), any())).thenReturn(saved);

        controller.updateDataSource(88L, updateRequest(), "1002", "ORDINARY_USER", "USER",
                "PROJECT", "205", "205:READER");

        verify(service).updateDataSource(eq(88L), eq("order-source"), eq("jdbc:mysql://localhost:3306/order_db"),
                eq("readonly_user"), isNull(), eq("updated"), eq("SOURCE"));
    }

    @Test
    void instanceManageAuthorizationShouldNotDeleteDatasource() {
        DataSourceManagementService service = mock(DataSourceManagementService.class);
        DataSourceAuthorizationService authorizationService = mock(DataSourceAuthorizationService.class);
        DataSourceManagementController controller = new DataSourceManagementController(
                service,
                authorizationService,
                new DatasourceProjectScopeSupport(),
                mock(DataSourceCredentialCipherSupport.class)
        );
        DataSourceConfig saved = activeDatasource(89L, 205L, 1002L);
        when(service.getById(89L)).thenReturn(saved);
        when(authorizationService.hasActiveAuthorization(eq(89L), any(), eq(DataSourceAuthorizationAction.MANAGE)))
                .thenReturn(true);

        assertThatThrownBy(() -> controller.deleteDataSource(89L, "1003", "ORDINARY_USER", "USER",
                "PROJECT", "205", "205:READER"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(service, never()).deleteDataSource(89L);
    }

    @Test
    void instanceManageAuthorizationShouldEditDatasourceWhenActorJoinedProject() {
        DataSourceManagementService service = mock(DataSourceManagementService.class);
        DataSourceAuthorizationService authorizationService = mock(DataSourceAuthorizationService.class);
        DataSourceManagementController controller = new DataSourceManagementController(
                service,
                authorizationService,
                new DatasourceProjectScopeSupport(),
                mock(DataSourceCredentialCipherSupport.class)
        );
        DataSourceConfig saved = activeDatasource(90L, 205L, 1002L);
        when(service.getById(90L)).thenReturn(saved);
        when(authorizationService.hasActiveAuthorization(eq(90L), any(), eq(DataSourceAuthorizationAction.MANAGE)))
                .thenReturn(true);
        when(service.updateDataSource(eq(90L), any(), any(), any(), any(), any(), any())).thenReturn(saved);

        controller.updateDataSource(90L, updateRequest(), "1003", "ORDINARY_USER", "USER",
                "PROJECT", "205", "205:READER");

        verify(service).updateDataSource(eq(90L), eq("order-source"), eq("jdbc:mysql://localhost:3306/order_db"),
                eq("readonly_user"), isNull(), eq("updated"), eq("SOURCE"));
    }

    @Test
    void datasourceDetailShouldReturnBackendComputedEffectiveActions() {
        DataSourceManagementService service = mock(DataSourceManagementService.class);
        DataSourceAuthorizationService authorizationService = mock(DataSourceAuthorizationService.class);
        DataSourceCredentialCipherSupport cipherSupport = mock(DataSourceCredentialCipherSupport.class);
        DataSourceManagementController controller = new DataSourceManagementController(
                service,
                authorizationService,
                new DatasourceProjectScopeSupport(),
                cipherSupport
        );
        DataSourceConfig saved = activeDatasource(91L, 205L, 1002L);
        when(service.getById(91L)).thenReturn(saved);
        when(cipherSupport.sanitizeForApi(saved)).thenReturn(saved);
        when(authorizationService.hasActiveAuthorization(eq(91L), any(), any())).thenReturn(true);

        DataSourceConfig response = controller.getDataSource(
                        91L, "1003", "ORDINARY_USER", "USER", "SELF", "205", "205:READER")
                .getBody()
                .getData();

        assertThat(response.getEffectiveActions()).containsExactly("VIEW", "USE", "MANAGE");
    }

    @Test
    void listDataSourcesShouldRestrictSelfScopeToOwnerOrInstanceAuthorization() {
        DataSourceManagementService service = mock(DataSourceManagementService.class);
        DataSourceAuthorizationService authorizationService = mock(DataSourceAuthorizationService.class);
        DataSourceCredentialCipherSupport cipherSupport = mock(DataSourceCredentialCipherSupport.class);
        DataSourceManagementController controller = new DataSourceManagementController(
                service,
                authorizationService,
                new DatasourceProjectScopeSupport(),
                cipherSupport
        );
        Page<DataSourceConfig> emptyPage = new Page<>(1, 10, 0);
        when(service.page(any(), any())).thenReturn(emptyPage);
        when(authorizationService.findAuthorizedDatasourceIds(
                eq(10L), eq(205L), any(), eq(DataSourceAuthorizationAction.VIEW)))
                .thenReturn(List.of(89L));
        when(cipherSupport.sanitizeForApi(org.mockito.ArgumentMatchers.<List<DataSourceConfig>>any()))
                .thenReturn(List.of());

        controller.listDataSources(1, 10, 10L, 205L, null, null, null, null,
                10L, 205L, "1003", "ORDINARY_USER", "USER", "SELF", "205", "205:READER");

        verify(authorizationService).findAuthorizedDatasourceIds(
                eq(10L), eq(205L), any(), eq(DataSourceAuthorizationAction.VIEW));
        verify(service).page(any(), any());
    }

    private DataSourceConfig activeDatasource(Long id, Long projectId, Long ownerId) {
        DataSourceConfig saved = new DataSourceConfig();
        saved.setId(id);
        saved.setTenantId(10L);
        saved.setProjectId(projectId);
        saved.setOwnerId(ownerId);
        saved.setStatus("ACTIVE");
        return saved;
    }

    private UpdateDataSourceRequest updateRequest() {
        UpdateDataSourceRequest request = new UpdateDataSourceRequest();
        request.setName("order-source");
        request.setJdbcUrl("jdbc:mysql://localhost:3306/order_db");
        request.setUsername("readonly_user");
        request.setPassword(null);
        request.setDescription("updated");
        request.setUsagePurpose("SOURCE");
        return request;
    }
}

/**
 * @Author : Cui
 * @Date: 2026/07/08 18:29
 * @Description DataSmart Govern Backend - DataSourceManagementControllerTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.controller;

import com.czh.datasmart.govern.datasource.controller.dto.CreateDataSourceRequest;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.service.DataSourceAuthorizationService;
import com.czh.datasmart.govern.datasource.service.DataSourceManagementService;
import com.czh.datasmart.govern.datasource.service.support.DatasourceProjectScopeSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
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
                new DatasourceProjectScopeSupport()
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
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        )).thenReturn(saved);

        controller.createDataSource(request, 10L, 205L);

        ArgumentCaptor<Long> workspaceCaptor = ArgumentCaptor.forClass(Long.class);
        verify(service).createDataSource(
                eq(10L),
                eq(205L),
                workspaceCaptor.capture(),
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
}

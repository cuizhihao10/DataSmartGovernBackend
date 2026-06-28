/**
 * @Author : Cui
 * @Date: 2026/06/28 23:52
 * @Description DataSmart Govern Backend - DataSourceCapabilitySnapshotControllerTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.controller;

import com.czh.datasmart.govern.datasource.common.ApiResponse;
import com.czh.datasmart.govern.datasource.controller.dto.DataSourceCapabilitySnapshotView;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.service.DataSourceManagementService;
import com.czh.datasmart.govern.datasource.service.support.ConnectorCapabilityRegistry;
import com.czh.datasmart.govern.datasource.service.support.DataSourceCapabilitySnapshotService;
import com.czh.datasmart.govern.datasource.service.support.DatasourceProjectScopeSupport;
import com.czh.datasmart.govern.datasource.support.ConnectionTestStatus;
import com.czh.datasmart.govern.datasource.support.DataSourceStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 数据源能力快照 Controller 单元测试。
 *
 * <p>这组测试关注入口安全语义，而不是连接器能力细节：
 * 公开路由必须继续执行项目范围校验；internal 路由必须要求可信服务账号上下文；
 * 两类路由都只能返回低敏能力快照。</p>
 */
class DataSourceCapabilitySnapshotControllerTest {

    private final DataSourceManagementService dataSourceManagementService = mock(DataSourceManagementService.class);
    private final DataSourceCapabilitySnapshotController controller = new DataSourceCapabilitySnapshotController(
            dataSourceManagementService,
            new DatasourceProjectScopeSupport(),
            new DataSourceCapabilitySnapshotService(new ConnectorCapabilityRegistry())
    );

    @Test
    void publicRouteShouldReturnSnapshotWhenProjectIsAuthorized() {
        when(dataSourceManagementService.getById(10L)).thenReturn(datasource(101L));

        ResponseEntity<ApiResponse<DataSourceCapabilitySnapshotView>> response = controller.getCapabilitySnapshot(
                10L,
                "PROJECT",
                "100,101",
                null,
                null,
                new MockHttpServletRequest("GET", "/datasources/10/capability-snapshot")
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("MYSQL", response.getBody().getData().getConnectorType());
        assertEquals(101L, response.getBody().getData().getProjectId());
    }

    @Test
    void publicRouteShouldRejectUnauthorizedProjectScope() {
        when(dataSourceManagementService.getById(10L)).thenReturn(datasource(999L));

        assertThrows(IllegalArgumentException.class, () -> controller.getCapabilitySnapshot(
                10L,
                "PROJECT",
                "100,101",
                null,
                null,
                new MockHttpServletRequest("GET", "/datasources/10/capability-snapshot")
        ));
    }

    @Test
    void internalRouteShouldRejectMissingServiceAccountContext() {
        when(dataSourceManagementService.getById(10L)).thenReturn(datasource(101L));

        ResponseEntity<ApiResponse<DataSourceCapabilitySnapshotView>> response = controller.getCapabilitySnapshot(
                10L,
                null,
                null,
                null,
                null,
                new MockHttpServletRequest("GET", "/internal/datasources/10/capability-snapshot")
        );

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN.value(), response.getBody().getCode());
    }

    @Test
    void internalRouteShouldAllowTrustedDataSyncServiceAccount() {
        when(dataSourceManagementService.getById(10L)).thenReturn(datasource(101L));

        ResponseEntity<ApiResponse<DataSourceCapabilitySnapshotView>> response = controller.getCapabilitySnapshot(
                10L,
                null,
                null,
                "DATA-SYNC",
                "service_account",
                new MockHttpServletRequest("GET", "/internal/datasources/10/capability-snapshot")
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("CONNECTION_VERIFIED", response.getBody().getData().getHealthStatus());
    }

    private DataSourceConfig datasource(Long projectId) {
        DataSourceConfig datasource = new DataSourceConfig();
        datasource.setId(10L);
        datasource.setTenantId(1L);
        datasource.setProjectId(projectId);
        datasource.setWorkspaceId(201L);
        datasource.setType("MYSQL");
        datasource.setStatus(DataSourceStatus.ACTIVE);
        datasource.setLastTestStatus(ConnectionTestStatus.SUCCESS);
        return datasource;
    }
}

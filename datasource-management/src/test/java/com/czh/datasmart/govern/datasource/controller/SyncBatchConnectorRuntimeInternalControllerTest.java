/**
 * @Author : Cui
 * @Date: 2026/06/29 12:07
 * @Description DataSmart Govern Backend - SyncBatchConnectorRuntimeInternalControllerTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.controller;

import com.czh.datasmart.govern.datasource.common.ApiResponse;
import com.czh.datasmart.govern.datasource.controller.dto.SyncBatchRunOnceInternalRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncBatchRunOnceInternalResponse;
import com.czh.datasmart.govern.datasource.service.execution.SyncBatchConnectorRuntimeRunOnceService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 批量连接器运行时 internal Controller 测试。
 *
 * <p>这组测试只关注入口安全语义：run-once 是真实读写入口，只能由 data-sync 服务账号调用。
 * 执行编排细节已在 {@code SyncBatchConnectorRuntimeRunOnceServiceTest} 中覆盖。</p>
 */
class SyncBatchConnectorRuntimeInternalControllerTest {

    @Test
    void runOnceShouldRejectUntrustedCaller() {
        SyncBatchConnectorRuntimeInternalController controller =
                new SyncBatchConnectorRuntimeInternalController(new StubRunOnceService());

        ResponseEntity<ApiResponse<SyncBatchRunOnceInternalResponse>> response = controller.runOnce(
                new SyncBatchRunOnceInternalRequest(),
                "agent-runtime",
                "SERVICE_ACCOUNT"
        );

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN.value(), response.getBody().getCode());
    }

    @Test
    void runOnceShouldAllowDataSyncServiceAccount() {
        SyncBatchConnectorRuntimeInternalController controller =
                new SyncBatchConnectorRuntimeInternalController(new StubRunOnceService());

        ResponseEntity<ApiResponse<SyncBatchRunOnceInternalResponse>> response = controller.runOnce(
                new SyncBatchRunOnceInternalRequest(),
                "DATA-SYNC",
                "service_account"
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("BATCH_WRITTEN_MORE_REMAIN", response.getBody().getData().getRunStatus());
    }

    /**
     * 测试替身。
     *
     * <p>Controller 测试不需要真实 reader/writer，因此通过子类覆盖 runOnce。
     * 这样可以避免引入 Mockito 动态代理，也能让测试只聚焦路由身份校验。</p>
     */
    private static class StubRunOnceService extends SyncBatchConnectorRuntimeRunOnceService {

        private StubRunOnceService() {
            super(null, null, null);
        }

        @Override
        public SyncBatchRunOnceInternalResponse runOnce(SyncBatchRunOnceInternalRequest request) {
            return new SyncBatchRunOnceInternalResponse(
                    100L,
                    900L,
                    "BATCH_WRITTEN_MORE_REMAIN",
                    1L,
                    1L,
                    0L,
                    1L,
                    1L,
                    0L,
                    false,
                    false,
                    true,
                    false,
                    false,
                    "CHECKPOINT_VALUE_NOT_RETURNED_USE_SECURE_HANDOFF_BEFORE_INCREMENTAL_CLOSURE",
                    false,
                    false,
                    "TIME_WATERMARK",
                    "WORKER_INTERNAL_AND_SYNC_CHECKPOINT_TABLE_ONLY",
                    null,
                    List.of(),
                    SyncBatchRunOnceInternalResponse.PAYLOAD_POLICY
            );
        }
    }
}

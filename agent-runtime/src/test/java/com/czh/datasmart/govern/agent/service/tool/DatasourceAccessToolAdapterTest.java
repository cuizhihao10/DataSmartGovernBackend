/**
 * @Author : Cui
 * @Date: 2026-07-11 04:50
 * @Description DataSmart Govern Backend - DatasourceAccessToolAdapterTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatasourceAccessToolAdapterTest {

    @Test
    void currentDatasourceContractUsesSuccessTestStatus() {
        assertTrue(DatasourceAccessToolAdapter.isSuccessfulConnectionTest(Map.of(
                "testStatus", "SUCCESS",
                "message", "Connection succeeded and metadata is discoverable."
        )));
        assertFalse(DatasourceAccessToolAdapter.isSuccessfulConnectionTest(Map.of(
                "testStatus", "FAILED"
        )));
    }

    @Test
    void legacySuccessBooleanRemainsCompatibleDuringRollingUpgrade() {
        assertTrue(DatasourceAccessToolAdapter.isSuccessfulConnectionTest(Map.of("success", true)));
        assertFalse(DatasourceAccessToolAdapter.isSuccessfulConnectionTest(Map.of("success", false)));
    }
}

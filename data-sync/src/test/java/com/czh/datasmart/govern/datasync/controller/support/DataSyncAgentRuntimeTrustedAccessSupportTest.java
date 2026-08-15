/**
 * @Author : Cui
 * @Date: 2026/08/15
 * @Description DataSmart Govern Backend - DataSyncAgentRuntimeTrustedAccessSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.support;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.datasync.config.DataSyncAgentRuntimeObservationProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirectAgentInvocationContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 Agent 内部身份必须同时满足来源、令牌和关联 ID，空配置不能放行。 */
class DataSyncAgentRuntimeTrustedAccessSupportTest {

    @Test
    void shouldResolveCompleteTrustedDirectInvocation() {
        DataSyncAgentRuntimeObservationProperties properties = new DataSyncAgentRuntimeObservationProperties();
        properties.setInternalServiceToken("test-token");
        DataSyncAgentRuntimeTrustedAccessSupport support =
                new DataSyncAgentRuntimeTrustedAccessSupport(properties);
        HttpHeaders headers = headers("test-token");

        SyncDirectAgentInvocationContext invocation = support.resolveDirectInvocation(headers, "trace-1");

        assertThat(invocation.sessionId()).isEqualTo("session-1");
        assertThat(invocation.runId()).isEqualTo("run-1");
        assertThat(invocation.auditId()).isEqualTo("audit-1");
    }

    @Test
    void shouldFailClosedForForgedAgentHeaders() {
        DataSyncAgentRuntimeObservationProperties properties = new DataSyncAgentRuntimeObservationProperties();
        properties.setInternalServiceToken("expected-token");
        DataSyncAgentRuntimeTrustedAccessSupport support =
                new DataSyncAgentRuntimeTrustedAccessSupport(properties);

        assertThatThrownBy(() -> support.resolveDirectInvocation(headers("wrong-token"), "trace-1"))
                .isInstanceOf(PlatformBusinessException.class);
    }

    private HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(PlatformContextHeaders.SOURCE_SERVICE, "agent-runtime");
        headers.set(PlatformContextHeaders.INTERNAL_SERVICE_TOKEN, token);
        headers.set(PlatformContextHeaders.AGENT_SESSION_ID, "session-1");
        headers.set(PlatformContextHeaders.AGENT_RUN_ID, "run-1");
        headers.set(PlatformContextHeaders.AGENT_AUDIT_ID, "audit-1");
        return headers;
    }
}

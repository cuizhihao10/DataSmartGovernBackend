/**
 * @Author : Cui
 * @Date: 2026/08/15
 * @Description DataSmart Govern Backend - DataSyncAgentRuntimeTrustedAccessSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.support;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.config.DataSyncAgentRuntimeObservationProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncDirectAgentInvocationContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * data-sync 对 Agent 相关内部调用的统一可信入口校验器。
 *
 * <p>来源服务名只是一段可伪造文本，所以不能单独作为身份。这里要求“固定服务白名单 + 部署 Secret”同时成立，
 * 且使用常量时间比较令牌。空配置始终拒绝，避免部署漏配时把内部写入口静默变成公开接口。</p>
 */
@Component
@RequiredArgsConstructor
public class DataSyncAgentRuntimeTrustedAccessSupport {

    private final DataSyncAgentRuntimeObservationProperties properties;

    /** 要求调用方是指定内部服务；失败时不返回任何令牌细节。 */
    public void requireService(HttpHeaders headers, String expectedSourceService) {
        String actualSource = text(headers.getFirst(PlatformContextHeaders.SOURCE_SERVICE));
        String expectedSource = text(expectedSourceService);
        String expectedToken = text(properties.getInternalServiceToken());
        String actualToken = text(headers.getFirst(PlatformContextHeaders.INTERNAL_SERVICE_TOKEN));
        boolean sourceMatches = expectedSource != null && expectedSource.equalsIgnoreCase(actualSource);
        boolean tokenMatches = expectedToken != null && actualToken != null && MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8), actualToken.getBytes(StandardCharsets.UTF_8));
        if (!sourceMatches || !tokenMatches) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN, "data-sync 内部服务身份校验失败");
        }
    }

    /**
     * 从普通任务运行路由中识别受信 Agent 直接工具调用。
     *
     * <p>没有任何 Agent 标记时返回 null，保持人工运行语义。只要调用方声明 agent-runtime 或携带任一 Agent
     * 关联字段，就必须完整通过令牌和三项 ID 校验；这样恶意请求不能通过“少传一个字段”降级为人工入口。</p>
     */
    public SyncDirectAgentInvocationContext resolveDirectInvocation(HttpHeaders headers, String traceId) {
        String source = text(headers.getFirst(PlatformContextHeaders.SOURCE_SERVICE));
        String sessionId = text(headers.getFirst(PlatformContextHeaders.AGENT_SESSION_ID));
        String runId = text(headers.getFirst(PlatformContextHeaders.AGENT_RUN_ID));
        String auditId = text(headers.getFirst(PlatformContextHeaders.AGENT_AUDIT_ID));
        boolean hasAgentMarker = "agent-runtime".equalsIgnoreCase(source)
                || sessionId != null || runId != null || auditId != null;
        if (!hasAgentMarker) {
            return null;
        }
        requireService(headers, "agent-runtime");
        if (sessionId == null || runId == null || auditId == null) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "Agent 直接工具调用缺少 sessionId、runId 或 auditId");
        }
        return new SyncDirectAgentInvocationContext(sessionId, runId, auditId, text(traceId), "agent-runtime");
    }

    private String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

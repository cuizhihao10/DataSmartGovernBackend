/**
 * @Author : Cui
 * @Date: 2026/07/07 23:27
 * @Description DataSmart Govern Backend - SyncPartitionProbeInternalController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.controller;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasource.common.ApiResponse;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPartitionRangeProbeInternalRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncPartitionRangeProbeInternalResponse;
import com.czh.datasmart.govern.datasource.service.execution.SyncPartitionRangeProbeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 分片范围探测 internal 控制器。
 *
 * <p>该路由用于 data-sync 在执行 DataX-style {@code AUTO_SPLIT_PK} 前探测源端 splitPk 的 min/max。
 * 它只允许 data-sync 服务账号调用，普通前端、Agent 工具、task-management 或 gateway 公开路由都不应直接访问。</p>
 */
@RestController
@RequestMapping("/internal/sync-partitions")
@RequiredArgsConstructor
public class SyncPartitionProbeInternalController {

    private static final Set<String> TRUSTED_SOURCE_SERVICES = Set.of("data-sync");
    private static final String TRUSTED_ACTOR_ROLE = "SERVICE_ACCOUNT";

    private final SyncPartitionRangeProbeService rangeProbeService;

    /**
     * 探测 splitPk 范围。
     *
     * @param request 探测请求。
     * @param sourceService 调用方服务，必须为 data-sync。
     * @param actorRole 调用方角色，必须为 SERVICE_ACCOUNT。
     * @return 低敏 min/max/count 探测事实。
     */
    @PostMapping("/range-probe")
    public ResponseEntity<ApiResponse<SyncPartitionRangeProbeInternalResponse>> probeRange(
            @Valid @RequestBody SyncPartitionRangeProbeInternalRequest request,
            @RequestHeader(value = PlatformContextHeaders.SOURCE_SERVICE, required = false) String sourceService,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole) {
        if (!isTrustedInternalCaller(sourceService, actorRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(
                    HttpStatus.FORBIDDEN.value(),
                    "分片范围探测 internal 路由仅允许 data-sync 服务账号调用"
            ));
        }
        return ResponseEntity.ok(ApiResponse.success("分片范围探测完成", rangeProbeService.probeRange(request)));
    }

    private boolean isTrustedInternalCaller(String sourceService, String actorRole) {
        return TRUSTED_SOURCE_SERVICES.stream().anyMatch(service -> service.equalsIgnoreCase(trim(sourceService)))
                && TRUSTED_ACTOR_ROLE.equalsIgnoreCase(trim(actorRole));
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}

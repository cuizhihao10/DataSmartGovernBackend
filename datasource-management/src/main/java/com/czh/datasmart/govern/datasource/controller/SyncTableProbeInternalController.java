/**
 * @Author : Cui
 * @Date: 2026/07/09 22:39
 * @Description DataSmart Govern Backend - SyncTableProbeInternalController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.controller;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasource.common.ApiResponse;
import com.czh.datasmart.govern.datasource.controller.dto.SyncTableRowCountProbeInternalRequest;
import com.czh.datasmart.govern.datasource.controller.dto.SyncTableRowCountProbeInternalResponse;
import com.czh.datasmart.govern.datasource.service.execution.SyncTableRowCountProbeService;
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
 * 同步表级探测 internal 控制器。
 *
 * <p>该控制器当前提供目标表行数探测能力，主要被 data-sync 的创建任务预检查调用。
 * 它不是普通前端 API，也不是通用 SQL 查询接口；它只回答“这张目标表当前是否为空”这一类执行准入问题。</p>
 *
 * <p>为什么要单独做 internal 路由：</p>
 * <p>1. 行数探测会触达客户目标库，虽然只返回 count，但仍属于数据源使用动作；</p>
 * <p>2. data-sync 需要这个事实来判断全量 INSERT 是否安全，而普通用户不应绕过同步任务流程直接扫任意表；</p>
 * <p>3. 独立路由便于后续叠加 HMAC、mTLS、服务网格或限流策略，不影响普通元数据发现接口。</p>
 */
@RestController
@RequestMapping("/internal/sync-tables")
@RequiredArgsConstructor
public class SyncTableProbeInternalController {

    private static final Set<String> TRUSTED_SOURCE_SERVICES = Set.of("data-sync");
    private static final String TRUSTED_ACTOR_ROLE = "SERVICE_ACCOUNT";

    private final SyncTableRowCountProbeService rowCountProbeService;

    /**
     * 探测目标表行数。
     *
     * @param request 行数探测请求。
     * @param sourceService 调用方服务，必须为 data-sync。
     * @param actorRole 调用方角色，必须为 SERVICE_ACCOUNT。
     * @return 低敏 row-count 探测事实。
     */
    @PostMapping("/row-count-probe")
    public ResponseEntity<ApiResponse<SyncTableRowCountProbeInternalResponse>> probeRowCount(
            @Valid @RequestBody SyncTableRowCountProbeInternalRequest request,
            @RequestHeader(value = PlatformContextHeaders.SOURCE_SERVICE, required = false) String sourceService,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole) {
        if (!isTrustedInternalCaller(sourceService, actorRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(
                    HttpStatus.FORBIDDEN.value(),
                    "同步表行数探测 internal 路由仅允许 data-sync 服务账号调用"
            ));
        }
        return ResponseEntity.ok(ApiResponse.success("同步表行数探测完成", rowCountProbeService.probeRowCount(request)));
    }

    private boolean isTrustedInternalCaller(String sourceService, String actorRole) {
        return TRUSTED_SOURCE_SERVICES.stream().anyMatch(service -> service.equalsIgnoreCase(trim(sourceService)))
                && TRUSTED_ACTOR_ROLE.equalsIgnoreCase(trim(actorRole));
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}

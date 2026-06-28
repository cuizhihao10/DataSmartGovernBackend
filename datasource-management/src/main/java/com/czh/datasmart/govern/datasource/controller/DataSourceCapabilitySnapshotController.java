/**
 * @Author : Cui
 * @Date: 2026/06/28 23:52
 * @Description DataSmart Govern Backend - DataSourceCapabilitySnapshotController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.controller;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasource.common.ApiResponse;
import com.czh.datasmart.govern.datasource.controller.dto.DataSourceCapabilitySnapshotView;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.service.DataSourceManagementService;
import com.czh.datasmart.govern.datasource.service.support.DataSourceCapabilitySnapshotService;
import com.czh.datasmart.govern.datasource.service.support.DatasourceProjectScopeSupport;
import com.czh.datasmart.govern.datasource.service.support.DatasourceProjectVisibility;
import com.czh.datasmart.govern.datasource.support.DataSourceStatus;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;
import java.util.Set;

/**
 * 数据源能力快照查询控制器。
 *
 * <p>该 Controller 是 datasource-management 与 data-sync 闭环之间的轻量桥梁。
 * 它回答的是：“某个 datasourceId 当前对应什么连接器类型、生命周期状态、最近连接测试状态和低敏能力画像？”</p>
 *
 * <p>它刻意不复用详情接口的返回体。详情接口当前仍会返回完整 {@code DataSourceConfig}，
 * 而跨微服务自动补全 connectorType 时绝不能携带 jdbcUrl、username、password 等敏感配置。</p>
 *
 * <p>路由设计：</p>
 * <p>1. {@code GET /datasources/{id}/capability-snapshot} 面向经过 gateway 的管理台、Agent 工具适配器和普通业务调用；</p>
 * <p>2. {@code GET /internal/datasources/{id}/capability-snapshot} 面向 data-sync 等服务账号调用；</p>
 * <p>3. 两类路由返回同一低敏 DTO，但 internal 路由额外要求可信服务账号 Header，避免内部路径被任意调用。</p>
 */
@RestController
@RequestMapping({"/datasources", "/internal/datasources"})
@RequiredArgsConstructor
public class DataSourceCapabilitySnapshotController {

    /**
     * 允许调用 internal 快照路由的上游服务。
     *
     * <p>当前最核心消费者是 data-sync。task-management 与 agent-runtime 暂时列入白名单，
     * 是为了后续任务执行预检和 Agent 工具规划可以复用同一低敏事实源。
     * 生产环境仍应继续升级为 gateway 签名、mTLS 或服务网格鉴权。</p>
     */
    private static final Set<String> TRUSTED_INTERNAL_SOURCE_SERVICES = Set.of(
            "data-sync",
            "task-management",
            "agent-runtime"
    );

    /**
     * 内部路由要求机器服务账号身份。
     *
     * <p>这不是最终权限中心，只是当前阶段在 Controller 入口做的最小 fail-closed 保护。</p>
     */
    private static final String TRUSTED_INTERNAL_ACTOR_ROLE = "SERVICE_ACCOUNT";

    /**
     * 数据源主服务。
     *
     * <p>本 Controller 只调用 {@code getById} 加载数据源主记录，用于项目可见性校验和快照构建。
     * 不调用连接测试、元数据发现或只读 SQL 执行，避免查询快照产生外部副作用。</p>
     */
    private final DataSourceManagementService dataSourceManagementService;

    /**
     * 项目级可见性支持组件。
     *
     * <p>公开路由会复用当前 datasource-management 已有的 PROJECT 数据范围 Header 语义，
     * 防止调用方通过猜测 datasourceId 获取其他项目的数据源能力事实。</p>
     */
    private final DatasourceProjectScopeSupport datasourceProjectScopeSupport;

    /**
     * 低敏能力快照构建服务。
     */
    private final DataSourceCapabilitySnapshotService capabilitySnapshotService;

    /**
     * 查询单个数据源的低敏能力快照。
     *
     * @param id 数据源 ID。只用于定位平台内部数据源记录，不包含连接配置。
     * @param dataScopeLevel gateway 注入的数据范围级别，例如 PROJECT、TENANT、PLATFORM。
     * @param authorizedProjectIds gateway 注入的授权项目集合，PROJECT 范围下必须参与可见性校验。
     * @param sourceService 内部调用方服务名。只有访问 internal 路由时才强制校验。
     * @param actorRole 内部调用方角色。只有访问 internal 路由时才强制要求 SERVICE_ACCOUNT。
     * @param servletRequest 用于识别当前请求是否命中 /internal 路由。
     * @return 只包含低敏能力事实的统一响应。
     */
    @GetMapping("/{id}/capability-snapshot")
    public ResponseEntity<ApiResponse<DataSourceCapabilitySnapshotView>> getCapabilitySnapshot(
            @PathVariable Long id,
            @RequestHeader(value = PlatformContextHeaders.DATA_SCOPE_LEVEL, required = false) String dataScopeLevel,
            @RequestHeader(value = PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, required = false) String authorizedProjectIds,
            @RequestHeader(value = PlatformContextHeaders.SOURCE_SERVICE, required = false) String sourceService,
            @RequestHeader(value = PlatformContextHeaders.ACTOR_ROLE, required = false) String actorRole,
            HttpServletRequest servletRequest) {
        if (isInternalRequest(servletRequest) && !isTrustedInternalCaller(sourceService, actorRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(
                    HttpStatus.FORBIDDEN.value(),
                    "数据源能力快照 internal 路由仅允许可信服务账号调用"
            ));
        }

        DataSourceConfig datasource = getRequiredVisibleDataSource(id, dataScopeLevel, authorizedProjectIds);
        DataSourceCapabilitySnapshotView snapshot = capabilitySnapshotService.buildSnapshot(datasource);
        return ResponseEntity.ok(ApiResponse.success("数据源能力快照查询成功", snapshot));
    }

    /**
     * 加载并校验数据源可见性。
     *
     * <p>该方法把“资源是否存在”和“当前调用上下文是否可读”放在构建快照之前，
     * 这样快照服务只处理低敏业务转换，不需要理解 HTTP Header 或权限协议。</p>
     */
    private DataSourceConfig getRequiredVisibleDataSource(Long id, String dataScopeLevel, String authorizedProjectIds) {
        DataSourceConfig datasource = dataSourceManagementService.getById(id);
        if (datasource == null || DataSourceStatus.DELETED.equals(datasource.getStatus())) {
            throw new NoSuchElementException("数据源不存在: " + id);
        }
        DatasourceProjectVisibility visibility = datasourceProjectScopeSupport.resolveVisibility(
                null, null, dataScopeLevel, authorizedProjectIds);
        datasourceProjectScopeSupport.validateProjectReadable(datasource.getProjectId(), visibility, "数据源");
        return datasource;
    }

    /**
     * 判断当前请求是否来自 internal 路由。
     *
     * <p>同一个 Controller 同时承载公开路由和内部路由，因此需要根据实际 URI 判断是否启用服务账号校验。
     * 如果以后拆成独立内部 Controller，该判断可以移除。</p>
     */
    private boolean isInternalRequest(HttpServletRequest servletRequest) {
        if (servletRequest == null || servletRequest.getRequestURI() == null) {
            return false;
        }
        String requestUri = servletRequest.getRequestURI();
        String contextPath = servletRequest.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && requestUri.startsWith(contextPath)) {
            requestUri = requestUri.substring(contextPath.length());
        }
        return requestUri.startsWith("/internal/");
    }

    /**
     * 校验内部服务调用方。
     *
     * <p>当前只做最小白名单和 SERVICE_ACCOUNT 检查。它不能替代完整的服务间认证，
     * 但可以避免 internal 路由在开发阶段被无上下文调用。</p>
     */
    private boolean isTrustedInternalCaller(String sourceService, String actorRole) {
        return TRUSTED_INTERNAL_SOURCE_SERVICES.stream().anyMatch(service -> service.equalsIgnoreCase(trim(sourceService)))
                && TRUSTED_INTERNAL_ACTOR_ROLE.equalsIgnoreCase(trim(actorRole));
    }

    /**
     * Header 值归一化。
     */
    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}

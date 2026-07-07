/**
 * @Author : Cui
 * @Date: 2026/06/28 23:28
 * @Description DataSmart Govern Backend - DataSyncConnectorCapabilityController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller;

import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasync.controller.dto.SyncConnectorCapabilityView;
import com.czh.datasmart.govern.datasync.controller.dto.SyncConnectorCompatibilityView;
import com.czh.datasmart.govern.datasync.service.support.SyncConnectorCapabilityRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据同步连接器能力 API。
 *
 * <p>该 Controller 是 data-sync 走向“泛用数据移动产品”的基础控制面。
 * 它不负责真实连接、不读取连接串、不执行 SQL、不读取样本数据，只返回低敏能力矩阵：
 * 哪些 connector type 存在、支持哪些同步模式、某个源/目标/模式组合是否建议进入模板创建或 Agent 规划。</p>
 *
 * <p>路由同时提供直连路径和网关路径：
 * 1. `/sync-connectors/...` 便于模块本地调试；
 * 2. `/api/sync-connectors/...` 便于后续 gateway 统一代理和前端管理台接入。</p>
 */
@RestController
@RequiredArgsConstructor
public class DataSyncConnectorCapabilityController {

    private final SyncConnectorCapabilityRegistry capabilityRegistry;

    /**
     * 查询全部连接器能力。
     *
     * <p>典型使用场景：</p>
     * <ul>
     *     <li>前端创建同步模板时，根据源端/目标端 connector type 动态展示可选同步模式；</li>
     *     <li>Agent 规划同步任务时，先知道 Kafka 不应被当作传统全量表同步源；</li>
     *     <li>运营人员评估某个连接器是否已具备 checkpoint、preview、field mapping 等生产能力。</li>
     * </ul>
     */
    @GetMapping({"/sync-connectors/capabilities", "/api/sync-connectors/capabilities"})
    public PlatformApiResponse<List<SyncConnectorCapabilityView>> listCapabilities(
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(capabilityRegistry.listCapabilities(), traceId);
    }

    /**
     * 查询连接器与同步模式兼容性。
     *
     * <p>该接口只做产品级能力预检，不做真实连通性校验。真实执行前仍必须走：
     * datasource-management 连接测试、permission-admin 权限判断、sync template 字段映射校验、task 状态机和 worker lease。</p>
     *
     * @param sourceConnectorType 源端连接器类型，例如 MYSQL。
     * @param targetConnectorType 目标端连接器类型，例如 POSTGRESQL。
     * @param syncMode 同步模式。用户新建任务时推荐只传 FULL、SCHEDULED_FULL、SCHEDULED_BATCH、
     *                 CUSTOM_SQL_QUERY、CDC_STREAMING 五个一级传输模式；历史增量、回放、补数等能力应走专用流程。
     */
    @GetMapping({"/sync-connectors/compatibility", "/api/sync-connectors/compatibility"})
    public PlatformApiResponse<SyncConnectorCompatibilityView> checkCompatibility(
            @RequestParam String sourceConnectorType,
            @RequestParam String targetConnectorType,
            @RequestParam String syncMode,
            @RequestHeader(value = PlatformContextHeaders.TRACE_ID, required = false) String traceId) {
        return PlatformApiResponse.success(capabilityRegistry.checkCompatibility(
                sourceConnectorType, targetConnectorType, syncMode), traceId);
    }
}

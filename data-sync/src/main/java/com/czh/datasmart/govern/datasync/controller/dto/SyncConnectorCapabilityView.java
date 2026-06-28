/**
 * @Author : Cui
 * @Date: 2026/06/28 23:28
 * @Description DataSmart Govern Backend - SyncConnectorCapabilityView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.util.List;

/**
 * 同步连接器能力视图。
 *
 * <p>该 DTO 是给前端、Agent、同步模板校验器和运营台使用的低敏能力目录。
 * 它只描述“某类 connector 理论上支持什么”，不会包含真实 host、port、database、topic、bucket、账号、密钥或内部 endpoint。
 * 真实连接实例仍应由 datasource-management 管理，data-sync 只根据 connectorType 和能力矩阵做产品级判断。</p>
 *
 * @param connectorType 稳定连接器类型，例如 MYSQL、POSTGRESQL、KAFKA。
 * @param displayName 面向用户展示的连接器名称。
 * @param supportLevel 当前产品支持等级。`PRIMARY` 表示优先落地，`PREPARED` 表示能力模型已预留但 worker 可后置。
 * @param canRead 是否支持作为同步源读取数据。
 * @param canWrite 是否支持作为同步目标写入数据。
 * @param supportsMetadataDiscovery 是否支持元数据发现，例如表、字段、topic、文件 schema。
 * @param supportsSchemaDiscovery 是否支持结构化 schema 发现。
 * @param supportsFieldSampling 是否支持字段或记录抽样预览。真实样本不能通过该 DTO 返回。
 * @param supportsPreview 是否支持同步前预览或 dry-run 估算。
 * @param supportsFullSync 是否支持全量同步。
 * @param supportsIncrementalSync 是否支持增量同步。
 * @param supportsStreaming 是否支持流式或 CDC 类持续同步。
 * @param supportsCheckpointResume 是否支持 checkpoint 断点续行。
 * @param supportsPartitionParallelism 是否支持分区并行或分片执行。
 * @param supportsFieldMapping 是否支持字段映射。
 * @param supportsTransformationHook 是否支持转换 hook 或轻量变换。
 * @param supportsDataValidation 是否支持同步前后数据校验。
 * @param supportsAdminThrottling 是否支持管理员限速、并发或维护窗口治理。
 * @param supportedModes 该连接器可作为源端或目标端参与的同步模式。
 * @param recommendedCheckpointTypes 推荐 checkpoint 类型，例如 PAGE_CURSOR、TIME_WINDOW、OFFSET。
 * @param performanceNotes 性能与可靠性提示，只能是低敏说明。
 * @param safetyNotes 安全与治理提示，只能是低敏说明。
 */
public record SyncConnectorCapabilityView(
        String connectorType,
        String displayName,
        String supportLevel,
        boolean canRead,
        boolean canWrite,
        boolean supportsMetadataDiscovery,
        boolean supportsSchemaDiscovery,
        boolean supportsFieldSampling,
        boolean supportsPreview,
        boolean supportsFullSync,
        boolean supportsIncrementalSync,
        boolean supportsStreaming,
        boolean supportsCheckpointResume,
        boolean supportsPartitionParallelism,
        boolean supportsFieldMapping,
        boolean supportsTransformationHook,
        boolean supportsDataValidation,
        boolean supportsAdminThrottling,
        List<String> supportedModes,
        List<String> recommendedCheckpointTypes,
        List<String> performanceNotes,
        List<String> safetyNotes
) {
}

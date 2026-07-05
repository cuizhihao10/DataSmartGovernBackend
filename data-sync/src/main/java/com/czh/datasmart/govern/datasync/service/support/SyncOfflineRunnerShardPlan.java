/**
 * @Author : Cui
 * @Date: 2026/07/05 14:26
 * @Description DataSmart Govern Backend - SyncOfflineRunnerShardPlan.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.util.List;

/**
 * DataX-style 离线 Runner 的低敏分片计划摘要。
 *
 * <p>该对象回答“未来执行器应该如何把一个离线作业拆成可调度、可重试、可观测的执行单元”。它不是
 * DataX job JSON，也不是 SQL 计划，更不会包含表清单、字段清单、where 条件、partitionConfig 原文、
 * objectMappingConfig 原文或 checkpoint 原始值。</p>
 *
 * <p>为什么要单独建模分片计划：</p>
 * <p>1. 商用数据同步不会永远只有单表全量，后续会出现多表 fan-out、schema 迁移、定时批窗口、自定义 SQL、
 * 回放、补数、离线导入/导出等不同拆分方式；</p>
 * <p>2. 分片策略直接影响并发度、资源配额、失败重试粒度、checkpoint 保存频率和执行报告维度；</p>
 * <p>3. 先用低敏摘要固定合同，可以让 UI、Agent、审批流和运维台知道“需要哪类 Runner 能力”，但不会把敏感执行细节提前暴露。</p>
 *
 * @param shardPlanVersion 分片计划版本。后续 Runner 合同升级时用于兼容老 worker。
 * @param shardKind 分片类型，例如 SINGLE_OBJECT、OBJECT_FAN_OUT、SCHEDULED_WINDOW、CUSTOM_SQL_RESULT_SET。
 * @param shardStrategy 更细的策略摘要，通常来自离线作业计划中的 shardStrategy。
 * @param estimatedShardCount 低敏分片数量估计。-1 表示必须运行时发现，例如整库迁移或按 schema 扫描。
 * @param shardCountConfidence 分片数量可信度。EXACT 表示来自显式对象映射，RUNTIME_DISCOVERY 表示需要 Runner 探测。
 * @param parallelismPolicy 并发策略摘要，例如最小 bridge 串行、专用 Runner 按对象并发、按窗口并发等。
 * @param checkpointAware 是否需要 checkpoint 感知分片。为 true 时 Runner 必须按分片保存可恢复水位。
 * @param scheduleAware 是否需要任务层调度语义。定时批量、定时全量等场景通常为 true。
 * @param objectMappingDeclared 是否声明对象映射配置。这里只暴露布尔值，不暴露对象名。
 * @param partitionDeclared 是否声明分区配置。这里只暴露布尔值，不暴露字段、范围或表达式。
 * @param requiredRunnerCapabilities Runner 必须具备的能力标签，例如 OBJECT_FAN_OUT、CHECKPOINT_HANDOFF。
 * @param payloadPolicy 低敏载荷策略说明。
 */
public record SyncOfflineRunnerShardPlan(
        String shardPlanVersion,
        String shardKind,
        String shardStrategy,
        int estimatedShardCount,
        String shardCountConfidence,
        String parallelismPolicy,
        boolean checkpointAware,
        boolean scheduleAware,
        boolean objectMappingDeclared,
        boolean partitionDeclared,
        List<String> requiredRunnerCapabilities,
        String payloadPolicy
) {
}

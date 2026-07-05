/**
 * @Author : Cui
 * @Date: 2026/07/05 15:58
 * @Description DataSmart Govern Backend - SyncDataXJobExecutionContract.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.util.List;

/**
 * DataX-style Job 执行拓扑合同。
 *
 * <p>这是当前 data-sync 离线执行模型从“调度摘要”继续向“可执行拓扑”收敛的一步。
 * DataX 的核心思想不是“Python 去搬数据”，而是将一个 Job 拆成 Task、TaskGroup 和 Channel，再由 Reader/Writer 插件
 * 在执行面完成抽取和写入。我们现在先不引入完整 DataX 引擎，但必须把这个执行模型显式固定下来，否则后续多表、
 * 分片并发、限速、脏数据、checkpoint 和 callback 都会继续散落在不同 if/else 里。</p>
 *
 * <p>本合同与 {@link SyncOfflineRunnerJobContract} 的关系：</p>
 * <p>1. RunnerJobContract 回答“这次离线任务能不能派发、需要哪类 Runner、是否需要审批或 checkpoint”；</p>
 * <p>2. DataXJobExecutionContract 回答“如果要派发，Job/TaskGroup/Channel/Reader/Writer 应该具备怎样的低敏拓扑”；</p>
 * <p>3. 二者都不携带 SQL 正文、连接串、字段映射原文、过滤条件原文、对象清单正文或行样本。</p>
 *
 * @param contractVersion 合同版本，用于后续 Runner、Agent 和测试做兼容判断。
 * @param topologyStatus 拓扑状态，例如 MINIMAL_SINGLE_CHANNEL_RUN_ONCE_TOPOLOGY、DEDICATED_DATAX_STYLE_TOPOLOGY_REQUIRED。
 * @param jobKind Job 类型，例如 SINGLE_OBJECT_JOB、MULTI_OBJECT_FAN_OUT_JOB、CUSTOM_SQL_JOB。
 * @param jobExecutionMode 执行模式，例如最小 Java run-once bridge、专用异步 Runner、实时 CDC 不接收。
 * @param referenceRuntime 参考运行时，例如 DATAX_STYLE_OFFLINE_READER_WRITER_RUNNER。
 * @param sourceConnectorType 源端连接器类型低敏枚举。
 * @param targetConnectorType 目标端连接器类型低敏枚举。
 * @param readerFamily Reader 家族摘要。
 * @param writerFamily Writer 家族摘要。
 * @param modeFamily 模式族摘要。
 * @param shardKind 分片类型摘要，来自离线 Runner shardPlan。
 * @param estimatedTaskGroupCount TaskGroup 数量估计。-1 表示必须由运行时发现。
 * @param estimatedChannelCount Channel 数量估计。-1 表示必须由运行时根据配额、分片和资源组决定。
 * @param minimalBridgeCompatible 是否与当前最小 run-once bridge 兼容。
 * @param dedicatedRunnerRequired 是否必须由专用 DataX-style Runner 承接。
 * @param runtimeSafetyPolicy 运行安全策略。
 * @param taskGroups 代表性 TaskGroup 合同。为低敏摘要，不是完整任务清单。
 * @param requiredRunnerCapabilities Runner 必须具备的能力标签。
 * @param issueCodes 低敏问题码。
 * @param payloadPolicy 当前对象低敏载荷策略。
 */
public record SyncDataXJobExecutionContract(
        String contractVersion,
        String topologyStatus,
        String jobKind,
        String jobExecutionMode,
        String referenceRuntime,
        String sourceConnectorType,
        String targetConnectorType,
        String readerFamily,
        String writerFamily,
        String modeFamily,
        String shardKind,
        int estimatedTaskGroupCount,
        int estimatedChannelCount,
        boolean minimalBridgeCompatible,
        boolean dedicatedRunnerRequired,
        SyncDataXRuntimeSafetyPolicy runtimeSafetyPolicy,
        List<SyncDataXTaskGroupContract> taskGroups,
        List<String> requiredRunnerCapabilities,
        List<String> issueCodes,
        String payloadPolicy
) {
}

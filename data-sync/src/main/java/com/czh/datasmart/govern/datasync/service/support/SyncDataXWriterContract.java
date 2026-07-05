/**
 * @Author : Cui
 * @Date: 2026/07/05 15:58
 * @Description DataSmart Govern Backend - SyncDataXWriterContract.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

/**
 * DataX-style Writer 低敏执行合同。
 *
 * <p>Writer 是离线同步里的“加载侧”。它决定数据如何写入目标端、如何处理冲突、如何保证重试时不重复写错、
 * 以及在失败时如何提交或回滚。真实生产系统不能只说“把数据写过去”，因为 APPEND、UPSERT、REPLACE、OVERWRITE
 * 对幂等、审计、回滚和审批的要求完全不同。</p>
 *
 * <p>本合同只描述写入策略和安全边界，不包含目标表名、字段列表、连接信息或样本数据。</p>
 *
 * @param writerFamily Writer 家族，例如 JDBC_WRITER、FILE_WRITER、OBJECT_STORAGE_WRITER。
 * @param connectorType 目标端连接器类型。
 * @param datasourceBindingPolicy 数据源绑定策略。目标凭据必须由执行面根据 datasourceId 受控解析。
 * @param objectWritePolicy 目标对象写入策略，例如单对象写入、多对象 fan-out、导出 artifact 或运行时发现。
 * @param writeStrategy 写入策略，例如 APPEND、UPSERT、INSERT_IGNORE、REPLACE、OVERWRITE。
 * @param batchWritePolicy 批量写入策略，例如 JDBC batch、分片提交、专用 Runner 控制 batchSize。
 * @param idempotencyPolicy 幂等策略。说明重试、回放和失败恢复如何避免重复写入或覆盖错误。
 * @param conflictPolicy 冲突处理策略。UPSERT/REPLACE/INSERT_IGNORE 等模式必须有冲突键或目标端能力支撑。
 * @param commitPolicy 提交策略。说明最小 bridge 或专用 Runner 如何处理批次提交、失败和最终报告。
 * @param payloadPolicy 当前对象低敏载荷策略。
 */
public record SyncDataXWriterContract(
        String writerFamily,
        String connectorType,
        String datasourceBindingPolicy,
        String objectWritePolicy,
        String writeStrategy,
        String batchWritePolicy,
        String idempotencyPolicy,
        String conflictPolicy,
        String commitPolicy,
        String payloadPolicy
) {
}

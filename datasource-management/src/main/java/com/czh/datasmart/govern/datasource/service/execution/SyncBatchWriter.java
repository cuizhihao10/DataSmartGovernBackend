/**
 * @Author : Cui
 * @Date: 2026/06/20 03:22
 * @Description DataSmart Govern Backend - SyncBatchWriter.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution;

/**
 * 同步批处理写入器接口。
 *
 * <p>写入器是 reader 的下游，负责把一批已经经过字段映射、类型转换和治理校验的数据写入目标端。
 * 当前阶段只定义边界，不实现真实 JDBC 写入，是因为真实写入必须同时考虑连接池、事务、幂等、死锁重试、
 * 失败行采样、敏感字段遮蔽、目标端限流和审批策略。</p>
 */
public interface SyncBatchWriter {

    /**
     * 写入一批数据。
     *
     * @param context 写入上下文，包含内部 SQL 模板、写入策略、批大小、提交间隔和执行标识。
     * @param recordBatch reader 输出的内部记录批次，可能包含真实业务数据，只能在 worker 内部流转。
     * @return 写入结果摘要。该结果只包含数量和错误摘要，不包含真实行数据。
     */
    SyncBatchWriteResult writeBatch(SyncBatchWriteContext context, SyncBatchRecordBatch recordBatch);
}

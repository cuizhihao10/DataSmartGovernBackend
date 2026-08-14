/**
 * @Author : Cui
 * @Date: 2026/06/29 12:38
 * @Description DataSmart Govern Backend - DatasourceRunOnceResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.runonce;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * datasource-management run-once 低敏响应镜像。
 *
 * <p>该响应承载本批读写数量、累计数量、callback 建议、checkpoint 类型和低敏错误摘要。
 * 唯一的敏感执行字段是 {@link #checkpointCandidateValue}：它只在 data-sync 内存中短暂存在，
 * 完成范围校验与断点持久化后不得进入任何公开输出。</p>
 */
@Getter
@Setter
public class DatasourceRunOnceResponse {

    private Long taskId;
    private Long executionId;
    private String runStatus;
    private Long batchRecordsRead;
    private Long batchRecordsWritten;
    private Long batchFailedRecordCount;
    private Long totalRecordsRead;
    private Long totalRecordsWritten;
    private Long totalFailedRecordCount;
    private Boolean endOfSource;
    private Boolean failed;
    private Boolean progressCallbackRecommended;
    private Boolean checkpointCallbackRecommended;
    private Boolean checkpointCandidateProduced;
    /**
     * datasource-management 产生的下一 checkpoint 原始值。
     *
     * <p>该字段只能由内部服务账号调用链消费。禁止记录完整响应对象，也禁止把该值复制到执行结果、
     * WebSocket、Agent 证据、审计摘要或 task-management 回执。</p>
     */
    private Object checkpointCandidateValue;
    private String checkpointHandoffMode;
    private Boolean completeCallbackRecommended;
    private Boolean failCallbackRecommended;
    private String checkpointType;
    private String checkpointValueVisibility;
    private String errorSummary;
    private List<DatasourceDirtyRecordSample> dirtySamples;
    private Boolean dirtyThresholdExceeded;
    private List<String> warnings;
    private String payloadPolicy;
}

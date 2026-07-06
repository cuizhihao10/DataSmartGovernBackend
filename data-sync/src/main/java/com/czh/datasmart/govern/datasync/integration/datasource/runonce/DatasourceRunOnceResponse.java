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
 * <p>该响应只承载本批读写数量、累计数量、callback 建议、checkpoint 类型和低敏错误摘要。
 * 它不承载真实行数据、SQL、连接信息、字段值、失败样本或 checkpoint 原始值。</p>
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

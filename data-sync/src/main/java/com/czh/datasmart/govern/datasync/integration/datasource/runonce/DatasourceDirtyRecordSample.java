/**
 * @Author : Cui
 * @Date: 2026/07/07 23:42
 * @Description DataSmart Govern Backend - DatasourceDirtyRecordSample.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.runonce;

import lombok.Getter;
import lombok.Setter;

/**
 * datasource-management run-once 返回的结构化脏数据样本镜像。
 *
 * <p>该镜像用于 data-sync 将低敏脏数据样本落入 {@code data_sync_error_sample}。
 * 它不携带完整行数据，只携带错误分类、定位摘要和脱敏 payload。</p>
 */
@Getter
@Setter
public class DatasourceDirtyRecordSample {

    private String errorType;
    private String errorCode;
    private String errorMessage;
    private String sourceRecordKey;
    private String targetRecordKey;
    private String samplePayload;
    private Boolean retryable;
}

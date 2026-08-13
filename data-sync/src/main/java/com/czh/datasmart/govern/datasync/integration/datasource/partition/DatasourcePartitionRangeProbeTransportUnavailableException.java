/**
 * @Author : Cui
 * @Date: 2026/08/13 23:58
 * @Description DataSmart Govern Backend - DatasourcePartitionRangeProbeTransportUnavailableException.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.partition;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;

/**
 * 标识探测 AUTO_SPLIT_PK 源端范围时发生的临时传输故障。
 *
 * <p>范围探测与 run-once 同属 datasource-management 的传输边界。将此异常与 HTTP 拒绝分开，
 * data-sync 才能只把连接拒绝和超时标记为可重试；无效契约、权限故障和凭据故障仍保持 fail-closed
 * 且不可重试。</p>
 */
public final class DatasourcePartitionRangeProbeTransportUnavailableException extends PlatformBusinessException {

    /**
     * 创建低敏错误，不保留远程 URL、响应体或凭据细节。
     *
     * @param datasourceId 受限范围探测使用的源数据源标识
     */
    public DatasourcePartitionRangeProbeTransportUnavailableException(Long datasourceId) {
        super(PlatformErrorCode.EXTERNAL_DEPENDENCY_FAILED,
                "datasource-management range-probe transport is temporarily unavailable; "
                        + "data-sync stopped this partition probe fail-closed, datasourceId=" + datasourceId);
    }
}

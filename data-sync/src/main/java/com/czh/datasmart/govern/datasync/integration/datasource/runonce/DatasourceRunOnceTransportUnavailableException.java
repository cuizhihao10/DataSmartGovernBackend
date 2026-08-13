/**
 * @Author : Cui
 * @Date: 2026/08/13 23:15
 * @Description DataSmart Govern Backend - DatasourceRunOnceTransportUnavailableException.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.runonce;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;

/**
 * 标识 data-sync 调用数据源执行面时发生的传输层故障。
 *
 * <p>此类型刻意比通用外部依赖故障更窄。连接拒绝、连接超时或读取超时无需改变已批准任务就可能恢复，
 * 因此 Autopilot 可在原始授权范围内重试。HTTP 拒绝或无效响应不使用该类型表示，因为它们可能意味着
 * 权限、凭据、契约或服务端业务问题。</p>
 */
public final class DatasourceRunOnceTransportUnavailableException extends PlatformBusinessException {

    /**
     * 创建低敏传输故障，不保留响应体、端点或凭据。
     *
     * @param executionId 其内部 run-once 调用无法到达可用传输端点的 execution
     */
    public DatasourceRunOnceTransportUnavailableException(Long executionId) {
        super(PlatformErrorCode.EXTERNAL_DEPENDENCY_FAILED,
                "datasource-management run-once transport is temporarily unavailable; "
                        + "data-sync stopped this dispatch fail-closed, executionId=" + executionId);
    }
}

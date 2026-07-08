/**
 * @Author : Cui
 * @Date: 2026/07/09 22:43
 * @Description DataSmart Govern Backend - DatasourceTableRowCountProbeResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.tableprobe;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * data-sync 侧的 datasource-management 表行数探测响应镜像。
 *
 * <p>只复制预检查所需字段。data-sync 不关心 datasource-management 内部 SQL、连接、审计实现，
 * 只根据 {@code probeStatus/rowCount/empty} 判断是否允许全量 INSERT 继续进入执行准入。</p>
 */
@Getter
@Setter
public class DatasourceTableRowCountProbeResponse {

    private String probeStatus;
    private Long rowCount;
    private Boolean empty;
    private Long durationMs;
    private List<String> warnings;
    private String payloadPolicy;

    public boolean probed() {
        return "ROW_COUNT_PROBED".equalsIgnoreCase(probeStatus);
    }
}

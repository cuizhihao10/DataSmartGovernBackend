/**
 * @Author : Cui
 * @Date: 2026/07/27 20:00
 * @Description DataSmart Govern Backend - CdcInfrastructureReadinessProbe.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.czh.datasmart.govern.datasource.controller.dto.DataSourceCdcReadinessResult;

import java.util.List;

/** Isolates Kafka/Kafka Connect network probes from CDC database admission logic. */
public interface CdcInfrastructureReadinessProbe {

    List<DataSourceCdcReadinessResult.CheckItem> probe();
}

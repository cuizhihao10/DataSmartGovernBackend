/**
 * @Author : Cui
 * @Date: 2026/07/27 20:00
 * @Description DataSmart Govern Backend - DataSourceCdcReadinessService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service;

import com.czh.datasmart.govern.datasource.controller.dto.DataSourceCdcReadinessRequest;
import com.czh.datasmart.govern.datasource.controller.dto.DataSourceCdcReadinessResult;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;

/** Evaluates whether a requested datasource pair is ready for a real CDC pipeline. */
public interface DataSourceCdcReadinessService {

    DataSourceCdcReadinessResult check(DataSourceConfig sourceDatasource,
                                       DataSourceConfig targetDatasource,
                                       DataSourceCdcReadinessRequest request);
}

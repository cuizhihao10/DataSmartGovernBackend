package com.czh.datasmart.govern.datasource.service;

import com.czh.datasmart.govern.datasource.controller.dto.DataSourceSchemaRepairApplyRequest;
import com.czh.datasmart.govern.datasource.controller.dto.DataSourceSchemaRepairPreviewRequest;
import com.czh.datasmart.govern.datasource.controller.dto.DataSourceSchemaRepairResult;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;

/** Controlled preview/apply boundary for Agent-assisted schema repairs. */
public interface DataSourceSchemaRepairService {

    DataSourceSchemaRepairResult preview(DataSourceConfig datasource,
                                         DataSourceSchemaRepairPreviewRequest request,
                                         Long actorId);

    DataSourceSchemaRepairResult apply(DataSourceConfig datasource,
                                       DataSourceSchemaRepairApplyRequest request,
                                       Long actorId);
}

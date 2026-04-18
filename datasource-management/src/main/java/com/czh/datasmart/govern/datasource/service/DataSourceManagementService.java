package com.czh.datasmart.govern.datasource.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.czh.datasmart.govern.datasource.entity.DataSourceConfig;
import com.czh.datasmart.govern.datasource.entity.DataSourceConnectionTestResult;

/**
 * 数据源管理服务接口。
 */
public interface DataSourceManagementService extends IService<DataSourceConfig> {

    DataSourceConfig createDataSource(String name, String type, String jdbcUrl, String username,
                                      String password, String description);

    DataSourceConfig updateDataSource(Long id, String name, String jdbcUrl, String username,
                                      String password, String description);

    DataSourceConfig enableDataSource(Long id);

    DataSourceConfig disableDataSource(Long id);

    DataSourceConfig deleteDataSource(Long id);

    DataSourceConnectionTestResult testConnection(Long id);
}

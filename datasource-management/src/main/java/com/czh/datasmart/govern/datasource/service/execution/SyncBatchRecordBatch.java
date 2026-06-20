/**
 * @Author : Cui
 * @Date: 2026/06/20 03:42
 * @Description DataSmart Govern Backend - SyncBatchRecordBatch.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 批处理记录批次。
 *
 * <p>这是 reader 到 writer 之间的内部数据载体，不是 controller DTO，也不能进入 runtime event、审计摘要、
 * 普通日志、前端响应或 current-repo-state 这类低敏记录。它可能包含真实客户业务数据，因此只能在受控 worker
 * 内存管道中短暂存在。</p>
 *
 * <p>为什么需要这个对象：</p>
 * <p>1. 上一阶段的 `SyncBatchReadResult` 只包含低敏数量摘要，适合回写 progress；</p>
 * <p>2. 但真实 writer 必须拿到 reader 读取出的字段值，才能绑定 PreparedStatement；</p>
 * <p>3. 把业务行数据单独封装成内部批次对象，可以让“统计摘要”和“高敏数据管道”清晰分离。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncBatchRecordBatch {

    /**
     * 字段顺序。
     * writer 会按 `SyncPreparedJdbcStatement.parameterNames` 绑定值，
     * 该字段用于辅助校验 reader 输出和 writer 入参是否一致。
     */
    private List<String> columns;

    /**
     * 批次内行数据。
     * key 为字段名，value 为 JDBC 读取出的原始值或类型转换后的值。
     */
    private List<Map<String, Object>> rows;

    /**
     * 返回批次大小。
     */
    public int size() {
        return rows == null ? 0 : rows.size();
    }

    /**
     * 判断当前批次是否为空。
     */
    public boolean isEmpty() {
        return size() == 0;
    }
}

/**
 * @Author : Cui
 * @Date: 2026/06/20 03:22
 * @Description DataSmart Govern Backend - SyncBatchReadContext.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution;

import com.czh.datasmart.govern.datasource.service.execution.jdbc.SyncPreparedJdbcStatement;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 批处理读取上下文。
 *
 * <p>该对象属于 worker 内部执行层，不是 API DTO。
 * 它可以持有内部 SQL 模板，但仍不持有 JDBC URL、用户名、密码、样本数据或真实 checkpoint 值。
 * 真实 checkpoint 值应由 reader 在执行时从 checkpoint 存储读取，并通过 PreparedStatement 参数绑定。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncBatchReadContext {

    /**
     * 任务 ID，用于日志、幂等和回调关联。
     */
    private Long taskId;

    /**
     * execution ID，用于本次运行实例的进度和 checkpoint 回写。
     */
    private Long executionId;

    /**
     * 源数据源 ID。
     */
    private Long datasourceId;

    /**
     * checkpoint 类型，例如 TIME_WATERMARK、ID_WATERMARK。
     */
    private String checkpointType;

    /**
     * 推荐读取批大小。
     */
    private Integer fetchSize;

    /**
     * 内部预编译读取语句。
     */
    private SyncPreparedJdbcStatement readStatement;

    /**
     * 读取语句参数。
     * key 必须与 `readStatement.parameterNames` 对齐，例如 `checkpointValue`、`limit`。
     * 真实 checkpoint 值只允许进入该内部参数映射，并通过 PreparedStatement 绑定，不能拼接到 SQL 字符串。
     */
    private Map<String, Object> parameterValues;
}

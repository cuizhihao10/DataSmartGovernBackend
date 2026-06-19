/**
 * @Author : Cui
 * @Date: 2026/06/20 03:05
 * @Description DataSmart Govern Backend - SyncJdbcWriteStatementSpec.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution.jdbc;

import com.czh.datasmart.govern.datasource.support.SyncWriteStrategy;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * JDBC 写入语句生成规格。
 *
 * <p>该规格描述 worker 准备写入目标端时所需的结构化输入。
 * 它把“目标对象、字段清单、主键字段、写入策略、批大小”集中到一个对象里，
 * 让不同数据库方言可以在同一套输入之上生成 INSERT、UPSERT、INSERT IGNORE、MERGE 等内部 SQL。</p>
 *
 * <p>该对象不包含行数据本身。真实行数据后续应由 reader 读取后交给 writer，
 * writer 再按 `parameterNames` 顺序绑定到 PreparedStatement，不能把值拼接进 SQL 字符串。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncJdbcWriteStatementSpec {

    /**
     * 目标端对象定位符。
     */
    private String objectLocator;

    /**
     * 写入字段清单。
     * 字段顺序决定 PreparedStatement 参数绑定顺序，必须与 reader 输出或字段映射结果保持一致。
     */
    private List<String> columns;

    /**
     * 冲突判定字段。
     * UPSERT、INSERT_IGNORE、REPLACE 等策略通常必须提供主键或唯一键字段。
     */
    private List<String> primaryKeyColumns;

    /**
     * 写入策略。
     * 直接使用领域枚举，避免 worker 使用任意字符串导致语义漂移。
     */
    private SyncWriteStrategy writeStrategy;

    /**
     * 单批写入上限。
     * 方言层不直接使用该值生成 SQL，但保留在规格中，便于后续 writer 根据该值切分 batch。
     */
    private Integer batchSize;
}

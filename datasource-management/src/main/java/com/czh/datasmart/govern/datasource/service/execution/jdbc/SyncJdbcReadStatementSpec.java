/**
 * @Author : Cui
 * @Date: 2026/06/20 03:05
 * @Description DataSmart Govern Backend - SyncJdbcReadStatementSpec.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution.jdbc;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * JDBC 读取语句生成规格。
 *
 * <p>执行计划 `SyncBatchExecutionPlan.ReadPlan` 仍然是低敏控制面对象，不直接携带 SQL。
 * 当真实 worker 准备执行读取时，需要把 readPlan 再翻译成内部 SQL 生成规格。
 * 这个规格就是该翻译层的输入：它只描述要读哪个对象、哪些列、按哪个 checkpoint 字段推进、每批最多读多少行。</p>
 *
 * <p>注意：该对象依然不包含 JDBC URL、用户名、密码、checkpoint 真实值或业务数据。
 * checkpoint 真实值后续应由 worker 从 checkpoint 表或运行状态中读取，并通过 PreparedStatement 参数绑定。</p>
 */
@Data
@NoArgsConstructor
public class SyncJdbcReadStatementSpec {

    /**
     * 源端对象定位符。
     * 通常是 `schema.table` 或 `table`，方言层会逐段校验和引用，防止对象名被拼接成注入片段。
     */
    private String objectLocator;

    /**
     * 需要读取的列。
     * 为空时方言层会使用 `*`，真实生产建议由字段映射模板显式下发列清单，减少网络传输和字段漂移风险。
     */
    private List<String> selectedColumns;

    /**
     * checkpoint 推进字段。
     * 增量读取时必填，例如 `updated_at` 或自增 ID 字段；全量读取可以为空。
     */
    private String checkpointColumn;

    /**
     * 结构化过滤条件。
     *
     * <p>这些条件来自 data-sync 已校验过的 filterConfig，但 JDBC 方言层仍会二次校验字段名和操作符。
     * 条件值不会拼入 SQL，只会通过 parameterNames 和 readContext.parameterValues 绑定到 PreparedStatement。</p>
     */
    private List<SyncJdbcFilterCondition> filterConditions;

    /**
     * 复杂 where 谓词片段。
     *
     * <p>当用户在对象映射里配置了 OR、括号、函数或子查询时，控制面无法安全地拆成
     * {@link #filterConditions} 这种参数化条件。该字段只保存 {@code WHERE} 后面的谓词片段，
     * 由 JDBC 方言层二次校验后拼接为 {@code WHERE (...)}。它属于 internal SQL 生成输入，
     * 不能进入普通日志或公开响应。</p>
     */
    private String wherePredicate;

    /**
     * 全量扫描时用于稳定翻页的排序列。
     *
     * <p>DataX 类离线同步不会把整张表一次性加载到内存，而是按 task/batch 分段读取。
     * 对关系型数据库来说，分段读取必须有稳定顺序，否则 LIMIT/OFFSET 在源表并发变更或执行计划变化时
     * 可能出现重复读、漏读或顺序抖动。这里优先由字段映射中的目标主键反推出源端主键列；
     * 如果用户没有配置主键，准备层会退化为按已选择列排序，只作为最小闭环兜底。</p>
     */
    private List<String> stableSortColumns;

    /**
     * 读取策略。
     * 示例：FULL_OBJECT_SCAN、INCREMENTAL_TIME_WINDOW、INCREMENTAL_ID_RANGE。
     */
    private String readStrategy;

    /**
     * 单批读取上限。
     * 该值来自执行计划推荐 fetchSize，方言层只负责生成 limit/top 语义，真实限流还要结合 worker 线程池和连接池。
     */
    private Integer limit;

    /**
     * 自定义 SQL 查询正文。
     *
     * <p>只有 {@code readStrategy=CUSTOM_SQL_RESULT_SET} 时才使用该字段。它不来自普通对象定位符，
     * 而是由 data-sync 在执行前完成只读 SQL 校验后通过 internal 服务账号链路传入。方言层会再次做
     * 防御性只读校验，并把它包装为可分页结果集。</p>
     */
    private String customSql;

    public SyncJdbcReadStatementSpec(String objectLocator,
                                     List<String> selectedColumns,
                                     String checkpointColumn,
                                     List<SyncJdbcFilterCondition> filterConditions,
                                     List<String> stableSortColumns,
                                     String readStrategy,
                                     Integer limit) {
        this(objectLocator, selectedColumns, checkpointColumn, filterConditions, null, stableSortColumns,
                readStrategy, limit, null);
    }

    public SyncJdbcReadStatementSpec(String objectLocator,
                                     List<String> selectedColumns,
                                     String checkpointColumn,
                                     List<SyncJdbcFilterCondition> filterConditions,
                                     String wherePredicate,
                                     List<String> stableSortColumns,
                                     String readStrategy,
                                     Integer limit,
                                     String customSql) {
        this.objectLocator = objectLocator;
        this.selectedColumns = selectedColumns;
        this.checkpointColumn = checkpointColumn;
        this.filterConditions = filterConditions;
        this.wherePredicate = wherePredicate;
        this.stableSortColumns = stableSortColumns;
        this.readStrategy = readStrategy;
        this.limit = limit;
        this.customSql = customSql;
    }

    /**
     * 兼容旧构造器。
     */
    public SyncJdbcReadStatementSpec(String objectLocator,
                                     List<String> selectedColumns,
                                     String checkpointColumn,
                                     String readStrategy,
                                     Integer limit) {
        this(objectLocator, selectedColumns, checkpointColumn, List.of(), null, List.of(), readStrategy, limit, null);
    }

    /**
     * 兼容旧调用方的结构化过滤构造器。
     */
    public SyncJdbcReadStatementSpec(String objectLocator,
                                     List<String> selectedColumns,
                                     String checkpointColumn,
                                     List<SyncJdbcFilterCondition> filterConditions,
                                     String readStrategy,
                                     Integer limit) {
        this(objectLocator, selectedColumns, checkpointColumn, filterConditions, null, List.of(), readStrategy, limit, null);
    }
}

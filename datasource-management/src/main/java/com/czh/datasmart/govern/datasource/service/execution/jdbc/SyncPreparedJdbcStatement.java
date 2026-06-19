/**
 * @Author : Cui
 * @Date: 2026/06/20 03:05
 * @Description DataSmart Govern Backend - SyncPreparedJdbcStatement.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution.jdbc;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * JDBC 执行层内部预编译语句描述。
 *
 * <p>这个对象只用于 datasource-management 内部的执行器/方言层，不应该被普通管理 API、runtime event、
 * 审计投影或前端控制台直接返回。原因很简单：它包含真实 SQL 模板，虽然参数值仍然通过占位符传入，
 * 但 SQL 结构本身已经属于执行细节，公开后可能泄露库表结构、字段命名、同步策略或内部实现方式。</p>
 *
 * <p>为什么仍然要把 SQL 模板封装成对象，而不是直接返回字符串：</p>
 * <p>1. `sql` 负责表达数据库方言生成的预编译语句；</p>
 * <p>2. `parameterNames` 记录占位符顺序，worker 后续绑定参数时可以按名称对齐，避免字段顺序混乱；</p>
 * <p>3. `executionIntent` 说明这条语句用于全量读取、增量读取、追加写入还是冲突写入；</p>
 * <p>4. `safetyNotes` 记录这条语句的安全边界，便于测试和后续 worker 接入时做防误用检查。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncPreparedJdbcStatement {

    /**
     * 执行意图。
     * 示例：FULL_READ、INCREMENTAL_READ、APPEND_WRITE、UPSERT_WRITE。
     */
    private String executionIntent;

    /**
     * 预编译 SQL 模板。
     * 该字段只允许在内部 worker/dialect 层使用，不能进入外部响应或低敏事件投影。
     */
    private String sql;

    /**
     * 参数绑定顺序。
     * 列表中的每个名称对应 SQL 中的一个 `?` 占位符，worker 应按该顺序绑定真实值。
     */
    private List<String> parameterNames;

    /**
     * 安全说明。
     * 用于提醒调用方：SQL 模板不能外泄、参数值必须由 PreparedStatement 绑定、不能字符串拼接业务值。
     */
    private List<String> safetyNotes;
}

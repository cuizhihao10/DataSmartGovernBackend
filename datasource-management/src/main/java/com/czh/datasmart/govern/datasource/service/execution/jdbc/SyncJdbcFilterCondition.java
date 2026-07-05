/**
 * @Author : Cui
 * @Date: 2026/07/05 15:35
 * @Description DataSmart Govern Backend - SyncJdbcFilterCondition.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution.jdbc;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * JDBC 读取过滤条件。
 *
 * <p>该对象是 datasource-management worker 内部 SQL 模板生成层使用的结构化 where 条件。
 * 它与普通 where 字符串最大的区别是：字段名和操作符由方言层白名单校验，业务值只作为参数值绑定，
 * 不会被拼接进 SQL 模板。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncJdbcFilterCondition {

    /**
     * 源端字段名，只允许安全标识符。
     */
    private String column;

    /**
     * 标准操作符，例如 EQ、NE、GT、GTE、LT、LTE、LIKE、IS_NULL、IS_NOT_NULL。
     */
    private String operator;

    /**
     * 参数值。
     */
    private Object value;

    /**
     * 是否需要绑定参数。
     */
    private Boolean valueRequired;
}

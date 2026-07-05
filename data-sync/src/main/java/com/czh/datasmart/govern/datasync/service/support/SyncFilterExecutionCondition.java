/**
 * @Author : Cui
 * @Date: 2026/07/05 15:30
 * @Description DataSmart Govern Backend - SyncFilterExecutionCondition.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import lombok.Getter;

/**
 * 最小 JDBC run-once 可执行的过滤条件。
 *
 * <p>该对象来自同步模板的 {@code filterConfig}，用于表达用户在创建任务时配置的 where 条件。
 * 它不是公开响应 DTO，因为 {@code value} 可能包含业务状态、日期范围、租户编号、部门编号等客户数据范围信息。
 * data-sync 只在内部把它传给 datasource-management 的 internal run-once 接口，最终由 JDBC 方言层生成
 * {@code WHERE column operator ?} 并通过 {@code PreparedStatement} 绑定值。</p>
 *
 * <p>当前第一阶段只支持 AND 组合的一维条件，目的是优先打通“用户设置 where 条件后真实执行生效”的闭环。
 * OR、括号嵌套、函数表达式、子查询、动态分区表达式和自定义 SQL 暂不在这里放开，避免把普通过滤配置演变成
 * 难以审计的 SQL 注入入口。</p>
 */
@Getter
public class SyncFilterExecutionCondition {

    /**
     * 源端字段名。
     *
     * <p>只允许安全标识符，例如 {@code status}、{@code biz_date}、{@code tenant_code}。
     * 不允许 {@code a.b}、函数调用、引号、空格、注释或 SQL 片段。</p>
     */
    private final String column;

    /**
     * 标准化后的操作符。
     *
     * <p>当前支持 EQ、NE、GT、GTE、LT、LTE、LIKE、IS_NULL、IS_NOT_NULL。
     * 方言层会把它转换为数据库 SQL 操作符，但业务值始终通过参数绑定。</p>
     */
    private final String operator;

    /**
     * 过滤值。
     *
     * <p>该值只允许在 internal 执行链路中流转，不能进入普通日志、runtime event、审计摘要或进度文档。
     * IS_NULL/IS_NOT_NULL 不需要该值。</p>
     */
    private final Object value;

    /**
     * 是否需要绑定参数值。
     */
    private final boolean valueRequired;

    public SyncFilterExecutionCondition(String column, String operator, Object value, boolean valueRequired) {
        this.column = column;
        this.operator = operator;
        this.value = value;
        this.valueRequired = valueRequired;
    }
}

/**
 * @Author : Cui
 * @Date: 2026/07/03 20:02
 * @Description DataSmart Govern Backend - AgentRuntimeJdbcSqlExceptionSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.persistence;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;

/**
 * Agent Runtime JDBC 异常识别工具。
 *
 * <p>agent-runtime 早期的数据库实现以 MySQL 为目标，因此很多 Store 直接判断
 * {@link SQLIntegrityConstraintViolationException} 或 SQLState {@code 23000} 来识别唯一键冲突。
 * PostgreSQL 的唯一键冲突 SQLState 是 {@code 23505}，如果继续只判断 MySQL 状态码，幂等写入会被误认为系统异常，
 * 进而破坏 outbox、DAG confirmation、Skill 快照索引和受控工具提交事实的“重复写入即已存在”语义。</p>
 *
 * <p>本工具把数据库方言差异收敛到一个位置：</p>
 * <p>1. {@code 23000}：MySQL / JDBC 通用 integrity constraint violation；</p>
 * <p>2. {@code 23505}：PostgreSQL unique_violation；</p>
 * <p>3. {@link SQLIntegrityConstraintViolationException}：部分驱动会直接抛出的标准 JDBC 子类。</p>
 *
 * <p>这样上层 Store 不需要知道当前具体是 MySQL 兼容期还是 PostgreSQL 目标态，只需要表达业务语义：
 * 唯一键冲突代表“同一业务事实已经存在”，不代表需要重复执行副作用。</p>
 */
public final class AgentRuntimeJdbcSqlExceptionSupport {

    private static final String MYSQL_INTEGRITY_CONSTRAINT_STATE = "23000";
    private static final String POSTGRES_UNIQUE_VIOLATION_STATE = "23505";

    private AgentRuntimeJdbcSqlExceptionSupport() {
        throw new UnsupportedOperationException("AgentRuntimeJdbcSqlExceptionSupport 是工具类，不允许实例化");
    }

    /**
     * 判断异常链中是否存在数据库唯一键或完整性约束冲突。
     *
     * @param exception 可能由连接管理器包装过的运行时异常或 JDBC 异常。
     * @return true 表示可以按幂等重复写入处理；false 表示应继续抛出业务异常或系统异常。
     */
    public static boolean isDuplicateKey(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLIntegrityConstraintViolationException) {
                return true;
            }
            if (current instanceof SQLException sqlException && isDuplicateSqlState(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 统一判断 MySQL 与 PostgreSQL 的唯一键冲突 SQLState。
     *
     * <p>SQLState 为空通常代表异常不是数据库规范错误码，例如连接池超时、网络断开或驱动初始化失败；
     * 这些错误不能被吞掉，否则 outbox/receipt 可能出现“其实没写入但被当作重复”的危险误判。</p>
     */
    private static boolean isDuplicateSqlState(String sqlState) {
        return MYSQL_INTEGRITY_CONSTRAINT_STATE.equals(sqlState)
                || POSTGRES_UNIQUE_VIOLATION_STATE.equals(sqlState);
    }
}

/**
 * @Author : Cui
 * @Date: 2026/06/01 18:40
 * @Description DataSmart Govern Backend - RecordingDataSource.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;

/**
 * Agent Runtime 测试专用的轻量级 JDBC 事务记录器。
 *
 * <p>这个类不模拟 SQL 执行能力，只模拟 {@link DataSource#getConnection()} 和
 * {@link Connection#setAutoCommit(boolean)}、{@link Connection#commit()}、{@link Connection#rollback()} 等事务生命周期方法。
 * 它的业务价值是让 selected-node outbox 测试可以验证“服务层是否真的进入 JDBC 事务边界”，而不需要为了一个事务选择断言启动真实 MySQL 或内存数据库。</p>
 *
 * <p>为什么不直接使用 H2：当前项目生产存储是 MySQL，H2 容易把测试关注点带到方言兼容、建表 SQL 和 JSON 字段模拟上。
 * selected-node 这组测试只需要证明外层事务被打开、成功时提交、失败时可回滚；真正的 MySQL INSERT、唯一键冲突和 JSON 映射已经由各自 JDBC store 测试覆盖。</p>
 */
final class RecordingDataSource {

    int connectionRequests;
    int commitCount;
    int rollbackCount;
    private boolean autoCommit = true;

    DataSource proxy() {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        connectionRequests++;
                        return connectionProxy();
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private Connection connectionProxy() {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAutoCommit" -> autoCommit;
                    case "setAutoCommit" -> {
                        autoCommit = (boolean) args[0];
                        yield null;
                    }
                    case "commit" -> {
                        commitCount++;
                        yield null;
                    }
                    case "rollback" -> {
                        rollbackCount++;
                        yield null;
                    }
                    case "close" -> null;
                    case "isClosed" -> false;
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (boolean.class.equals(returnType)) {
            return false;
        }
        if (void.class.equals(returnType)) {
            return null;
        }
        return 0;
    }
}

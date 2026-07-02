/**
 * @Author : Cui
 * @Date: 2026/07/03 18:20
 * @Description DataSmart Govern Backend - AgentRuntimeStoreMode.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import java.util.Locale;

/**
 * Agent Runtime 仓储模式判断工具。
 *
 * <p>项目正在从 MySQL 迁移到 PostgreSQL，但 agent-runtime 的早期配置、注释、单元测试和部分运维脚本里已经存在
 * {@code store=mysql} 这样的历史值。直接把所有值一次性改成 {@code postgresql} 容易造成两个问题：</p>
 * <p>1. 老环境或老脚本升级后因为字符串不匹配而回退到 memory store，导致“以为已经持久化，实际仍在内存”；</p>
 * <p>2. 大量类名、注释和测试同时重命名，会制造很大的无业务收益 diff，反而增加闭环阶段的回归风险。</p>
 *
 * <p>因此本类把“使用 JDBC 持久化承载控制面事实”抽象成稳定判断：</p>
 * <p>- {@code memory}：仍表示 JVM 内热窗口，适合本地学习、单测和不启数据库的轻量启动；</p>
 * <p>- {@code mysql}：历史兼容别名，迁移期仍可识别，但不再代表目标数据库架构；</p>
 * <p>- {@code postgresql}/{@code postgres}/{@code jdbc}：新的推荐写法，表达 Agent Runtime 控制面事实由
 * PostgreSQL {@code agent_runtime} schema 承载。</p>
 *
 * <p>把判断集中到这里后，Spring 条件 Bean、事务边界和服务层能力判断都可以复用同一语义，避免各处散落字符串判断。</p>
 */
public final class AgentRuntimeStoreMode {

    private AgentRuntimeStoreMode() {
    }

    /**
     * 判断某个 store 配置是否属于 JDBC 持久化模式。
     *
     * @param store 配置值，可能来自 application.yml、环境变量或测试代码。
     * @return true 表示应注册 JDBC store，并要求数据库连接池、Flyway schema 和运维备份策略已经准备好。
     */
    public static boolean isJdbcDurable(String store) {
        String normalized = normalize(store);
        return "mysql".equals(normalized)
                || "postgresql".equals(normalized)
                || "postgres".equals(normalized)
                || "jdbc".equals(normalized);
    }

    /**
     * 判断某个 store 配置是否为 memory。
     *
     * <p>该方法主要用于注释和测试表达完整语义；当前大多数 memory Bean 仍直接使用 Spring 条件判断
     * {@code equalsIgnoreCase('memory')}，因为 memory 是默认路径，不需要迁移兼容别名。</p>
     */
    public static boolean isMemory(String store) {
        return "memory".equals(normalize(store));
    }

    private static String normalize(String store) {
        if (store == null || store.isBlank()) {
            return "memory";
        }
        return store.trim().toLowerCase(Locale.ROOT);
    }
}

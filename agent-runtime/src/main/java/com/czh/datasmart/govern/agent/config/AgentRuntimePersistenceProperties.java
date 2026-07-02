/**
 * @Author : Cui
 * @Date: 2026/05/28 23:32
 * @Description DataSmart Govern Backend - AgentRuntimePersistenceProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent Runtime 持久化策略配置。
 *
 * <p>Agent Runtime 里有两类状态必须逐步从“内存热窗口”升级为“系统事实库”：
 * 1. 会话、Run、工具审计等控制面业务状态；
 * 2. runtime event、tool event outbox、长期记忆写入等可回放事实。
 *
 * <p>当前阶段先从工具执行审计开始抽象持久化边界，因为它直接影响 Agent 是否能够像 Codex/Claude Code 一样恢复工具调用历史、
 * 解释审批过程、重放执行状态、排查失败原因，并为后续长期记忆写入提供可信来源。</p>
 *
 * <p>为什么默认仍然是 memory：
 * 1. 本项目当前仍处于多模块收敛阶段，本地学习和单元测试不应该因为没启动 PostgreSQL 而全部失败；
 * 2. JDBC 持久化需要与工具事件 outbox 放在同一个事务边界中设计，否则会形成新的双写问题；
 * 3. 先把配置契约、SQL 表、仓储端口和服务保存顺序固定下来，可以让后续 PostgreSQL 实现是“增量替换”，而不是推翻重构。</p>
 */
@Data
@ConfigurationProperties(prefix = "datasmart.agent-runtime.persistence")
public class AgentRuntimePersistenceProperties {

    /**
     * 工具执行审计仓储类型。
     *
     * <p>当前支持：
     * - memory：默认值，使用 JVM 内 ConcurrentHashMap，适合本地学习、单测、早期联调；
     * - postgresql/jdbc：推荐值，使用 PostgreSQL agent_runtime schema 承载控制面事实；
     * - mysql：历史兼容值，迁移期仍识别为 JDBC durable store，但不再代表目标数据库架构。
     *
     * <p>生产环境最终不应长期使用 memory。memory 无法跨实例共享，服务重启后数据丢失，也不能支撑审计合规、
     * 断线重连 replay、长期记忆追溯和 outbox 补偿。</p>
     */
    private String auditStore = "memory";

    /**
     * 工具执行事件 outbox 仓储类型。
     *
     * <p>当前支持：
     * - memory：默认值，使用 JVM 内存热窗口，适合本地学习和单测；
     * - postgresql/jdbc：使用 PostgreSQL outbox 表，适合集成环境和生产环境；
     * - mysql：历史兼容值，迁移期仍识别为 JDBC durable store。
     *
     * <p>从商业化可靠性角度看，auditStore 与 outboxStore 最终应该同时切到 PostgreSQL/JDBC，
     * 并放入同一事务边界。但工程落地可以分阶段灰度：先启用审计事实持久化，再启用 outbox，
     * 最后再合并事务。</p>
     */
    private String outboxStore = "memory";

    /**
     * 是否期望启用数据库持久化链路。
     *
     * <p>该字段当前主要作为配置契约和运维提示：JDBC 仓储实现可以同时要求
     * {@code auditStore=postgresql/outboxStore=postgresql} 与 {@code databaseEnabled=true}，
     * 避免误配置一个字符串就让应用尝试连接数据库。
     * 这种“双开关”对商业化部署很有用，因为生产切换持久化通常需要先建表、迁移、灰度和回滚预案。</p>
     */
    private boolean databaseEnabled = false;

    /**
     * 判断工具审计状态与工具事件 outbox 是否都已经启用 JDBC 持久化。
     *
     * <p>这个判断会被事务边界和 outbox 必达策略共同使用。原因是商业化事务 outbox 的核心不是“存在一个数据库连接”，
     * 而是“业务状态表和事件箱表在同一个数据库事务里提交”。如果只把 auditStore 或 outboxStore 其中一个切到 JDBC，
     * 仍然无法获得完整原子性，因此这里必须同时检查三个条件：</p>
     * <p>1. databaseEnabled=true，确认部署方明确打开数据库链路；</p>
     * <p>2. auditStore=postgresql/jdbc/mysql，确认工具审计状态进入 durable store；</p>
     * <p>3. outboxStore=postgresql/jdbc/mysql，确认工具事件 outbox 也进入 durable store。</p>
     *
     * @return true 表示可以启用“审计状态 + outbox 事件”同事务语义。
     */
    public boolean isStateAndOutboxJdbcEnabled() {
        return databaseEnabled
                && AgentRuntimeStoreMode.isJdbcDurable(auditStore)
                && AgentRuntimeStoreMode.isJdbcDurable(outboxStore);
    }

    /**
     * JDBC 连接配置。
     *
     * <p>这里没有复用 Spring Boot 默认的 {@code spring.datasource}，是一个有意的边界控制：
     * agent-runtime 当前默认仍是内存模式，如果把 PostgreSQL 连接放入全局 datasource，很容易让 Spring Boot 在本地开发时自动尝试连接数据库。
     * 通过独立配置块和条件化连接池，只有明确打开数据库持久化时才会创建连接。</p>
     */
    private Jdbc jdbc = new Jdbc();

    /**
     * Agent Runtime 专用 JDBC 配置。
     *
     * <p>生产环境建议通过环境变量或配置中心注入，不要把真实账号密码提交到仓库。
     * 当前默认值只用于本地 Docker/PostgreSQL 约定，且只有 databaseEnabled 与至少一个 JDBC durable store 同时满足时才会被使用。</p>
     */
    @Data
    public static class Jdbc {

        /**
         * PostgreSQL JDBC URL。
         *
         * <p>默认指向 {@code agent_runtime} schema，并设置 {@code ApplicationName}，便于 DBA 在
         * {@code pg_stat_activity} 中直接区分 Agent Runtime 控制面连接。生产环境还应继续配置服务 DNS、
         * 只读/读写账号分离、连接超时、慢查询告警和备份恢复策略。</p>
         */
        private String url = "jdbc:postgresql://localhost:5432/datasmart_govern?currentSchema=agent_runtime&ApplicationName=datasmart-agent-runtime";

        /**
         * 数据库用户名。
         */
        private String username = "datasmart";

        /**
         * 数据库密码。
         *
         * <p>本地可以为空；生产环境必须来自环境变量、Nacos 加密配置或专门的 secrets 管理系统。</p>
         */
        private String password = "";

        /**
         * JDBC Driver 类名。
         */
        private String driverClassName = "org.postgresql.Driver";

        /**
         * 连接池名称。
         */
        private String poolName = "datasmart-agent-runtime-jdbc";

        /**
         * 最小空闲连接数。
         */
        private int minimumIdle = 1;

        /**
         * 最大连接池大小。
         *
         * <p>工具审计写入频率与 Agent 工具调用量相关，不应盲目放大。
         * 后续压测时应结合 run 并发、outbox dispatcher 频率、PostgreSQL CPU/IO、锁等待和慢 SQL 指标调整。</p>
         */
        private int maximumPoolSize = 10;

        /**
         * 获取连接最大等待时间，单位毫秒。
         */
        private long connectionTimeoutMs = 3000;

        /**
         * 空闲连接保留时间，单位毫秒。
         */
        private long idleTimeoutMs = 600000;

        /**
         * 连接最大生命周期，单位毫秒。
         */
        private long maxLifetimeMs = 1800000;

        /**
         * 数据库查询最大返回行数。
         *
         * <p>当前仓储端口还没有分页对象，因此数据库实现先设置硬上限，避免诊断接口或错误调用一次扫出大量历史审计。
         * 后续做审计中心/管理后台时，应升级为 page/pageSize/tenant/project 组合查询。</p>
         */
        private int maxQueryLimit = 1000;
    }
}

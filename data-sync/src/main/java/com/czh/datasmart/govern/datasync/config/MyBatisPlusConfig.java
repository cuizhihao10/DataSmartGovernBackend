/**
 * @Author : Cui
 * @Date: 2026/07/02 00:00
 * @Description DataSmartGovernBackend - MyBatisPlusConfig.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * data-sync 的 MyBatis-Plus 基础配置。
 *
 * <p>本类只描述 data-sync “平台控制面数据库”的 ORM 行为：
 * 模板、任务、执行记录、checkpoint、幂等记录、事故、错误样本、审计和 outbox 等表已经迁移到 PostgreSQL。
 * 它不代表 data-sync 只能同步 PostgreSQL 数据，也不影响客户侧 MySQL/PostgreSQL/Kafka/文件等连接器能力；
 * 外部源端和目标端连接仍通过 datasource-management 与受控 connector runtime 访问。</p>
 */
@Configuration
public class MyBatisPlusConfig {

    /**
     * 注册 MyBatis-Plus 拦截器链。
     *
     * <p>分页不是完全数据库无关的能力。MyBatis-Plus 生成 count SQL、LIMIT/OFFSET SQL 时必须知道目标数据库方言。
     * data-sync 当前平台业务库已经切到 PostgreSQL，因此这里使用 {@link DbType#POSTGRE_SQL}。
     * 如果只替换 JDBC URL 和驱动，却继续使用 MySQL 方言，简单查询可能短期可用，
     * 但分页列表、复杂 count 包装和未来 PostgreSQL 专属表达式会逐步暴露运行期兼容问题。</p>
     *
     * @return MyBatis-Plus 拦截器聚合器，由 Spring 注入 SqlSessionFactory。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }

    /**
     * 统一填充创建时间和更新时间。
     *
     * <p>data-sync 的控制面表大量依赖 createTime/updateTime 做运营筛选、保留期清理、事故追踪和审计排序。
     * 将这些字段交给 MetaObjectHandler 统一维护，可以避免每个 service 分支重复写时间字段，
     * 也能保证模板、任务、执行、checkpoint、outbox 等表的时间语义保持一致。</p>
     *
     * <p>Java 实体使用 {@link LocalDateTime}，PostgreSQL DDL 对应 {@code TIMESTAMP WITHOUT TIME ZONE}。
     * 后续如果需要按租户时区展示，应在 API/展示边界做转换，不要让数据库隐式时区转换改写审计事实。</p>
     *
     * @return MyBatis-Plus 元对象填充器。
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
                this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}

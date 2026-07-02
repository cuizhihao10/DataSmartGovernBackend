package com.czh.datasmart.govern.datasource.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * @Author : Cui
 * @Date: 2026/4/18 21:55
 * @Description DataSmart Govern Backend - MyBatisPlusConfig.java
 * @Version:1.0.0
 *
 * <p>datasource-management 的 MyBatis-Plus 基础配置。</p>
 *
 * <p>需要特别区分两种数据库语义：</p>
 * <p>1. 平台自身业务事实库：保存 datasource_config、sync_task、sync_execution 等控制面数据，
 * 当前已经迁移到 PostgreSQL。</p>
 * <p>2. 客户侧外部数据源：用户登记的 MySQL、PostgreSQL、SQL Server 等数据源，由连接器能力按需访问，
 * 不通过这里的 MyBatis-Plus 分页插件生成 SQL。</p>
 *
 * <p>因此本类的分页方言只描述“平台管理库”的 SQL 生成规则，不代表平台只能连接 PostgreSQL 外部数据源。</p>
 */
@Configuration
public class MyBatisPlusConfig {

    /**
     * 注册 MyBatis-Plus 分页拦截器。
     *
     * <p>selectPage、Page 查询和部分管理台列表接口都会经过该拦截器生成分页 SQL。
     * datasource-management 的平台业务库已经切换到 PostgreSQL，因此必须使用 {@link DbType#POSTGRE_SQL}。
     * 如果这里继续使用 MySQL 方言，简单 LIMIT 场景可能暂时看不出问题，但复杂 count 包装、
     * OFFSET 语义、保留字处理和未来 PostgreSQL 专属表达式会逐步暴露兼容风险。</p>
     *
     * @return MyBatis-Plus 插件聚合器，Spring 会注入到 SqlSessionFactory。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }

    /**
     * 自动填充创建时间和更新时间。
     *
     * <p>MyBatis-Plus 会在 insert 或 update 时调用该处理器，为实体中标注
     * {@code FieldFill.INSERT} 或 {@code FieldFill.INSERT_UPDATE} 的字段写入当前时间。
     * 这样控制器和服务层不需要在每个创建、更新分支重复维护时间字段，也能保证所有表的时间语义一致。</p>
     *
     * <p>本项目的 Java 实体使用 {@link LocalDateTime}，PostgreSQL DDL 对应
     * {@code TIMESTAMP WITHOUT TIME ZONE}。应用层后续如果统一 UTC 或租户时区展示，应在边界层完成转换，
     * 不要让数据库隐式时区转换改变历史审计语义。</p>
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

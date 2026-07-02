/**
 * @Author : Cui
 * @Date: 2026/4/18 21:30
 * @Description DataSmart Govern Backend - MyBatisPlusConfig.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 配置。
 * 当前数据质量模块和其他业务模块保持一致的 ORM 基础能力，
 * 目的是减少跨模块学习和维护时的心智切换成本。
 *
 * 这里主要解决两个问题：
 * 1. 提供分页拦截器，支撑规则列表分页查询。
 * 2. 提供时间字段自动填充，避免每次写规则或报告都重复设置时间。
 */
@Configuration
public class MyBatisPlusConfig {

    /**
     * 注册 MyBatis-Plus 拦截器链。
     *
     * <p>分页 SQL 并不是数据库无关的：不同数据库对 LIMIT/OFFSET、参数绑定和 count 优化的处理不同。
     * data-quality 已完成 PostgreSQL 切换，因此这里必须显式选择 {@link DbType#POSTGRE_SQL}。
     * 如果只换 JDBC 驱动却继续保留 MYSQL 方言，普通 CRUD 可能暂时正常，但分页列表会在真实流量下
     * 生成错误或次优 SQL，这正是“看似迁移成功、实际未完成”的典型风险。</p>
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }

    /**
     * 自动填充创建时间和更新时间。
     * 这些字段属于典型元数据，适合交给框架统一维护。
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

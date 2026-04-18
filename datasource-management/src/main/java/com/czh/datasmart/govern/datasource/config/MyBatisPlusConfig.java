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
 * MyBatis-Plus 配置。
 * <p>
 * 这个配置类主要解决两个通用问题：
 * 1. 分页插件，让分页查询不需要手写 limit / offset SQL。
 * 2. 自动填充时间字段，让 createTime / updateTime 这类审计字段保持统一。
 * <p>
 * 这里沿用 task-management 模块的设计方式，后续其他模块也可以照这个模板继续扩展。
 */
@Configuration
public class MyBatisPlusConfig {

    /**
     * 分页插件。
     * 当前数据库是 MySQL，因此 DbType 显式指定为 MYSQL。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    /**
     * 时间字段自动填充。
     * <p>
     * 这样可以避免每个 Service 方法都重复设置时间戳，
     * 同时让审计字段保持一致的写入策略。
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

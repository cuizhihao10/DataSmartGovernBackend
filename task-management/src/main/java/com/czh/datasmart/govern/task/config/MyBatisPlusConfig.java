package com.czh.datasmart.govern.task.config;

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
 * @Date: 2026/4/18 22:10
 * @Description DataSmart Govern Backend - MyBatisPlusConfig.java
 * @Version:1.0.0
 *
 * MyBatis-Plus 配置类。
 * 任务模块当前使用 MyBatis-Plus 的原因很直接：
 * 1. 基础 CRUD、分页和条件查询能快速落地。
 * 2. 对初学者来说，比纯 XML SQL 更容易先建立“实体 -> Mapper -> Service”的认知链路。
 * 3. 后续如果有复杂查询，依然可以继续引入自定义 SQL，不会把路堵死。
 *
 * 当前配置重点解决两个问题：
 * - 分页查询插件，避免手写分页 SQL。
 * - 通用时间字段自动填充，减少 createTime/updateTime 的重复维护代码。
 */
@Configuration
public class MyBatisPlusConfig {

    /**
     * 注册 MyBatis-Plus 拦截器。
     * 当前只启用了分页拦截器，因为任务中心天然会有列表查询、日志查询、历史检索等场景。
     * 指定 DbType.MYSQL 后，分页 SQL 会按 MySQL 方言生成。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    /**
     * 注册自动填充处理器。
     * 当实体字段标注了 FieldFill.INSERT 或 FieldFill.INSERT_UPDATE 时，
     * MyBatis-Plus 会在插入和更新节点回调这个处理器，自动补齐时间字段。
     *
     * 这样做的价值在于：
     * 1. 减少每个 Service 方法都手动 set 更新时间的样板代码。
     * 2. 让时间字段维护规则集中在一个地方，更不容易漏掉。
     * 3. 后续如果要接入审计字段（如 createBy/updateBy），可以沿着这套机制扩展。
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

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
 * task-management 的 MyBatis-Plus 基础配置。
 *
 * <p>本配置只描述任务中心自己的平台控制面数据库行为：
 * 任务主表、草稿、执行 run、执行日志、回调幂等、Agent command inbox、data-sync worker command outbox
 * 和 data-sync worker execution receipt 等事实表已经逐步迁移到 PostgreSQL 的 {@code task_management} schema。</p>
 *
 * <p>学习这个类时可以抓住两个重点：</p>
 * <p>1. MyBatis-Plus 负责把 Java 实体、Mapper 和数据库表连接起来，减少基础 CRUD 样板代码；</p>
 * <p>2. 数据库方言不是可忽略细节。分页插件会生成 count、limit/offset 等 SQL，如果仍使用 MySQL 方言，
 * 简单 CRUD 可能短期看不出问题，但真实列表、运维分页和后续复杂查询会逐步暴露兼容风险。</p>
 */
@Configuration
public class MyBatisPlusConfig {

    /**
     * 注册 MyBatis-Plus 拦截器链。
     *
     * <p>分页是任务中心的核心能力之一：任务列表、执行历史、队列检查、outbox 运维视图和 receipt 查询
     * 都需要稳定分页。如果不注册分页拦截器，Service 层就要散落手写分页 SQL；
     * 如果注册但使用错误方言，MyBatis-Plus 生成的分页 SQL 可能在 PostgreSQL 上运行失败。</p>
     *
     * <p>这里使用 {@link DbType#POSTGRE_SQL}，表示 task-management 的平台业务库已经切到 PostgreSQL。
     * 这不影响系统管理外部 MySQL 数据源的能力，因为外部数据源连接属于 datasource-management 和 connector runtime 的职责，
     * 不属于任务中心主 datasource。</p>
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
     * 注册自动填充处理器。
     *
     * <p>任务中心的 createTime/updateTime 不只是普通审计字段，它们还会参与：</p>
     * <p>1. 任务列表和队列积压排序；</p>
     * <p>2. 执行 run、回调幂等、Agent inbox/outbox 的保留期清理；</p>
     * <p>3. 运维诊断、Prometheus 指标归因和未来的审计导出。</p>
     *
     * <p>把通用时间填充集中在一个地方，可以减少 Service 分支里反复 set 时间字段的样板代码，
     * 也能降低新增表或新增流程时遗漏更新时间的概率。</p>
     *
     * <p>Java 实体使用 {@link LocalDateTime}，PostgreSQL DDL 使用 {@code TIMESTAMP WITHOUT TIME ZONE}。
     * 如果后续要按用户时区展示，应在 API 或展示边界转换，不要让数据库隐式时区转换改写任务审计事实。</p>
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

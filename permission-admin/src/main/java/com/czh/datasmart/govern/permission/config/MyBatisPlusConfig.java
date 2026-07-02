/**
 * @Author : Cui
 * @Date: 2026/07/02 23:17
 * @Description DataSmartGovernBackend - MyBatisPlusConfig.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * permission-admin 的 MyBatis-Plus 扩展配置。
 *
 * <p>权限中心有两个典型分页场景：
 * 1. 项目成员授权列表，用于排查某个 actor 在哪些 project/workspace 下拥有授权；
 * 2. 权限 outbox 和审计记录列表，用于运营人员查看投递失败、人工重试和授权变更历史。
 *
 * <p>这些分页如果没有数据库方言拦截器，MyBatis-Plus 无法稳定生成 PostgreSQL 的 LIMIT/OFFSET SQL。
 * 对商业系统来说，这不是“列表慢一点”的小问题：权限审计和 outbox 可能长期累积，如果误把大表全量读入 JVM，
 * 会同时放大数据库压力、网关响应延迟和管理后台内存风险。因此模块迁移 PostgreSQL 时必须显式绑定
 * {@link DbType#POSTGRE_SQL}，而不是依赖框架猜测当前 JDBC URL。</p>
 */
@Configuration
public class MyBatisPlusConfig {

    /**
     * 注册 MyBatis-Plus 分页拦截器。
     *
     * <p>该 Bean 只负责 SQL 方言与分页物理改写，不承载租户过滤、角色过滤或数据范围过滤。
     * 权限语义仍然放在 service/support 层处理，避免把业务规则隐藏进 ORM 插件里，后续排查授权问题时更容易追踪。</p>
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }
}

/**
 * @Author : Cui
 * @Date: 2026/07/08 23:12
 * @Description DataSmart Govern Backend - PermissionProjectMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.permission.entity.PermissionProject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 项目主数据 Mapper。
 *
 * <p>项目控制面当前主要使用 MyBatis-Plus 基础 CRUD，同时补一个 PostgreSQL 序列取号方法。
 * 使用数据库序列而不是让前端传 projectId，有两个好处：</p>
 *
 * <p>1. 用户不用理解内部数字主键，页面只填写项目名称、编码和描述；</p>
 * <p>2. 并发创建项目时由数据库保证 ID 唯一，避免应用层用 max(project_id)+1 产生竞态。</p>
 */
@Mapper
public interface PermissionProjectMapper extends BaseMapper<PermissionProject> {

    /**
     * 获取下一个项目 ID。
     *
     * <p>该序列由 V15 迁移脚本创建，并从 100000 开始，避开已有初始化项目 900、101 等固定 ID。
     * 如果未来项目迁移到分布式 ID、雪花算法或租户内局部编号，只需要替换这里和 Service 的取号策略。</p>
     */
    @Select("SELECT nextval('permission_admin.permission_project_id_seq')")
    Long nextProjectId();

    /**
     * 查询租户默认可用应用。
     *
     * <p>当前前端只展示“项目”，不再要求用户理解 applicationId。
     * 这里优先选择租户表 default_application_code 指向的应用；如果租户没有配置默认应用，则选择租户内第一个 ACTIVE 应用。
     * 这让 FlashSync 本地开租、企业交付脚本和未来多应用租户都能复用同一套项目创建入口。</p>
     */
    @Select("""
            SELECT application.application_id
            FROM permission_application application
            LEFT JOIN permission_tenant tenant
              ON tenant.tenant_id = application.tenant_id
            WHERE application.tenant_id = #{tenantId}
              AND application.status = 'ACTIVE'
            ORDER BY CASE
                       WHEN tenant.default_application_code IS NOT NULL
                            AND application.application_code = tenant.default_application_code THEN 0
                       ELSE 1
                     END,
                     application.application_id ASC
            LIMIT 1
            """)
    Long selectDefaultApplicationId(@Param("tenantId") Long tenantId);

    /**
     * 判断指定应用是否属于目标租户且处于可用状态。
     *
     * <p>平台管理员或交付脚本可以显式指定 applicationId，但仍不能把项目挂到其他租户应用下；
     * 否则后续项目切换、菜单、审计和计费都会出现跨租户串扰。</p>
     */
    @Select("""
            SELECT COUNT(1)
            FROM permission_application
            WHERE tenant_id = #{tenantId}
              AND application_id = #{applicationId}
              AND status = 'ACTIVE'
            """)
    long countActiveApplication(@Param("tenantId") Long tenantId,
                                @Param("applicationId") Long applicationId);

    /**
     * 统计项目下仍处于活动状态的数据源数量。
     *
     * <p>permission-admin 不引入 datasource-management 的 Java 实体，是为了避免模块编译期耦合。
     * 这里使用 schema 限定的只读 count SQL，表达的是“删除/归档项目前的控制面占用检查”：
     * 只要还有未删除数据源，就说明项目仍承载可被同步任务引用的连接配置，不能直接归档式删除。</p>
     */
    @Select("""
            SELECT COUNT(1)
            FROM datasource_management.datasource_config
            WHERE tenant_id = #{tenantId}
              AND project_id = #{projectId}
              AND COALESCE(status, 'ACTIVE') <> 'DELETED'
            """)
    long countActiveDatasources(@Param("tenantId") Long tenantId,
                                @Param("projectId") Long projectId);

    /**
     * 统计项目下仍启用的数据同步模板数量。
     *
     * <p>模板是可复用的同步配置。即使当前没有任务正在运行，只要模板仍启用，用户或 Agent 就可能继续基于模板创建任务，
     * 因此归档项目之前必须先禁用或迁移模板。</p>
     */
    @Select("""
            SELECT COUNT(1)
            FROM data_sync.data_sync_template
            WHERE tenant_id = #{tenantId}
              AND project_id = #{projectId}
              AND enabled = TRUE
            """)
    long countEnabledSyncTemplates(@Param("tenantId") Long tenantId,
                                   @Param("projectId") Long projectId);

    /**
     * 统计项目下未归档的数据同步任务数量。
     *
     * <p>任务是用户可见的执行与调度主对象。只要任务还不是 ARCHIVED，就可能处于编辑、等待调度、运行、失败待处理、
     * 回收站查看或人工介入等状态，删除项目会让任务失去归属上下文，所以必须先由 data-sync 完成任务下线/归档。</p>
     */
    @Select("""
            SELECT COUNT(1)
            FROM data_sync.data_sync_task
            WHERE tenant_id = #{tenantId}
              AND project_id = #{projectId}
              AND current_state <> 'ARCHIVED'
            """)
    long countActiveSyncTasks(@Param("tenantId") Long tenantId,
                              @Param("projectId") Long projectId);
}

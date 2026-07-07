/**
 * @Author : Cui
 * @Date: 2026/07/07 22:40
 * @Description DataSmart Govern Backend - SyncTaskGroupMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasync.entity.SyncTaskGroup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 同步任务分组 Mapper。
 *
 * <p>分组已经从“任务表上的两个展示字段”升级为独立资源，因此这里提供两类访问能力：</p>
 * <p>1. 精确作用域访问：创建、删除、默认分组兜底都必须落在 tenant/project/workspace 这个明确作用域内；</p>
 * <p>2. 可见范围访问：列表和树形菜单需要结合 permission-admin 透传的数据范围，把当前操作者可见的分组一次性取出。</p>
 *
 * <p>为什么仍然不使用递归 SQL 构树：本项目处于 MySQL 向 PostgreSQL 迁移阶段，递归 CTE 在两端语法和优化器表现上存在差异。
 * 分组树通常不会很深，也不会达到百万级节点，使用 Java 内存构树更容易保持跨数据库兼容和业务可读性。</p>
 */
@Mapper
public interface SyncTaskGroupMapper extends BaseMapper<SyncTaskGroup> {

    /**
     * 按精确作用域和稳定编码查询一个分组。
     *
     * <p>projectId/workspaceId 使用“同为空或同值”的判断，而不是简单 eq，是为了支持租户级分组、项目级分组和工作空间级分组共存。
     * 这个方法服务于创建任务、移组、删除分组、校验父分组等写路径，因此必须使用精确作用域，避免误把其它项目的同名分组当成当前分组。</p>
     */
    @Select("""
            <script>
            SELECT *
            FROM data_sync_task_group
            WHERE tenant_id = #{tenantId}
              AND group_code = #{groupCode}
            <choose>
              <when test="projectId == null">
                AND project_id IS NULL
              </when>
              <otherwise>
                AND project_id = #{projectId}
              </otherwise>
            </choose>
            <choose>
              <when test="workspaceId == null">
                AND workspace_id IS NULL
              </when>
              <otherwise>
                AND workspace_id = #{workspaceId}
              </otherwise>
            </choose>
            <if test="includeArchived == false">
              AND archived = FALSE
            </if>
            ORDER BY id DESC
            LIMIT 1
            </script>
            """)
    SyncTaskGroup selectByScopeAndCode(@Param("tenantId") Long tenantId,
                                       @Param("projectId") Long projectId,
                                       @Param("workspaceId") Long workspaceId,
                                       @Param("groupCode") String groupCode,
                                       @Param("includeArchived") boolean includeArchived);

    /**
     * 查询某个精确作用域下的全部分组。
     *
     * <p>删除父分组时，服务层会用该方法加载同一作用域的所有分组，然后在内存中找出子孙节点。
     * 这样可以避免递归 SQL，并且保证“删除项目 A 的分组”不会误伤项目 B 的同名分组树。</p>
     */
    @Select("""
            <script>
            SELECT *
            FROM data_sync_task_group
            WHERE tenant_id = #{tenantId}
              AND archived = FALSE
            <choose>
              <when test="projectId == null">
                AND project_id IS NULL
              </when>
              <otherwise>
                AND project_id = #{projectId}
              </otherwise>
            </choose>
            <choose>
              <when test="workspaceId == null">
                AND workspace_id IS NULL
              </when>
              <otherwise>
                AND workspace_id = #{workspaceId}
              </otherwise>
            </choose>
            ORDER BY display_order ASC, group_name ASC, group_code ASC
            </script>
            """)
    List<SyncTaskGroup> selectActiveGroupsInExactScope(@Param("tenantId") Long tenantId,
                                                       @Param("projectId") Long projectId,
                                                       @Param("workspaceId") Long workspaceId);

    /**
     * 按当前操作者可见范围查询分组树原始节点。
     *
     * <p>这里的 PROJECT 范围过滤与任务列表保持一致：
     * 如果 gateway 明确透传了 authorizedProjectIds 且调用方没有指定 projectId，就使用 `project_id IN (...)` 收口；
     * 如果授权项目集合为空，则返回空集合，不能退化为租户全量。</p>
     */
    @Select("""
            <script>
            SELECT *
            FROM data_sync_task_group
            WHERE archived = FALSE
            <if test="tenantId != null">
              AND tenant_id = #{tenantId}
            </if>
            <if test="projectId != null">
              AND project_id = #{projectId}
            </if>
            <if test="workspaceId != null">
              AND workspace_id = #{workspaceId}
            </if>
            <if test="projectScopeEnforced and projectId == null">
              AND project_id IN
              <foreach collection="authorizedProjectIds" item="projectIdItem" open="(" separator="," close=")">
                #{projectIdItem}
              </foreach>
            </if>
            <if test="groupCode != null and groupCode != ''">
              AND group_code = #{groupCode}
            </if>
            ORDER BY tenant_id ASC, project_id ASC, workspace_id ASC, display_order ASC, group_name ASC, group_code ASC
            LIMIT #{limit}
            </script>
            """)
    List<SyncTaskGroup> selectVisibleGroups(@Param("tenantId") Long tenantId,
                                            @Param("projectId") Long projectId,
                                            @Param("workspaceId") Long workspaceId,
                                            @Param("projectScopeEnforced") boolean projectScopeEnforced,
                                            @Param("authorizedProjectIds") List<Long> authorizedProjectIds,
                                            @Param("groupCode") String groupCode,
                                            @Param("limit") int limit);

    /**
     * 归档同一作用域内的一批分组。
     *
     * <p>删除分组采用逻辑归档而非物理删除。原因是分组名称、父子关系和删除时间都可能在审计或事故复盘中有价值。
     * 归档后该分组不再被新任务选择，也不会出现在默认树形菜单中。</p>
     */
    @Update("""
            <script>
            UPDATE data_sync_task_group
            SET archived = TRUE,
                updated_by = #{updatedBy},
                update_time = LOCALTIMESTAMP
            WHERE tenant_id = #{tenantId}
              AND group_code IN
              <foreach collection="groupCodes" item="groupCode" open="(" separator="," close=")">
                #{groupCode}
              </foreach>
            <choose>
              <when test="projectId == null">
                AND project_id IS NULL
              </when>
              <otherwise>
                AND project_id = #{projectId}
              </otherwise>
            </choose>
            <choose>
              <when test="workspaceId == null">
                AND workspace_id IS NULL
              </when>
              <otherwise>
                AND workspace_id = #{workspaceId}
              </otherwise>
            </choose>
            </script>
            """)
    int archiveGroupsInExactScope(@Param("tenantId") Long tenantId,
                                  @Param("projectId") Long projectId,
                                  @Param("workspaceId") Long workspaceId,
                                  @Param("groupCodes") List<String> groupCodes,
                                  @Param("updatedBy") Long updatedBy);
}

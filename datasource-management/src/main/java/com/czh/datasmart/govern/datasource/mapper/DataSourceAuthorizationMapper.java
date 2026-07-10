/**
 * @Author : Cui
 * @Date: 2026/07/09 01:07
 * @Description DataSmart Govern Backend - DataSourceAuthorizationMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasource.entity.DataSourceAuthorization;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 数据源授权 Mapper。
 *
 * <p>当前授权表的查询主要是按 datasourceId 查看授权清单、按 subject 匹配当前用户是否具备实例级访问权。
 * 普通账本 CRUD 继续使用 BaseMapper；主体匹配属于安全关键查询，使用显式 SQL 固定 USER、ROLE、SERVICE_ACCOUNT
 * 三类主体的 OR 关系，避免条件构造器嵌套后在不同数据库方言中产生难以审计的组合语义。</p>
 */
@Mapper
public interface DataSourceAuthorizationMapper extends BaseMapper<DataSourceAuthorization> {

    /**
     * 查询可能授予当前 actor 权限的生效授权候选。
     *
     * <p>SQL 只负责租户、项目、数据源、授权状态和主体身份匹配；动作包含关系与过期时间继续由服务层统一判断。
     * {@code trim prefixOverrides="OR"} 允许调用方只提供 actorId、只提供角色或同时提供多种身份信号，
     * 最终都生成一个边界清晰的括号条件。</p>
     */
    @Select("""
            <script>
            SELECT *
            FROM datasource_authorization
            WHERE status = 'ACTIVE'
            <if test="tenantId != null">
              AND tenant_id = #{tenantId}
            </if>
            <if test="projectId != null">
              AND project_id = #{projectId}
            </if>
            <if test="datasourceId != null">
              AND datasource_id = #{datasourceId}
            </if>
            <trim prefix="AND (" suffix=")" prefixOverrides="OR">
              <if test="actorId != null and actorId != ''">
                OR (subject_type = 'USER' AND subject_id = #{actorId})
              </if>
              <if test="actorRole != null and actorRole != ''">
                OR (subject_type = 'ROLE' AND (subject_id = #{actorRole} OR subject_role = #{actorRole}))
              </if>
              <if test="serviceAccount and actorId != null and actorId != ''">
                OR (subject_type = 'SERVICE_ACCOUNT' AND subject_id = #{actorId})
              </if>
            </trim>
            ORDER BY id DESC
            </script>
            """)
    List<DataSourceAuthorization> selectAuthorizationCandidates(@Param("tenantId") Long tenantId,
                                                                @Param("projectId") Long projectId,
                                                                @Param("datasourceId") Long datasourceId,
                                                                @Param("actorId") String actorId,
                                                                @Param("actorRole") String actorRole,
                                                                @Param("serviceAccount") boolean serviceAccount);
}

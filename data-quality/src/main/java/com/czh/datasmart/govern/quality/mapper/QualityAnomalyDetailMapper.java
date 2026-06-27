/**
 * @Author : Cui
 * @Date: 2026/04/27 21:20
 * @Description DataSmart Govern Backend - QualityAnomalyDetailMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.quality.controller.dto.QualityAnomalyAggregationItem;
import com.czh.datasmart.govern.quality.entity.QualityAnomalyDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 质量异常明细 Mapper。
 *
 * <p>当前使用 MyBatis-Plus 的 BaseMapper 即可满足基础写入与查询需求。
 * 这里单独建立 Mapper 而不是把异常明细塞进报告 Mapper，是为了保持表职责清晰：
 * report 表负责报告摘要，detail 表负责样本级证据。未来如果要做批量导出、分片查询、
 * 按字段聚合统计或冷热分层存储，也可以只扩展这个 Mapper，不影响报告主流程。
 */
@Mapper
public interface QualityAnomalyDetailMapper extends BaseMapper<QualityAnomalyDetail> {

    /**
     * 按指定维度聚合异常明细。
     *
     * <p>这里使用 ${groupColumn} 是为了让 GROUP BY 能作用于不同列。
     * 这类写法如果直接接收前端输入会有 SQL 注入风险，所以调用方必须先通过
     * QualityAnomalyAggregationDimension 枚举把业务维度转换成受控列名。
     *
     * <p>查询条件与异常分页接口保持一致，方便运营人员先看聚合分布，再下钻到具体明细。
     */
    @Select("""
            <script>
            SELECT ${groupColumn} AS aggregate_key,
                   COUNT(1) AS anomaly_count,
                   MAX(create_time) AS latest_create_time
            FROM quality_anomaly_detail
            <where>
                <if test="tenantId != null">
                    AND tenant_id = #{tenantId}
                </if>
                <if test="reportId != null">
                    AND report_id = #{reportId}
                </if>
                <if test="ruleId != null">
                    AND rule_id = #{ruleId}
                </if>
                <if test="anomalyType != null and anomalyType != ''">
                    AND anomaly_type = #{anomalyType}
                </if>
                <if test="fieldName != null and fieldName != ''">
                    AND field_name LIKE CONCAT('%', #{fieldName}, '%')
                </if>
                <if test="severity != null and severity != ''">
                    AND severity = #{severity}
                </if>
                <if test="targetObject != null and targetObject != ''">
                    AND target_object LIKE CONCAT('%', #{targetObject}, '%')
                </if>
                <if test="projectId != null">
                    AND project_id = #{projectId}
                </if>
                <if test="workspaceId != null">
                    AND workspace_id = #{workspaceId}
                </if>
                <if test="projectScopeEnforced">
                    AND project_id IN
                    <foreach collection="authorizedProjectIds" item="authorizedProjectId" open="(" separator="," close=")">
                        #{authorizedProjectId}
                    </foreach>
                </if>
                <if test="startTime != null">
                    AND create_time &gt;= #{startTime}
                </if>
                <if test="endTime != null">
                    AND create_time &lt;= #{endTime}
                </if>
                AND ${groupColumn} IS NOT NULL
                AND ${groupColumn} != ''
            </where>
            GROUP BY ${groupColumn}
            ORDER BY anomaly_count DESC, latest_create_time DESC
            LIMIT #{limit}
            </script>
            """)
    @Results(id = "QualityAnomalyAggregationItemMap", value = {
            @Result(property = "aggregateKey", column = "aggregate_key"),
            @Result(property = "anomalyCount", column = "anomaly_count"),
            @Result(property = "latestCreateTime", column = "latest_create_time")
    })
    List<QualityAnomalyAggregationItem> aggregateAnomalies(
            @Param("groupColumn") String groupColumn,
            @Param("tenantId") Long tenantId,
            @Param("reportId") Long reportId,
            @Param("ruleId") Long ruleId,
            @Param("anomalyType") String anomalyType,
            @Param("fieldName") String fieldName,
            @Param("severity") String severity,
            @Param("targetObject") String targetObject,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("limit") Integer limit,
            @Param("projectId") Long projectId,
            @Param("workspaceId") Long workspaceId,
            @Param("authorizedProjectIds") List<Long> authorizedProjectIds,
            @Param("projectScopeEnforced") boolean projectScopeEnforced);
}

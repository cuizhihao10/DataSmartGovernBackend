/**
 * @Author : Cui
 * @Date: 2026/04/27 21:25
 * @Description DataSmart Govern Backend - QualityCheckExecutionMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.quality.entity.QualityCheckExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 质量检测执行记录 Mapper。
 */
@Mapper
public interface QualityCheckExecutionMapper extends BaseMapper<QualityCheckExecution> {

    /**
     * 查询某条规则的最大执行序号。
     *
     * <p>执行序号用于让运营人员看到“这是第几次检测”，比单纯数据库 ID 更贴近业务语义。
     */
    @Select("""
            SELECT COALESCE(MAX(execution_no), 0)
            FROM quality_check_execution
            WHERE rule_id = #{ruleId}
            """)
    Long selectMaxExecutionNo(@Param("ruleId") Long ruleId);
}

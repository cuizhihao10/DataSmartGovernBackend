/**
 * @Author : Cui
 * @Date: 2026/07/06 21:49
 * @Description DataSmart Govern Backend - SyncObjectExecutionMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasync.entity.SyncObjectExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 对象级同步执行记录 Mapper。
 *
 * <p>普通 CRUD 继续交给 MyBatis-Plus，额外显式声明按父 execution 查询的方法。fan-out 恢复、部分成功报告和
 * 未来对象级重试入口都会先读取同一个父 execution 下的对象执行账本，再决定哪些对象可以跳过、哪些对象需要继续重试。</p>
 */
@Mapper
public interface SyncObjectExecutionMapper extends BaseMapper<SyncObjectExecution> {

    /**
     * 按父 execution 查询对象级执行记录，并按 objectOrdinal 保持用户配置顺序。
     *
     * <p>保持顺序很重要：多表迁移往往隐含主从表、维表、事实表等依赖关系。当前版本仍然串行执行，因此必须稳定遵守
     * objectMappingConfig.mappings 中的顺序，避免恢复后执行顺序漂移。</p>
     *
     * @param executionId 父级 data_sync_execution 主键。
     * @return 父 execution 下所有对象级执行记录。
     */
    @Select("""
            SELECT *
            FROM data_sync_object_execution
            WHERE execution_id = #{executionId}
            ORDER BY object_ordinal ASC, id ASC
            """)
    List<SyncObjectExecution> selectByExecutionId(@Param("executionId") Long executionId);
}

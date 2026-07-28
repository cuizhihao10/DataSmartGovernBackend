/**
 * @Author : Cui
 * @Date: 2026/05/07 21:29
 * @Description DataSmart Govern Backend - SyncTaskDefinitionMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasync.entity.SyncTaskDefinition;
import org.apache.ibatis.annotations.Mapper;

/**
 * 同步任务定义 Mapper。
 *
 * <p>定义主键与 taskId 相同，因此所有读取都从任务入口进入，不提供独立列表和创建能力。</p>
 */
@Mapper
public interface SyncTaskDefinitionMapper extends BaseMapper<SyncTaskDefinition> {
}

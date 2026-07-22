/**
 * @Author : Cui
 * @Date: 2026/07/22 18:35
 * @Description DataSmart Govern Backend - SyncTaskImportArtifactMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasync.entity.SyncTaskImportArtifact;
import org.apache.ibatis.annotations.Mapper;

/** Persists immutable task-import artifact versions in the data_sync schema. */
@Mapper
public interface SyncTaskImportArtifactMapper extends BaseMapper<SyncTaskImportArtifact> {
}

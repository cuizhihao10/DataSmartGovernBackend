package com.czh.datasmart.govern.datasource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasource.entity.SyncAuditRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * @Author : Cui
 * @Date: 2026/4/18 23:12
 * @Description DataSmart Govern Backend - SyncAuditRecordMapper.java
 * @Version:1.0.0
 *
 * 同步审计记录 Mapper。
 */
@Mapper
public interface SyncAuditRecordMapper extends BaseMapper<SyncAuditRecord> {
}

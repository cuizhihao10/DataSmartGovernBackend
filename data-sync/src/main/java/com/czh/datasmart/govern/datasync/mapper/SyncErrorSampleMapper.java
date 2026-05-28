/**
 * @Author : Cui
 * @Date: 2026/05/07 21:39
 * @Description DataSmart Govern Backend - SyncErrorSampleMapper.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czh.datasmart.govern.datasync.entity.SyncErrorSample;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 同步错误样本 Mapper。
 *
 * <p>错误样本常包含脱敏后的源端记录定位、目标端定位和失败摘要，排障价值很高，
 * 但它也会随着批量同步失败快速增长，因此需要保留期清理能力。
 */
@Mapper
public interface SyncErrorSampleMapper extends BaseMapper<SyncErrorSample> {

    /**
     * 删除超过保留期的历史错误样本。
     *
     * <p>这里以 `create_time` 为保留期依据，因为错误样本代表“平台发现并记录错误的时间”，
     * 对运营排障、报表统计和保留期合规都更直观。
     *
     * @param expireBefore 创建时间早于该值的错误样本可以清理
     * @param limit 单轮删除上限
     * @return 本轮删除数量
     */
    @Delete("""
            DELETE FROM data_sync_error_sample
            WHERE create_time < #{expireBefore}
            ORDER BY create_time ASC
            LIMIT #{limit}
            """)
    int deleteExpiredErrorSamples(@Param("expireBefore") LocalDateTime expireBefore, @Param("limit") int limit);
}

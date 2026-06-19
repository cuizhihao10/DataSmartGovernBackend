/**
 * @Author : Cui
 * @Date: 2026/06/20 03:22
 * @Description DataSmart Govern Backend - SyncBatchReadResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 批处理读取结果摘要。
 *
 * <p>该结果只表达读取统计和控制信号，不表达真实行数据。
 * 真实行数据属于高敏业务内容，只能在 reader 到 writer 的受控内部管道中短暂存在。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncBatchReadResult {

    /**
     * 本批读取记录数。
     */
    private Long recordsRead;

    /**
     * 是否已经读到源端结尾。
     */
    private Boolean endOfSource;

    /**
     * 是否建议本批后保存 checkpoint。
     */
    private Boolean checkpointRecommended;

    /**
     * 低敏错误摘要。
     */
    private String errorSummary;
}

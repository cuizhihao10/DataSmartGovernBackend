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
 * <p>该对象仍属于 worker 内部执行层，不是普通 API DTO。
 * `recordsRead`、`endOfSource`、`checkpointRecommended` 和 `errorSummary` 是低敏摘要，
 * 但 `recordBatch` 可能包含真实业务行数据，只能在 reader 到 writer 的受控内部管道中短暂存在。</p>
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

    /**
     * 本批读取出的真实记录。
     * 该字段是高敏内部载体，不能进入控制台响应、runtime event、审计投影或普通日志。
     */
    private SyncBatchRecordBatch recordBatch;
}

/**
 * @Author : Cui
 * @Date: 2026/07/07 23:40
 * @Description DataSmart Govern Backend - SyncDirtyRecordSample.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 结构化脏数据样本。
 *
 * <p>脏数据不是普通报错日志，而是“某一行数据因为字段转换、约束冲突、主键重复、日期格式、枚举值等原因
 * 未能写入目标端”的结构化诊断事实。它需要进入 data-sync 的错误样本表，供后续查询、人工修复、导出和重放。</p>
 *
 * <p>安全边界：</p>
 * <p>1. 这里不保存完整原始行，只保存低敏 key、错误分类、摘要和哈希/字段名摘要；</p>
 * <p>2. samplePayload 只能是脱敏或摘要后的 JSON 字符串，不能包含密码、token、连接串或完整业务大字段；</p>
 * <p>3. 真正落库由 data-sync 控制面完成，datasource-management 只作为执行面报告样本。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncDirtyRecordSample {

    /** 错误类型，例如 DUPLICATE_KEY、NOT_NULL_VIOLATION、TYPE_CONVERSION_ERROR、TARGET_WRITE_ERROR。 */
    private String errorType;

    /** 数据库错误码、SQLState 或内部标准码。 */
    private String errorCode;

    /** 低敏错误摘要。 */
    private String errorMessage;

    /** 源端记录定位，优先取主键字段摘要；没有主键时使用行号或哈希摘要。 */
    private String sourceRecordKey;

    /** 目标端记录定位，通常是目标主键或约束名摘要。 */
    private String targetRecordKey;

    /** 脱敏样本载荷，通常包含字段名集合、行摘要、约束名，不包含完整原始行。 */
    private String samplePayload;

    /** 当前脏数据是否适合修复后重放。 */
    private Boolean retryable;
}

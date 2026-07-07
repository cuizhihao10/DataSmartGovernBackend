/**
 * @Author : Cui
 * @Date: 2026/07/07 19:25
 * @Description DataSmart Govern Backend - SyncTaskImportResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 同步任务导入结果。
 *
 * <p>导入接口采用“先全量校验、再统一写入”的策略。只要存在冲突或校验失败，默认不会创建任何任务。
 * 因此返回对象必须同时表达总行数、冲突行、失败行、创建数量和每行诊断，方便前端提示用户修改文件后重试。</p>
 */
@Data
public class SyncTaskImportResult {

    /**
     * 是否 dry-run。
     */
    private Boolean dryRun;

    /**
     * 是否要求导入后立即执行一次。
     */
    private Boolean runImmediately;

    /**
     * 解析出的数据行数，不含表头。
     */
    private Integer totalRows = 0;

    /**
     * 校验通过行数。
     */
    private Integer validRows = 0;

    /**
     * 成功创建任务数。
     */
    private Integer createdCount = 0;

    /**
     * 导入后保持 DRAFT 的任务数。
     */
    private Integer draftCount = 0;

    /**
     * 导入后立即进入 QUEUED 的任务数。
     */
    private Integer queuedCount = 0;

    /**
     * 唯一键冲突数量。
     */
    private Integer conflictCount = 0;

    /**
     * 校验失败数量。
     */
    private Integer failedCount = 0;

    /**
     * 总体状态。
     *
     * <p>常见值：VALIDATED、IMPORTED、BLOCKED_BY_CONFLICT、BLOCKED_BY_VALIDATION。</p>
     */
    private String status;

    /**
     * 总体低敏说明。
     */
    private String message;

    /**
     * 行级结果。
     */
    private List<SyncTaskImportRowResult> rows = new ArrayList<>();
}

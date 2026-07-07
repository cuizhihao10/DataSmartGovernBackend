/**
 * @Author : Cui
 * @Date: 2026/07/07 19:24
 * @Description DataSmart Govern Backend - SyncTaskImportRowResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 同步任务导入单行结果。
 *
 * <p>批量导入一定要把“哪一行为什么失败”说清楚，否则用户只能反复猜文件哪里有问题。
 * 该 DTO 只返回低敏诊断，例如模板 ID、任务名、冲突原因、目标状态，不返回模板配置正文或执行器内部计划。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncTaskImportRowResult {

    /**
     * 文件中的行号。
     *
     * <p>CSV/Excel 第 1 行是表头，因此第一条数据通常从第 2 行开始。</p>
     */
    private Integer rowNumber;

    /**
     * 导入成功后创建的新任务 ID。
     *
     * <p>dry-run、冲突、校验失败时为空。</p>
     */
    private Long taskId;

    /**
     * 文件中声明的任务名称。
     */
    private String name;

    /**
     * 行级状态。
     *
     * <p>常见值：VALIDATED、CREATED_DRAFT、QUEUED、CONFLICT、FAILED。</p>
     */
    private String status;

    /**
     * 任务最终主状态。
     *
     * <p>例如 DRAFT、QUEUED；失败行为空或保留原诊断状态。</p>
     */
    private String currentState;

    /**
     * 低敏说明。
     */
    private String message;
}

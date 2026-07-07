/**
 * @Author : Cui
 * @Date: 2026/07/07 20:00
 * @Description DataSmart Govern Backend - SyncTaskBatchItemResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 同步任务批量操作单条结果。
 *
 * <p>批量接口不能只返回一个全局成功或失败，否则用户无法知道 200 个任务中到底哪几个已经处理、哪几个因为状态不允许失败。
 * 因此每个 taskId 都会返回一条结构化结果，前端可以直接渲染为批量操作明细，Agent 也可以据此生成下一步修复建议。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncTaskBatchItemResult {

    /**
     * 来源任务 ID。
     *
     * <p>对于下线、回收、彻底删除、手工调度，它就是被操作的任务 ID。
     * 如果未来扩展批量克隆，resultTaskId 可表示新生成的任务 ID。</p>
     */
    private Long taskId;

    /**
     * 动作产物任务 ID。
     *
     * <p>当前批量下线/删除类动作通常与 taskId 相同；保留该字段是为了后续批量克隆、批量导入派生任务、
     * 批量按模板生成任务时，不需要再修改响应结构。</p>
     */
    private Long resultTaskId;

    /**
     * 本条是否成功。
     *
     * <p>true 表示该任务动作已经完成；false 表示失败或被跳过，需要查看 code/message。</p>
     */
    private Boolean success;

    /**
     * 结果码。
     *
     * <p>成功时通常为 SUCCESS；业务失败时使用平台错误码名称，例如 VALIDATION_ERROR、TENANT_SCOPE_DENIED；
     * 被 fail-fast 策略跳过时为 SKIPPED_AFTER_FAILURE。</p>
     */
    private String code;

    /**
     * 动作完成后的任务状态。
     *
     * <p>例如 OFFLINE、RECYCLED、DELETED、QUEUED。失败或跳过时可能为空。</p>
     */
    private String state;

    /**
     * 面向用户、运营人员和 Agent 的低敏说明。
     *
     * <p>该字段只应包含状态、任务 ID、失败原因摘要，不应包含连接串、密码、完整 SQL 或客户样本数据。</p>
     */
    private String message;

    public static SyncTaskBatchItemResult success(Long taskId, Long resultTaskId, String state, String message) {
        return new SyncTaskBatchItemResult(taskId, resultTaskId, Boolean.TRUE, "SUCCESS", state, message);
    }

    public static SyncTaskBatchItemResult failure(Long taskId, String code, String message) {
        return new SyncTaskBatchItemResult(taskId, null, Boolean.FALSE, code, null, message);
    }

    public static SyncTaskBatchItemResult skipped(Long taskId, String message) {
        return new SyncTaskBatchItemResult(taskId, null, Boolean.FALSE, "SKIPPED_AFTER_FAILURE", null, message);
    }
}

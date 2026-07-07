/**
 * @Author : Cui
 * @Date: 2026/07/07 20:00
 * @Description DataSmart Govern Backend - SyncTaskBatchOperationRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 同步任务批量控制面操作请求。
 *
 * <p>该请求用于批量下线、批量移入回收站、批量彻底删除、批量手工调度等“控制面动作”。
 * 它不会携带源端连接、目标端连接、SQL 或字段映射正文，因为这些动作只改变任务定义或调度意图，
 * 真实数据搬运仍由 worker loop、execution 租约和回调协议完成。</p>
 */
@Data
public class SyncTaskBatchOperationRequest {

    /**
     * 本次批量操作的任务 ID 列表。
     *
     * <p>单次最多 200 个，是为了避免一个 HTTP 请求占用过长事务和连接时间。
     * 更大规模的“批量停服、批量下线、批量迁移”后续应升级为后台治理作业，
     * 由 task-management 生成可暂停、可恢复、可审计的长任务。</p>
     */
    @NotEmpty(message = "批量操作任务 ID 列表不能为空")
    @Size(max = 200, message = "单次批量操作最多支持 200 个同步任务")
    private List<@NotNull(message = "批量操作任务 ID 不能包含空值") Long> taskIds = new ArrayList<>();

    /**
     * 操作原因。
     *
     * <p>原因会进入批量审计摘要，也会传递给单任务生命周期动作。
     * 请只填写低敏说明，例如“项目迁移完成后下线旧任务”“客户确认删除测试任务”，不要写密码、token、完整 SQL、样本数据或 prompt。</p>
     */
    @Size(max = 500, message = "批量操作原因不能超过 500 个字符")
    private String reason;

    /**
     * 是否在单条失败后继续处理后续任务。
     *
     * <p>默认 true，原因是批量运营通常希望“能处理的先处理”，并在结果明细里看到失败项。
     * 如果调用方设置为 false，则遇到第一条失败后会停止，剩余任务标记为 SKIPPED，适合高风险批量彻底删除等保守场景。</p>
     */
    private Boolean continueOnError = Boolean.TRUE;

    public boolean shouldContinueOnError() {
        return !Boolean.FALSE.equals(continueOnError);
    }
}

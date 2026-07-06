/**
 * @Author : Cui
 * @Date: 2026/07/07 23:58
 * @Description DataSmart Govern Backend - SyncDirtyRecordReplayRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import lombok.Data;

import java.util.List;

/**
 * 脏数据修复重放请求。
 *
 * <p>该请求服务于 DataX-style dirty record threshold 之后的“治理闭环”：
 * 同步执行过程中少量坏数据不会立刻拖垮整批任务，而是结构化落入 {@code data_sync_error_sample}；
 * 运营人员、项目负责人或租户管理员完成字段映射、目标表约束、重复主键、枚举值或日期格式等问题修复后，
 * 可以通过本请求把选中的错误样本派生成一次新的 replay execution。</p>
 *
 * <p>为什么这里不直接接收修复后的行数据：
 * 1. API 层保存原始业务行会扩大敏感数据暴露面，也会绕过源端权限与目标端写入策略；
 * 2. 当前平台的同步执行应继续由 worker/connector 按模板、checkpoint、字段映射和幂等策略完成；
 * 3. 控制面只保存“哪些错误样本需要被重放”的低敏 selector，由 worker 后续受控读取。</p>
 */
@Data
public class SyncDirtyRecordReplayRequest {

    /**
     * 脏数据样本所属的来源 execution。
     *
     * <p>该字段必填，因为错误样本必须锚定到某一次历史运行。
     * 即使调用方传了 errorSampleIds，服务端也会要求它们全部属于该 execution，防止跨执行记录混合重放。</p>
     */
    private Long executionId;

    /**
     * 精确选择的错误样本 ID。
     *
     * <p>适用于用户在错误样本列表中勾选若干条坏数据进行修复重放。
     * 如果为空，则必须显式把 {@link #replayAllRetryableInExecution} 设为 true，表示重放该 execution 下全部可重试脏样本。</p>
     */
    private List<Long> errorSampleIds;

    /**
     * 是否重放当前 execution 下全部 retryable=true 的错误样本。
     *
     * <p>该字段必须显式为 true 才会启用“全选可重试样本”模式，避免调用方漏传 errorSampleIds 时误触发大范围重放。</p>
     */
    private Boolean replayAllRetryableInExecution;

    /**
     * 操作者是否确认已经完成必要修复。
     *
     * <p>脏数据重放不是普通 retry。对于字段映射错误、类型转换失败、目标端唯一键冲突等问题，
     * 如果不先修复配置或目标端数据，直接重放只会重复失败。因此服务端默认要求调用方显式确认修复已完成。</p>
     */
    private Boolean repairConfirmed;

    /**
     * 修复策略摘要。
     *
     * <p>建议使用低敏枚举风格字符串，例如 {@code MANUAL_FIXED_AND_REPLAY}、
     * {@code TARGET_DEDUP_REPLAY}、{@code MAPPING_FIXED_REPLAY}。禁止写入 SQL、样本行、连接串或凭据。</p>
     */
    private String repairStrategy;

    /**
     * 全选模式下最多允许纳入的错误样本数量。
     *
     * <p>该值用于保护控制面和后续 worker，避免一次“重放全部可重试样本”生成过大的 selector。
     * 服务端还会设置硬上限，调用方不能通过传极大值绕过保护。</p>
     */
    private Integer maxSampleCount;

    /**
     * 修复重放原因。
     *
     * <p>原因会进入审计摘要和恢复计划 reason。它应该解释“为什么现在可以重放”，
     * 而不是粘贴 SQL、异常堆栈、业务样本、prompt 或工具原始参数。</p>
     */
    private String reason;
}

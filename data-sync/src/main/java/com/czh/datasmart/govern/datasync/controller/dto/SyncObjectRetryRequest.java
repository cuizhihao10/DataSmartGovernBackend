/**
 * @Author : Cui
 * @Date: 2026/07/06 23:35
 * @Description DataSmart Govern Backend - SyncObjectRetryRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import lombok.Data;

import java.util.List;

/**
 * 失败对象选择性重试请求。
 *
 * <p>该请求解决的是 OBJECT_LIST 父 execution 进入 PARTIALLY_SUCCEEDED 或 FAILED 后的恢复问题。
 * 传统“任务级 retry”通常会新建一条 execution，适合整单重跑；而 DataX-style 对象级恢复更希望做到：</p>
 * <p>1. 已经成功的对象不再重跑，避免 APPEND 场景重复写入；</p>
 * <p>2. 失败对象可以按对象 ID 或 ordinal 精确选择；</p>
 * <p>3. 如果不传选择范围，则默认重试当前父 execution 下全部 FAILED 对象，满足运维“一键重传失败项”的常见操作；</p>
 * <p>4. 重试动作只重置控制面账本和父 execution 队列状态，真实数据搬运仍由 worker 重新认领后执行。</p>
 *
 * <p>安全说明：reason 会进入审计摘要，因此 Service 层会做低敏清洗，避免把 SQL、密码、token、
 * 样本数据或连接串写入审计表。</p>
 */
@Data
public class SyncObjectRetryRequest {

    /**
     * 可选：按对象级账本主键精确选择要重试的失败对象。
     */
    private List<Long> objectExecutionIds;

    /**
     * 可选：按 objectMappingConfig.mappings 中的顺序号选择要重试的失败对象。
     */
    private List<Integer> objectOrdinals;

    /**
     * 可选：本次重试希望给对象保留的最大尝试次数。取值会被限制在 1 到 10 之间。
     */
    private Integer retryAttemptBudget;

    /**
     * 可选：是否把失败对象的 attemptCount 重置为 0。默认 true。
     */
    private Boolean resetAttemptCount;

    /**
     * 可选：人工重试原因。该字段用于审计，不参与真实数据同步配置。
     */
    private String reason;
}

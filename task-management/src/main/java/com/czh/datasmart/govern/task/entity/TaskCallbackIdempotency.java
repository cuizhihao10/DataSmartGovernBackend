package com.czh.datasmart.govern.task.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @Author : Cui
 * @Date: 2026/05/07 21:10
 * @Description DataSmart Govern Backend - TaskCallbackIdempotency.java
 * @Version:1.0.0
 *
 * 任务执行器回调幂等记录实体。
 *
 * <p>在真实生产环境里，执行器回调经常会遇到“服务端已经处理成功，但 HTTP 响应丢失或调用方超时”的情况。
 * 如果执行器再次使用同一个请求重试，而服务端没有幂等保护，就可能重复完成任务、重复失败任务、重复 defer 回队列，
 * 最终造成任务状态错乱、执行日志重复、告警误报甚至下游数据重复写入。</p>
 *
 * <p>因此本表把回调幂等从“日志里模糊查一段文本”的临时方案，升级为独立的业务事实表：
 * taskId + action + idempotencyKey 形成唯一约束，用数据库唯一索引承担并发竞争下的最终裁决。
 * 这比应用内先查再写更可靠，因为多个 worker 或多个服务实例同时处理同一个重试请求时，
 * 唯一索引可以保证只有一个事务能插入成功，其他事务会被识别为重复回调。</p>
 */
@Data
@TableName("task_callback_idempotency")
public class TaskCallbackIdempotency {

    /**
     * 幂等记录主键。
     *
     * <p>主键仅用于内部更新和排查；真正的业务唯一性由 taskId、action、idempotencyKey 联合决定。</p>
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关联任务 ID。
     *
     * <p>幂等保护必须绑定具体任务，避免不同任务恰好传入相同 idempotencyKey 时互相误判。</p>
     */
    private Long taskId;

    /**
     * 回调动作编码。
     *
     * <p>例如 PROGRESS、COMPLETE、FAIL、DEFER。动作也参与唯一键，是因为同一次 run 可能先上报进度，
     * 后续再完成任务，这两个动作即使使用相似的调用链信息，也应该作为不同业务动作分别处理。</p>
     */
    private String action;

    /**
     * 调用方生成的幂等键。
     *
     * <p>推荐由调用方按“来源服务 + taskId + runId + action”稳定生成。
     * 同一次业务动作重试必须复用同一个键；新的执行 run 或新的动作必须换新键。</p>
     */
    private String idempotencyKey;

    /**
     * 本次回调所属的执行 run ID。
     *
     * <p>runId 用于区分同一个任务的多次执行尝试。任务失败后重试会生成新的 run，
     * 旧 run 的迟到回调不能污染新 run，因此生命周期逻辑会同时校验当前任务快照里的 currentExecutionRunId。</p>
     */
    private Long runId;

    /**
     * 发起回调的执行器实例 ID。
     *
     * <p>executorId 用于审计和安全校验。服务端会要求它与当前任务租约中的 currentExecutorId 一致，
     * 避免过期 worker、错误 worker 或伪造请求回写任务结果。</p>
     */
    private String executorId;

    /**
     * 请求摘要。
     *
     * <p>这里不保存完整请求体，是为了避免把大结果、异常堆栈或敏感业务参数长期落库。
     * 摘要主要服务于排障：当重复请求出现时，运营人员可以判断它是否与首次请求语义一致。</p>
     */
    private String requestDigest;

    /**
     * 回调处理状态。
     *
     * <p>当前取值包括 PROCESSING、SUCCEEDED、FAILED。
     * PROCESSING 表示当前事务已经占住幂等键但尚未完成业务状态变更；
     * SUCCEEDED 表示业务动作已经成功落地；
     * FAILED 预留给未来 REQUIRES_NEW 事务或异常审计能力，用于记录失败尝试。</p>
     */
    private String callbackState;

    /**
     * 成功响应摘要。
     *
     * <p>当重复回调到来时，服务端通常可以直接返回成功，不必再次修改任务状态。
     * 这里保存一个简短摘要，便于后续扩展成“返回首次处理结果”的标准幂等响应模型。</p>
     */
    private String responseSummary;

    /**
     * 失败信息摘要。
     *
     * <p>当前事务内异常会导致插入一起回滚，因此该字段主要为后续独立事务记录失败尝试预留。
     * 预留字段能避免未来补充失败审计时再修改表结构。</p>
     */
    private String errorMessage;

    /**
     * 首次看到该幂等键的时间。
     *
     * <p>用于定位首次请求发生时间，也可用于后续清理过期幂等记录的保留策略。</p>
     */
    private LocalDateTime firstSeenTime;

    /**
     * 最近一次看到该幂等键的时间。
     *
     * <p>重复请求不会再次推进任务状态，但会刷新该字段，帮助判断调用方是否正在持续重试。</p>
     */
    private LocalDateTime lastSeenTime;

    /**
     * 记录创建时间。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 记录更新时间。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}

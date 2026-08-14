/**
 * @Author : Cui
 * @Date: 2026/08/11 00:05
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryAction.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.support;

/**
 * data-sync 已知的恢复动作白名单。
 *
 * <p>枚举中的布尔值是平台安全上限，与任务自己的 allowedActions 策略相互独立。
 * 任务可以继续收紧动作集合，但不能仅通过修改 JSON 就把 replay 或 backfill 提升为无人值守动作。</p>
 */
public enum SyncAutopilotRecoveryAction {

    /** 在不改变配置的前提下重跑已有 execution。 */
    RETRY_EXECUTION(true),
    /**
     * 隔离一组已由摘要绑定的可重试脏数据，不删除或修改源数据。
     *
     * <p>只有专用 Autopilot 接口重新验证持久 preview、精确 {@code PRIMARY_KEY_EQ} 选择器、
     * 任务授权、case 预算和持久回执时，该动作才属于低风险。普通浏览器隔离接口仍要求显式确认。</p>
     */
    APPLY_QUARANTINE(true),
    /** 重新连接外部数据源可能改变外部系统状态，必须审批。 */
    RECONNECT_DATASOURCE(false),
    /** 在不修改源端/目标端配置的前提下从持久 checkpoint 恢复。 */
    RESUME_FROM_CHECKPOINT(true),
    /** 只在既有任务范围内重放之前失败的分片。 */
    REPLAY_FAILED_SHARDS(true),
    /** 刷新元数据并重新预检；发现结果只能影响当前已有任务范围。 */
    REFRESH_METADATA(true),
    /** 回滚到最近一次成功 execution 的低敏运行策略快照，不恢复 SQL、凭据或数据范围。 */
    ROLLBACK_EXECUTION_POLICY(true),
    /** 只允许降低 channel/批量或在硬上限内提高 timeout 的 execution 运行策略调整。 */
    TUNE_EXECUTION_POLICY(true),
    /** 只应用元数据能够唯一证明且不会扩大同步范围的字段映射修复。 */
    REPAIR_FIELD_MAPPING(true),
    /** 修改 schema 合同，永远不能无人值守执行。 */
    CHANGE_SCHEMA(false),
    /** 修改连接凭据，本恢复控制面既不携带也不执行。 */
    CHANGE_CREDENTIAL(false),
    /** 删除数据，始终要求人工控制流程。 */
    DELETE_DATA(false),
    /** 覆盖目标数据，始终要求人工控制流程。 */
    OVERWRITE_TARGET(false),
    /** 扩大源端或目标端数据范围，始终要求人工控制流程。 */
    EXPAND_DATA_SCOPE(false),

    /** 兼容旧合同的失败对象重试别名，不能绕过新动作目录。 */
    RETRY_FAILED_OBJECTS(false),
    /** 兼容旧 API 的 checkpoint 重放别名，在平台上限中仍要求审批。 */
    REPLAY_FROM_CHECKPOINT(false),
    /** 补数会改变数据量或时间范围，因此始终要求审批。 */
    BACKFILL_WINDOW(false);

    private final boolean automaticLowRiskWhitelisted;

    SyncAutopilotRecoveryAction(boolean automaticLowRiskWhitelisted) {
        this.automaticLowRiskWhitelisted = automaticLowRiskWhitelisted;
    }

    /**
     * 返回该动作是否位于平台级无人值守低风险上限内。
     *
     * <p>该标志独立于任务策略列表：任务可以继续限制动作，但不能通过编辑 JSON 把 {@code false}
     * 变成自动执行。此只读判断无副作用且幂等，只是范围、可信度、证据、风险、轮次、截止时间和
     * 持久授权等多重门禁中的一项。</p>
     *
     * @return 仅当动作位于平台无人值守低风险白名单时返回 {@code true}
     */
    public boolean isAutomaticLowRiskWhitelisted() {
        return automaticLowRiskWhitelisted;
    }
}

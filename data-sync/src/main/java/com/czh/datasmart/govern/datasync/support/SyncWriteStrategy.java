/**
 * @Author : Cui
 * @Date: 2026/06/29 22:18
 * @Description DataSmart Govern Backend - SyncWriteStrategy.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.support;

import java.util.Arrays;

/**
 * data-sync 写入策略枚举。
 *
 * <p>写入策略回答的是“目标端如何接纳源端同步过来的记录”。它不是单纯的前端下拉选项，而是后续
 * batch runner、冲突处理、重试、回放、补数和审计解释都会依赖的执行语义。</p>
 *
 * <p>为什么在 data-sync 模块内独立定义，而不是直接复用 datasource-management 的同名枚举：</p>
 * <p>1. data-sync 是同步任务控制面，datasource-management 是数据源与连接器运行时，两者应通过显式契约交互，
 * 不应为了一个枚举把模块边界耦合起来；</p>
 * <p>2. 当前两边枚举值保持一致，后续如果 datasource-management 内部 runner 需要新增某个数据库私有策略，
 * data-sync 也可以先通过兼容性矩阵决定是否对外开放，而不是被内部实现牵着走；</p>
 * <p>3. 该枚举只包含低敏执行语义，不包含 SQL、字段映射正文、样本数据、连接地址或凭据。</p>
 */
public enum SyncWriteStrategy {

    /**
     * 追加写入。
     *
     * <p>APPEND 不主动处理冲突，适合日志、流水、离线导出导入等天然可追加场景。它的风险是重试或回放时可能产生重复数据，
     * 因此高价值业务表通常需要搭配下游去重、幂等键或更严格的写入策略。</p>
     */
    APPEND(false, false),

    /**
     * 冲突时更新，不冲突时插入。
     *
     * <p>UPSERT 适合主数据、维表、用户画像等“同一业务主键只保留一份最新记录”的场景。它必须依赖目标端主键或唯一键，
     * 否则 runner 无法判断哪一行算冲突。</p>
     */
    UPSERT(true, false),

    /**
     * 冲突时忽略。
     *
     * <p>INSERT_IGNORE 适合“先到先得”或历史事实不可覆盖的场景，例如已经入仓的订单快照不希望被后续重复消息覆盖。
     * 它同样需要目标端具备可判断冲突的唯一约束。</p>
     */
    INSERT_IGNORE(true, false),

    /**
     * 冲突时替换。
     *
     * <p>REPLACE 通常比 UPSERT 更激进，可能表现为删除旧行再插入新行。不同数据库语义不完全一致，
     * 因此后续接入真实 runner 时应在 connector capability 中再次确认支持程度。</p>
     */
    REPLACE(true, false),

    /**
     * 覆盖式写入。
     *
     * <p>OVERWRITE 通常意味着清空或替换目标范围，破坏性最强。当前枚举先保留该能力，方便产品设计支持离线重建、
     * 全量重刷和紧急修复，但真正执行前应接入审批、权限、备份和影响范围预估。</p>
     */
    OVERWRITE(false, true);

    /**
     * 当前策略是否要求目标端具备冲突判断键。
     *
     * <p>如果该值为 true，模板必须声明 primaryKeyField。否则即使源端和目标端连接器兼容，也不应该进入真实执行，
     * 因为写入器无法保证幂等、去重或冲突处理。</p>
     */
    private final boolean requiresConflictKey;

    /**
     * 当前策略是否具有明显覆盖/破坏风险。
     *
     * <p>该字段用于预览、审批和 workerPlan 风险提示。它不代表禁止使用，而是提醒平台在进入执行前补足权限、
     * 审批、审计和恢复方案。</p>
     */
    private final boolean destructiveRewrite;

    SyncWriteStrategy(boolean requiresConflictKey, boolean destructiveRewrite) {
        this.requiresConflictKey = requiresConflictKey;
        this.destructiveRewrite = destructiveRewrite;
    }

    public boolean requiresConflictKey() {
        return requiresConflictKey;
    }

    public boolean isDestructiveRewrite() {
        return destructiveRewrite;
    }

    /**
     * 将外部输入归一化为平台内部写入策略。
     *
     * <p>空值默认回落到 APPEND，是为了兼容历史模板和最小迁移成本；但预览和 workerPlan 会对默认策略做风险提示，
     * 防止用户误以为平台已经配置了幂等写入。</p>
     *
     * @param value 请求体、数据库或旧模板中的策略字符串。
     * @return 归一化后的写入策略。
     */
    public static SyncWriteStrategy fromValue(String value) {
        if (value == null || value.isBlank()) {
            return APPEND;
        }
        return Arrays.stream(values())
                .filter(item -> item.name().equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的同步写入策略: " + value));
    }
}

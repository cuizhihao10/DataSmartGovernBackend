/**
 * @Author : Cui
 * @Date: 2026/05/07 21:26
 * @Description DataSmart Govern Backend - SyncMode.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.support;

/**
 * 数据同步传输模式。
 *
 * <p>这里需要特别区分“用户新建任务时选择的传输模式”和“平台内部运行期能力”：</p>
 * <p>1. 用户可选传输模式只保留 5 个：全量、定期全量、定期批量、SQL 语句自定义、实时；</p>
 * <p>2. 失败回放、历史补数、离线导入、离线导出等不是一级传输模式，而是运行期恢复、补救或制品流程能力；</p>
 * <p>3. 历史枚举暂时保留为非用户可选，是为了兼容已有代码、审计记录、测试和迁移数据，避免一次性硬删造成执行链路断裂。</p>
 *
 * <p>商业产品里如果把“恢复动作”和“任务传输模式”混在同一个下拉框里，用户会很难理解：
 * 新建任务到底是在配置正常同步，还是在配置事故恢复。因此本枚举通过 {@link #isUserSelectableTransferMode()}
 * 明确给前端、Agent 和运维台一个稳定边界。</p>
 */
public enum SyncMode {

    /**
     * 全量同步。
     *
     * <p>每次运行读取当前任务范围内的完整源端数据，并写入目标端。它适合首次初始化、小表周期性人工刷新、
     * 或者需要重新构建目标表数据的场景。是否覆盖、追加或幂等写入由 writeStrategy 决定。</p>
     */
    FULL(true, "全量", "一次性读取本次任务范围内的完整源端数据，并写入目标端。"),

    /**
     * 定期全量。
     *
     * <p>这是从产品视角独立出来的一等传输模式。早期实现用 {@code FULL + scheduleConfig} 表达定期全量，
     * 虽然方便后端复用，但对用户和前端并不直观。现在用户可以直接选择 SCHEDULED_FULL，
     * 后端仍复用全量 Reader/Writer，只是在任务层要求配置 scheduleConfig。</p>
     */
    SCHEDULED_FULL(true, "定期全量", "按 cron 或固定频率重复执行全量同步；每次触发仍是一轮完整范围扫描。"),

    /**
     * 定期批量。
     *
     * <p>按计划周期同步一个有界批处理窗口，例如最近 1 小时、某个日期分区或某个业务批次。
     * 它和定期全量的区别是：定期全量每次扫描完整范围；定期批量每次只处理一个被配置约束住的窗口。</p>
     */
    SCHEDULED_BATCH(true, "定期批量", "按计划周期同步一个有界批处理窗口，例如最近 1 小时或某个分区范围。"),

    /**
     * SQL 语句自定义传输。
     *
     * <p>以受控只读 SQL 查询结果作为 Reader 数据集。该模式必须继续保留审批、只读 SQL 校验、
     * SQL 正文低敏保护、超时限制和目标字段映射校验，不能让前端、Agent 或 worker 任意拼接 SQL。</p>
     */
    CUSTOM_SQL_QUERY(true, "SQL 语句自定义", "以受控只读 SQL 查询结果作为 Reader 数据集，审批通过后写入目标端。"),

    /**
     * 实时同步。
     *
     * <p>面向 binlog、WAL、change stream 或 Kafka topic 等持续事件流。它通常不应进入离线 DataX-style runner，
     * 而应走 Debezium/Kafka Connect 风格的 CDC pipeline。</p>
     */
    CDC_STREAMING(true, "实时", "持续捕获 binlog/WAL/change stream 等变更事件，并通过实时链路写入目标端。"),

    /**
     * 按时间增量。
     *
     * <p>历史预留能力：未来可以作为“定期批量”的窗口策略，例如 update_time > last_checkpoint，
     * 但不再作为用户新建任务时的一级传输模式展示。</p>
     */
    INCREMENTAL_TIME(false, "按时间增量", "历史预留能力：后续应作为定期批量的窗口策略或高级配置，不作为新建任务一级模式。"),

    /**
     * 按主键增量。
     *
     * <p>历史预留能力：未来可以作为“定期批量”的窗口策略或分片策略，例如 id > last_id 或 ID range，
     * 但不再作为用户新建任务时的一级传输模式展示。</p>
     */
    INCREMENTAL_ID(false, "按主键增量", "历史预留能力：后续应作为定期批量的窗口策略或分片策略，不作为新建任务一级模式。"),

    /**
     * 一次性迁移。
     *
     * <p>历史预留能力：当前产品主流程统一用 FULL + 同步范围 + 写入策略表达一次性迁移诉求，
     * 因此它不再出现在任务传输模式下拉框中。</p>
     */
    ONE_TIME_MIGRATION(false, "一次性迁移", "历史预留能力：当前产品主流程统一用 FULL + 范围/写入策略表达，不作为一级任务模式。"),

    /**
     * 失败回放。
     *
     * <p>运行期恢复动作：应该通过回放接口、错误样本修复入口或恢复计划触发，
     * 不应该要求用户在新建同步任务时选择“失败回放”作为传输模式。</p>
     */
    REPLAY(false, "失败回放", "运行期恢复动作：通过回放接口、错误样本修复入口或恢复计划触发，不在新建任务模式中展示。"),

    /**
     * 历史补数。
     *
     * <p>运行期补救动作：用于历史缺口修复、晚到数据补齐、指定范围重刷。
     * 它应是已有任务上的补数动作，而不是新建任务时的一种主传输模式。</p>
     */
    BACKFILL(false, "历史补数", "运行期补救动作：用于历史缺口修复或指定范围重刷，不在新建任务模式中展示。"),

    /**
     * 离线导入。
     *
     * <p>制品型能力：例如文件、对象存储包、外部导入制品进入目标表。它更适合独立导入流程，
     * 不应该出现在数据库同步任务的主模式选择中。</p>
     */
    OFFLINE_IMPORT(false, "离线导入", "制品型能力：后续应放在文件/对象导入流程中，而不是放进数据库同步任务模式下拉框。"),

    /**
     * 离线导出。
     *
     * <p>制品型能力：例如表或查询结果导出为文件、对象存储制品。它更适合独立导出流程，
     * 不应该出现在同步任务的主模式选择中。</p>
     */
    OFFLINE_EXPORT(false, "离线导出", "制品型能力：后续应放在导出任务或结果制品流程中，而不是放进同步任务模式下拉框。");

    private final boolean userSelectableTransferMode;
    private final String displayName;
    private final String description;

    SyncMode(boolean userSelectableTransferMode, String displayName, String description) {
        this.userSelectableTransferMode = userSelectableTransferMode;
        this.displayName = displayName;
        this.description = description;
    }

    /**
     * 判断该模式是否可以出现在“新建同步任务/同步模板”的一级传输模式选择中。
     */
    public boolean isUserSelectableTransferMode() {
        return userSelectableTransferMode;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    /**
     * 判断该模式的单次 execution 是否等价于全量 Reader/Writer 执行链路。
     *
     * <p>SCHEDULED_FULL 在产品上是一种独立模式，但执行层不应该重复造一套“定期全量 Reader”。
     * 它每次触发仍然走全量扫描，只是由 task scheduler 周期性创建 execution。</p>
     */
    public boolean isFullScanExecutionMode() {
        return this == FULL || this == SCHEDULED_FULL || this == ONE_TIME_MIGRATION;
    }

    /**
     * 判断该模式是否必须在任务层提供 scheduleConfig。
     */
    public boolean requiresTaskScheduleConfig() {
        return this == SCHEDULED_FULL || this == SCHEDULED_BATCH;
    }

    /**
     * 判断该模式是否允许携带 scheduleConfig。
     *
     * <p>FULL 不再允许通过“偷偷加 scheduleConfig”变成定期全量；要表达定期全量必须显式使用 SCHEDULED_FULL。
     * 这能让 API、前端、Agent 和审计语义保持一致。</p>
     */
    public boolean allowsTaskScheduleConfig() {
        return requiresTaskScheduleConfig();
    }
}

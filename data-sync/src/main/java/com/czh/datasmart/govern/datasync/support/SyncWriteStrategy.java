/**
 * @Author : Cui
 * @Date: 2026/06/29 22:18
 * @Description DataSmart Govern Backend - SyncWriteStrategy.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.support;

import java.util.Arrays;
import java.util.Locale;

/**
 * data-sync 写入策略枚举。
 *
 * <p>写入策略回答的是“目标端如何接纳源端同步过来的记录”。它同时服务两个层面：</p>
 * <p>1. 产品层：前端创建向导只应展示 INSERT 和 UPDATE 两种用户可理解的意图；</p>
 * <p>2. 执行层：历史 runner 和桥接计划仍可能使用 APPEND、UPSERT、INSERT_IGNORE、REPLACE、OVERWRITE 等更细的内部策略。</p>
 *
 * <p>为什么不直接删除历史策略：已有模板、测试、导入导出文件、审计记录和最小 JDBC runner 仍可能引用这些编码。
 * 因此当前采用“新建入口收口、内部兼容保留”的方式：新创建的模板保存 INSERT/UPDATE，派发执行时再通过
 * {@link #toRunnerStrategy()} 翻译成 runner 可理解的编码。</p>
 */
public enum SyncWriteStrategy {

    /**
     * 插入写入。
     *
     * <p>这是普通创建向导默认展示的策略，表达“把读取到的新数据写入目标表”。用户不需要手填主键、冲突字段或增量字段；
     * 目标表是否具备主键、唯一约束、外键、非空约束、字段兼容性等，应在预检查阶段由系统基于目标元数据自动判断。</p>
     *
     * <p>执行兼容：当前最小 JDBC runner 使用 APPEND 表达追加写入，因此派发前会翻译为 APPEND。</p>
     */
    INSERT(false, false, true, "APPEND"),

    /**
     * 更新/合并写入。
     *
     * <p>这是普通创建向导展示的第二种策略，表达“目标端已有记录则更新，没有记录则插入”的业务意图。
     * 它不再要求用户手填 primaryKeyField/conflictField，因为主键、唯一键或可冲突判断字段应该由预检查读取目标元数据后确认。</p>
     *
     * <p>执行兼容：当前最小 JDBC runner 使用 UPSERT 表达冲突更新，因此派发前会翻译为 UPSERT。</p>
     */
    UPDATE(false, false, true, "UPSERT"),

    /**
     * 追加写入。
     *
     * <p>APPEND 是历史执行器策略，适合日志、流水、离线导入等天然可追加场景。它现在不再作为普通创建向导的展示项；
     * 如果旧脚本仍提交 APPEND，模板创建会兼容折叠为 INSERT。</p>
     */
    APPEND(false, false, false, "APPEND"),

    /**
     * 冲突时更新，不冲突时插入。
     *
     * <p>UPSERT 是历史执行器策略，适合主数据、维表、用户画像等“同一业务主键保留一份最新记录”的场景。
     * 新建入口会把旧 UPSERT 输入折叠为 UPDATE，由预检查自动确认目标表是否具备冲突判断能力。</p>
     */
    UPSERT(true, false, false, "UPSERT"),

    /**
     * 冲突时忽略。
     *
     * <p>该策略属于更细的执行器能力，普通创建向导不展示。后续如果要开放，应放入高级策略或管理员控制面，并要求明确审计和风险提示。</p>
     */
    INSERT_IGNORE(true, false, false, "INSERT_IGNORE"),

    /**
     * 冲突时替换。
     *
     * <p>REPLACE 在不同数据库中的语义差异较大，有些实现接近“删除旧行再插入新行”，因此不适合作为普通用户创建任务时的默认选择。</p>
     */
    REPLACE(true, false, false, "REPLACE"),

    /**
     * 覆盖式写入。
     *
     * <p>OVERWRITE 通常意味着清空或替换目标范围，破坏性最强。它可以作为未来离线重建、紧急修复或管理员运维动作的能力，
     * 但不应出现在普通创建向导中。</p>
     */
    OVERWRITE(false, true, false, "OVERWRITE");

    /**
     * 当前策略是否要求目标端具备冲突判断键。
     *
     * <p>历史 UPSERT/INSERT_IGNORE/REPLACE 仍然保留该语义，便于旧模板和内部执行计划继续 fail-fast。新的 UPDATE 用户策略
     * 不要求用户手填主键字段，而是把判断职责移到预检查阶段。</p>
     */
    private final boolean requiresConflictKey;

    /**
     * 当前策略是否具有明显覆盖/破坏风险。
     *
     * <p>该字段用于预览、审批和 workerPlan 风险提示。它不代表永久禁止使用，而是提醒平台在进入执行前补足权限、审计和恢复方案。</p>
     */
    private final boolean destructiveRewrite;

    /**
     * 是否属于普通创建向导可展示的产品级策略。
     */
    private final boolean userFacingStrategy;

    /**
     * 发送给现有 runner 的兼容策略编码。
     */
    private final String runnerStrategy;

    SyncWriteStrategy(boolean requiresConflictKey,
                      boolean destructiveRewrite,
                      boolean userFacingStrategy,
                      String runnerStrategy) {
        this.requiresConflictKey = requiresConflictKey;
        this.destructiveRewrite = destructiveRewrite;
        this.userFacingStrategy = userFacingStrategy;
        this.runnerStrategy = runnerStrategy;
    }

    public boolean requiresConflictKey() {
        return requiresConflictKey;
    }

    public boolean isDestructiveRewrite() {
        return destructiveRewrite;
    }

    public boolean isUserFacingStrategy() {
        return userFacingStrategy;
    }

    public String toRunnerStrategy() {
        return runnerStrategy;
    }

    /**
     * 将外部输入归一化为平台内部写入策略。
     *
     * <p>空值现在默认回落到 INSERT，而不是历史 APPEND。这样新建任务在没有显式策略时会得到更符合用户心智的“插入写入”；
     * 执行层仍会把 INSERT 翻译为 APPEND，从而兼容现有最小 runner。</p>
     *
     * @param value 请求体、数据库或旧模板中的策略字符串
     * @return 归一化后的写入策略
     */
    public static SyncWriteStrategy fromValue(String value) {
        if (value == null || value.isBlank()) {
            return INSERT;
        }
        return Arrays.stream(values())
                .filter(item -> item.name().equalsIgnoreCase(value.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的同步写入策略: " + value));
    }

    /**
     * 按同步模式解析写入策略。
     *
     * <p>普通离线模式在用户没有显式选择时仍默认 INSERT，这是为了保持“全量传输首次落目标表”的直观体验。
     * 但实时 CDC 不同：实时链路消费的是持续变化事件，天然会遇到同一主键的 insert/update/delete 多次变化。
     * 如果把实时默认成 INSERT，第二次捕获同一业务主键时就很容易产生主键冲突或重复行；因此实时模式在写入策略为空时
     * 统一默认 UPDATE，也就是产品语义里的 merge/upsert。</p>
     *
     * <p>这个方法只负责“默认值”解析，不负责判断某个显式策略是否允许用于某个模式。
     * 例如调用方显式传入 INSERT + CDC_STREAMING 时，本方法会尊重输入返回 INSERT，随后由创建向导或执行预检查给出
     * {@code REALTIME_WRITE_STRATEGY_MUST_BE_MERGE} 这类更清晰的业务错误。这样既方便兼容旧数据，也便于前端展示精确原因。</p>
     *
     * @param value 请求体、数据库或旧模板中的策略字符串。
     * @param syncMode 当前同步模式字符串，可为空；为空时按普通离线模式默认 INSERT。
     * @return 结合模式默认值后的写入策略。
     */
    public static SyncWriteStrategy fromValueForMode(String value, String syncMode) {
        if (value == null || value.isBlank()) {
            return isRealtimeMode(syncMode) ? UPDATE : INSERT;
        }
        return fromValue(value);
    }

    /**
     * 判断当前策略是否表达“插入/追加”语义。
     *
     * <p>该方法用于模式矩阵预检查。INSERT 和历史 APPEND 都属于“只新增，不处理已有行”的语义，
     * 在全量传输中要求目标表为空，在实时 CDC 中通常不应开放给普通创建向导。</p>
     */
    public boolean insertLike() {
        return this == INSERT || this == APPEND;
    }

    /**
     * 判断当前策略是否表达“更新/合并”语义。
     *
     * <p>UPDATE 是当前用户可见的 merge 语义，UPSERT 是历史执行器语义。二者都要求目标端能识别同一业务记录，
     * 因此前置预检查需要关注目标主键、唯一键或可冲突判断字段。</p>
     */
    public boolean mergeLike() {
        return this == UPDATE || this == UPSERT || this == INSERT_IGNORE || this == REPLACE;
    }

    private static boolean isRealtimeMode(String syncMode) {
        return syncMode != null && "CDC_STREAMING".equals(syncMode.trim().toUpperCase(Locale.ROOT));
    }
}

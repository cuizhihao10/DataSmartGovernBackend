/**
 * @Author : Cui
 * @Date: 2026/07/07 23:00
 * @Description DataSmart Govern Backend - SyncTaskScheduleConfigSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.entity.SyncTask;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 同步任务调度配置解析与时间计算组件。
 *
 * <p>这个类专门处理 {@code data_sync_task.schedule_config}，不负责创建 execution，也不负责扫描数据库。
 * 拆出来的原因是：调度配置是产品合同，里面包含触发类型、时区、错过触发策略、并发策略、补偿次数等业务语义；
 * 如果直接散落在 ServiceImpl 或 Scheduler 里，后续很难判断某个任务为什么触发、为什么跳过、为什么补偿。</p>
 *
 * <p>当前支持的 JSON 示例：</p>
 * <pre>
 * {
 *   "type": "FIXED_RATE",
 *   "intervalSeconds": 3600,
 *   "timezone": "Asia/Shanghai",
 *   "misfirePolicy": "FIRE_ONCE",
 *   "allowConcurrentRuns": false,
 *   "maxCatchUpRuns": 1
 * }
 * </pre>
 *
 * <p>也可以使用 CRON：</p>
 * <pre>
 * {
 *   "type": "CRON",
 *   "cron": "0 0 2 * * *",
 *   "timezone": "Asia/Shanghai",
 *   "misfirePolicy": "SKIP"
 * }
 * </pre>
 *
 * <p>设计边界：</p>
 * <p>1. 本类只保存和返回低敏调度事实，不接触 SQL、字段映射、数据源密码或样本数据；</p>
 * <p>2. CRON 使用 Spring {@link CronExpression}，沿用 Spring 6 的六段 cron 表达式格式；</p>
 * <p>3. FIXED_RATE 适合“每小时/每天固定频率”；FIXED_DELAY 适合“本轮触发后再等待一段时间”，因此默认不制造历史补偿风暴；</p>
 * <p>4. CATCH_UP_LIMITED 只做有上限补偿，避免服务恢复后瞬间生成成百上千条 execution。</p>
 */
@Component
@RequiredArgsConstructor
public class SyncTaskScheduleConfigSupport {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int HARD_MAX_CATCH_UP_RUNS = 20;
    private static final int HARD_MAX_MISFIRE_SCAN_STEPS = 10000;

    private final ObjectMapper objectMapper;

    /**
     * 判断请求是否显式声明了调度配置。
     *
     * <p>调用方会用这个方法判断任务是否声明了调度配置。当前产品已经把“定期全量”独立为
     * SCHEDULED_FULL，不再允许用 {@code FULL + scheduleConfig} 隐式表达。也就是说：
     * FULL 只能是手工/一次性全量；SCHEDULED_FULL 与 SCHEDULED_BATCH 才能携带 scheduleConfig。</p>
     */
    public boolean hasScheduleConfig(String scheduleConfig) {
        return StringUtils.hasText(scheduleConfig);
    }

    /**
     * 解析并校验调度配置。
     *
     * @param scheduleConfig 任务级调度 JSON。
     * @return 结构化调度定义。
     */
    public SyncTaskScheduleDefinition parseRequired(String scheduleConfig) {
        if (!StringUtils.hasText(scheduleConfig)) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "定时同步任务必须提供 scheduleConfig");
        }
        try {
            JsonNode root = objectMapper.readTree(scheduleConfig);
            SyncTaskScheduleType type = parseType(text(root, "type", null), root);
            ZoneId zoneId = parseZone(text(root, "timezone", null));
            SyncTaskMisfirePolicy misfirePolicy = parseMisfirePolicy(text(root, "misfirePolicy", "FIRE_ONCE"));
            boolean allowConcurrentRuns = bool(root, "allowConcurrentRuns", false);
            int maxCatchUpRuns = boundedCatchUpRuns(integer(root, "maxCatchUpRuns", 1));
            LocalDateTime startAt = parseOptionalDateTime(text(root, "startAt", null), zoneId);

            return switch (type) {
                case FIXED_DELAY, FIXED_RATE -> {
                    long intervalSeconds = longNumber(root, "intervalSeconds", 0L);
                    if (intervalSeconds < 1L) {
                        throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                                "FIXED_DELAY/FIXED_RATE 调度必须提供大于 0 的 intervalSeconds");
                    }
                    yield new SyncTaskScheduleDefinition(
                            type,
                            intervalSeconds,
                            null,
                            zoneId,
                            misfirePolicy,
                            maxCatchUpRuns,
                            allowConcurrentRuns,
                            startAt
                    );
                }
                case CRON -> {
                    String cron = text(root, "cron", null);
                    if (!StringUtils.hasText(cron)) {
                        throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                                "CRON 调度必须提供 cron 表达式");
                    }
                    /*
                     * CronExpression.parse 会在表达式非法时抛 IllegalArgumentException。
                     * 这里主动解析一次，是为了让 createTask 阶段 fail-fast，避免任务已经进入 SCHEDULED 后，
                     * 后台调度器才反复报错。
                     */
                    CronExpression.parse(cron.trim());
                    yield new SyncTaskScheduleDefinition(
                            type,
                            null,
                            cron.trim(),
                            zoneId,
                            misfirePolicy,
                            maxCatchUpRuns,
                            allowConcurrentRuns,
                            startAt
                    );
                }
            };
        } catch (PlatformBusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "scheduleConfig 不是合法的调度 JSON 或包含非法字段: " + exception.getMessage());
        }
    }

    /**
     * 计算任务创建后的首次触发时间。
     *
     * <p>如果配置了 startAt 且它晚于当前时间，则优先使用 startAt；否则按调度表达式计算下一次未来触发时间。
     * 这样用户可以创建“明天凌晨 2 点开始，每天执行”的任务，也可以创建“从现在开始每小时执行”的任务。</p>
     */
    public LocalDateTime initialNextFireTime(String scheduleConfig, LocalDateTime now) {
        SyncTaskScheduleDefinition definition = parseRequired(scheduleConfig);
        LocalDateTime safeNow = now == null ? LocalDateTime.now() : now;
        if (definition.startAt() != null && definition.startAt().isAfter(safeNow)) {
            return definition.startAt();
        }
        return nextAfter(definition, safeNow, safeNow);
    }

    /**
     * 根据当前任务游标计算本轮调度动作。
     *
     * @param task 到期任务。
     * @param now 当前调度时间。
     * @param platformMaxCatchUpRunsPerTask 平台级单轮补偿上限，防止某个任务恢复后制造过多 execution。
     * @return 本轮应派发的计划触发时间、下一次游标和错过计数。
     */
    public SyncTaskScheduleDispatchPlan buildDispatchPlan(SyncTask task,
                                                          LocalDateTime now,
                                                          int platformMaxCatchUpRunsPerTask) {
        SyncTaskScheduleDefinition definition = parseRequired(task.getScheduleConfig());
        LocalDateTime safeNow = now == null ? LocalDateTime.now() : now;
        LocalDateTime dueTime = task.getNextFireTime() == null
                ? initialNextFireTime(task.getScheduleConfig(), safeNow)
                : task.getNextFireTime();
        if (dueTime.isAfter(safeNow)) {
            return new SyncTaskScheduleDispatchPlan(definition, List.of(), dueTime, 0L, false);
        }

        if (definition.type() == SyncTaskScheduleType.FIXED_DELAY) {
            /*
             * FIXED_DELAY 表示“触发一次后再等待 interval”。它不追逐历史固定刻度，所以即使服务停机很久，
             * 恢复后也只补一次，并把下一次时间设为 now + interval。这可以避免服务恢复瞬间生成大量历史执行。
             */
            return new SyncTaskScheduleDispatchPlan(
                    definition,
                    List.of(dueTime),
                    nextAfter(definition, safeNow, safeNow),
                    0L,
                    false
            );
        }

        long dueCount = countDueTimes(definition, dueTime, safeNow);
        if (definition.misfirePolicy() == SyncTaskMisfirePolicy.SKIP) {
            return new SyncTaskScheduleDispatchPlan(
                    definition,
                    List.of(),
                    nextFutureAfter(definition, dueTime, safeNow),
                    dueCount,
                    true
            );
        }

        if (definition.misfirePolicy() == SyncTaskMisfirePolicy.FIRE_ONCE || !definition.allowConcurrentRuns()) {
            /*
             * FIRE_ONCE 表示不管错过多少个历史窗口，都只补一条 execution。
             * 如果 allowConcurrentRuns=false，即使配置了 CATCH_UP_LIMITED，也只能先派发一条，避免多个历史窗口并发写同一目标。
             */
            return new SyncTaskScheduleDispatchPlan(
                    definition,
                    List.of(dueTime),
                    nextFutureAfter(definition, dueTime, safeNow),
                    Math.max(0L, dueCount - 1L),
                    false
            );
        }

        int catchUpLimit = boundedCatchUpRuns(Math.min(definition.maxCatchUpRuns(), platformMaxCatchUpRunsPerTask));
        List<LocalDateTime> fireTimes = new ArrayList<>();
        LocalDateTime cursor = dueTime;
        while (!cursor.isAfter(safeNow) && fireTimes.size() < catchUpLimit) {
            fireTimes.add(cursor);
            cursor = nextAfter(definition, cursor, safeNow);
        }
        return new SyncTaskScheduleDispatchPlan(
                definition,
                List.copyOf(fireTimes),
                cursor,
                0L,
                false
        );
    }

    /**
     * 从某个触发时间向后寻找第一个未来触发时间。
     *
     * <p>该方法用于 SKIP 与 FIRE_ONCE：当服务停机错过了多个窗口时，需要把游标推进到未来，
     * 否则调度器会在下一轮又看到同一个过期时间并重复处理。</p>
     */
    public LocalDateTime nextFutureAfter(SyncTaskScheduleDefinition definition,
                                         LocalDateTime from,
                                         LocalDateTime now) {
        LocalDateTime cursor = from;
        int guard = 0;
        while (!cursor.isAfter(now)) {
            cursor = nextAfter(definition, cursor, now);
            guard++;
            if (guard > HARD_MAX_MISFIRE_SCAN_STEPS) {
                throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                        "调度补偿计算超过安全步数，请检查 scheduleConfig 是否间隔过小或 cron 表达式异常");
            }
        }
        return cursor;
    }

    /**
     * 计算下一个触发时间。
     *
     * <p>FIXED_RATE 从上一计划触发时间继续推进；FIXED_DELAY 从当前调度时间推进；
     * CRON 则根据配置时区计算下一次 cron 命中点，再转回 {@link LocalDateTime} 持久化。</p>
     */
    public LocalDateTime nextAfter(SyncTaskScheduleDefinition definition,
                                   LocalDateTime reference,
                                   LocalDateTime now) {
        if (definition.type() == SyncTaskScheduleType.FIXED_DELAY) {
            return (now == null ? LocalDateTime.now() : now).plusSeconds(definition.intervalSeconds());
        }
        if (definition.type() == SyncTaskScheduleType.FIXED_RATE) {
            return reference.plusSeconds(definition.intervalSeconds());
        }
        ZonedDateTime zonedReference = reference.atZone(definition.zoneId());
        ZonedDateTime next = CronExpression.parse(definition.cron()).next(zonedReference);
        if (next == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "cron 表达式无法计算下一次触发时间: " + definition.cron());
        }
        return next.toLocalDateTime();
    }

    private long countDueTimes(SyncTaskScheduleDefinition definition, LocalDateTime firstDue, LocalDateTime now) {
        long count = 0L;
        LocalDateTime cursor = firstDue;
        while (!cursor.isAfter(now)) {
            count++;
            cursor = nextAfter(definition, cursor, now);
            if (count > HARD_MAX_MISFIRE_SCAN_STEPS) {
                throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                        "调度错过次数计算超过安全步数，请检查 scheduleConfig 是否间隔过小");
            }
        }
        return count;
    }

    private SyncTaskScheduleType parseType(String rawType, JsonNode root) {
        String value = rawType;
        if (!StringUtils.hasText(value)) {
            value = StringUtils.hasText(text(root, "cron", null)) ? "CRON" : "FIXED_RATE";
        }
        try {
            return SyncTaskScheduleType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "不支持的调度类型: " + value + "，当前支持 FIXED_DELAY、FIXED_RATE、CRON");
        }
    }

    private SyncTaskMisfirePolicy parseMisfirePolicy(String rawPolicy) {
        try {
            return SyncTaskMisfirePolicy.valueOf(rawPolicy.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "不支持的 misfirePolicy: " + rawPolicy + "，当前支持 SKIP、FIRE_ONCE、CATCH_UP_LIMITED");
        }
    }

    private ZoneId parseZone(String rawZone) {
        if (!StringUtils.hasText(rawZone)) {
            return DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(rawZone.trim());
        } catch (Exception exception) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "scheduleConfig.timezone 不是合法时区: " + rawZone);
        }
    }

    private LocalDateTime parseOptionalDateTime(String rawValue, ZoneId zoneId) {
        if (!StringUtils.hasText(rawValue)) {
            return null;
        }
        String value = rawValue.trim();
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(zoneId).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // 尝试下一种常见格式。
        }
        try {
            return Instant.parse(value).atZone(zoneId).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // 尝试下一种常见格式。
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "scheduleConfig.startAt 不是合法时间，建议使用 ISO-8601，例如 2026-07-07T02:00:00+08:00");
        }
    }

    private int boundedCatchUpRuns(int value) {
        if (value < 1) {
            return 1;
        }
        return Math.min(value, HARD_MAX_CATCH_UP_RUNS);
    }

    private String text(JsonNode root, String fieldName, String defaultValue) {
        JsonNode value = root == null ? null : root.get(fieldName);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        String text = value.asText();
        return StringUtils.hasText(text) ? text.trim() : defaultValue;
    }

    private boolean bool(JsonNode root, String fieldName, boolean defaultValue) {
        JsonNode value = root == null ? null : root.get(fieldName);
        return value == null || value.isNull() ? defaultValue : value.asBoolean(defaultValue);
    }

    private int integer(JsonNode root, String fieldName, int defaultValue) {
        JsonNode value = root == null ? null : root.get(fieldName);
        return value == null || value.isNull() ? defaultValue : value.asInt(defaultValue);
    }

    private long longNumber(JsonNode root, String fieldName, long defaultValue) {
        JsonNode value = root == null ? null : root.get(fieldName);
        return value == null || value.isNull() ? defaultValue : value.asLong(defaultValue);
    }

    /**
     * 调度触发类型。
     */
    public enum SyncTaskScheduleType {
        FIXED_DELAY,
        FIXED_RATE,
        CRON
    }

    /**
     * 错过触发策略。
     */
    public enum SyncTaskMisfirePolicy {
        /**
         * 直接跳过过期窗口，只推进到下一次未来触发。
         */
        SKIP,
        /**
         * 不管错过多少次，只补发一次 execution。
         */
        FIRE_ONCE,
        /**
         * 在平台与任务配置允许的上限内逐步补偿历史窗口。
         */
        CATCH_UP_LIMITED
    }

    /**
     * 结构化调度定义。
     */
    public record SyncTaskScheduleDefinition(
            SyncTaskScheduleType type,
            Long intervalSeconds,
            String cron,
            ZoneId zoneId,
            SyncTaskMisfirePolicy misfirePolicy,
            int maxCatchUpRuns,
            boolean allowConcurrentRuns,
            LocalDateTime startAt
    ) {
    }

    /**
     * 本轮调度计划。
     *
     * @param definition 解析后的调度配置。
     * @param fireTimes 本轮需要创建 SCHEDULED execution 的计划触发时间列表。
     * @param nextFireTime 调度游标推进后的下一次触发时间。
     * @param misfireIncrement 本轮应累计的错过次数。
     * @param skippedByPolicy 是否因为 SKIP 策略只推进游标、不创建 execution。
     */
    public record SyncTaskScheduleDispatchPlan(
            SyncTaskScheduleDefinition definition,
            List<LocalDateTime> fireTimes,
            LocalDateTime nextFireTime,
            long misfireIncrement,
            boolean skippedByPolicy
    ) {
    }
}

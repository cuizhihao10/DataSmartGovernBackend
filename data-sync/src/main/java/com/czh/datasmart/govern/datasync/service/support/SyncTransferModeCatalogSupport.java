/**
 * @Author : Cui
 * @Date: 2026/07/07 23:59
 * @Description DataSmart Govern Backend - SyncTransferModeCatalogSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncTransferModeOption;
import com.czh.datasmart.govern.datasync.support.SyncMode;
import com.czh.datasmart.govern.datasync.support.SyncTransferChannel;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 同步任务传输模式目录。
 *
 * <p>这个组件是“前端模式下拉框”和“Agent 规划可选项”的权威来源。此前系统把很多能力都放进 SyncMode：
 * 按时间增量、按主键增量、失败回放、历史补数、离线导入、离线导出等，这些能力在商业产品里确实需要，
 * 但它们不是用户新建同步任务时的一级传输模式。把它们展示在同一个下拉框中，会让用户误以为“失败回放”
 * 也能像“全量”一样新建为普通同步任务。</p>
 *
 * <p>因此这里故意只返回 5 个主模式：</p>
 * <p>1. FULL：全量传输；</p>
 * <p>2. SCHEDULED_BATCH：定期批量；</p>
 * <p>3. SCHEDULED_FULL：定期全量；</p>
 * <p>4. CUSTOM_SQL_QUERY：SQL语句；</p>
 * <p>5. CDC_STREAMING：实时。</p>
 *
 * <p>失败回放、补数、脏数据修复重放继续通过任务详情页、执行历史页、错误样本页或运维动作入口触发；
 * 离线导入导出后续应进入文件/对象制品流程，而不是污染数据源到数据源同步任务的主模式。</p>
 */
@Component
public class SyncTransferModeCatalogSupport {

    private static final List<SyncMode> USER_MODE_ORDER = List.of(
            SyncMode.FULL,
            SyncMode.SCHEDULED_BATCH,
            SyncMode.SCHEDULED_FULL,
            SyncMode.CUSTOM_SQL_QUERY,
            SyncMode.CDC_STREAMING
    );

    /**
     * 返回用户可选的任务传输模式。
     *
     * <p>返回顺序就是前端推荐展示顺序，避免每个客户端自己排序导致产品体验不一致。</p>
     */
    public List<SyncTransferModeOption> listUserSelectableModes() {
        return USER_MODE_ORDER.stream()
                .map(this::toOption)
                .toList();
    }

    private SyncTransferModeOption toOption(SyncMode mode) {
        SyncTransferChannel transferChannel = SyncTransferChannelSupport.resolve(mode);
        return new SyncTransferModeOption(
                mode.name(),
                productDisplayName(mode),
                transferChannel == null ? null : transferChannel.name(),
                mode.requiresTaskScheduleConfig(),
                mode.allowsTaskScheduleConfig(),
                mode == SyncMode.CUSTOM_SQL_QUERY,
                defaultScopeType(mode),
                mode.description(),
                recommendedActions(mode)
        );
    }

    /**
     * 返回创建任务页面使用的产品化展示名。
     *
     * <p>{@link SyncMode} 枚举中保留了一些内部说明和历史兼容描述，而创建向导需要的是更贴近用户语言的
     * 五个一级模式名称。这里单独收口展示名，可以避免为了调整 UI 文案去改动底层枚举语义。</p>
     */
    private String productDisplayName(SyncMode mode) {
        return switch (mode) {
            case FULL -> "全量传输";
            case SCHEDULED_BATCH -> "定期批量";
            case SCHEDULED_FULL -> "定期全量";
            case CUSTOM_SQL_QUERY -> "SQL语句";
            case CDC_STREAMING -> "实时";
            default -> mode.displayName();
        };
    }

    private String defaultScopeType(SyncMode mode) {
        if (mode == SyncMode.CUSTOM_SQL_QUERY) {
            return "CUSTOM_SQL_QUERY";
        }
        return "SINGLE_OBJECT";
    }

    private List<String> recommendedActions(SyncMode mode) {
        return switch (mode) {
            case FULL -> List.of(
                    "选择源端和目标端数据源",
                    "选择同步对象、字段映射、where 条件和写入策略",
                    "大表建议配置 splitPk、分片数、并发通道和脏数据阈值"
            );
            case SCHEDULED_FULL -> List.of(
                    "必须在创建任务时配置 scheduleConfig，例如 CRON 或 FIXED_RATE",
                    "每次触发都会执行完整范围扫描，请评估源端压力、目标端写入策略和维护窗口",
                    "建议关闭并发触发 allowConcurrentRuns=false，避免上一轮未结束又开始下一轮"
            );
            case SCHEDULED_BATCH -> List.of(
                    "必须在创建任务时配置 scheduleConfig",
                    "必须配置批处理窗口语义，例如时间窗口、分区范围或业务批次边界",
                    "建议配置幂等写入策略、重试上限和超时策略"
            );
            case CUSTOM_SQL_QUERY -> List.of(
                    "必须提供 customSqlConfig，并通过只读 SQL 校验",
                    "生产环境建议使用 statementRef 或托管 SQL 仓库，不直接暴露 SQL 正文",
                    "通常需要审批、行数成本评估、超时限制和目标字段映射校验"
            );
            case CDC_STREAMING -> List.of(
                    "实时模式应走 Debezium/Kafka Connect 风格 CDC pipeline",
                    "需要配置 offset、topic、消费组、顺序性和幂等写入策略",
                    "当前不要把实时任务塞进离线 DataX-style runner"
            );
            default -> List.of("该模式不是用户可选任务传输模式，请改用恢复、补数、导入导出或运维专用入口");
        };
    }
}

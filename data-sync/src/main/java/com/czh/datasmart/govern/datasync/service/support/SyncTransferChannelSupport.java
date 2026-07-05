/**
 * @Author : Cui
 * @Date: 2026/07/05 14:05
 * @Description DataSmart Govern Backend - SyncTransferChannelSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.support.SyncMode;
import com.czh.datasmart.govern.datasync.support.SyncTransferChannel;

import java.util.Locale;

/**
 * 同步模式到传输大类的映射规则。
 *
 * <p>本类的职责非常小，但非常关键：把“全量、定时、SQL、CDC”等具体模式稳定地归入 OFFLINE 或 REALTIME。
 * 这能避免后续代码到处写 if/else，也避免出现某个入口把 SCHEDULED_BATCH 当实时、另一个入口又把它当离线的产品语义分裂。</p>
 *
 * <p>当前项目的产品口径采用“两层语义”：</p>
 * <p>1. transferChannel 是顶层技术路线：CDC_STREAMING 归入 REALTIME，其余有明确开始、结束、窗口或制品边界的任务归入 OFFLINE；</p>
 * <p>2. syncMode 是细粒度业务模式：FULL 表示全量，FULL + 调度配置表示定时全量，SCHEDULED_BATCH 表示定时批量，
 * CUSTOM_SQL_QUERY 表示 SQL 自定义传输，ONE_TIME_MIGRATION/REPLAY/BACKFILL/OFFLINE_IMPORT/OFFLINE_EXPORT
 * 分别表示迁移、恢复、补数、导入和导出。</p>
 *
 * <p>这个分层能兼顾两件事：一方面让网关、Agent、监控和执行器可以快速判断“走离线作业还是实时 CDC”；
 * 另一方面又不会把所有 OFFLINE 都误称为“批量传输”。在本项目语义中，批量传输只对应 SCHEDULED_BATCH，
 * 但离线通道可以覆盖更多有界作业场景。</p>
 */
public final class SyncTransferChannelSupport {

    private SyncTransferChannelSupport() {
    }

    /**
     * 根据枚举模式解析传输大类。
     *
     * @param syncMode 同步模式；可以为空，空值表示模板尚未完成配置。
     * @return OFFLINE、REALTIME 或 null。
     */
    public static SyncTransferChannel resolve(SyncMode syncMode) {
        if (syncMode == null) {
            return null;
        }
        return syncMode == SyncMode.CDC_STREAMING
                ? SyncTransferChannel.REALTIME
                : SyncTransferChannel.OFFLINE;
    }

    /**
     * 根据字符串模式解析传输大类。
     *
     * <p>该方法用于 controller/service 中的低敏 DTO 生成，未知模式返回 null，由调用方继续追加 SYNC_MODE_UNSUPPORTED
     * 之类的 issueCode，而不是在这里抛异常导致预览接口无法一次性返回完整问题清单。</p>
     */
    public static SyncTransferChannel resolve(String syncMode) {
        if (syncMode == null || syncMode.isBlank()) {
            return null;
        }
        try {
            return resolve(SyncMode.valueOf(syncMode.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * 返回当前传输大类建议对齐的参考执行架构。
     *
     * <p>这里故意使用“参考架构”而不是“已接入 runtime”，因为当前 data-sync 仍处于控制面闭环阶段：
     * OFFLINE 已有最小 run-once bridge，但还不是完整 DataX runner；REALTIME 已有产品语义和能力矩阵，但还未真正接通
     * Debezium/Kafka Connect pipeline。</p>
     */
    public static String referenceRuntime(SyncTransferChannel channel) {
        if (channel == null) {
            return "UNKNOWN_RUNTIME";
        }
        return switch (channel) {
            case OFFLINE -> "DATAX_STYLE_OFFLINE_READER_WRITER_RUNNER";
            case REALTIME -> "DEBEZIUM_KAFKA_CONNECT_CDC_PIPELINE";
        };
    }

    /**
     * 返回面向用户和 Agent 的中文解释。
     *
     * <p>该说明会进入低敏预览/预检响应，用于让前端、Agent 规划和运维人员快速理解“为什么定时批量也属于离线传输”。</p>
     */
    public static String explanation(SyncTransferChannel channel) {
        if (channel == null) {
            return "尚未选择同步模式，无法判断传输大类";
        }
        return switch (channel) {
            case OFFLINE -> "离线传输：对齐 DataX 式 Reader/Writer 有界作业路线，覆盖全量、定时全量、定时批量、SQL 自定义传输、一次性迁移、回放、补数、离线导入和离线导出；注意只有 SCHEDULED_BATCH 才表示定时批量，不能把所有离线作业都叫批量传输";
            case REALTIME -> "实时传输：对齐 Debezium/Kafka Connect 式 CDC 路线，持续捕获 binlog/WAL/change stream 并通过 Kafka topic 推进下游消费";
        };
    }
}

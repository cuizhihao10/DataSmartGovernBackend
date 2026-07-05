/**
 * @Author : Cui
 * @Date: 2026/07/05 15:08
 * @Description DataSmart Govern Backend - SyncOfflineRunnerReportMetrics.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.controller.dto.SyncOfflineRunnerReportRequest;
import com.czh.datasmart.govern.datasync.controller.dto.SyncOfflineRunnerReportResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 专用离线 Runner 报告回调指标组件。
 *
 * <p>真实 DataX-style Runner 接入后，仅靠日志很难判断回调链路是否健康：
 * 是 runner 没有回传、回传被签名拒绝、checkpoint 卡住、complete/fail 未进入生命周期，还是 receipt 投递失败。
 * 本组件先提供最小低基数指标，让 Prometheus 能看到 report callback 的状态分布。</p>
 *
 * <p>指标标签必须谨慎：这里不使用 tenantId、taskId、executionId、traceId、tableName、SQL hash、字段名、
 * checkpointRef 或 checkpointDigest，因为这些值在真实客户环境会快速膨胀，导致 Prometheus 时序爆炸。
 * 只保留 runnerStatus、action、adapterCode、result 这类可控枚举或低基数字符串。</p>
 */
@Component
public class SyncOfflineRunnerReportMetrics {

    private final MeterRegistry meterRegistry;

    public SyncOfflineRunnerReportMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 记录一次离线 Runner 报告处理结果。
     *
     * @param request Runner 报告请求，仅读取低基数字段。
     * @param result data-sync 应用后的低敏结果。
     */
    public void recordReport(SyncOfflineRunnerReportRequest request, SyncOfflineRunnerReportResult result) {
        Counter.builder("datasmart_data_sync_offline_runner_report_total")
                .description("data-sync 专用离线 Runner 低敏报告处理次数")
                .tag("runner_status", normalize(request == null ? null : request.getRunnerStatus()))
                .tag("adapter", normalizeAdapter(request == null ? null : request.getAdapterCode()))
                .tag("action", normalize(result == null ? null : result.actionApplied()))
                .tag("result", result != null && result.accepted() ? "ACCEPTED" : "REJECTED")
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录报告处理异常。
     *
     * <p>异常类型也保持低基数，只区分业务拒绝、校验失败、未知异常等粗粒度类别；
     * 不把异常 message、SQL、连接串或堆栈摘要写入标签。</p>
     */
    public void recordFailure(SyncOfflineRunnerReportRequest request, RuntimeException exception) {
        Counter.builder("datasmart_data_sync_offline_runner_report_failure_total")
                .description("data-sync 专用离线 Runner 低敏报告处理失败次数")
                .tag("runner_status", normalize(request == null ? null : request.getRunnerStatus()))
                .tag("adapter", normalizeAdapter(request == null ? null : request.getAdapterCode()))
                .tag("exception", exception == null ? "UNKNOWN" : exception.getClass().getSimpleName())
                .register(meterRegistry)
                .increment();
    }

    private String normalizeAdapter(String value) {
        return normalize(value == null || value.isBlank() ? "UNKNOWN_ADAPTER" : value);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9_\\-]", "_");
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }
}

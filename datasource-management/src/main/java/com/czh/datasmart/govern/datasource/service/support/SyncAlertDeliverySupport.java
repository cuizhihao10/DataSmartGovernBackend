/**
 * @Author : Cui
 * @Date: 2026/05/05 23:40
 * @Description DataSmart Govern Backend - SyncAlertDeliverySupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.czh.datasmart.govern.datasource.config.SyncAlertProperties;
import com.czh.datasmart.govern.datasource.entity.SyncAlertDeliveryRecord;
import com.czh.datasmart.govern.datasource.entity.SyncGovernanceAlert;
import com.czh.datasmart.govern.datasource.mapper.SyncAlertDeliveryRecordMapper;
import com.czh.datasmart.govern.datasource.mapper.SyncGovernanceAlertMapper;
import com.czh.datasmart.govern.datasource.support.SyncAlertChannel;
import com.czh.datasmart.govern.datasource.support.SyncAlertDeliveryStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 同步治理告警投递支持组件。
 *
 * <p>该组件只关心“告警如何被送出去以及投递结果如何回写”。
 * 它不负责告警是否有权限被查看，也不负责告警是否已经被确认或解决。
 * 这种边界让我们可以独立增强通道链、Webhook、飞书、企业微信、内部日志、邮件、短信、事件总线等投递能力。</p>
 *
 * <p>商业化场景下，告警投递必须具备可追踪性：每次尝试走了哪个通道、什么时候开始、什么时候结束、
 * 对端返回什么、失败原因是什么，都需要落到 `sync_alert_delivery_record` 中，方便事故复盘和审计。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncAlertDeliverySupport {

    private final SyncAlertProperties syncAlertProperties;
    private final SyncGovernanceAlertMapper syncGovernanceAlertMapper;
    private final SyncAlertDeliveryRecordMapper syncAlertDeliveryRecordMapper;

    /**
     * 执行告警投递。
     *
     * @param alert 待投递告警。
     * @param actorId 触发人 ID；自动投递时可以是系统上下文。
     * @param actorRole 触发人角色；用于审计投递来源。
     * @param manualDispatch 是否人工触发。人工触发时“通道未配置”更倾向记录为跳过，而不是制造系统失败。
     */
    public void dispatchInternal(SyncGovernanceAlert alert, Long actorId, String actorRole, boolean manualDispatch) {
        LocalDateTime now = LocalDateTime.now();
        int dispatchAttemptNo = (alert.getDeliveryAttemptCount() == null ? 0 : alert.getDeliveryAttemptCount()) + 1;
        alert.setDeliveryAttemptCount(dispatchAttemptNo);

        List<SyncAlertChannel> channelChain = resolveChannelChain();
        String lastError = null;
        boolean allSkipped = true;
        for (SyncAlertChannel channel : channelChain) {
            alert.setDeliveryChannel(channel.name());
            LocalDateTime startedAt = LocalDateTime.now();
            DeliveryAttemptResult attemptResult = attemptDeliveryByChannel(alert, channel, actorId, actorRole, manualDispatch);
            LocalDateTime finishedAt = LocalDateTime.now();
            recordDeliveryAttempt(alert, dispatchAttemptNo, channel, attemptResult, manualDispatch, actorId, actorRole, startedAt, finishedAt);
            if (attemptResult.deliveryStatus() != SyncAlertDeliveryStatus.SKIPPED) {
                allSkipped = false;
            }
            if (attemptResult.success()) {
                markSent(alert, finishedAt);
                return;
            }
            lastError = attemptResult.errorMessage();
        }
        markUnsent(alert, now, lastError, allSkipped);
    }

    /**
     * 分页查询某条告警的投递记录。
     */
    public IPage<SyncAlertDeliveryRecord> pageDeliveryRecords(Long alertId, Page<SyncAlertDeliveryRecord> page) {
        LambdaQueryWrapper<SyncAlertDeliveryRecord> wrapper = new LambdaQueryWrapper<SyncAlertDeliveryRecord>()
                .eq(SyncAlertDeliveryRecord::getAlertId, alertId)
                .orderByDesc(SyncAlertDeliveryRecord::getCreateTime)
                .orderByDesc(SyncAlertDeliveryRecord::getAttemptNo);
        return syncAlertDeliveryRecordMapper.selectPage(page, wrapper);
    }

    public boolean autoDeliverOnOpen() {
        return Boolean.TRUE.equals(syncAlertProperties.getAutoDeliverOnOpen());
    }

    public int resolveRetryDispatchBatchLimit() {
        return syncAlertProperties.getRetryDispatchBatchLimit() == null
                ? 100
                : Math.max(1, syncAlertProperties.getRetryDispatchBatchLimit());
    }

    private DeliveryAttemptResult attemptDeliveryByChannel(SyncGovernanceAlert alert,
                                                           SyncAlertChannel channel,
                                                           Long actorId,
                                                           String actorRole,
                                                           boolean manualDispatch) {
        return switch (channel) {
            case NONE -> DeliveryAttemptResult.skippedResult("当前未配置可用的告警投递通道，已跳过外部投递", "none");
            case INTERNAL_LOG -> {
                log.warn("同步治理告警进入内部日志通道: alertId={}, tenantId={}, taskId={}, type={}, severity={}, summary={}",
                        alert.getId(), alert.getTenantId(), alert.getSyncTaskId(), alert.getAlertType(), alert.getSeverity(), alert.getSummary());
                yield DeliveryAttemptResult.successResult("内部日志通道已记录告警", "internal-log");
            }
            case WEBHOOK -> attemptWebhookDelivery(alert, actorId, actorRole, syncAlertProperties.getWebhookUrl(), "通用 Webhook", manualDispatch);
            case FEISHU_WEBHOOK -> attemptWebhookDelivery(alert, actorId, actorRole, syncAlertProperties.getFeishuWebhookUrl(), "飞书机器人", manualDispatch);
            case WECOM_WEBHOOK -> attemptWebhookDelivery(alert, actorId, actorRole, syncAlertProperties.getWecomWebhookUrl(), "企业微信机器人", manualDispatch);
        };
    }

    private DeliveryAttemptResult attemptWebhookDelivery(SyncGovernanceAlert alert,
                                                         Long actorId,
                                                         String actorRole,
                                                         String webhookUrl,
                                                         String channelLabel,
                                                         boolean manualDispatch) {
        if (!Boolean.TRUE.equals(syncAlertProperties.getWebhookEnabled())) {
            return DeliveryAttemptResult.skippedResult(channelLabel + " 当前未启用", channelLabel);
        }
        if (isBlank(webhookUrl)) {
            return manualDispatch
                    ? DeliveryAttemptResult.skippedResult(channelLabel + " 未配置可用地址，已跳过外部投递", channelLabel)
                    : DeliveryAttemptResult.skippedResult(channelLabel + " 当前未配置地址", channelLabel);
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(resolveConnectTimeoutSeconds()))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(resolveReadTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildWebhookPayload(alert, actorId, actorRole)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String responseSummary = "HTTP " + response.statusCode();
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return DeliveryAttemptResult.successResult(responseSummary, truncate(webhookUrl, 256));
            }
            return DeliveryAttemptResult.failedResult(responseSummary + ": " + response.body(), truncate(webhookUrl, 256));
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return DeliveryAttemptResult.failedResult(exception.getClass().getSimpleName() + ": " + exception.getMessage(),
                    truncate(webhookUrl, 256));
        }
    }

    private void markSent(SyncGovernanceAlert alert, LocalDateTime finishedAt) {
        alert.setDeliveryStatus(SyncAlertDeliveryStatus.SENT.name());
        alert.setLastDeliveryAt(finishedAt);
        alert.setLastDeliveryError(null);
        alert.setNextDeliveryAttemptAt(null);
        alert.setDeadLetteredAt(null);
        alert.setDeadLetterReason(null);
        syncGovernanceAlertMapper.updateById(alert);
    }

    private void markUnsent(SyncGovernanceAlert alert, LocalDateTime now, String lastError, boolean allSkipped) {
        alert.setLastDeliveryAt(now);
        alert.setLastDeliveryError(truncate(lastError, 1000));
        if (allSkipped) {
            alert.setDeliveryStatus(SyncAlertDeliveryStatus.SKIPPED.name());
            alert.setNextDeliveryAttemptAt(null);
            alert.setDeadLetteredAt(null);
            alert.setDeadLetterReason(null);
        } else if (reachesDeadLetterThreshold(alert)) {
            alert.setDeliveryStatus(SyncAlertDeliveryStatus.DEAD_LETTER.name());
            alert.setDeadLetteredAt(now);
            alert.setDeadLetterReason(truncate(lastError, 1000));
            alert.setNextDeliveryAttemptAt(null);
        } else {
            alert.setDeliveryStatus(SyncAlertDeliveryStatus.FAILED.name());
            alert.setNextDeliveryAttemptAt(now.plusSeconds(resolveRetryBackoffSeconds()));
        }
        syncGovernanceAlertMapper.updateById(alert);
    }

    private List<SyncAlertChannel> resolveChannelChain() {
        String channelChain = syncAlertProperties.getChannelChain();
        if (isBlank(channelChain)) {
            return List.of(resolveDefaultChannel());
        }
        return Arrays.stream(channelChain.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(item -> SyncAlertChannel.valueOf(item.toUpperCase()))
                .toList();
    }

    private SyncAlertChannel resolveDefaultChannel() {
        if (isBlank(syncAlertProperties.getDefaultChannel())) {
            return SyncAlertChannel.NONE;
        }
        return SyncAlertChannel.valueOf(syncAlertProperties.getDefaultChannel().toUpperCase());
    }

    private int resolveConnectTimeoutSeconds() {
        return syncAlertProperties.getConnectTimeoutSeconds() == null
                ? 3
                : Math.max(1, syncAlertProperties.getConnectTimeoutSeconds());
    }

    private int resolveReadTimeoutSeconds() {
        return syncAlertProperties.getReadTimeoutSeconds() == null
                ? 5
                : Math.max(1, syncAlertProperties.getReadTimeoutSeconds());
    }

    private int resolveRetryBackoffSeconds() {
        return syncAlertProperties.getRetryBackoffSeconds() == null
                ? 300
                : Math.max(30, syncAlertProperties.getRetryBackoffSeconds());
    }

    private boolean reachesDeadLetterThreshold(SyncGovernanceAlert alert) {
        int maxRetryCount = syncAlertProperties.getMaxDeliveryRetryCount() == null
                ? 3
                : Math.max(1, syncAlertProperties.getMaxDeliveryRetryCount());
        return alert.getDeliveryAttemptCount() != null && alert.getDeliveryAttemptCount() >= maxRetryCount;
    }

    private void recordDeliveryAttempt(SyncGovernanceAlert alert,
                                       int attemptNo,
                                       SyncAlertChannel channel,
                                       DeliveryAttemptResult attemptResult,
                                       boolean manualDispatch,
                                       Long actorId,
                                       String actorRole,
                                       LocalDateTime startedAt,
                                       LocalDateTime finishedAt) {
        SyncAlertDeliveryRecord record = new SyncAlertDeliveryRecord();
        record.setTenantId(alert.getTenantId());
        record.setAlertId(alert.getId());
        record.setSyncTaskId(alert.getSyncTaskId());
        record.setAttemptNo(attemptNo);
        record.setChannel(channel.name());
        record.setDeliveryStatus(attemptResult.deliveryStatus().name());
        record.setTargetEndpoint(truncate(attemptResult.targetEndpoint(), 256));
        record.setManualDispatch(manualDispatch);
        record.setOperatorId(actorId);
        record.setOperatorRole(actorRole);
        record.setResponseSummary(truncate(attemptResult.responseSummary(), 1000));
        record.setErrorSummary(truncate(attemptResult.errorMessage(), 1000));
        record.setStartedAt(startedAt);
        record.setFinishedAt(finishedAt);
        syncAlertDeliveryRecordMapper.insert(record);
    }

    private String buildWebhookPayload(SyncGovernanceAlert alert, Long actorId, String actorRole) {
        StringBuilder builder = new StringBuilder("{");
        appendJsonField(builder, "alertId", alert.getId());
        appendJsonField(builder, "tenantId", alert.getTenantId());
        appendJsonField(builder, "syncTaskId", alert.getSyncTaskId());
        appendJsonField(builder, "alertType", alert.getAlertType());
        appendJsonField(builder, "severity", alert.getSeverity());
        appendJsonField(builder, "alertStatus", alert.getAlertStatus());
        appendJsonField(builder, "summary", alert.getSummary());
        appendJsonField(builder, "detail", alert.getDetail());
        appendJsonField(builder, "sourceResource", alert.getSourceResource());
        appendJsonField(builder, "triggeredByAction", alert.getTriggeredByAction());
        appendJsonField(builder, "occurrenceCount", alert.getOccurrenceCount());
        appendJsonField(builder, "actorId", actorId);
        appendJsonField(builder, "actorRole", actorRole);
        appendJsonField(builder, "sentAt", LocalDateTime.now());
        if (builder.charAt(builder.length() - 1) == ',') {
            builder.deleteCharAt(builder.length() - 1);
        }
        builder.append("}");
        return builder.toString();
    }

    private void appendJsonField(StringBuilder builder, String key, Object value) {
        builder.append("\"").append(escape(key)).append("\":");
        if (value == null) {
            builder.append("null,");
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            builder.append(value).append(",");
            return;
        }
        builder.append("\"").append(escape(String.valueOf(value))).append("\",");
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private record DeliveryAttemptResult(boolean success,
                                         SyncAlertDeliveryStatus deliveryStatus,
                                         String errorMessage,
                                         String responseSummary,
                                         String targetEndpoint) {

        private static DeliveryAttemptResult successResult(String responseSummary, String targetEndpoint) {
            return new DeliveryAttemptResult(true, SyncAlertDeliveryStatus.SENT, null, responseSummary, targetEndpoint);
        }

        private static DeliveryAttemptResult failedResult(String errorMessage, String targetEndpoint) {
            return new DeliveryAttemptResult(false, SyncAlertDeliveryStatus.FAILED, errorMessage, null, targetEndpoint);
        }

        private static DeliveryAttemptResult skippedResult(String responseSummary, String targetEndpoint) {
            return new DeliveryAttemptResult(false, SyncAlertDeliveryStatus.SKIPPED, null, responseSummary, targetEndpoint);
        }
    }
}

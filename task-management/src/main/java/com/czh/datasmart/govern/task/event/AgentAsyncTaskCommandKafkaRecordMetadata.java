/**
 * @Author : Cui
 * @Date: 2026/05/31 23:44
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandKafkaRecordMetadata.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.event;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Agent 异步工具命令 Kafka record 元数据。
 *
 * <p>这个对象只保存排障需要的低敏定位字段：topic、partition、offset、keyHash、timestamp 和 traceId。
 * 它刻意不保存 record key 原文、不保存 headers 全量内容，也不保存 payload，原因是这些字段可能携带租户、
 * 项目、业务对象、密钥引用或上游系统内部路径。生产诊断需要可定位，但不能把诊断系统变成敏感信息复制器。</p>
 *
 * <p>为什么 listener 需要把 ConsumerRecord 传进来：
 * 只拿 value 字符串时，一旦坏消息出现，运维只能知道“有一条坏消息”，却不知道它来自哪个 topic 分区和 offset。
 * 有了这些元数据，后续可以在 Kafka UI、命令行、DLQ 重放台或日志平台中精确定位消息。</p>
 *
 * @param topic Kafka topic 名称。
 * @param partition 分区号。
 * @param offset 分区内 offset。
 * @param keyHash record key 的短 hash，用于关联同一 key 的消息，同时避免暴露 key 原文。
 * @param timestampMillis Kafka record 时间戳毫秒值。
 * @param timestampType Kafka 时间戳类型，例如 CREATE_TIME 或 LOG_APPEND_TIME。
 * @param traceId 从常见 trace header 中提取的链路追踪 ID，未携带时为 UNKNOWN。
 */
public record AgentAsyncTaskCommandKafkaRecordMetadata(
        String topic,
        int partition,
        long offset,
        String keyHash,
        long timestampMillis,
        String timestampType,
        String traceId
) {

    private static final String UNKNOWN = "UNKNOWN";
    private static final int HASH_PREFIX_LENGTH = 16;
    private static final int TRACE_ID_MAX_LENGTH = 128;
    private static final List<String> TRACE_HEADER_NAMES = List.of(
            "traceId",
            "trace-id",
            "X-Trace-Id",
            "x-trace-id",
            "X-B3-TraceId",
            "traceparent"
    );

    /**
     * 构造一个空元数据对象。
     *
     * <p>单元测试、HTTP 联调入口或非 Kafka 触发场景没有 topic/partition/offset。
     * 使用统一的 empty 对象，可以避免业务代码到处写 null 判断。</p>
     */
    public static AgentAsyncTaskCommandKafkaRecordMetadata empty() {
        return new AgentAsyncTaskCommandKafkaRecordMetadata(UNKNOWN, -1, -1, UNKNOWN, -1, UNKNOWN, UNKNOWN);
    }

    /**
     * 从 Kafka ConsumerRecord 中提取低敏元数据。
     *
     * @param record Spring Kafka 传入的原始 record。
     * @return 用于日志、诊断快照和指标低基数标签的元数据。
     */
    public static AgentAsyncTaskCommandKafkaRecordMetadata from(ConsumerRecord<String, String> record) {
        if (record == null) {
            return empty();
        }
        return new AgentAsyncTaskCommandKafkaRecordMetadata(
                safe(record.topic()),
                record.partition(),
                record.offset(),
                hashKey(record.key()),
                record.timestamp(),
                record.timestampType() == null ? UNKNOWN : record.timestampType().name(),
                extractTraceId(record)
        );
    }

    /**
     * 生成适合日志和诊断使用的简短定位字符串。
     *
     * <p>该字符串不会包含 key 原文和 payload，可以安全进入普通应用日志。</p>
     */
    public String location() {
        return "topic=" + topic + ", partition=" + partition + ", offset=" + offset
                + ", keyHash=" + keyHash + ", traceId=" + traceId;
    }

    private static String extractTraceId(ConsumerRecord<String, String> record) {
        for (String headerName : TRACE_HEADER_NAMES) {
            Header header = record.headers() == null ? null : record.headers().lastHeader(headerName);
            String value = headerValue(header);
            if (!UNKNOWN.equals(value)) {
                return value;
            }
        }
        return UNKNOWN;
    }

    private static String headerValue(Header header) {
        if (header == null || header.value() == null || header.value().length == 0) {
            return UNKNOWN;
        }
        String value = new String(header.value(), StandardCharsets.UTF_8).trim();
        if (value.isBlank()) {
            return UNKNOWN;
        }
        return value.length() > TRACE_ID_MAX_LENGTH ? value.substring(0, TRACE_ID_MAX_LENGTH) : value;
    }

    private static String hashKey(String key) {
        if (key == null || key.isBlank()) {
            return UNKNOWN;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, HASH_PREFIX_LENGTH);
        } catch (NoSuchAlgorithmException exception) {
            return "HASH_UNAVAILABLE";
        }
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value.trim();
    }
}

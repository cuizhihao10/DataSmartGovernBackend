/**
 * @Author : Cui
 * @Date: 2026/06/06 02:37
 * @Description DataSmart Govern Backend - AgentExternalProtocolDiscoveryEventDisplayBuilder.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventDisplayView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 外部协议发现 runtime event 的展示解释器。
 *
 * <p>发现事件本身是机器友好的投影：protocol、method、count、endpointKind 等。
 * 前端 timeline 更需要一句话解释“发生了什么”和“是否需要行动”。本类把 MCP tools/list 与 A2A Agent Card
 * 发现事件转成可读展示信息，但仍然只使用已经脱敏的低敏 attributes。</p>
 */
final class AgentExternalProtocolDiscoveryEventDisplayBuilder {

    private AgentExternalProtocolDiscoveryEventDisplayBuilder() {
    }

    static AgentRuntimeEventDisplayView build(AgentRuntimeEventProjectionRecord record) {
        Map<String, Object> attributes = record.attributes() == null ? Map.of() : record.attributes();
        String protocol = text(attributes, "protocol", "UNKNOWN");
        String method = text(attributes, "discoveryMethod", "discovery");
        boolean callEnabled = booleanValue(attributes.get("callEnabled"));
        int returnedCount = intValue(attributes.get("returnedCount"));
        int totalCount = intValue(attributes.get("totalCount"));
        int readySkillCount = intValue(attributes.get("readySkillCount"));
        boolean nextCursorPresent = booleanValue(attributes.get("nextCursorPresent"));

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("protocol", protocol);
        metrics.put("discoveryMethod", method);
        putIfPositive(metrics, "returnedCount", returnedCount);
        putIfPositive(metrics, "totalCount", totalCount);
        putIfPositive(metrics, "readySkillCount", readySkillCount);
        metrics.put("nextCursorPresent", nextCursorPresent);
        metrics.put("endpointKind", text(attributes, "endpointKind", "MANAGEMENT"));
        metrics.put("payloadPolicy", text(attributes, "payloadPolicy", ""));

        return new AgentRuntimeEventDisplayView(
                "EXTERNAL_PROTOCOL_DISCOVERY",
                protocol + " 能力发现已完成",
                summary(protocol, returnedCount, totalCount, readySkillCount, nextCursorPresent),
                callEnabled ? "DISCOVERY_WITH_CALL_ENABLED" : "DISCOVERY_ONLY",
                "protocol-discovery",
                callEnabled,
                "APPEND_TO_TIMELINE_AND_ALLOW_ACK_CURSOR",
                recommendedActions(protocol, callEnabled, nextCursorPresent),
                Map.copyOf(metrics)
        );
    }

    private static String summary(String protocol,
                                  int returnedCount,
                                  int totalCount,
                                  int readySkillCount,
                                  boolean nextCursorPresent) {
        if ("MCP".equalsIgnoreCase(protocol)) {
            return "MCP tools/list 返回工具 " + returnedCount + " 个，总计 " + totalCount
                    + " 个，仍有下一页=" + nextCursorPresent + "。";
        }
        if ("A2A".equalsIgnoreCase(protocol)) {
            return "A2A Agent Card 公开 READY Skill " + readySkillCount + " 个。";
        }
        return "外部协议发现事件已写入 timeline。";
    }

    private static List<String> recommendedActions(String protocol, boolean callEnabled, boolean nextCursorPresent) {
        if (callEnabled) {
            return List.of("发现事件显示真实调用已启用，请确认 permission-admin、confirmation/outbox、限流和 worker pre-check 均已接入。");
        }
        if ("MCP".equalsIgnoreCase(protocol) && nextCursorPresent) {
            return List.of("如外部 Agent 需要完整工具目录，应按 nextCursor 继续分页读取；不要一次性放大 limit 绕过服务端保护。");
        }
        return List.of("该事件仅表示能力发现完成，不代表工具执行、A2A task 创建或审批通过。");
    }

    private static void putIfPositive(Map<String, Object> metrics, String key, int value) {
        if (value > 0) {
            metrics.put(key, value);
        }
    }

    private static String text(Map<String, Object> attributes, String key, String fallback) {
        Object value = attributes.get(key);
        return value == null || Objects.toString(value).isBlank() ? fallback : Objects.toString(value);
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        return 0;
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean bool && bool;
    }
}

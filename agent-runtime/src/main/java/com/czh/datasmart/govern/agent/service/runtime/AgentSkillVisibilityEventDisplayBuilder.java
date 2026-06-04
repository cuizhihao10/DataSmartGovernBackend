/**
 * @Author : Cui
 * @Date: 2026/06/04 19:16
 * @Description DataSmart Govern Backend - AgentSkillVisibilityEventDisplayBuilder.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentRuntimeEventDisplayView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Skill 可见性 runtime event 的展示解释构建器。
 *
 * <p>该类从 `AgentRuntimeEventDisplaySupport` 中拆出，是为了遵守单文件 500 行治理要求，也为了让不同
 * 事件类型的展示语义各自内聚。Skill 可见性事件不是工具执行、不是 DAG dry-run、也不是普通系统事件；
 * 它表达的是“当前会话能力暴露边界”，因此应该有专门的标题、状态、低敏指标和推荐动作。</p>
 *
 * <p>本 builder 只读取已经进入 Java 投影层的 attributes，不重新调用 permission-admin，也不重新计算
 * Skill 准入。展示层只能解释事实，不能制造新的授权事实，这是 Agent 控制面必须长期遵守的边界。</p>
 */
final class AgentSkillVisibilityEventDisplayBuilder {

    private static final String REPLAY_POLICY_APPEND_AND_ACK = "APPEND_TO_TIMELINE_AND_ALLOW_ACK_CURSOR";

    private AgentSkillVisibilityEventDisplayBuilder() {
    }

    static AgentRuntimeEventDisplayView build(AgentRuntimeEventProjectionRecord record) {
        Map<String, Object> attributes = safeAttributes(record);
        if (isBasicMasked(attributes)) {
            return new AgentRuntimeEventDisplayView(
                    "SKILL_VISIBILITY",
                    "会话 Skill 可见性快照已记录",
                    "当前角色只能查看脱敏后的 Skill 可见性进度，可联系项目负责人或审计员查看低敏聚合摘要。",
                    "SUMMARY_MASKED",
                    "skill",
                    false,
                    REPLAY_POLICY_APPEND_AND_ACK,
                    List.of("如需排查 Skill 被隐藏的原因，请使用具备项目或审计数据范围的账号查看详情。"),
                    Map.of("detailsMasked", true)
            );
        }

        int visibleCount = intAttribute(attributes, "visibleSkillCount");
        int hiddenCount = intAttribute(attributes, "hiddenSkillCount");
        int conditionalCount = intAttribute(attributes, "conditionalVisibleSkillCount");
        String permissionFactSource = textAttribute(attributes, "permissionFactSource");
        String manifestBindingStatus = textAttribute(attributes, "manifestBindingStatus");
        boolean available = booleanAttribute(attributes, "available");

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("available", available);
        metrics.put("visibleSkillCount", visibleCount);
        metrics.put("hiddenSkillCount", hiddenCount);
        metrics.put("conditionalVisibleSkillCount", conditionalCount);
        putIfPresent(metrics, "permissionFactSource", permissionFactSource);
        putIfPresent(metrics, "actorRoleSource", textAttribute(attributes, "actorRoleSource"));
        metrics.put("visibleRiskLevelCount", mapSize(attributes.get("visibleRiskLevelCounts")));
        metrics.put("visibleDomainCount", mapSize(attributes.get("visibleDomainCounts")));
        metrics.put("hiddenAdmissionStatusCount", mapSize(attributes.get("hiddenAdmissionStatusCounts")));
        putIfPresent(metrics, "manifestBindingStatus", manifestBindingStatus);
        putIfPresent(metrics, "manifestSource", textAttribute(attributes, "manifestSource"));
        metrics.put("manifestFingerprintPresent", !textAttribute(attributes, "manifestFingerprint").isBlank());
        metrics.put("manifestReadySkillCount", intAttribute(attributes, "manifestReadySkillCount"));

        return new AgentRuntimeEventDisplayView(
                "SKILL_VISIBILITY",
                title(available, hiddenCount, conditionalCount),
                summary(visibleCount, hiddenCount, conditionalCount, permissionFactSource),
                available ? "AVAILABLE" : "NEEDS_GOVERNANCE",
                "skill",
                !available || hiddenCount > 0 || conditionalCount > 0,
                REPLAY_POLICY_APPEND_AND_ACK,
                recommendedActions(hiddenCount, conditionalCount, permissionFactSource, manifestBindingStatus),
                Collections.unmodifiableMap(metrics)
        );
    }

    private static String title(boolean available, int hiddenCount, int conditionalCount) {
        if (hiddenCount > 0) {
            return "会话存在被隐藏的 Agent Skill";
        }
        if (conditionalCount > 0) {
            return "会话存在条件性可见的 Agent Skill";
        }
        return available ? "会话 Skill 可见性快照已记录" : "会话 Skill 可见性需要治理确认";
    }

    private static String summary(int visibleCount, int hiddenCount, int conditionalCount, String permissionFactSource) {
        String source = permissionFactSource == null || permissionFactSource.isBlank() ? "unknown" : permissionFactSource;
        return "可见 Skill " + visibleCount + " 个，隐藏 Skill " + hiddenCount
                + " 个，条件性可见 " + conditionalCount + " 个，权限事实来源：" + source + "。";
    }

    private static List<String> recommendedActions(
            int hiddenCount,
            int conditionalCount,
            String permissionFactSource,
            String manifestBindingStatus) {
        List<String> actions = new ArrayList<>();
        if (hiddenCount > 0) {
            actions.add("查看 hiddenAdmissionStatusCounts，确认是缺权限、角色不足、租户开关关闭还是风险策略阻断。");
        }
        if (conditionalCount > 0) {
            actions.add("条件性可见通常表示缺少可信控制面事实，生产环境应由 gateway 注入 trustedControlPlane.skillAdmission。");
        }
        if ("legacy-request-variables".equals(normalize(permissionFactSource))) {
            actions.add("当前仍使用旧式请求变量作为权限事实来源，应迁移到 gateway 签名的 trustedControlPlane。");
        }
        String normalizedManifestStatus = normalize(manifestBindingStatus);
        if ("local_default_or_fallback".equals(normalizedManifestStatus)
                || "unbound_not_configured".equals(normalizedManifestStatus)) {
            actions.add("当前会话未绑定远端 Skill Manifest 指纹；生产环境应接入 Java 发布事实源以支持灰度和审计回放。");
        } else if ("remote_ready_without_fingerprint".equals(normalizedManifestStatus)) {
            actions.add("远端 Skill Manifest 可用但缺少 contentFingerprint，应补齐发布指纹以定位能力目录版本。");
        }
        if (actions.isEmpty()) {
            actions.add("可将该快照与 Skill Manifest 指纹、工具预算和模型路由一起用于会话治理排障。");
        }
        return List.copyOf(actions);
    }

    private static Map<String, Object> safeAttributes(AgentRuntimeEventProjectionRecord record) {
        if (record.attributes() == null || record.attributes().isEmpty()) {
            return Map.of();
        }
        return record.attributes();
    }

    private static boolean isBasicMasked(Map<String, Object> attributes) {
        Object visibilityLevel = attributes.get(AgentRuntimeEventVisibilitySupport.VISIBILITY_LEVEL_ATTRIBUTE);
        return "BASIC".equals(Objects.toString(visibilityLevel, ""));
    }

    private static int intAttribute(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (value instanceof String stringValue) {
            try {
                return Math.max(0, Integer.parseInt(stringValue));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static boolean booleanAttribute(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value == null) {
            return false;
        }
        return switch (Objects.toString(value, "").trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on", "enabled" -> true;
            default -> false;
        };
    }

    private static int mapSize(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        return 0;
    }

    private static String textAttribute(Map<String, Object> attributes, String key) {
        Object value = attributes == null ? null : attributes.get(key);
        return value == null ? "" : Objects.toString(value, "").trim();
    }

    private static void putIfPresent(Map<String, Object> values, String key, String value) {
        if (value != null && !value.isBlank()) {
            values.put(key, value);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}

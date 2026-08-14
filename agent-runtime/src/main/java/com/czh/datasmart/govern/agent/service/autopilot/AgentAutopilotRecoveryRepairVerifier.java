/**
 * @Author : Cui
 * @Date: 2026/08/14
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryRepairVerifier.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 在 Java 控制面复核 Python 提出的低风险修复参数和跨语言动作指纹。
 *
 * <p>模型可以根据日志、指标、历史事故和 runbook 选择动作，但不能决定动作参数的解释方式。本验证器
 * 对每种自动修复声明唯一参数合同，并把事件、错误指纹、当前 execution、动作和规范参数共同计算为
 * SHA-256。只有该结果与 Python 候选中的 {@code repairFingerprint} 完全相同，候选才可能进入 data-sync。</p>
 *
 * <p>验证器不访问数据库、不执行修复，也不授予权限。data-sync 仍会第三次读取持久 case、授权、任务和
 * 元数据并复算相同指纹。这种双重确定性校验用于发现模型输出漂移、序列化差异或服务合同被意外放宽。</p>
 */
@Component
public class AgentAutopilotRecoveryRepairVerifier {

    private static final Set<String> ACTIONS = Set.of(
            "ROLLBACK_EXECUTION_POLICY",
            "TUNE_EXECUTION_POLICY",
            "REFRESH_METADATA",
            "RESUME_FROM_CHECKPOINT",
            "REPLAY_FAILED_SHARDS",
            "REPAIR_FIELD_MAPPING");
    private static final List<String> TUNING_FIELDS = List.of(
            "maxChannel", "readBatchSize", "writeBatchSize", "timeoutSeconds");

    /**
     * 判断动作是否属于本验证器管理的受治理修复目录。
     *
     * @param action Python 候选动作，可为空或使用连字符
     * @return 动作是否属于六类固定修复之一
     */
    public boolean supports(String action) {
        return ACTIONS.contains(code(action));
    }

    /**
     * 验证动作参数，并返回可安全传给 data-sync 的不可修改规范副本。
     *
     * <p>固定动作必须精确匹配单一参数；调参动作只接受四个正整数，并受绝对上限限制。这里的上限只做
     * 第一层防护，data-sync 还会把建议值与失败 execution 的真实策略快照比较，确保并发和批量不增加、
     * timeout 最多增加到原值两倍且不超过 3600 秒。</p>
     *
     * @param trigger 已验证的恢复事件，用于绑定事件、错误和 execution
     * @param response Python 返回的低敏候选
     * @return 按合同规范化后的修复参数
     * @throws PlatformBusinessException 参数、动作或指纹不符合确定性合同时失败关闭
     */
    public Map<String, Object> verify(
            AgentAutopilotVerifiedRecoveryTrigger trigger,
            AgentAutopilotRecoveryPlanResponse response) {
        if (trigger == null || trigger.event() == null || response == null || !supports(response.action())) {
            throw invalid("AUTOPILOT_REPAIR_PARAMETERS_INVALID");
        }
        String action = code(response.action());
        Map<String, Object> parameters = response.repairParameters() == null
                ? Map.of() : response.repairParameters();
        Map<String, Object> normalized = switch (action) {
            case "ROLLBACK_EXECUTION_POLICY" -> fixed(
                    parameters, Map.of("rollbackTarget", "LAST_SUCCESSFUL_EXECUTION"));
            case "REFRESH_METADATA" -> fixed(parameters, Map.of("forceRefresh", true));
            case "RESUME_FROM_CHECKPOINT" -> fixed(
                    parameters, Map.of("checkpointSelector", "LATEST_PERSISTED"));
            case "REPLAY_FAILED_SHARDS" -> fixed(parameters, Map.of(
                    "objectState", "FAILED", "workUnitType", "PARTITION_SHARD"));
            case "REPAIR_FIELD_MAPPING" -> fixed(
                    parameters, Map.of("repairMode", "METADATA_PROVEN_SAFE"));
            case "TUNE_EXECUTION_POLICY" -> tuning(parameters);
            default -> throw invalid("AUTOPILOT_REPAIR_PARAMETERS_INVALID");
        };
        String canonicalParameters = new TreeMap<>(normalized).entrySet().stream()
                .map(entry -> entry.getKey() + "=" + canonicalValue(entry.getValue()))
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        AgentAutopilotRecoveryTriggerEvent event = trigger.event();
        String expectedFingerprint = sha256(String.join("|",
                text(event.eventId()), text(event.errorFingerprint()), text(event.currentExecutionId()),
                action, canonicalParameters));
        if (!Objects.equals(expectedFingerprint, lower(response.repairFingerprint()))) {
            throw invalid("AUTOPILOT_REPAIR_FINGERPRINT_INVALID");
        }
        return Map.copyOf(normalized);
    }

    /** 固定动作只允许与服务端目录完全相同的键和值。 */
    private Map<String, Object> fixed(Map<String, Object> supplied, Map<String, Object> expected) {
        if (!Objects.equals(supplied, expected)) {
            throw invalid("AUTOPILOT_REPAIR_PARAMETERS_INVALID");
        }
        return expected;
    }

    /** 将调参值规范为整数，并拒绝未知字段、小数、零值和超过平台硬上限的值。 */
    private Map<String, Object> tuning(Map<String, Object> supplied) {
        if (supplied.isEmpty() || supplied.keySet().stream().anyMatch(key -> !TUNING_FIELDS.contains(key))) {
            throw invalid("AUTOPILOT_REPAIR_PARAMETERS_INVALID");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (String field : TUNING_FIELDS) {
            if (!supplied.containsKey(field)) {
                continue;
            }
            Object raw = supplied.get(field);
            if (!(raw instanceof Number number)) {
                throw invalid("AUTOPILOT_REPAIR_PARAMETERS_INVALID");
            }
            int value = number.intValue();
            int maximum = "maxChannel".equals(field) ? 64
                    : "timeoutSeconds".equals(field) ? 3_600 : 100_000;
            if (value <= 0 || value > maximum || number.doubleValue() != value) {
                throw invalid("AUTOPILOT_REPAIR_PARAMETERS_INVALID");
            }
            normalized.put(field, value);
        }
        return normalized;
    }

    /** 将布尔值按小写文本输出，使 Java、Python 和 data-sync 的指纹材料完全一致。 */
    private String canonicalValue(Object value) {
        return value instanceof Boolean bool ? String.valueOf(bool).toLowerCase(Locale.ROOT) : text(value);
    }

    private String code(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private String lower(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 对已规范化的 UTF-8 材料计算小写十六进制 SHA-256。 */
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 不支持 SHA-256", exception);
        }
    }

    private PlatformBusinessException invalid(String reasonCode) {
        return new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, reasonCode);
    }
}

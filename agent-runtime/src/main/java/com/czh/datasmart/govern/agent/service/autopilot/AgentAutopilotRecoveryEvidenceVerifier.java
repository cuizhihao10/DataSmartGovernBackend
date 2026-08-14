/**
 * @Author : Cui
 * @Date: 2026/08/11 20:05
 * @Description DataSmart Govern Backend - AgentAutopilotRecoveryEvidenceVerifier.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.autopilot;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 验证 Python Recovery 候选携带的诊断与检索证据摘要。
 *
 * <p>模型不能用 ``queryDigest`` 冒充 ``evidenceDigest``，也不能只写 ``evidenceAvailable=true``。
 * 本服务会重算诊断 evidence records 的 canonical JSON digest、RAG evidence ID digest，检查来源、时间、
 * 数量和 tenant/project/task/execution scope。验证只处理低敏元数据，不需要读取文档或日志正文。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentAutopilotRecoveryEvidenceVerifier {

    private static final String RESPONSE_SCHEMA = "datasmart.autopilot.recovery-candidate.v1";
    private static final Set<String> AUTHORITATIVE_DIAGNOSTIC_SOURCES = Set.of(
            "STRUCTURED_API", "EXECUTION_LOG", "MONITORING_API");

    private final ObjectMapper objectMapper;

    /**
     * 对一份可执行候选执行完整的证据、范围和绑定校验。
     *
     * <p>输入是已经完成会话/授权验证的触发器和 Python 候选响应；输出只有 {@code true}，表示所有证据都可被
     * Java 独立复算。方法不写数据库、不调用 RAG 或 Python，也不把证据转换为执行许可。它检查合同版本、
     * event 与错误指纹绑定、置信度、租户/项目/任务/execution/workspace 范围、诊断摘要和可选检索摘要。</p>
     *
     * <p>范围不一致会以 {@code FORBIDDEN} 拒绝，防止模型把证据带到其他租户或资源；摘要、来源、时间或数量
     * 不一致会以业务状态冲突拒绝。验证没有缓存或去重副作用，对相同材料可重复执行；不过新鲜度校验依赖当前
     * 时间，过期证据会在后续调用被拒绝。失败时始终抛出稳定异常而非返回含糊的 {@code false}，调用方因此
     * 不会把“证据无效”误当作“证据不足但可自动执行”。</p>
     *
     * @param trigger 已验证 session、run、授权和恢复时限的可信触发器
     * @param response Python 返回的可执行候选
     * @return 始终为 {@code true}，表示候选证据满足本层要求
     * @throws PlatformBusinessException 当候选、范围、摘要、来源或时间不符合证据合同或权限范围时
     */
    public boolean verify(AgentAutopilotVerifiedRecoveryTrigger trigger,
                          AgentAutopilotRecoveryPlanResponse response) {
        if (trigger == null || response == null || !RESPONSE_SCHEMA.equals(response.schemaVersion())
                || !"CANDIDATE_READY".equals(code(response.status()))) {
            throw conflict("AUTOPILOT_RECOVERY_CANDIDATE_INVALID");
        }
        AgentAutopilotRecoveryTriggerEvent event = trigger.event();
        if (!event.eventId().equals(response.eventId())
                || !fingerprint(response.repairFingerprint())
                || !event.errorFingerprint().equalsIgnoreCase(response.errorFingerprint())
                || response.confidence() < 0.0d || response.confidence() > 1.0d
                || !response.evidenceAvailable()) {
            throw conflict("AUTOPILOT_RECOVERY_CANDIDATE_BINDING_INVALID");
        }
        verifyScope(trigger, response.evidenceScope());
        Set<String> diagnosticSources = verifyDiagnosticAudit(trigger, response.evidenceAudit());
        if (diagnosticSources.stream().noneMatch(AUTHORITATIVE_DIAGNOSTIC_SOURCES::contains)) {
            throw conflict("AUTOPILOT_DIAGNOSTIC_SOURCE_NOT_AUTHORITATIVE");
        }
        if ("SEARCH".equals(code(response.retrievalDecision()))) {
            verifyRetrievalAudit(trigger, response.retrievalAudit());
            if (!diagnosticSources.contains("RAG")) {
                throw conflict("AUTOPILOT_SEARCH_RESULT_NOT_BOUND_TO_RECOVERY_EVIDENCE");
            }
        }
        return true;
    }

    /**
     * 验证 Python 提供的证据范围没有扩大 Java 已确认的资源边界。
     *
     * <p>输入为可信触发器及其候选 {@code evidenceScope}，没有返回值和副作用。它逐项比较 tenant、project、
     * task、execution 和 workspace，而不是只信任某一个相关标识；这些字段共同构成证据可用于当前恢复的权限
     * 证明。该比较不修改 scope，也不负责 Kafka 去重。</p>
     *
     * @param trigger 已由持久化事实验证的恢复触发器
     * @param scope Python 声明的证据资源范围
     * @throws PlatformBusinessException 当任一范围字段不匹配时，以 {@code FORBIDDEN} 阻止跨范围使用证据
     */
    private void verifyScope(AgentAutopilotVerifiedRecoveryTrigger trigger, Map<String, Object> scope) {
        AgentAutopilotRecoveryTriggerEvent event = trigger.event();
        if (!String.valueOf(event.tenantId()).equals(text(scope.get("tenantId")))
                || !String.valueOf(event.projectId()).equals(text(scope.get("projectId")))
                || !String.valueOf(event.syncTaskId()).equals(text(scope.get("taskId")))
                || !String.valueOf(event.currentExecutionId()).equals(text(scope.get("executionId")))
                || !String.valueOf(trigger.session().getWorkspaceKey()).equals(text(scope.get("workspaceKey")))) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "AUTOPILOT_RECOVERY_EVIDENCE_SCOPE_MISMATCH");
        }
    }

    /**
     * 重算诊断 evidence records 的规范 JSON 摘要，并返回已验证的来源类型集合。
     *
     * <p>输入是触发器和 Python 的诊断审计 Map，输出是来源类型集合，供调用方判断是否存在权威诊断来源。
     * 方法只在内存中排序和哈希，不读取证据正文、不发起网络调用，也不修改审计数据。它验证记录数量、记录 ID
     * 唯一性、查询摘要绑定、检索时间、声明来源和 {@code evidenceDigest}，从而把“模型声称看过证据”变成
     * Java 可复算的完整性证据。</p>
     *
     * <p>相同审计材料可重复得到等价来源集合；时间窗口除外，过期材料会被拒绝。该方法不授予权限，任何格式、
     * 摘要或来源冲突都会抛出稳定业务状态冲突，避免不完整证据进入自动恢复策略。</p>
     *
     * @param trigger 已验证的恢复触发器，用于范围和时间边界
     * @param audit Python 返回的诊断审计摘要
     * @return 已规范化且通过单条记录校验的来源类型集合
     * @throws PlatformBusinessException 当诊断证据无法按合同复算或绑定时
     */
    private Set<String> verifyDiagnosticAudit(AgentAutopilotVerifiedRecoveryTrigger trigger,
                                              Map<String, Object> audit) {
        int count = positiveInt(audit.get("evidenceCount"), "AUTOPILOT_EVIDENCE_COUNT_INVALID");
        List<Map<String, Object>> records = records(audit.get("evidenceRecords"), count);
        String expectedDigest = "sha256:" + sha256(writeCanonical(records));
        String actualDigest = text(audit.get("evidenceDigest"));
        if (!digest(actualDigest) || !constantEquals(expectedDigest, actualDigest)) {
            throw conflict("AUTOPILOT_EVIDENCE_DIGEST_MISMATCH");
        }
        String queryDigest = text(audit.get("queryDigest"));
        if (!digest(queryDigest) || queryDigest.equalsIgnoreCase(actualDigest)) {
            throw conflict("AUTOPILOT_EVIDENCE_QUERY_DIGEST_INVALID");
        }
        verifyFreshTime(trigger, text(audit.get("retrievedAt")));

        Set<String> evidenceIds = new HashSet<>();
        Set<String> sourceTypes = new HashSet<>();
        for (Map<String, Object> record : records) {
            String evidenceId = text(record.get("evidenceId"));
            String sourceType = code(record.get("sourceType"));
            if (evidenceId.isBlank() || !evidenceIds.add(evidenceId) || sourceType.isBlank()) {
                throw conflict("AUTOPILOT_EVIDENCE_RECORD_INVALID");
            }
            sourceTypes.add(sourceType);
            verifyEvidenceSource(record);
            verifyFreshTime(trigger, text(record.get("retrievedAt")));
            verifyEvidenceConfidence(record);
            Object recordQueryDigest = record.get("queryDigest");
            if (recordQueryDigest != null && !queryDigest.equalsIgnoreCase(text(recordQueryDigest))) {
                throw conflict("AUTOPILOT_EVIDENCE_QUERY_BINDING_MISMATCH");
            }
        }
        if (!declaredSourceTypes(audit).containsAll(sourceTypes)) {
            throw conflict("AUTOPILOT_EVIDENCE_SOURCE_TYPES_MISMATCH");
        }
        return sourceTypes;
    }

    /**
     * 校验 RAG 检索摘要，并用按顺序连接的 evidence ID 重新计算检索摘要。
     *
     * <p>输入是可信触发器和 Python 的 retrieval 审计 Map，没有返回值；成功仅表示检索证据可以与当前范围
     * 绑定，不能单独授权恢复动作。该纯校验会验证记录数量、每个 evidence ID、摘要、tenant/project/
     * workspace 范围和新鲜时间，不读取文档正文或模型回答，因此不会引入额外数据访问副作用。</p>
     *
     * <p>证据 ID 的原有顺序是摘要材料的一部分，调用方不能排序或去重后再比较。重复验证不会修改状态；
     * 摘要、范围或时间错误一律抛出业务状态冲突或权限拒绝，使检索结果不能被移植到另一条恢复请求。</p>
     *
     * @param trigger 已验证的恢复触发器
     * @param audit Python 返回的检索审计摘要
     * @throws PlatformBusinessException 当检索摘要、范围、数量或时间不满足合同要求时
     */
    private void verifyRetrievalAudit(AgentAutopilotVerifiedRecoveryTrigger trigger,
                                      Map<String, Object> audit) {
        int count = positiveInt(audit.get("evidenceCount"), "AUTOPILOT_RETRIEVAL_EVIDENCE_MISSING");
        List<Map<String, Object>> records = records(audit.get("evidenceRecords"), count);
        List<String> evidenceIds = records.stream()
                .map(record -> text(record.get("evidenceId")))
                .toList();
        if (evidenceIds.stream().anyMatch(String::isBlank)) {
            throw conflict("AUTOPILOT_RETRIEVAL_EVIDENCE_ID_INVALID");
        }
        String expectedDigest = "sha256:" + sha256(String.join("|", evidenceIds));
        String actualDigest = text(audit.get("evidenceDigest"));
        if (!constantEquals(expectedDigest, actualDigest)) {
            throw conflict("AUTOPILOT_RETRIEVAL_DIGEST_MISMATCH");
        }
        Object rawScope = audit.get("scope");
        if (!(rawScope instanceof Map<?, ?> scope)) {
            throw conflict("AUTOPILOT_RETRIEVAL_SCOPE_MISSING");
        }
        if (!String.valueOf(trigger.event().tenantId()).equals(text(scope.get("tenantId")))
                || !String.valueOf(trigger.event().projectId()).equals(text(scope.get("projectId")))
                || !String.valueOf(trigger.session().getWorkspaceKey()).equals(text(scope.get("workspaceKey")))) {
            throw new PlatformBusinessException(PlatformErrorCode.FORBIDDEN,
                    "AUTOPILOT_RETRIEVAL_SCOPE_MISMATCH");
        }
        verifyFreshTime(trigger, text(audit.get("retrievedAt")));
        for (Map<String, Object> record : records) {
            if (code(record.get("sourceType")).isBlank()) {
                throw conflict("AUTOPILOT_RETRIEVAL_SOURCE_TYPE_INVALID");
            }
            verifyEvidenceSource(record);
            verifyFreshTime(trigger, text(record.get("retrievedAt")));
            verifyEvidenceConfidence(record);
        }
    }

    /**
     * 校验证据记录携带了可追溯的低敏来源引用。
     *
     * <p>诊断证据和 RAG 证据统一使用 {@code sourceRef}。RAG 可以继续附带兼容字段
     * {@code sourceUri}，但兼容字段不能替代统一合同。本方法只检查引用是否存在，
     * 不会读取引用指向的日志或文档正文。</p>
     *
     * @param record 已完成容器类型校验的单条证据记录
     * @throws PlatformBusinessException 当来源引用缺失时
     */
    private void verifyEvidenceSource(Map<String, Object> record) {
        if (text(record.get("sourceRef")).isBlank()) {
            throw conflict("AUTOPILOT_EVIDENCE_SOURCE_REFERENCE_MISSING");
        }
    }

    /**
     * 校验逐条证据可信度及其校准依据。
     *
     * <p>可信度必须是 0 到 1 的有限数值，且必须说明它来自平台事实、执行日志、
     * 历史事故还是混合检索评分。这样可以防止模型只给出一个无法解释的自评分。</p>
     *
     * @param record 已完成容器类型校验的单条证据记录
     * @throws PlatformBusinessException 当可信度越界、非数值或没有校准依据时
     */
    private void verifyEvidenceConfidence(Map<String, Object> record) {
        Object value = record.get("confidence");
        if (!(value instanceof Number number)
                || !Double.isFinite(number.doubleValue())
                || number.doubleValue() < 0.0d
                || number.doubleValue() > 1.0d
                || text(record.get("confidenceBasis")).isBlank()) {
            throw conflict("AUTOPILOT_EVIDENCE_CONFIDENCE_INVALID");
        }
    }

    /**
     * 将不可信的 {@code evidenceRecords} 值转换为字符串键 Map 列表，并验证声明数量。
     *
     * <p>输入可以是任意反序列化对象，输出是不可修改的 Map 列表；每个顶层键都被安全转换为字符串。
     * 该方法不访问外部证据、不修改原始集合，也不决定权限。它只建立后续摘要复算所需的标准容器，使数量不一致、
     * 非集合或非 Map 的记录立即成为合同冲突，而不是被静默跳过。</p>
     *
     * <p>同一集合顺序会原样保留，因为诊断摘要材料依赖该顺序。方法本身没有持久化或去重副作用，重复调用只会
     * 重新构造等价的只读视图。</p>
     *
     * @param value Python 审计中原始的记录集合
     * @param expectedCount 审计声明的精确记录数量
     * @return 可用于摘要校验的不可修改记录列表
     * @throws PlatformBusinessException 当记录不是指定数量的 Map 集合时
     */
    private List<Map<String, Object>> records(Object value, int expectedCount) {
        if (!(value instanceof Collection<?> collection) || collection.size() != expectedCount) {
            throw conflict("AUTOPILOT_EVIDENCE_RECORD_COUNT_MISMATCH");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : collection) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw conflict("AUTOPILOT_EVIDENCE_RECORD_INVALID");
            }
            Map<String, Object> record = new LinkedHashMap<>();
            raw.forEach((key, recordValue) -> record.put(String.valueOf(key), recordValue));
            result.add(record);
        }
        return List.copyOf(result);
    }

    /**
     * 读取审计中声明的来源类型，兼容数组字段和计数 Map 字段。
     *
     * <p>输入是诊断审计 Map，输出为规范化后的来源类型集合。该纯读取不修改审计内容、不调用外部服务，也不以
     * 声明本身授予证据可信度；调用方还会用返回集合与每条记录实际声明的来源进行包含关系校验。相同输入可
     * 重复得到等价集合，空或未知结构返回空集合并最终触发缺失来源的证据拒绝。</p>
     *
     * @param audit Python 返回的诊断审计摘要
     * @return 已规范化的声明来源类型；未声明时为空集合
     */
    private Set<String> declaredSourceTypes(Map<String, Object> audit) {
        Object value = audit.get("sourceTypes");
        if (!(value instanceof Collection<?>)) {
            value = audit.get("evidenceSourceTypes");
        }
        if (value instanceof Collection<?> collection) {
            Set<String> result = new HashSet<>();
            collection.forEach(item -> result.add(code(item)));
            return result;
        }
        Object counts = audit.get("sourceTypeCounts");
        if (counts instanceof Map<?, ?> map) {
            Set<String> result = new HashSet<>();
            map.keySet().forEach(item -> result.add(code(item)));
            return result;
        }
        return Set.of();
    }

    /**
     * 验证一条证据时间位于本轮恢复允许的新鲜度窗口内，并早于授权 deadline。
     *
     * <p>输入是可信触发器和 ISO-8601 时间文本，没有返回值或外部副作用。时间必须不早于触发前五分钟、
     * 不晚于当前时间后五分钟，并且不能晚于授权截止时间；这既防止旧证据被重放，也限制未来时间伪造。
     * 它不授予权限，但把时限作为证据可用于当前恢复的必要条件。</p>
     *
     * <p>校验依赖当前 UTC 时间，因此同一字符串在不同时间调用可能从通过变为拒绝；这属于有意的新鲜度语义，
     * 不是缓存或幂等故障。无法解析或越界的时间会转换为稳定业务状态冲突。</p>
     *
     * @param trigger 已验证且包含恢复 deadline 的触发器
     * @param value 待解析的 ISO-8601 时间文本
     * @throws PlatformBusinessException 当时间格式非法、越出容差窗口或超过授权 deadline 时
     */
    private void verifyFreshTime(AgentAutopilotVerifiedRecoveryTrigger trigger, String value) {
        try {
            OffsetDateTime timestamp = OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC);
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            OffsetDateTime triggeredAt = OffsetDateTime.parse(trigger.event().triggeredAt())
                    .withOffsetSameInstant(ZoneOffset.UTC);
            if (timestamp.isBefore(triggeredAt.minusMinutes(5))
                    || timestamp.isAfter(now.plusMinutes(5))
                    || timestamp.isAfter(trigger.deadlineAt())) {
                throw conflict("AUTOPILOT_EVIDENCE_TIME_OUT_OF_RANGE");
            }
        } catch (PlatformBusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw conflict("AUTOPILOT_EVIDENCE_TIME_INVALID");
        }
    }

    /**
     * 将任意 Map 的键按文本排序、保留列表顺序，生成可与 Python 规范 JSON 对齐的摘要材料。
     *
     * <p>输入可以是 Map、集合或标量，输出是递归规范化后的对象图：Map 使用排序后的键，集合保持原来的元素
     * 顺序。该纯函数不读取证据正文以外的数据、不修改原对象、不校验权限；它只消除 Map 迭代顺序差异，
     * 让 Java 可以独立复算 Python 声明的证据 digest。</p>
     *
     * <p>相同结构和值总会得到等价的规范化材料，适合重复验证；列表顺序被故意保留，调用方不能把排序当作
     * 去重或幂等策略。异常对象类型仍由后续 JSON 序列化处理。</p>
     *
     * @param value 待规范化的证据记录、集合或标量
     * @return 适合稳定序列化和摘要计算的对象图
     */
    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonical(item)));
            return sorted;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(this::canonical).toList();
        }
        return value;
    }

    /**
     * 使用配置好的 Jackson 将规范化证据材料序列化为摘要所需的 JSON 文本。
     *
     * <p>输入会先经过 {@link #canonical(Object)}，输出是不含业务副作用的 JSON 字符串。该方法不调用 RAG、
     * 不访问数据库，也不决定任何权限；它的唯一职责是提供 Java 与 Python 都能复算的证据摘要材料。
     * 对同一输入和同一 ObjectMapper 配置，重复调用产生相同序列化文本。</p>
     *
     * @param value 原始证据记录或集合
     * @return 规范化后的 JSON 文本
     * @throws PlatformBusinessException 当证据对象无法安全序列化时
     */
    private String writeCanonical(Object value) {
        try {
            return objectMapper.writeValueAsString(canonical(value));
        } catch (JsonProcessingException exception) {
            throw conflict("AUTOPILOT_EVIDENCE_CANONICALIZATION_FAILED");
        }
    }

    /**
     * 读取一个必须大于零的证据数量。
     *
     * <p>输入来自不可信审计字段，输出为正整数；该纯校验不修改响应，也不授予权限。数量是摘要记录完整性的
     * 一部分，允许零、负数或非数字会使后续记录校验失去基线，因此统一转换为稳定业务状态冲突。</p>
     *
     * @param value 原始数量值
     * @param reasonCode 数量无效时要返回的稳定原因码
     * @return 大于零的数量
     * @throws PlatformBusinessException 当值不能解析为正整数时
     */
    private int positiveInt(Object value, String reasonCode) {
        try {
            int result = Integer.parseInt(String.valueOf(value));
            if (result <= 0) {
                throw new NumberFormatException("not positive");
            }
            return result;
        } catch (NumberFormatException exception) {
            throw conflict(reasonCode);
        }
    }

    /**
     * 计算 UTF-8 文本的小写十六进制 SHA-256 摘要。
     *
     * <p>输入是已经规范化的摘要材料，输出为 64 位十六进制文本。该计算没有网络、持久化或权限副作用，
     * 对相同输入可幂等复算，用于验证证据完整性而不是作为审批凭证或加密手段。</p>
     *
     * @param value 要进行摘要的非空文本
     * @return 小写十六进制 SHA-256 摘要
     * @throws IllegalStateException 当运行 JDK 不支持 SHA-256 时
     */
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK does not support SHA-256", exception);
        }
    }

    /**
     * 在两个摘要都存在时使用常量时间比较其字节内容。
     *
     * <p>输入是期望摘要和候选摘要，输出表示二者是否完全一致。该纯函数不修改证据、不查询权限，也不产生
     * 缓存副作用；它避免在有效摘要比较时按首个不同字符提前返回，减少摘要校验的时序泄漏。候选摘要为空时
     * 直接返回 {@code false}，调用方会将其转换为相应的证据冲突。</p>
     *
     * @param expected Java 复算得到的摘要
     * @param actual Python 或审计字段声明的摘要，可为空
     * @return 两个非空摘要是否完全相同
     */
    private boolean constantEquals(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 判断文本是否具有 {@code sha256:} 前缀和 64 位十六进制摘要的语法。
     *
     * <p>输入是未验证的摘要文本，输出只说明格式是否可能正确；它不会重算哈希、不读取证据，也不能当作
     * 权限或完整性结论。格式检查没有状态副作用，可重复调用；调用方必须继续使用 {@link #constantEquals}
     * 比较实际内容。</p>
     *
     * @param value 待检查的摘要文本，可为空
     * @return 文本是否符合受支持的摘要格式
     */
    private boolean digest(String value) {
        return value != null && value.matches("sha256:[0-9a-fA-F]{64}");
    }

    /**
     * 判断动作或错误指纹是否为 data-sync 与 Java 约定的纯 64 位十六进制值。
     *
     * <p>该方法只检查语法，不验证指纹由何种材料产生，也不授予动作权限。它没有副作用，适合在每次重复
     * Kafka 投递时重新执行；真正的幂等语义来自后续将修复指纹与历史 case/错误指纹绑定的策略。</p>
     *
     * @param value 待检查的指纹文本，可为空
     * @return 文本是否符合 64 位十六进制指纹格式
     */
    private boolean fingerprint(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    /**
     * 将任意枚举式值规范为可比较的大写下划线编码。
     *
     * <p>输入可为空，输出为空字符串或去空白、转大写并替换连字符后的文本。该纯格式化不验证权限、证据
     * 来源或业务状态，但避免不同服务的大小写写法改变策略分支；它不保存结果，因此没有额外幂等副作用。</p>
     *
     * @param value 待规范化的值，可为空
     * @return 用于内部比较的规范化编码，或空字符串
     */
    private String code(Object value) {
        return text(value).toUpperCase(Locale.ROOT).replace('-', '_');
    }

    /**
     * 安全地将任意值转换为去空白文本，并把缺失值表示为空字符串。
     *
     * <p>该方法没有 I/O、权限或证据判断副作用。统一的空字符串约定让上层验证可以用相同的空白规则处理
     * 缺失与空白值；它不应被误用为把必填字段默认成有效值。</p>
     *
     * @param value 原始值，可为空
     * @return 去除首尾空白后的文本，或空字符串
     */
    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * 创建不携带原始证据正文的稳定业务状态冲突异常。
     *
     * <p>输出包含平台错误类别和低敏原因码，供调用方、指标和审计链路区分证据合同失败；不会把日志正文、
     * RAG 文档或模型内容泄漏到异常消息。工厂没有副作用，每次调用只创建新异常对象，不改变重复验证语义。</p>
     *
     * @param reasonCode 可稳定分类的证据失败原因码
     * @return 用于中止当前自动恢复的业务异常
     */
    private PlatformBusinessException conflict(String reasonCode) {
        return new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT, reasonCode);
    }
}

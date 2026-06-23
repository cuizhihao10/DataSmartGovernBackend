/**
 * @Author : Cui
 * @Date: 2026-06-23 02:30
 * @Description DataSmart Govern Backend - AgentCommandSafetyPrecheckEvidenceEvaluator.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.execution;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 命令安全预检证据复核器。
 *
 * <p>5.92 已经提供了独立的命令安全预检接口，但真实 worker 领取 outbox command 时不能只相信“调用方曾经调过
 * safety-precheck”。因为调用方可能忘记把 verdict 放入 command payload，也可能把包含原始 commandLine、
 * workingDirectory、referencedPaths 的高敏对象塞进 payload。该组件就是 worker pre-check 的第二道门：
 * 它只接受低敏、可审计、可 replay 的安全证据段。</p>
 *
 * <p>本类刻意放在 execution 包，而不是 runtime 包：runtime 包负责生成安全预检 verdict，execution 包负责在
 * worker 派发前复核 outbox payload 是否携带了合格证据。这样可以避免“生成者”和“验收者”耦合在同一个服务里，
 * 后续即便命令预检接口升级为 MySQL fact store 或 permission-admin 策略查询，worker 复核语义也不需要推翻。</p>
 *
 * <p>重要边界：</p>
 * <p>1. 本组件不会执行命令，不会读取文件，不会访问 workspace；</p>
 * <p>2. 本组件不要求所有历史 command 都携带安全预检证据，只有 payload 显式声明 required 时才强制；</p>
 * <p>3. 如果证据段携带 commandLine、真实路径、stdout/stderr、prompt、SQL、arguments、payload、凭据或内部 endpoint，
 *    即使命令本身看起来安全，也会 fail-closed，因为 outbox payload 已经违反低敏合同。</p>
 */
@Component
public class AgentCommandSafetyPrecheckEvidenceEvaluator {

    /**
     * outbox payload 中承载低敏命令安全预检证据的字段名。
     *
     * <p>字段名固定在 evaluator 中，而不是散落在 pre-check 主服务里，便于后续 Java writer、task-management worker
     * 和文档都引用同一个合同。</p>
     */
    public static final String EVIDENCE_FIELD = "commandSafetyPrecheck";

    /**
     * 根级 required 开关。它允许某些 payload 不放完整证据段，但明确告诉 worker“这是命令执行，必须有证据”。
     */
    public static final String REQUIRED_FIELD = "commandSafetyPrecheckRequired";

    private static final String DECISION_ALLOW = "ALLOW_CONTROLLED_EXECUTION";
    private static final String DECISION_APPROVAL = "REQUIRE_HUMAN_APPROVAL";
    private static final String DECISION_BLOCK = "BLOCKED_BY_COMMAND_SAFETY";
    private static final String DECISION_NOT_APPLICABLE = "NOT_APPLICABLE";

    /**
     * safety-precheck 原始服务已经定义的一组硬阻断或审批问题码。
     *
     * <p>这里复制稳定 issueCode 而不直接依赖 runtime 包的包内策略类，是为了保持生成者和验收者的边界。
     * 如果未来新增安全问题码，应同时更新本白名单、指标白名单和测试，避免 worker 把未知高风险 code 当作可放行。</p>
     */
    private static final Set<String> OPEN_SAFETY_ISSUE_CODES = Set.of(
            "PRECHECK_POLICY_DISABLED",
            "COMMAND_LINE_REQUIRED",
            "COMMAND_LINE_TOO_LONG",
            "DANGEROUS_COMMAND_FRAGMENT",
            "WORKSPACE_ROOT_REQUIRED",
            "WORKING_DIRECTORY_OUTSIDE_WORKSPACE",
            "REFERENCED_PATH_OUTSIDE_WORKSPACE",
            "BLOCKED_PATH_FRAGMENT",
            "PATH_NORMALIZATION_FAILED",
            "TENANT_SCOPE_MISMATCH",
            "PROJECT_SCOPE_NOT_AUTHORIZED",
            "APPROVAL_FACT_UNSAFE",
            "UNKNOWN_COMMAND_REQUIRES_APPROVAL",
            "WRITE_COMMAND_REQUIRES_APPROVAL",
            "NETWORK_COMMAND_REQUIRES_APPROVAL",
            "PATH_PARENT_SEGMENT_REQUIRES_REVIEW",
            "APPROVAL_FACT_REQUIRES_SERVER_VERIFICATION"
    );

    /**
     * safety evidence 段内绝不允许出现的字段名。
     *
     * <p>注意这里使用“字段名精确匹配”，而不是 contains 匹配。原因是 `payloadPolicy`、`pathValuesReturned`、
     * `normalizedOutputByteLimitBytes` 等低敏字段名称里也会出现 payload/path/output 词根，但它们不携带真实值。
     * 真正危险的是 `commandLine`、`workingDirectory`、`referencedPaths`、`arguments`、`payload` 这类原文载荷。</p>
     */
    private static final Set<String> SENSITIVE_EVIDENCE_KEYS = Set.of(
            "commandLine",
            "rawCommandLine",
            "commandText",
            "workspaceRoot",
            "workingDirectory",
            "referencedPaths",
            "pathValues",
            "environment",
            "environmentVariables",
            "env",
            "stdout",
            "stderr",
            "output",
            "script",
            "sql",
            "prompt",
            "messages",
            "arguments",
            "payload",
            "sampleData",
            "modelOutput",
            "credentials",
            "credential",
            "password",
            "secret",
            "token",
            "apiKey",
            "authorization",
            "url",
            "baseUrl",
            "endpoint",
            "internalEndpoint"
    );

    /**
     * 从 outbox payload 中复核命令安全预检证据。
     *
     * @param payload 已经由 {@link AgentAsyncTaskCommandPreCheckService} 解析出的 outbox payload Map。
     * @return 低敏复核结果；调用方负责把 issue/reason/action 合并进最终 worker pre-check verdict。
     */
    public AgentCommandSafetyPrecheckEvidenceResult evaluate(Map<String, Object> payload) {
        Map<String, Object> safePayload = payload == null ? Map.of() : payload;
        boolean rootRequired = bool(safePayload.get(REQUIRED_FIELD));
        Object evidenceValue = safePayload.get(EVIDENCE_FIELD);
        if (!(evidenceValue instanceof Map<?, ?> rawEvidence)) {
            if (rootRequired) {
                return blocked(
                        true,
                        false,
                        null,
                        null,
                        List.of("COMMAND_SAFETY_PRECHECK_REQUIRED_MISSING"),
                        List.of("command payload 声明需要命令安全预检证据，但缺少 commandSafetyPrecheck 低敏证据段。"),
                        List.of("请先调用命令安全预检，并只把低敏 verdict 写入 outbox payload，不能写入原始 commandLine 或真实路径。")
                );
            }
            return notRequired();
        }

        Map<String, Object> evidence = normalizeMap(rawEvidence);
        boolean evidenceRequired = rootRequired || bool(evidence.get("required"));
        String decision = text(evidence.get("decision"));
        String policyVersion = text(evidence.get("policyVersion"));
        List<String> issueCodes = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        Set<String> acceptedEvidence = new LinkedHashSet<>();

        if (!evidenceRequired && !hasText(decision)) {
            acceptedEvidence.add("COMMAND_SAFETY_PRECHECK_NOT_REQUIRED");
            reasons.add("当前 command payload 未声明命令执行安全预检必需，worker 按历史低敏工具命令兼容路径继续复核。");
            return passed(false, true, decision, policyVersion, acceptedEvidence, reasons, actions);
        }
        if (!evidenceRequired && DECISION_NOT_APPLICABLE.equals(decision)) {
            acceptedEvidence.add("COMMAND_SAFETY_PRECHECK_NOT_APPLICABLE");
            reasons.add("当前 command payload 显式声明命令安全预检不适用，通常表示该 command 不是 run-program/shell 类副作用。");
            return passed(false, true, decision, policyVersion, acceptedEvidence, reasons, actions);
        }

        inspectLowSensitiveBoundary(evidence, issueCodes, reasons, actions);
        inspectPolicyFields(evidence, decision, policyVersion, evidenceRequired, issueCodes, reasons, actions);
        inspectDecisionFields(evidence, decision, issueCodes, reasons, actions);
        inspectBudgetFields(evidence, evidenceRequired, issueCodes, reasons, actions);

        if (issueCodes.isEmpty()) {
            acceptedEvidence.add("COMMAND_SAFETY_PRECHECK_PRESENT");
            acceptedEvidence.add("COMMAND_SAFETY_PRECHECK_LOW_SENSITIVE");
            acceptedEvidence.add("COMMAND_SAFETY_PRECHECK_ALLOW_VERDICT");
            acceptedEvidence.add("COMMAND_SAFETY_PRECHECK_BUDGET_PRESENT");
            reasons.add("命令安全预检证据通过 worker 复核：证据段只包含低敏 verdict，且 decision/executable/审批/预算字段允许进入受控执行链路。");
            actions.add("worker 后续仍必须在真实执行前申请 lease、裁剪 stdout/stderr、过滤环境变量，并回写低敏 receipt。");
            return passed(evidenceRequired, true, decision, policyVersion, acceptedEvidence, reasons, actions);
        }
        return blocked(
                evidenceRequired,
                true,
                decision,
                policyVersion,
                issueCodes,
                reasons,
                actions
        );
    }

    /**
     * 校验证据段是否仍保持低敏。
     *
     * <p>这是最重要的防线之一：即使 decision 是 ALLOW，只要证据段复制了 commandLine 或真实路径，也必须阻断。
     * outbox 是持久化事实，一旦把高敏内容写进去，后续 dispatcher、runtime event、日志和诊断接口都可能扩大泄露面。</p>
     */
    private void inspectLowSensitiveBoundary(Map<String, Object> evidence,
                                             List<String> issueCodes,
                                             List<String> reasons,
                                             List<String> actions) {
        Set<String> sensitiveKeys = new LinkedHashSet<>();
        collectSensitiveKeys(evidence, sensitiveKeys);
        if (!sensitiveKeys.isEmpty()) {
            issueCodes.add("COMMAND_SAFETY_PRECHECK_LOW_SENSITIVE_VIOLATION");
            reasons.add("commandSafetyPrecheck 证据段包含不允许进入 outbox 的高敏字段: " + sensitiveKeys + "。");
            actions.add("请只保存 decision、issueCodes、reasonCodes、pathCategories、预算裁剪和布尔边界，不要保存命令正文、真实路径或输出。");
        }
    }

    /**
     * 校验策略版本和 payloadPolicy。
     *
     * <p>policyVersion 让后续审计知道这份 verdict 来自哪一版安全规则；payloadPolicy 则让消费方知道它是否遵守
     * “只保存低敏摘要”的合同。缺少这两个字段时，worker 不能确信证据来自受控安全预检服务。</p>
     */
    private void inspectPolicyFields(Map<String, Object> evidence,
                                     String decision,
                                     String policyVersion,
                                     boolean required,
                                     List<String> issueCodes,
                                     List<String> reasons,
                                     List<String> actions) {
        if (required && !hasText(policyVersion)) {
            issueCodes.add("COMMAND_SAFETY_PRECHECK_POLICY_VERSION_MISSING");
            reasons.add("命令安全预检证据缺少 policyVersion，无法证明使用了哪一版 safe-cmd/dangerous-path 策略。");
            actions.add("请重新生成安全预检 verdict，并把 policyVersion 写入低敏证据段。");
        }
        String payloadPolicy = text(evidence.get("payloadPolicy"));
        if (hasText(decision) && (payloadPolicy == null || !payloadPolicy.toUpperCase(Locale.ROOT).contains("LOW_SENSITIVE"))) {
            issueCodes.add("COMMAND_SAFETY_PRECHECK_PAYLOAD_POLICY_INVALID");
            reasons.add("命令安全预检证据缺少 LOW_SENSITIVE payloadPolicy，worker 不能确认该证据段适合持久化和展示。");
            actions.add("请使用命令安全预检接口返回的低敏 payloadPolicy，不要手写未声明边界的 payload。");
        }
    }

    /**
     * 校验 decision、executable、approval、blocked 和低敏布尔锚点。
     */
    private void inspectDecisionFields(Map<String, Object> evidence,
                                       String decision,
                                       List<String> issueCodes,
                                       List<String> reasons,
                                       List<String> actions) {
        if (!hasText(decision)) {
            issueCodes.add("COMMAND_SAFETY_PRECHECK_DECISION_REQUIRED");
            reasons.add("命令安全预检证据缺少 decision，worker 无法判断命令是允许、审批还是阻断。");
            actions.add("请重新调用安全预检并保存 decision/executable/requiresHumanApproval/blocked 等低敏字段。");
            return;
        }
        if (DECISION_APPROVAL.equals(decision) || bool(evidence.get("requiresHumanApproval"))) {
            issueCodes.add("COMMAND_SAFETY_PRECHECK_APPROVAL_REQUIRED");
            reasons.add("命令安全预检证据仍要求 Human-in-the-loop，worker 不能绕过审批继续执行。");
            actions.add("请先完成审批事实回查，并由新的安全预检证据证明 requiresHumanApproval=false。");
        }
        if (DECISION_BLOCK.equals(decision) || bool(evidence.get("blocked"))) {
            issueCodes.add("COMMAND_SAFETY_PRECHECK_NOT_ALLOWED");
            reasons.add("命令安全预检证据显示该命令被安全策略阻断，worker 必须阻止副作用。");
            actions.add("请改写命令或路径后重新预检，不能通过 outbox 重放绕过阻断。");
        }
        if (!DECISION_ALLOW.equals(decision)) {
            issueCodes.add("COMMAND_SAFETY_PRECHECK_ALLOW_DECISION_REQUIRED");
            reasons.add("命令安全预检 decision 不是 ALLOW_CONTROLLED_EXECUTION，不能进入自动 worker 执行。");
            actions.add("只有允许态 verdict 才能进入受控执行链路；审批态或阻断态必须先处理。");
        }
        if (!bool(evidence.get("executable"))) {
            issueCodes.add("COMMAND_SAFETY_PRECHECK_NOT_EXECUTABLE");
            reasons.add("命令安全预检证据中的 executable 不是 true，说明当前命令仍不具备执行条件。");
            actions.add("请重新生成 executable=true 的低敏安全证据，或者转人工处置。");
        }
        if (!Boolean.FALSE.equals(booleanObject(evidence.get("commandLineReturned")))
                || !Boolean.FALSE.equals(booleanObject(evidence.get("pathValuesReturned")))
                || !Boolean.FALSE.equals(booleanObject(evidence.get("sideEffectExecuted")))) {
            issueCodes.add("COMMAND_SAFETY_PRECHECK_SIDE_EFFECT_FLAG_INVALID");
            reasons.add("命令安全预检证据没有明确证明 commandLine/pathValues 未返回且 sideEffect 未执行。");
            actions.add("请保留 commandLineReturned=false、pathValuesReturned=false、sideEffectExecuted=false 三个低敏锚点。");
        }
        List<String> embeddedIssueCodes = stringList(evidence.get("issueCodes"));
        for (String issueCode : embeddedIssueCodes) {
            if (OPEN_SAFETY_ISSUE_CODES.contains(issueCode)) {
                issueCodes.add("COMMAND_SAFETY_PRECHECK_HAS_OPEN_ISSUES");
                reasons.add("命令安全预检证据仍包含未关闭的安全 issueCode=" + issueCode + "。");
                actions.add("请先处理安全预检中的阻断/审批问题，再重新生成 allow verdict。");
                return;
            }
        }
    }

    /**
     * 校验超时和输出预算。
     *
     * <p>预算本身不是权限，但它是防止命令卡死、刷屏或把大量敏感输出写入 receipt 的基础控制面事实。</p>
     */
    private void inspectBudgetFields(Map<String, Object> evidence,
                                     boolean required,
                                     List<String> issueCodes,
                                     List<String> reasons,
                                     List<String> actions) {
        if (!required) {
            return;
        }
        Integer timeoutSeconds = positiveInteger(evidence.get("normalizedTimeoutSeconds"));
        Integer outputLimitBytes = positiveInteger(evidence.get("normalizedOutputByteLimitBytes"));
        if (timeoutSeconds == null || outputLimitBytes == null) {
            issueCodes.add("COMMAND_SAFETY_PRECHECK_BUDGET_INVALID");
            reasons.add("命令安全预检证据缺少有效的 normalizedTimeoutSeconds 或 normalizedOutputByteLimitBytes。");
            actions.add("请保留服务端裁剪后的超时和输出预算，worker 必须按该预算执行。");
        }
    }

    private void collectSensitiveKeys(Object value, Set<String> sensitiveKeys) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (SENSITIVE_EVIDENCE_KEYS.contains(key)) {
                    sensitiveKeys.add(key);
                }
                collectSensitiveKeys(entry.getValue(), sensitiveKeys);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                collectSensitiveKeys(item, sensitiveKeys);
            }
        }
    }

    private AgentCommandSafetyPrecheckEvidenceResult notRequired() {
        return new AgentCommandSafetyPrecheckEvidenceResult(
                false,
                false,
                true,
                null,
                null,
                List.of(),
                List.of("COMMAND_SAFETY_PRECHECK_NOT_REQUIRED"),
                List.of("当前 command payload 未声明需要命令安全预检证据，worker 按历史兼容路径继续执行其它复核。"),
                List.of()
        );
    }

    private AgentCommandSafetyPrecheckEvidenceResult passed(boolean required,
                                                            boolean present,
                                                            String decision,
                                                            String policyVersion,
                                                            Set<String> acceptedEvidence,
                                                            List<String> reasons,
                                                            List<String> actions) {
        return new AgentCommandSafetyPrecheckEvidenceResult(
                required,
                present,
                true,
                decision,
                policyVersion,
                List.of(),
                List.copyOf(acceptedEvidence),
                List.copyOf(reasons),
                List.copyOf(actions)
        );
    }

    private AgentCommandSafetyPrecheckEvidenceResult blocked(boolean required,
                                                             boolean present,
                                                             String decision,
                                                             String policyVersion,
                                                             List<String> issueCodes,
                                                             List<String> reasons,
                                                             List<String> actions) {
        return new AgentCommandSafetyPrecheckEvidenceResult(
                required,
                present,
                false,
                decision,
                policyVersion,
                List.copyOf(issueCodes),
                List.of(),
                List.copyOf(reasons),
                List.copyOf(actions)
        );
    }

    private Map<String, Object> normalizeMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(this::hasText)
                    .distinct()
                    .toList();
        }
        String text = text(value);
        return text == null ? List.of() : List.of(text);
    }

    private Integer positiveInteger(Object value) {
        if (value instanceof Number number) {
            int result = number.intValue();
            return result > 0 ? result : null;
        }
        String text = text(value);
        if (text == null) {
            return null;
        }
        try {
            int result = Integer.parseInt(text);
            return result > 0 ? result : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean bool(Object value) {
        return Boolean.TRUE.equals(booleanObject(value));
    }

    private Boolean booleanObject(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = text(value);
        if (text == null) {
            return null;
        }
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        return null;
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * worker pre-check 对命令安全预检证据的低敏复核结果。
     *
     * @param required 当前 payload 是否声明命令安全预检证据是必需项。
     * @param present 当前 payload 是否携带 commandSafetyPrecheck 证据段。
     * @param passed 证据是否通过低敏、决策、预算和布尔锚点复核。
     * @param decision 安全预检决策；只保存枚举值，不保存命令正文。
     * @param policyVersion 安全策略版本，用于后续审计和回放。
     * @param issueCodes 复核失败的问题码。
     * @param acceptedEvidence 复核通过时确认的证据类型。
     * @param reasons 中文解释，帮助学习和排障。
     * @param recommendedActions 下一步建议，供 dispatcher、管理员或未来 receipt 生成器使用。
     */
    public record AgentCommandSafetyPrecheckEvidenceResult(
            Boolean required,
            Boolean present,
            Boolean passed,
            String decision,
            String policyVersion,
            List<String> issueCodes,
            List<String> acceptedEvidence,
            List<String> reasons,
            List<String> recommendedActions
    ) {

        public AgentCommandSafetyPrecheckEvidenceResult {
            issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
            acceptedEvidence = acceptedEvidence == null ? List.of() : List.copyOf(acceptedEvidence);
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            recommendedActions = recommendedActions == null ? List.of() : List.copyOf(recommendedActions);
        }
    }
}

/**
 * @Author : Cui
 * @Date: 2026/08/11 00:10
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryPolicyEvaluator.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import com.czh.datasmart.govern.datasync.support.SyncAutopilotExecutionMode;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryAction;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRecoveryCaseState;
import com.czh.datasmart.govern.datasync.support.SyncAutopilotRiskLevel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 对任务本地的 Autopilot 授权做确定性评估，不产生传输或执行副作用。
 *
 * <p>相同授权 JSON 与相同低敏事实必须得到相同结论。本类不会调用 worker、Python Runtime、HTTP、Kafka，
 * 也不会修改全局执行策略；调用方可以先持久化可审计决策，再由后续状态机决定是否消费
 * {@code AUTO_APPROVED} case。把“判断能不能做”和“真正执行动作”分开，是防止模型输出直接变成权限的关键边界。</p>
 */
@Component
public class SyncAutopilotRecoveryPolicyEvaluator {

    private static final Pattern SHA_256 = Pattern.compile("^[0-9a-fA-F]{64}$");
    private static final Pattern SAFE_AUTHORIZATION_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final Pattern SAFE_RECEIPT_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private final ObjectMapper objectMapper;

    /**
     * 创建生产评估器，并使用独立 JSON Mapper 解析持久授权。
     *
     * <p>构造过程只准备解析能力，不读取任务、不访问远端、不修改状态，也不缓存授权。每次评估都显式接收数据库中的
     * 授权正文，因此授权判断可以复现，并始终绑定调用方正在处理的任务。</p>
     */
    public SyncAutopilotRecoveryPolicyEvaluator() {
        this(new ObjectMapper());
    }

    SyncAutopilotRecoveryPolicyEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 校验内部恢复请求携带的用户、Agent 和委派关系是否仍与首次授权快照完全一致。
     *
     * <p>内部服务令牌只能证明请求来自受信任服务，不能证明 Header 中的主体就是最初被授权的主体。因此受治理修复在
     * 真正产生副作用前必须重新读取任务定义中的授权 JSON，并同时比较 {@code userId}、{@code actorId}、
     * {@code agentId} 与 {@code delegationId}。当前会话创建规则要求 userId 与 actorId 指向同一被代理用户，
     * 任一字段缺失、授权不是 ACTIVE、JSON 损坏或主体不一致都返回 {@code false}，调用方据此 fail-closed。</p>
     *
     * <p>该方法只解析和比较低敏标识，不写数据库、不调用远端，也不把原始授权内容写入日志。它刻意不为旧版缺少主体字段
     * 的策略提供宽松回退，因为高级自动修复比普通只读操作风险更高，旧授权应由用户重新确认后再获得这类能力。</p>
     *
     * @param autopilotPolicyJson 任务定义中持久化的首次授权快照
     * @param representedActorId 当前请求代表的用户主体
     * @param agentId 当前执行修复的 Agent 主体
     * @param delegationId 用户授予该 Agent 的委派标识
     * @return 只有授权处于 ACTIVE 且四项主体事实精确匹配时返回 {@code true}
     */
    public boolean matchesPrincipalBinding(String autopilotPolicyJson,
                                           String representedActorId,
                                           String agentId,
                                           String delegationId) {
        if (!hasText(autopilotPolicyJson) || !hasText(representedActorId)
                || !hasText(agentId) || !hasText(delegationId)) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(autopilotPolicyJson);
            if (root == null || !root.isObject()
                    || !"ACTIVE".equals(requiredText(root, "state").toUpperCase(Locale.ROOT))) {
                return false;
            }
            String normalizedActor = representedActorId.trim();
            return normalizedActor.equals(requiredText(root, "userId"))
                    && normalizedActor.equals(requiredText(root, "actorId"))
                    && agentId.trim().equals(requiredText(root, "agentId"))
                    && delegationId.trim().equals(requiredText(root, "delegationId"));
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * 使用任务本地 Autopilot 授权评估一个低敏恢复候选。
     *
     * <p>输入包括持久授权 JSON，以及只含 ID、枚举、计数器、时间戳和指纹的请求。输出包含 case 状态、必要时的
     * 低敏关注原因，以及用于绑定授权但不暴露正文的摘要。本方法不持久化、不调用模型或远端、不重试任务，也不执行
     * 恢复动作；只有在请求没有提供 {@code evaluatedAt} 时读取当前 UTC 时间。</p>
     *
     * <p>判断顺序本身就是安全策略：范围、动作或有效期非法时返回 {@code REJECTED}；证据不足、预算耗尽、错误重复、
     * 风险未解析或置信度不足时先返回 {@code ATTENTION_REQUIRED}。相同评估时刻下的等价输入必然得到相同结果。
     * 授权 JSON 永远不会被直接当作 worker 执行许可，后续 case/receipt 状态机仍掌握最终执行权。</p>
     *
     * @param autopilotPolicyJson 已持久化的任务授权 JSON，必须符合有界安全结构
     * @param request 单个恢复候选的低敏事实
     * @return 可直接持久化的不可变决策，状态为自动批准、等待审批、拒绝或需要关注之一
     */
    public SyncAutopilotRecoveryPolicyDecision evaluate(String autopilotPolicyJson,
                                                         SyncAutopilotRecoveryEvaluationRequest request) {
        LocalDateTime now = request == null || request.evaluatedAt() == null
                ? LocalDateTime.now(ZoneOffset.UTC)
                : request.evaluatedAt();
        String rawPolicyDigest = SyncAutopilotDigestSupport.sha256(autopilotPolicyJson);
        if (request == null) {
            return attention("INVALID_EVALUATION_REQUEST", rawPolicyDigest, rawPolicyDigest, 1,
                    now.plusSeconds(1));
        }

        ParsedPolicy policy;
        try {
            policy = parsePolicy(autopilotPolicyJson);
        } catch (RuntimeException exception) {
            return attention("INVALID_AUTOPILOT_POLICY", rawPolicyDigest, rawPolicyDigest, 1,
                    now.plusSeconds(1));
        }

        String authorizationDigest = SyncAutopilotDigestSupport.sha256(policy.authorizationId());
        String policyDigest = policyDigest(policy);
        LocalDateTime policyDeadlineAt = normalizeDatabaseTimestamp(boundedPolicyDeadline(policy, now));
        LocalDateTime requestedDeadlineAt = normalizeDatabaseTimestamp(request.deadlineAt());
        if (requestedDeadlineAt != null
                && isAfterUtcInstant(requestedDeadlineAt, policyDeadlineAt)) {
            // 调用方可以缩短无人值守窗口，但不能用更晚的时间扩大持久授权。
            return rejected(authorizationDigest, policyDigest, policy.maxCycles(), policyDeadlineAt);
        }
        LocalDateTime deadlineAt = requestedDeadlineAt == null ? policyDeadlineAt : requestedDeadlineAt;

        if (request.executionMode() != SyncAutopilotExecutionMode.AUTOPILOT) {
            return rejected(authorizationDigest, policyDigest, policy.maxCycles(), deadlineAt);
        }
        boolean automaticallyAuthorized = policy.allowedActions().contains(request.action());
        boolean approvalAuthorized = policy.approvalActions().contains(request.action());
        if (!scopeMatches(policy, request) || (!automaticallyAuthorized && !approvalAuthorized)) {
            return rejected(authorizationDigest, policyDigest, policy.maxCycles(), deadlineAt);
        }
        if (!isAfterInstant(policy.expiresAt(), now)) {
            return rejected(authorizationDigest, policyDigest, policy.maxCycles(), deadlineAt);
        }
        if (policy.maxAutomaticRisk() != SyncAutopilotRiskLevel.LOW) {
            return attention("MAX_AUTOMATIC_RISK_MUST_BE_LOW", authorizationDigest, policyDigest,
                    policy.maxCycles(), deadlineAt);
        }
        if (!validFingerprint(request.repairFingerprint())) {
            return attention("MISSING_OR_INVALID_ACTION_FINGERPRINT", authorizationDigest, policyDigest,
                    policy.maxCycles(), deadlineAt);
        }
        if (!hasText(request.receiptId()) || !SAFE_RECEIPT_ID.matcher(request.receiptId().trim()).matches()) {
            return attention("MISSING_OR_INVALID_RECEIPT_ID", authorizationDigest, policyDigest,
                    policy.maxCycles(), deadlineAt);
        }
        if (!validFingerprint(request.lastErrorFingerprint())) {
            return attention("MISSING_OR_INVALID_ERROR_FINGERPRINT", authorizationDigest, policyDigest,
                    policy.maxCycles(), deadlineAt);
        }
        if (request.cycle() < 1 || request.cycle() > policy.maxCycles()) {
            return attention("AUTOPILOT_CYCLE_BUDGET_EXHAUSTED", authorizationDigest, policyDigest,
                    policy.maxCycles(), deadlineAt);
        }
        if (!isAfterUtcInstant(deadlineAt, now)) {
            return attention("AUTOPILOT_DEADLINE_EXCEEDED", authorizationDigest, policyDigest,
                    policy.maxCycles(), deadlineAt);
        }
        if (request.repeatedErrorCount() < 0 || request.repeatedErrorCount() >= policy.maxRepeatedErrorCount()) {
            return attention("REPEATED_ERROR_LIMIT_REACHED", authorizationDigest, policyDigest,
                    policy.maxCycles(), deadlineAt);
        }
        if (!request.evidenceAvailable()) {
            return attention("RECOVERY_EVIDENCE_MISSING", authorizationDigest, policyDigest,
                    policy.maxCycles(), deadlineAt);
        }
        if (request.action() == SyncAutopilotRecoveryAction.RETRY_EXECUTION
                && !request.automaticRetryFactsVerified()) {
            return attention("RECOVERY_AUTOMATIC_RETRY_FACTS_REQUIRED", authorizationDigest, policyDigest,
                    policy.maxCycles(), deadlineAt);
        }
        if (request.confidenceScore() < policy.minimumConfidence() || request.confidenceScore() > 100) {
            return attention("RECOVERY_CONFIDENCE_TOO_LOW", authorizationDigest, policyDigest,
                    policy.maxCycles(), deadlineAt);
        }
        if (request.riskLevel() == null) {
            return attention("RECOVERY_RISK_UNRESOLVED", authorizationDigest, policyDigest,
                    policy.maxCycles(), deadlineAt);
        }
        if (approvalAuthorized || !request.riskLevel().canBeAutomaticallyApproved()) {
            return new SyncAutopilotRecoveryPolicyDecision(
                    SyncAutopilotRecoveryCaseState.WAITING_APPROVAL,
                    null,
                    authorizationDigest,
                    policyDigest,
                    policy.maxCycles(),
                    deadlineAt
            );
        }
        if (request.riskLevel().canBeAutomaticallyApproved()) {
            if (!request.action().isAutomaticLowRiskWhitelisted()) {
                return rejected(authorizationDigest, policyDigest, policy.maxCycles(), deadlineAt);
            }
            return new SyncAutopilotRecoveryPolicyDecision(
                    SyncAutopilotRecoveryCaseState.AUTO_APPROVED,
                    null,
                    authorizationDigest,
                    policyDigest,
                    policy.maxCycles(),
                    deadlineAt
            );
        }
        return new SyncAutopilotRecoveryPolicyDecision(
                SyncAutopilotRecoveryCaseState.WAITING_APPROVAL,
                null,
                authorizationDigest,
                policyDigest,
                policy.maxCycles(),
                deadlineAt
        );
    }

    /**
     * 计算当前授权快照最多能够授予到何时。
     *
     * <p>无人值守恢复同时受授权绝对过期时间和“从本次评估开始的最大持续时间”约束，两者取更早值。
     * 转换为 UTC {@link LocalDateTime} 是为了保持 PostgreSQL 现有字段合同，同时避免使用服务器默认时区做时间运算。
     * 该纯函数不会信任调用方自行提交的更晚截止时间。</p>
     *
     * @param policy 已解析且经过边界校验的授权策略
     * @param evaluatedAt 以 UTC 本地时间表达的评估时刻
     * @return 最早且安全的 UTC 截止时间
     */
    private LocalDateTime boundedPolicyDeadline(ParsedPolicy policy, LocalDateTime evaluatedAt) {
        LocalDateTime durationDeadline = evaluatedAt.plusSeconds(policy.maxDurationSeconds());
        LocalDateTime expiryDeadline = policy.expiresAt()
                .withOffsetSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();
        return isAfterUtcInstant(durationDeadline, expiryDeadline) ? expiryDeadline : durationDeadline;
    }

    /**
     * 把授权时间规范化为 PostgreSQL {@code timestamp} 的微秒精度。
     *
     * <p>Java 时间可以携带纳秒，而 PostgreSQL 当前表字段只保存微秒并按最接近的微秒四舍五入。例如
     * {@code .161252900} 写库后会成为 {@code .161253}。如果首次评估返回纳秒值、二次执行门禁直接拿
     * 数据库回读值与原始值比较，就会把同一个截止时间误判为“调用方扩大了 100ns 授权窗口”。</p>
     *
     * <p>评估入口在比较和返回前统一调用本方法，因此新 case 与历史回读 case 都遵循同一数据库精度。
     * 这里最多只处理一个微秒内的表示差异，不会把授权放宽到毫秒或秒，也不会改变绝对 UTC 语义。</p>
     *
     * @param value 待持久化或从数据库回读的 UTC 本地时间；可以为空
     * @return 按 PostgreSQL 行为四舍五入到微秒的时间，空输入仍返回空
     */
    private LocalDateTime normalizeDatabaseTimestamp(LocalDateTime value) {
        return value == null ? null : value.plusNanos(500).truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 解析并校验评估器真正需要的小型授权结构。
     *
     * <p>解析器只接受包含授权标识、租户范围、过期时间、有界预算、风险上限和枚举动作列表的对象。它只识别文档声明的
     * 兼容别名，拒绝 JSON 类型混淆和越界数值，也不保留原始正文。解析过程确定且无副作用；任何损坏输入都会抛错，
     * 让 {@link #evaluate(String, SyncAutopilotRecoveryEvaluationRequest)} 失败关闭为关注状态，而不是意外授权。</p>
     *
     * @param autopilotPolicyJson 待解析的任务本地持久策略正文
     * @return 仅供本次评估使用的规范化内存策略
     * @throws IllegalArgumentException 策略缺失、损坏、不安全或不完整时抛出
     */
    private ParsedPolicy parsePolicy(String autopilotPolicyJson) {
        if (!hasText(autopilotPolicyJson)) {
            throw new IllegalArgumentException("autopilotPolicy is required");
        }
        try {
            JsonNode root = objectMapper.readTree(autopilotPolicyJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("autopilotPolicy must be a JSON object");
            }
            String authorizationId = requiredText(root, "authorizationId", "policyId");
            if (!SAFE_AUTHORIZATION_ID.matcher(authorizationId).matches()) {
                throw new IllegalArgumentException("authorizationId is not low-sensitive identifier text");
            }
            Long tenantId = requiredLong(root, "tenantId");
            Long taskId = nullableLong(root, "taskId");
            Long projectId = nullableLong(root, "projectId");
            OffsetDateTime expiresAt = parseDateTime(requiredText(root, "expiresAt"));
            int maxCycles = boundedInt(root, "maxCycles", "maxRecoveryCycles", 5, 1, 10);
            int maxDurationSeconds = root.has("maxTotalDurationMinutes")
                    ? boundedInt(root, "maxTotalDurationMinutes", null, 120, 5, 1440) * 60
                    : boundedInt(root, "maxDurationSeconds", null, 7200, 1, 86_400);
            int maxRepeatedErrorCount = boundedInt(root, "maxRepeatedErrorCount", null, 3, 1, 10);
            int minimumConfidence = boundedInt(root, "minimumConfidence", null, 70, 0, 100);
            SyncAutopilotRiskLevel maxAutomaticRisk = SyncAutopilotRiskLevel.valueOf(
                    requiredText(root, "maxAutomaticRisk", "maxAutomaticRiskLevel").toUpperCase(Locale.ROOT));
            JsonNode allowedNode = root.has("allowedRecoveryActions")
                    ? root.path("allowedRecoveryActions") : root.path("allowedActions");
            Set<SyncAutopilotRecoveryAction> allowedActions = parseActions(allowedNode, true);
            Set<SyncAutopilotRecoveryAction> approvalActions = parseActions(root.path("requireApprovalFor"), false);
            if (tenantId <= 0 || (taskId != null && taskId <= 0) || allowedActions.isEmpty()) {
                throw new IllegalArgumentException("autopilotPolicy contains an invalid scope or action list");
            }
            return new ParsedPolicy(authorizationId, tenantId, projectId, taskId, expiresAt, maxCycles,
                    maxDurationSeconds, maxRepeatedErrorCount, minimumConfidence, maxAutomaticRisk,
                    allowedActions, approvalActions);
        } catch (Exception exception) {
            if (exception instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) exception;
            }
            throw new IllegalArgumentException("Cannot parse autopilotPolicy", exception);
        }
    }

    /**
     * 把文档声明的恢复动作 JSON 数组转换为封闭枚举集合。
     *
     * <p>必填列表缺失、不是数组或为空都属于非法授权；可选审批列表缺失时返回空集合。每个元素必须是文本并能解析为
     * 服务端枚举，任意工具名或模型自造动作不能进入授权决策。本方法无持久化和执行副作用。</p>
     *
     * @param actions 包含动作列表的 JSON 节点
     * @param required 缺失或空列表是否必须拒绝策略
     * @return 本次评估使用的封闭枚举集合
     * @throws IllegalArgumentException 必填列表缺失或元素不是已知枚举时抛出
     */
    private Set<SyncAutopilotRecoveryAction> parseActions(JsonNode actions, boolean required) {
        if (actions == null || !actions.isArray() || actions.isEmpty()) {
            if (!required) {
                return EnumSet.noneOf(SyncAutopilotRecoveryAction.class);
            }
            throw new IllegalArgumentException("allowedActions must be a non-empty array");
        }
        Set<SyncAutopilotRecoveryAction> result = EnumSet.noneOf(SyncAutopilotRecoveryAction.class);
        for (JsonNode action : actions) {
            if (!action.isTextual()) {
                throw new IllegalArgumentException("allowedActions must contain enum names only");
            }
            result.add(SyncAutopilotRecoveryAction.valueOf(action.asText().trim().toUpperCase(Locale.ROOT)));
        }
        return result;
    }

    /**
     * 检查授权与候选是否属于同一租户、项目和任务范围。
     *
     * <p>策略可以有意省略 {@code taskId}，此时作用于其租户/项目范围；一旦提供任务 ID 就必须精确匹配。
     * 该幂等纯检查不会修改 case。返回 false 只能被解释为拒绝，不能据此扩大范围或回退到另一份策略。</p>
     *
     * @param policy 规范化后的持久授权范围
     * @param request 正在校验归属的恢复候选事实
     * @return 所有必需范围字段匹配时才返回 {@code true}
     */
    private boolean scopeMatches(ParsedPolicy policy, SyncAutopilotRecoveryEvaluationRequest request) {
        return Objects.equals(policy.tenantId(), request.tenantId())
                && Objects.equals(policy.projectId(), request.projectId())
                && (policy.taskId() == null || Objects.equals(policy.taskId(), request.syncTaskId()));
    }

    /**
     * 为实际影响评估的规范化授权字段生成稳定摘要。
     *
     * <p>动作集合会先排序再计算，避免 JSON 数组顺序改变绑定结果。摘要确定且无状态副作用，用于代替原始授权正文进入
     * case 身份和审计；它不是加密，也不能代替服务边界对当前持久归属的再次校验。</p>
     *
     * @param policy 已校验的规范化策略
     * @return 授权相关字段的小写 SHA-256 摘要
     */
    private String policyDigest(ParsedPolicy policy) {
        String actions = policy.allowedActions().stream()
                .map(Enum::name)
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        String approvalActions = policy.approvalActions().stream()
                .map(Enum::name)
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return SyncAutopilotDigestSupport.sha256(policy.authorizationId() + "|" + policy.tenantId() + "|"
                + policy.projectId() + "|" + policy.taskId() + "|" + policy.expiresAt().toInstant() + "|"
                + policy.maxCycles() + "|" + policy.maxDurationSeconds() + "|"
                + policy.maxRepeatedErrorCount() + "|" + policy.minimumConfidence() + "|"
                + policy.maxAutomaticRisk() + "|" + actions + "|" + approvalActions);
    }

    /**
     * 为授权边界失败生成不可执行的拒绝决策。
     *
     * <p>该方法没有状态或传输副作用，但仍返回与允许决策相同的有界生命周期元数据，使 case 服务无需暴露授权正文即可
     * 审计策略上下文。相同输入重复调用得到相同结果。</p>
     *
     * @param authorizationDigest 授权标识摘要
     * @param policyDigest 规范化策略摘要
     * @param maxCycles 恢复轮次预算
     * @param deadlineAt 策略推导出的 case 截止时间
     * @return 状态为 {@code REJECTED} 的不可变决策
     */
    private SyncAutopilotRecoveryPolicyDecision rejected(String authorizationDigest,
                                                          String policyDigest,
                                                          int maxCycles,
                                                          LocalDateTime deadlineAt) {
        return new SyncAutopilotRecoveryPolicyDecision(
                SyncAutopilotRecoveryCaseState.REJECTED,
                null,
                authorizationDigest,
                policyDigest,
                maxCycles,
                deadlineAt
        );
    }

    /**
     * 在安全门禁不满足时生成不可执行的关注决策。
     *
     * <p>原因使用稳定低敏业务码，不携带原始策略、错误或证据正文。该纯函数只描述 case 服务可持久化的状态，
     * 不授予审批也不自行升级权限，后续应由人工或其他受治理流程处理。</p>
     *
     * @param reason 自动化必须停止的稳定原因码
     * @param authorizationDigest 授权标识摘要
     * @param policyDigest 规范化策略摘要
     * @param maxCycles 恢复轮次预算
     * @param deadlineAt 策略推导出的 case 截止时间
     * @return 状态为 {@code ATTENTION_REQUIRED} 的不可变决策
     */
    private SyncAutopilotRecoveryPolicyDecision attention(String reason,
                                                           String authorizationDigest,
                                                           String policyDigest,
                                                           int maxCycles,
                                                           LocalDateTime deadlineAt) {
        return new SyncAutopilotRecoveryPolicyDecision(
                SyncAutopilotRecoveryCaseState.ATTENTION_REQUIRED,
                reason,
                authorizationDigest,
                policyDigest,
                maxCycles,
                deadlineAt
        );
    }

    /**
     * 读取一个必填非空文本字段，并可接受文档声明的旧字段别名。
     *
     * <p>只有确认 JSON 值确实为文本后才去除首尾空白；数字、对象和数组不会被强制转成字符串，从而阻断授权解析中的
     * 类型混淆。该纯函数可重复执行，字段非法时抛出明确异常，不会自行发明默认值。</p>
     *
     * @param root 已解析的策略对象
     * @param field 首选结构字段名
     * @param alias 可选兼容字段名
     * @return 首选字段或别名中的非空文本
     * @throws IllegalArgumentException 两个字段都没有提供有效文本时抛出
     */
    private String requiredText(JsonNode root, String field, String alias) {
        JsonNode value = root.get(field);
        if ((value == null || value.isNull()) && alias != null) {
            value = root.get(alias);
        }
        if (value == null || !value.isTextual() || !hasText(value.asText())) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.asText().trim();
    }

    private String requiredText(JsonNode root, String field) {
        return requiredText(root, field, null);
    }

    /**
     * 读取必填整数标识，不把文本或浮点 JSON 值转换为 ID。
     *
     * <p>标识存在性与业务范围校验分开，因为不同字段拥有不同边界。该纯函数不会创建默认 ID 或改变请求范围，
     * 缺失值会在策略可能授权到其他租户或任务前失败关闭。</p>
     *
     * @param root 已解析的策略对象
     * @param field 必填整数字段名
     * @return JSON 整数表示的 Long 值
     * @throws IllegalArgumentException 字段缺失或不能转换为 Long 时抛出
     */
    private Long requiredLong(JsonNode root, String field) {
        Long value = nullableLong(root, field);
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    /**
     * 读取可选整数字段，并保留“缺失”和“非法”两种语义差异。
     *
     * <p>{@code null} 只表示策略省略了可选范围字段；存在但不是整数的输入会被拒绝，而不是强制转换。
     * 调用方据此应用准确范围语义，不能因解析失败获得更宽的回退范围。</p>
     *
     * @param root 已解析的策略对象
     * @param field 可选整数字段名
     * @return Long 值；只有字段缺失或为 null 时返回 {@code null}
     * @throws IllegalArgumentException 已提供字段但不是整数时抛出
     */
    private Long nullableLong(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.canConvertToLong()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return value.longValue();
    }

    /**
     * 读取有界整数预算或阈值，只有字段省略时才使用安全默认值。
     *
     * <p>可选别名只支持已声明的结构改名。显式值必须是闭区间内整数，策略 JSON 不能把循环改成无界，也不能通过
     * 小数截断偷偷改变预算。该纯函数只向评估器返回数值，不更新 case、不调度任务也不触发重试。</p>
     *
     * @param root 已解析的策略对象
     * @param field 首选字段名
     * @param alias 可选旧字段名
     * @param fallback 字段省略时的安全默认值
     * @param min 允许的最小显式值
     * @param max 允许的最大显式值
     * @return 默认值或经过校验的显式整数
     * @throws IllegalArgumentException 显式值不是整数或超出安全范围时抛出
     */
    private int boundedInt(JsonNode root, String field, String alias, int fallback, int min, int max) {
        JsonNode value = root.get(field);
        if ((value == null || value.isNull()) && alias != null) {
            value = root.get(alias);
        }
        if (value == null || value.isNull()) {
            return fallback;
        }
        if (!value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        int parsed = value.intValue();
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(field + " is outside the supported safety range");
        }
        return parsed;
    }

    /**
     * 从带时区偏移的 ISO 文本或旧版 UTC 本地 ISO 文本解析过期时间。
     *
     * <p>带偏移文本会保留偏移直到比较绝对时刻；若丢弃偏移，{@code 17:00+08:00} 会被误当成 17:00 UTC，
     * 无声扩大八小时授权。旧本地文本为兼容而保留，但明确解释为 UTC，因为持久请求和截止时间合同使用 UTC
     * {@link LocalDateTime}。该方法不读取时钟、不持久化，无法解析时直接失败关闭。</p>
     *
     * @param value 必填 ISO-8601 时间文本
     * @return 带偏移的过期时间；旧本地值按 UTC 解释
     * @throws RuntimeException 两种格式都无法解析时抛出
     */
    private OffsetDateTime parseDateTime(String value) {
        try {
            return OffsetDateTime.parse(value);
        } catch (RuntimeException ignored) {
            return LocalDateTime.parse(value).atOffset(ZoneOffset.UTC);
        }
    }

    /**
     * 在绝对 UTC 时间线上比较策略过期时间和 UTC 本地评估时间。
     *
     * <p>策略可以携带任意 ISO-8601 偏移，现有命令 DTO 则为兼容 PostgreSQL 保留 {@link LocalDateTime}。
     * 将请求时间明确视为 UTC 并转成瞬时值比较，可以保持合同，又不会让策略的本地钟面表示改变授权时长。</p>
     *
     * @param candidate 必须晚于评估时刻的策略时间
     * @param evaluatedAt UTC 本地评估时间
     * @return 策略瞬时严格晚于评估瞬时时返回 {@code true}
     */
    private boolean isAfterInstant(OffsetDateTime candidate, LocalDateTime evaluatedAt) {
        return candidate.toInstant().isAfter(evaluatedAt.toInstant(ZoneOffset.UTC));
    }

    /**
     * 比较两个持久 UTC 本地截止时间，不继承宿主 JVM 默认时区。
     *
     * <p>两个值都来自 data-sync 持久合同，其中 {@link LocalDateTime} 表示 UTC 钟面时间。显式转成 UTC 瞬时值，
     * 可避免在区域时区调用 {@code now()} 后误认为它与数据库截止时间代表同一物理时刻。</p>
     *
     * @param candidate 必须仍处于未来的截止时间
     * @param evaluatedAt UTC 本地评估时间
     * @return 截止瞬时严格晚于评估瞬时时返回 {@code true}
     */
    private boolean isAfterUtcInstant(LocalDateTime candidate, LocalDateTime evaluatedAt) {
        return candidate.toInstant(ZoneOffset.UTC).isAfter(evaluatedAt.toInstant(ZoneOffset.UTC));
    }

    /**
     * 校验关联指纹是否严格为 SHA-256 十六进制文本。
     *
     * <p>这里只做输入边界检查，不重新计算底层错误或修复事实。它阻止原始自然语言、SQL、URL 和任意标识进入低敏策略
     * 合同；校验失败时评估器停在关注状态，不会自动恢复。</p>
     *
     * @param value 候选指纹文本
     * @return 非空且恰好为 64 位十六进制 SHA-256 时返回 {@code true}
     */
    private boolean validFingerprint(String value) {
        return hasText(value) && SHA_256.matcher(value.trim()).matches();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record ParsedPolicy(
            String authorizationId,
            Long tenantId,
            Long projectId,
            Long taskId,
            OffsetDateTime expiresAt,
            int maxCycles,
            int maxDurationSeconds,
            int maxRepeatedErrorCount,
            int minimumConfidence,
            SyncAutopilotRiskLevel maxAutomaticRisk,
            Set<SyncAutopilotRecoveryAction> allowedActions,
            Set<SyncAutopilotRecoveryAction> approvalActions
    ) {
    }
}

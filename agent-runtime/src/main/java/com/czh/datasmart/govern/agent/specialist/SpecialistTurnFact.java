/**
 * @Author : Cui
 * @Date: 2026/08/05 00:00
 * @Description DataSmart Govern Backend - SpecialistTurnFact.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.specialist;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 专业 Agent 一次 turn 的低敏事实快照。
 *
 * <p>这个对象是 Java Agent Runtime 与 Python 专业 Agent 之间的持久化边界。
 * 它记录“谁在什么租户、项目、会话和运行中，由哪个专业 Agent 完成了哪一次 turn”，
 * 以及足以让前端和运维人员定位流程的摘要、引用和耗时；它不是模型对话正文的归档表。
 * 因此这里故意没有 prompt、思维链、SQL、工具参数、凭据、样本数据或模型输出正文等字段。</p>
 *
 * <p>记录在进入 Store 之前就完成一次低敏校验。这样即使调用方绕过 Controller 直接构造
 * record，也不会因为“数据库字段本身看起来只是 summary/ref”而把高敏正文写进事实库。</p>
 *
 * @param userId 代表本次 Agent 工作的业务用户 ID，也是普通用户查询事实时的归属边界
 * @param tenantId 租户边界，所有事实必须归属于一个租户
 * @param applicationId 应用边界，防止同租户下不同产品应用错误共享 Agent 审计事实
 * @param projectId 项目边界，专业 Agent 不跨项目复用一次 turn 事实
 * @param sessionId Agent 会话 ID，用于恢复同一会话的专业处理历史
 * @param runId Agent Run ID，用于区分同一会话中的不同处理轮次
 * @param turnId 专业 Agent turn ID，用于标识某个专业 Agent 的一次工作轮次
 * @param idempotencyKey 由调用方生成的 turn 幂等键，重试同一个 turn 时必须保持不变
 * @param agentId 专业 Agent 的稳定标识，例如 knowledge-agent 或 datasource-agent
 * @param role 专业 Agent 角色，例如 KNOWLEDGE_AGENT 或 DATASOURCE_AGENT
 * @param delegationId 当前用户授予 Agent 的委托事实 ID，可为空但不能伪造用户权限
 * @param status turn 状态，例如 PLANNED、RUNNING、SUCCEEDED、FAILED 或 CANCELLED
 * @param lowSensitiveSummary 可展示的低敏摘要，不能承载模型输出正文或隐藏推理
 * @param modelInvocationId Provider 调用 ID，只保存可审计引用，不保存请求和响应正文
 * @param modelName 本次实际调用的模型名
 * @param toolActivitySummaryRefs 工具活动摘要引用，例如 tool-event:abc123
 * @param evidenceRefs 证据引用，例如 rag-evidence:case-001
 * @param durationMillis 本次 turn 耗时，单位为毫秒
 * @param startedAt turn 开始时间
 * @param finishedAt turn 结束时间，运行中可为空
 * @param createdAt 首次写入时间
 * @param updatedAt 最后一次状态更新时间
 */
public record SpecialistTurnFact(
        String userId,
        Long tenantId,
        Long applicationId,
        Long projectId,
        String sessionId,
        String runId,
        String turnId,
        String idempotencyKey,
        String agentId,
        String role,
        String delegationId,
        String status,
        String lowSensitiveSummary,
        String modelInvocationId,
        String modelName,
        List<String> toolActivitySummaryRefs,
        List<String> evidenceRefs,
        Long durationMillis,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        Instant updatedAt
) {

    /** 事实表允许的最大查询条数，防止一次会话查询把控制面内存和网络打满。 */
    public static final int MAX_QUERY_LIMIT = 1000;

    /** 事实摘要在数据库中的最大长度；摘要越短，越容易保持低敏和可审计。 */
    public static final int MAX_SUMMARY_LENGTH = 2048;

    /** 只允许引用型标识进入 refs 列表，不允许把 JSON、URL 查询参数或正文当作引用写入。 */
    private static final Pattern SAFE_REFERENCE = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9._:/-]{0,255}"
    );

    /**
     * 这些关键词代表正文型敏感内容的典型信号。
     *
     * <p>这不是完整的 DLP 引擎，但它能在事实库的第一道边界阻止最容易误用的字段。
     * 真正的 prompt、模型原文和工具参数应由独立的受控存储或脱敏服务处理，不能通过本表绕过。</p>
     */
    private static final Pattern FORBIDDEN_SUMMARY_CONTENT = Pattern.compile(
            "(?i)(prompt|chain\\s*[-_ ]?of\\s*[-_ ]?thought|thought\\s*process|"
                    + "\\breasoning\\b|\\b(sql|select|insert|update|delete|truncate|drop|alter)\\b|"
                    + "tool\\s*[-_ ]?(argument|parameter)s?|credential|password|secret|"
                    + "access\\s*[-_ ]?token|sample\\s*data|raw\\s*output|model\\s*output|stdout|stderr)"
    );

    /**
     * record 紧凑构造器负责做领域归一化，而不是把校验推迟到 JDBC 层。
     *
     * <p>这样做有两个好处：第一，内存测试、Kafka 消费和 JDBC 写入使用同一份安全语义；
     * 第二，幂等更新时不会因为大小写或空白差异产生不可解释的重复事实。</p>
     */
    public SpecialistTurnFact {
        userId = requiredText(userId, "userId", 128);
        tenantId = requiredPositive(tenantId, "tenantId");
        applicationId = requiredPositive(applicationId, "applicationId");
        projectId = requiredPositive(projectId, "projectId");
        sessionId = requiredText(sessionId, "sessionId", 160);
        runId = requiredText(runId, "runId", 160);
        turnId = requiredText(turnId, "turnId", 160);
        idempotencyKey = requiredText(idempotencyKey, "idempotencyKey", 320);
        agentId = safeReference(requiredText(agentId, "agentId", 160), "agentId");
        role = requiredText(role, "role", 80).toUpperCase(Locale.ROOT);
        delegationId = optionalReference(delegationId, "delegationId");
        status = requiredText(status, "status", 40).toUpperCase(Locale.ROOT);
        lowSensitiveSummary = safeSummary(lowSensitiveSummary);
        modelInvocationId = optionalReference(modelInvocationId, "modelInvocationId");
        modelName = optionalReference(modelName, "modelName");
        toolActivitySummaryRefs = safeReferences(toolActivitySummaryRefs, "toolActivitySummaryRefs");
        evidenceRefs = safeReferences(evidenceRefs, "evidenceRefs");
        if (durationMillis != null && durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis 不能小于 0");
        }
        if (startedAt != null && finishedAt != null && finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("finishedAt 不能早于 startedAt");
        }
        if (durationMillis == null && startedAt != null && finishedAt != null) {
            durationMillis = Math.max(0L, finishedAt.toEpochMilli() - startedAt.toEpochMilli());
        }
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt 不能早于 createdAt");
        }
    }

    /**
     * 判断两个事实是否属于同一个不可变 turn 身份。
     *
     * <p>幂等重试只允许更新状态、摘要、引用、模型调用信息和耗时，不能借同一个幂等键
     * 把事实从一个用户、租户、项目、会话或 Agent 迁移到另一个对象。</p>
     */
    public boolean sameIdentity(SpecialistTurnFact other) {
        if (other == null) {
            return false;
        }
        return Objects.equals(userId, other.userId)
                && Objects.equals(tenantId, other.tenantId)
                && Objects.equals(applicationId, other.applicationId)
                && Objects.equals(projectId, other.projectId)
                && Objects.equals(sessionId, other.sessionId)
                && Objects.equals(runId, other.runId)
                && Objects.equals(turnId, other.turnId)
                && Objects.equals(agentId, other.agentId)
                && Objects.equals(role, other.role)
                && Objects.equals(delegationId, other.delegationId);
    }

    /**
     * 判断事实是否落在某个查询范围内。
     *
     * <p>Store 会把范围下沉到 SQL；Service 还会再次调用本方法，形成“数据库过滤 + 对象过滤”
     * 两层边界，避免未来某个 Store 实现遗漏条件后直接把越权记录返回给 Controller。</p>
     */
    public boolean belongsTo(QueryScope scope) {
        if (scope == null
                || !Objects.equals(tenantId, scope.tenantId())
                || !Objects.equals(applicationId, scope.applicationId())
                || !Objects.equals(projectId, scope.projectId())) {
            return false;
        }
        return scope.actorId() == null || Objects.equals(userId, scope.actorId());
    }

    /**
     * 生成普通用户查询范围。
     *
     * @param tenantId 当前租户
     * @param applicationId 当前受信应用
     * @param projectId 当前项目
     * @param actorId 当前登录用户
     * @return 只允许读取本人事实的范围
     */
    public static QueryScope userScope(Long tenantId, Long applicationId, Long projectId, String actorId) {
        return new QueryScope(tenantId, applicationId, projectId, requiredText(actorId, "actorId", 128), false);
    }

    /**
     * 生成经过上层角色判断的项目内审计读取范围。
     *
     * <p>这个工厂方法不授予权限，只表达“调用方已经被 Controller/Service 判定可以查看项目内其他用户事实”。
     * 调用方不能通过请求体传入 {@code allowOtherActors=true}，Controller 不暴露这个字段。</p>
     */
    public static QueryScope projectAuditScope(Long tenantId, Long applicationId, Long projectId) {
        return new QueryScope(tenantId, applicationId, projectId, null, true);
    }

    /**
     * 查询范围是一个低敏内部对象，不包含 prompt、工具参数或模型输出。
     *
     * <p>applicationId 不能由 projectId 推断后省略。项目编号即使当前全局唯一，也仍应显式携带其所属应用，
     * 这样迁移、导入和历史数据异常不会把不同产品应用的 Agent 事实混入同一个查询结果。</p>
     */
    public record QueryScope(Long tenantId, Long applicationId, Long projectId, String actorId, boolean allowOtherActors) {

        /** 对查询范围做最小校验，防止空范围被解释成全库扫描。 */
        public QueryScope {
            if (tenantId == null || tenantId <= 0) {
                throw new IllegalArgumentException("查询范围 tenantId 必须是正整数");
            }
            if (applicationId == null || applicationId <= 0) {
                throw new IllegalArgumentException("查询范围 applicationId 必须是正整数");
            }
            if (projectId == null || projectId <= 0) {
                throw new IllegalArgumentException("查询范围 projectId 必须是正整数");
            }
            actorId = actorId == null || actorId.isBlank() ? null : actorId.trim();
            if (!allowOtherActors && actorId == null) {
                throw new IllegalArgumentException("普通用户查询范围必须包含 actorId");
            }
        }
    }

    /** 将用户输入的必填标识归一化，并限制长度避免异常请求制造超大索引键。 */
    private static String requiredText(String value, String field, int maxLength) {
        String normalized = value == null ? null : value.trim();
        if (normalized == null || normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " 长度不能超过 " + maxLength);
        }
        return normalized;
    }

    /** 校验租户和项目使用正整数，避免 NULL/0 被错误解释为公共范围。 */
    private static Long requiredPositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " 必须是正整数");
        }
        return value;
    }

    /** 校验只应出现稳定标识的字段，拒绝把任意正文伪装成 ID 或引用。 */
    private static String safeReference(String value, String field) {
        if (!SAFE_REFERENCE.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " 只能包含字母、数字、点、冒号、下划线、斜杠和短横线");
        }
        return value;
    }

    /** 处理可为空的 ID/模型名，并保持和必填引用相同的低敏字符约束。 */
    private static String optionalReference(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return safeReference(requiredText(value, field, 256), field);
    }

    /** 只允许可展示的短摘要，明确拒绝正文型敏感字段。 */
    private static String safeSummary(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_SUMMARY_LENGTH) {
            throw new IllegalArgumentException("lowSensitiveSummary 长度不能超过 " + MAX_SUMMARY_LENGTH);
        }
        if (FORBIDDEN_SUMMARY_CONTENT.matcher(normalized).find()) {
            throw new IllegalArgumentException(
                    "lowSensitiveSummary 只能保存低敏摘要，不能包含 prompt、思维链、SQL、工具参数、凭据、样本或模型输出正文"
            );
        }
        return normalized;
    }

    /** 去重并校验引用列表；LinkedHashSet 保留模型活动发生的可读顺序。 */
    private static List<String> safeReferences(List<String> values, String field) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            unique.add(safeReference(requiredText(value, field + " item", 256), field + " item"));
            if (unique.size() > 100) {
                throw new IllegalArgumentException(field + " 最多只能包含 100 个引用");
            }
        }
        return List.copyOf(new ArrayList<>(unique));
    }
}

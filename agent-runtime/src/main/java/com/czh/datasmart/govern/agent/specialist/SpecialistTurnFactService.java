/**
 * @Author : Cui
 * @Date: 2026/08/05 00:00
 * @Description DataSmart Govern Backend - SpecialistTurnFactService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.specialist;

import com.czh.datasmart.govern.agent.service.session.AgentSessionAccessContext;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 专业 Agent turn 事实应用服务。
 *
 * <p>Service 是事实写入和读取的第二道安全边界。Controller 负责 HTTP 输入与受信 Header，
 * Store 负责 SQL 和数据库约束，而本类负责把两者串起来：登记前检查服务身份，查询前要求完整
 * 的当前用户上下文，查询后再次对每条结果做租户、项目和操作者归属校验。</p>
 *
 * <p>这里不把请求体中的 tenantId、projectId 或 userId 当作查询授权依据。普通客户端只能使用
 * Gateway 注入的 {@link AgentSessionAccessContext}；即使未来某个 Controller 错误地把查询参数
 * 传进来，也不能绕过本层的对象归属判断。</p>
 */
@Service
@ConditionalOnBean(SpecialistTurnFactStore.class)
@RequiredArgsConstructor
public class SpecialistTurnFactService {

    /**
     * 只有这些终态表示专业 Agent 已经成功完成可作为提交结论依据的工作。
     *
     * <p>Python 专业 Agent 的领域协议使用 {@code COMPLETED}，Java 工具审计历史中仍可能使用
     * {@code SUCCEEDED}。两者都属于成功终态；{@code RUNNING}、{@code WAITING_FOR_INPUT}、
     * {@code WARNING}、{@code FAILED} 等状态即使有文本摘要，也不能被解释为完成证据。</p>
     */
    private static final Set<String> TERMINAL_SUCCESS_STATUSES = Set.of("COMPLETED", "SUCCEEDED");

    /** 低敏事实的持久化端口。 */
    private final SpecialistTurnFactStore store;

    /** 只保护“谁能登记”，不替代业务对象范围校验。 */
    private final SpecialistTurnFactTrustedServiceGuard trustedServiceGuard;

    /**
     * 登记或幂等更新一条专业 Agent turn 事实。
     *
     * <p>受信服务认证先于事实写入执行。这样浏览器即使构造出完整的低敏 JSON，也不能直接
     * 伪造一个“模型已经调用”“专业 Agent 已完成”的事实。幂等冲突和身份冲突由 Store 继续处理。</p>
     *
     * @param fact 低敏专业 Agent turn 事实
     * @param sourceService Gateway 清理后的来源服务名
     * @param presentedToken 内部共享凭证
     * @return 数据库最终保存的事实
     */
    public SpecialistTurnFact register(SpecialistTurnFact fact,
                                       String sourceService,
                                       String presentedToken) {
        trustedServiceGuard.requireTrustedRegistration(sourceService, presentedToken);
        if (fact == null) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, "专业 Agent turn 事实不能为空");
        }
        return store.save(fact);
    }

    /**
     * 按 session 查询当前用户可见的专业 Agent turn 事实。
     *
     * <p>普通用户的 SQL 范围已经包含 userId；返回后还会调用 {@link #canRead} 再做一次对象级
     * 校验。跨 actor 能力必须同时满足角色、显式 dataScope 和项目授权快照；即使是平台管理员，
     * 返回结果也必须精确匹配当前可信 tenant/project，不能因为角色名而绕过对象边界。</p>
     *
     * @param sessionId 会话 ID
     * @param accessContext Gateway 注入的当前用户上下文
     * @param limit 最大返回条数
     * @return 过滤后的低敏事实列表
     */
    public List<SpecialistTurnFact> findBySession(String sessionId,
                                                  AgentSessionAccessContext accessContext,
                                                  int limit) {
        requireQueryAccess(accessContext);
        String normalizedSessionId = requiredIdentifier(sessionId, "sessionId");
        SpecialistTurnFact.QueryScope scope = queryScope(accessContext);
        return store.findBySession(scope, normalizedSessionId, normalizeLimit(limit)).stream()
                .filter(fact -> canRead(fact, accessContext))
                .filter(fact -> fact.belongsTo(scope))
                .toList();
    }

    /**
     * 按 run 查询当前用户可见的专业 Agent turn 事实。
     *
     * <p>runId 本身不是权限凭据。攻击者即使猜中一个 runId，也必须同时满足当前租户、项目和
     * 操作者的对象归属校验，才能得到任何记录。</p>
     *
     * @param runId Agent Run ID
     * @param accessContext Gateway 注入的当前用户上下文
     * @param limit 最大返回条数
     * @return 过滤后的低敏事实列表
     */
    public List<SpecialistTurnFact> findByRun(String runId,
                                              AgentSessionAccessContext accessContext,
                                              int limit) {
        requireQueryAccess(accessContext);
        String normalizedRunId = requiredIdentifier(runId, "runId");
        SpecialistTurnFact.QueryScope scope = queryScope(accessContext);
        return store.findByRun(scope, normalizedRunId, normalizeLimit(limit)).stream()
                .filter(fact -> canRead(fact, accessContext))
                .filter(fact -> fact.belongsTo(scope))
                .toList();
    }

    /**
     * 判断当前确认后链路是否已经拥有全部要求的持久化专业 Agent 成功证据。
     *
     * <p>这是供 {@code AgentRunConfirmedExecutionService} 使用的内部控制面判定，不向浏览器暴露事实内容。
     * 它不会信任 Python continuation 中的 {@code executedRoles} 或 {@code status} 字段，而是只从 Store
     * 查询同一用户范围内的事实，并再次逐条比对 tenant/application/project/user/session/run 以及
     * 从主委托派生出的 Specialist 子委托。
     * 每个要求角色还必须同时具备非空的专业 Agent 身份、成功终态和至少一条低敏证据引用。</p>
     *
     * <p>方法在任何边界缺失、事实服务未正确装配后返回 {@code false}，而不是放宽查询条件。这样调用方可以
     * 将结果统一处理为“后置复核不完整”，既不会泄露跨范围事实，也不会把临时内存状态误判为可审计完成。</p>
     *
     * @param tenantId 已由会话归属确认的租户 ID
     * @param applicationId Gateway 从权限中心重建的当前应用 ID
     * @param projectId 已由会话归属确认的项目 ID
     * @param userId 发起并授权本次 Agent 操作的用户 ID
     * @param sessionId 当前受治理 Agent 会话 ID
     * @param runId 当前确认后继续处理的源 Run ID
     * @param delegationId 当前用户委托给主 Agent 的父委托 ID；专业 Agent 使用由它派生的子委托
     * @param requiredRoles 必须完成并留证的专业 Agent 角色集合
     * @return 每个要求角色都有同一双主体责任链下的成功事实与证据时返回 {@code true}
     */
    public boolean hasTerminalSuccessfulEvidenceForRoles(Long tenantId,
                                                          Long applicationId,
                                                          Long projectId,
                                                          String userId,
                                                          String sessionId,
                                                          String runId,
                                                          String delegationId,
                                                          Collection<String> requiredRoles) {
        if (!positive(tenantId) || !positive(applicationId) || !positive(projectId)
                || blank(userId) || blank(sessionId) || blank(runId) || blank(delegationId)) {
            return false;
        }
        Set<String> expectedRoles = normalizedRoleCodes(requiredRoles);
        if (expectedRoles.isEmpty()) {
            return false;
        }

        /*
         * 使用 userScope 而非 projectAuditScope，避免内部完成判定因为“项目中有人是管理员”而读取到
         * 另一位用户或另一条委托的事实。Store SQL 已收窄一次，下面的 object-level filter 再收窄一次，
         * 以抵御未来缓存/读副本实现遗漏 WHERE 条件的风险。
         */
        SpecialistTurnFact.QueryScope scope = SpecialistTurnFact.userScope(
                tenantId, applicationId, projectId, userId.trim());
        Set<String> verifiedRoles = new LinkedHashSet<>();
        for (SpecialistTurnFact fact : store.findByRun(scope, runId.trim(), SpecialistTurnFact.MAX_QUERY_LIMIT)) {
            if (!matchesPostConfirmEvidenceScope(fact, scope, sessionId, runId, delegationId)
                    || !hasTerminalSuccessfulEvidence(fact)) {
                continue;
            }
            String role = normalizeCode(fact.role());
            if (expectedRoles.contains(role)) {
                verifiedRoles.add(role);
            }
        }
        return verifiedRoles.containsAll(expectedRoles);
    }

    /**
     * 对 Store 返回的候选再次校验不可变的双主体责任链。
     *
     * <p>{@link SpecialistTurnFact#belongsTo(SpecialistTurnFact.QueryScope)} 覆盖用户、租户、应用和项目；
     * 此处补上 session/run 和父子 delegation 链，并要求 Agent 身份非空。专业 Agent 的 {@code agentId}
     * 与主编排 Agent 可能不同，delegationId 也必须是每个 turn 独立的子委托，不能错误地要求它等于主会话
     * 父委托。Java 使用与 Python 相同的公开派生合同独立重算子委托；任意字符串、另一个 turn/role/run 的
     * 子委托或旧会话事实都无法通过比较。</p>
     */
    private boolean matchesPostConfirmEvidenceScope(SpecialistTurnFact fact,
                                                    SpecialistTurnFact.QueryScope scope,
                                                    String sessionId,
                                                    String runId,
                                                    String parentDelegationId) {
        return fact != null
                && fact.belongsTo(scope)
                && sessionId.trim().equals(fact.sessionId())
                && runId.trim().equals(fact.runId())
                && expectedChildDelegationId(scope, sessionId, runId, parentDelegationId, fact)
                        .equals(fact.delegationId())
                && !blank(fact.agentId());
    }

    /**
     * 根据主会话父委托和 Specialist turn 身份重算预期子委托。
     *
     * <p>字段顺序是 Python/Java 的跨语言协议，不可随意调整：tenant、project、actor、session、run、
     * parent delegation、turn、role。applicationId 已由独立字段和查询范围校验，不重复放入历史算法，
     * 避免应用范围升级改变已持久化事实的派生结果。SHA-256 的前 24 个十六进制字符只用于稳定定位，
     * 真正授权仍由 Gateway、会话归属、工具白名单和事实登记服务身份共同完成。</p>
     */
    private String expectedChildDelegationId(SpecialistTurnFact.QueryScope scope,
                                             String sessionId,
                                             String runId,
                                             String parentDelegationId,
                                             SpecialistTurnFact fact) {
        String material = String.join("|",
                String.valueOf(scope.tenantId()),
                String.valueOf(scope.projectId()),
                scope.actorId(),
                sessionId.trim(),
                runId.trim(),
                parentDelegationId.trim(),
                fact.turnId(),
                normalizeCode(fact.role()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return "delegation-" + HexFormat.of().formatHex(digest).substring(0, 24);
        } catch (NoSuchAlgorithmException exception) {
            // JDK 21 guarantees SHA-256.  Treat a broken provider installation as a fatal configuration
            // problem rather than falling back to a weaker or non-deterministic delegation identifier.
            throw new IllegalStateException("JDK SHA-256 provider is unavailable", exception);
        }
    }

    /**
     * 将“成功”限定为终态加可回查低敏证据，避免仅靠角色名称或模型文本提前宣布完成。
     */
    private boolean hasTerminalSuccessfulEvidence(SpecialistTurnFact fact) {
        return TERMINAL_SUCCESS_STATUSES.contains(normalizeCode(fact.status()))
                && fact.evidenceRefs() != null
                && fact.evidenceRefs().stream().anyMatch(reference -> !blank(reference));
    }

    /** 将调用方传入的角色集合规整为固定协议码，空值和空白值不产生默认角色。 */
    private Set<String> normalizedRoleCodes(Collection<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String role : roles) {
            if (!blank(role)) {
                normalized.add(normalizeCode(role));
            }
        }
        return Set.copyOf(normalized);
    }

    /** 统一规范化状态和角色等固定协议字符串，确保大小写不会影响安全判断。 */
    private String normalizeCode(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    /** 数值范围不能以 0、负数或空值降级成全局范围。 */
    private boolean positive(Long value) {
        return value != null && value > 0;
    }

    /** 空白字段不能作为受治理身份、定位符或低敏证据引用。 */
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 把当前访问上下文转成 Store 可执行的查询范围。
     *
     * <p>普通用户必须带 userId；具备只读审计角色的调用者才可以在当前应用、项目内读取其他用户事实。
     * 这个方法只映射已经确定的权限结果，不接受 HTTP 请求体中的 allowOtherActors 开关。</p>
     */
    private SpecialistTurnFact.QueryScope queryScope(AgentSessionAccessContext accessContext) {
        if (accessContext.canReadOtherActorsForSpecialistFacts()) {
            return SpecialistTurnFact.projectAuditScope(
                    accessContext.tenantId(), accessContext.applicationId(), accessContext.projectId());
        }
        return SpecialistTurnFact.userScope(
                accessContext.tenantId(),
                accessContext.applicationId(),
                accessContext.projectId(),
                accessContext.actorId()
        );
    }

    /**
     * 对单条返回记录执行第二次对象归属校验。
     *
     * <p>数据库 WHERE 是性能和第一层隔离，代码过滤是纵深防御。尤其当未来加入缓存 Store、
     * 读副本或事件投影 Store 时，不能假设每个实现都和 JDBC 一样严格下沉了所有范围条件。</p>
     */
    private boolean canRead(SpecialistTurnFact fact, AgentSessionAccessContext accessContext) {
        if (fact == null || accessContext == null) {
            return false;
        }
        /*
         * 这里故意不使用 platformAdministrator() 绕过 tenant/application/project 比较。
         * “平台管理员能否访问哪个租户/应用/项目”由 Gateway + permission-admin 先决定，当前上下文中的
         * 三个 ID 只是本次已授权目标；Service 仍必须把它们当作不可变对象边界做第二次比较。
         * 这样即使 Store 实现、缓存或读副本未来错误地返回了其他范围记录，也不会把跨租户、跨应用或跨项目事实泄露出去。
         */
        boolean tenantMatches = Objects.equals(fact.tenantId(), accessContext.tenantId());
        boolean applicationMatches = Objects.equals(fact.applicationId(), accessContext.applicationId());
        boolean projectMatches = Objects.equals(fact.projectId(), accessContext.projectId());
        boolean actorMatches = Objects.equals(
                fact.userId(),
                accessContext.actorId() == null ? null : accessContext.actorId().trim()
        );
        return tenantMatches && applicationMatches && projectMatches
                && (actorMatches || accessContext.canReadOtherActorsForSpecialistFacts());
    }

    /** 缺少租户、应用、项目或操作者时 fail-closed，绝不能退化成全表查询。 */
    private void requireQueryAccess(AgentSessionAccessContext accessContext) {
        requireTrustedAuthorizationSnapshot(accessContext);
        if (accessContext == null
                || accessContext.tenantId() == null
                || accessContext.applicationId() == null
                || accessContext.projectId() == null
                || accessContext.tenantId() <= 0
                || accessContext.applicationId() <= 0
                || accessContext.projectId() <= 0
                || accessContext.actorId() == null
                || accessContext.actorId().isBlank()) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.FORBIDDEN,
                    "查询专业 Agent turn 事实必须提供可信的租户、应用、项目和操作者上下文"
            );
        }
    }

    /** 限制 Controller 传入的分页上限，并保证 JDBC LIMIT 永远是正数。 */
    /**
     * 校验当前请求是否携带了能够证明“当前项目可读”的完整权限快照。
     *
      * <p>事实中的 tenantId、applicationId、projectId、userId 既用于审计，也用于数据库过滤，
     * 但它们本身不是授权决定。Gateway 还必须把 permission-admin 的数据范围和项目
     * 授权快照注入到上下文中：SELF/PROJECT 必须命中当前项目授权集合，TENANT/PLATFORM
     * 则必须同时满足可信数据范围和可跨 actor 审计的角色。任何缺失或未知的组合都直接
     * 拒绝，避免权限中心故障或旧调用方把查询降级成默认放行。</p>
     */
    private void requireTrustedAuthorizationSnapshot(AgentSessionAccessContext accessContext) {
        if (accessContext == null) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.FORBIDDEN,
                    "查询专业 Agent turn 事实必须提供可信的授权上下文"
            );
        }

        switch (accessContext.normalizedDataScopeLevel()) {
            case "SELF", "PROJECT" -> {
                if (!accessContext.currentProjectIsAuthorized()) {
                    throw new PlatformBusinessException(
                            PlatformErrorCode.FORBIDDEN,
                            "当前请求没有可信的项目授权快照，拒绝查询专业 Agent turn 事实"
                    );
                }
            }
            case "TENANT", "PLATFORM" -> {
                if (!accessContext.canReadOtherActorsForSpecialistFacts()) {
                    throw new PlatformBusinessException(
                            PlatformErrorCode.FORBIDDEN,
                            "当前请求没有可信的租户或平台审计读取范围，拒绝查询专业 Agent turn 事实"
                    );
                }
            }
            default -> throw new PlatformBusinessException(
                    PlatformErrorCode.FORBIDDEN,
                    "专业 Agent turn 事实查询缺少明确的数据范围授权，拒绝继续"
            );
        }
    }

    private int normalizeLimit(int limit) {
        return Math.max(1, Math.min(limit <= 0 ? 100 : limit, SpecialistTurnFact.MAX_QUERY_LIMIT));
    }

    /** 校验查询定位符，防止空 ID 触发无意义的数据库访问或错误日志。 */
    private String requiredIdentifier(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, field + " 不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() > 160) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST, field + " 长度不能超过 160");
        }
        return normalized;
    }
}

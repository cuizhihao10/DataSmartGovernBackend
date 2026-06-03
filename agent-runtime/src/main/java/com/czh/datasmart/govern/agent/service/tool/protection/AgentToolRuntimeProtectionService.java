/**
 * @Author : Cui
 * @Date: 2026/06/04 00:03
 * @Description DataSmart Govern Backend - AgentToolRuntimeProtectionService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.tool.protection;

import com.czh.datasmart.govern.agent.config.AgentToolRuntimeProtectionProperties;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 工具运行时保护服务。
 *
 * <p>该服务解决的是 Agent 工具执行进入真实下游前的“容量与健康”问题。
 * sandbox 已经能回答“这个工具计划是否安全”，但即使工具安全，也可能因为以下原因不应该立刻执行：
 * 1. 当前 JVM 已经有太多工具在执行，继续放行会打满线程、连接池或内存；
 * 2. 某个租户正在异常高频触发工具，继续放行会影响其他租户；
 * 3. 某个 targetService 已经达到本地并发上限，继续调用会把下游压垮；
 * 4. targetService 连续失败，说明下游可能不可用，应先熔断冷却。</p>
 *
 * <p>当前实现是本地内存版，适合现阶段仓库成熟度：
 * - 不引入 Redis、Sentinel、Resilience4j 或服务网格，避免第一版过重；
 * - 通过清晰的 verdict 和 lease 契约固定业务语义；
 * - 后续只需要替换本服务内部计数和健康状态存储，即可升级为分布式版本。</p>
 *
 * <p>商业化生产提醒：
 * 多实例部署时，本地 in-flight 只能代表“当前实例压力”，不能代表全平台压力。
 * 真正生产应叠加 Redis 原子令牌桶、租户套餐配额、目标服务健康探针、服务网格熔断、Prometheus 告警和运维手动摘除能力。</p>
 */
@Service
public class AgentToolRuntimeProtectionService {

    private static final String UNKNOWN_TARGET_SERVICE = "UNKNOWN_TARGET_SERVICE";

    private final AgentToolRuntimeProtectionProperties properties;
    private final Clock clock;
    private final AtomicInteger globalInFlight = new AtomicInteger();
    private final Map<Long, AtomicInteger> tenantInFlight = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> targetServiceInFlight = new ConcurrentHashMap<>();
    private final Map<String, TargetServiceHealth> targetServiceHealth = new ConcurrentHashMap<>();

    /**
     * Spring 生产路径构造函数。
     *
     * <p>使用系统 UTC 时钟记录熔断截止时间。展示给前端时可以再按用户时区格式化；
     * 服务内部不要混用本地时区，避免多实例部署和夏令时导致判断不一致。</p>
     */
    @Autowired
    public AgentToolRuntimeProtectionService(AgentToolRuntimeProtectionProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /**
     * 测试构造函数。
     *
     * <p>允许传入固定 Clock，方便稳定验证熔断冷却期，而不需要在单测里 sleep。</p>
     */
    public AgentToolRuntimeProtectionService(AgentToolRuntimeProtectionProperties properties, Clock clock) {
        this.properties = properties == null ? new AgentToolRuntimeProtectionProperties() : properties;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * 创建一个关闭保护的服务实例，主要用于旧单测兼容。
     *
     * <p>旧测试通常只关心工具适配器是否返回正确结果，不希望突然被运行时保护计数影响。
     * Spring 生产环境不会使用该方法，而是注入配置化 bean。</p>
     */
    public static AgentToolRuntimeProtectionService disabledForTests() {
        AgentToolRuntimeProtectionProperties properties = new AgentToolRuntimeProtectionProperties();
        properties.setEnabled(false);
        return new AgentToolRuntimeProtectionService(properties);
    }

    /**
     * 只读检查某个工具计划当前是否满足运行时保护条件。
     *
     * @param session 当前会话事实，预留给后续按工作空间、角色或套餐做差异化保护。
     * @param run 当前 Run 事实，预留给后续按 run 优先级、DAG 批次或任务类型做差异化保护。
     * @param audit 工具执行审计事实，是当前版本判断 targetService、tenantId、toolCode 的核心输入。
     * @return 低敏、可展示、可告警的运行时保护 verdict。
     */
    public AgentToolRuntimeProtectionVerdict inspect(AgentSessionRecord session,
                                                     AgentRunRecord run,
                                                     AgentToolExecutionAuditRecord audit) {
        return inspectWithCounts(audit, currentCounts(audit));
    }

    /**
     * 进入真实工具执行区。
     *
     * <p>该方法会先占用 in-flight 计数，再基于占用后的计数生成 verdict。
     * 这样可以避免两个请求同时看到“还剩一个名额”而都放行，导致实际并发超过上限。</p>
     *
     * @return 运行时保护租约，调用方必须在 finally 中 close。
     */
    public AgentToolRuntimeProtectionLease beginExecution(AgentSessionRecord session,
                                                          AgentRunRecord run,
                                                          AgentToolExecutionAuditRecord audit) {
        if (!Boolean.TRUE.equals(properties.getEnabled())) {
            return new AgentToolRuntimeProtectionLease(() -> { }, () -> { }, (errorCode, message) -> { });
        }
        CountSnapshot counts = incrementCounts(audit);
        AgentToolRuntimeProtectionVerdict verdict = inspectWithCounts(audit, counts);
        if (!Boolean.TRUE.equals(verdict.allowed())) {
            releaseCounts(audit);
            throw new PlatformBusinessException(
                    PlatformErrorCode.RATE_LIMITED,
                    "Agent 工具运行时保护拒绝执行，toolCode=" + audit.getToolCode()
                            + "，targetService=" + verdict.targetService()
                            + "，issueCodes=" + verdict.issueCodes()
                            + "，reasons=" + verdict.reasons()
            );
        }
        return new AgentToolRuntimeProtectionLease(
                () -> releaseCounts(audit),
                () -> recordSuccess(audit),
                (errorCode, message) -> recordFailure(audit, errorCode)
        );
    }

    private AgentToolRuntimeProtectionVerdict inspectWithCounts(AgentToolExecutionAuditRecord audit, CountSnapshot counts) {
        List<String> issueCodes = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        boolean enabled = Boolean.TRUE.equals(properties.getEnabled());
        String targetService = normalizeTargetService(audit.getTargetService());
        TargetServiceHealth health = health(targetService);
        Instant now = Instant.now(clock);
        Instant circuitOpenUntil = health.circuitOpenUntil();
        boolean circuitOpen = circuitOpenUntil != null && circuitOpenUntil.isAfter(now);

        if (!enabled) {
            reasons.add("工具运行时保护当前已关闭，本次不会执行本地并发限制和连续失败熔断。");
            actions.add("生产环境建议开启 datasmart.agent-runtime.tool-runtime-protection.enabled，并配置租户、目标服务和全局并发上限。");
            return verdict(audit, targetService, counts, health, false, false, null, issueCodes, reasons, actions);
        }

        if (UNKNOWN_TARGET_SERVICE.equals(targetService)) {
            addIssue(issueCodes, reasons, actions,
                    "TARGET_SERVICE_EMPTY_FOR_RUNTIME_PROTECTION",
                    "工具缺少 targetService，运行时保护无法判断应该按哪个下游服务做并发隔离和熔断。",
                    "请先补齐工具目录和审计记录中的 targetService，再进入真实执行。");
        }
        if (counts.globalInFlight() > maxGlobalInFlight()) {
            addIssue(issueCodes, reasons, actions,
                    "GLOBAL_IN_FLIGHT_LIMIT_EXCEEDED",
                    "当前 agent-runtime 实例内工具执行总并发已超过配置上限，继续放行可能打满执行线程、连接池或内存。",
                    "请等待部分工具执行完成，或在压测后调大 max-global-in-flight；生产多实例应叠加全局配额中心。");
        }
        if (audit.getTenantId() != null && counts.tenantInFlight() > maxTenantInFlight()) {
            addIssue(issueCodes, reasons, actions,
                    "TENANT_IN_FLIGHT_LIMIT_EXCEEDED",
                    "当前租户在本实例内的工具执行并发已超过上限，继续放行会影响其他租户的公平性。",
                    "请降低该租户批量执行频率，或按租户套餐/SLA 调整 max-tenant-in-flight。");
        }
        if (counts.targetServiceInFlight() > maxTargetServiceInFlight()) {
            addIssue(issueCodes, reasons, actions,
                    "TARGET_SERVICE_IN_FLIGHT_LIMIT_EXCEEDED",
                    "目标服务当前工具并发已超过上限，继续调用可能压垮该下游微服务。",
                    "请等待该 targetService 的工具调用完成，或为该服务单独扩容连接池、worker 和限流阈值。");
        }
        if (Boolean.TRUE.equals(properties.getCircuitBreakerEnabled()) && circuitOpen) {
            addIssue(issueCodes, reasons, actions,
                    "TARGET_SERVICE_CIRCUIT_OPEN",
                    "目标服务最近连续失败已触发熔断，冷却期内拒绝新的 Agent 工具调用，避免故障被模型循环放大。",
                    "请检查目标服务健康、接口变更、网络连通性和最近错误码；必要时等待冷却期结束后再重试。");
        }

        if (issueCodes.isEmpty()) {
            reasons.add("工具运行时保护检查通过：当前全局、租户和目标服务并发未超过上限，目标服务未处于熔断冷却期。");
            actions.add("可以继续进入工具执行服务；执行完成后应回写成功/失败，以便刷新目标服务健康状态。");
        }
        return verdict(audit, targetService, counts, health, true, circuitOpen, circuitOpenUntil, issueCodes, reasons, actions);
    }

    private CountSnapshot currentCounts(AgentToolExecutionAuditRecord audit) {
        String targetService = normalizeTargetService(audit.getTargetService());
        return new CountSnapshot(
                globalInFlight.get(),
                audit.getTenantId() == null ? 0 : counterValue(tenantInFlight.get(audit.getTenantId())),
                counterValue(targetServiceInFlight.get(targetService))
        );
    }

    private CountSnapshot incrementCounts(AgentToolExecutionAuditRecord audit) {
        String targetService = normalizeTargetService(audit.getTargetService());
        int global = globalInFlight.incrementAndGet();
        int tenant = audit.getTenantId() == null
                ? 0
                : tenantInFlight.computeIfAbsent(audit.getTenantId(), key -> new AtomicInteger()).incrementAndGet();
        int target = targetServiceInFlight.computeIfAbsent(targetService, key -> new AtomicInteger()).incrementAndGet();
        return new CountSnapshot(global, tenant, target);
    }

    private void releaseCounts(AgentToolExecutionAuditRecord audit) {
        decrement(globalInFlight);
        if (audit.getTenantId() != null) {
            decrement(tenantInFlight.get(audit.getTenantId()));
        }
        decrement(targetServiceInFlight.get(normalizeTargetService(audit.getTargetService())));
    }

    private void recordSuccess(AgentToolExecutionAuditRecord audit) {
        health(normalizeTargetService(audit.getTargetService())).recordSuccess();
    }

    private void recordFailure(AgentToolExecutionAuditRecord audit, String errorCode) {
        if (!Boolean.TRUE.equals(properties.getCircuitBreakerEnabled())) {
            return;
        }
        if (ignoredFailureCode(errorCode)) {
            return;
        }
        TargetServiceHealth health = health(normalizeTargetService(audit.getTargetService()));
        int failures = health.recordFailure();
        if (failures >= consecutiveFailureThreshold()) {
            health.openCircuit(Instant.now(clock).plusSeconds(circuitOpenSeconds()));
        }
    }

    private AgentToolRuntimeProtectionVerdict verdict(AgentToolExecutionAuditRecord audit,
                                                      String targetService,
                                                      CountSnapshot counts,
                                                      TargetServiceHealth health,
                                                      boolean protectionEnabled,
                                                      boolean circuitOpen,
                                                      Instant circuitOpenUntil,
                                                      List<String> issueCodes,
                                                      List<String> reasons,
                                                      List<String> actions) {
        return new AgentToolRuntimeProtectionVerdict(
                audit.getAuditId(),
                audit.getToolCode(),
                targetService,
                audit.getTenantId(),
                protectionEnabled,
                issueCodes.isEmpty(),
                counts.globalInFlight(),
                counts.tenantInFlight(),
                counts.targetServiceInFlight(),
                maxGlobalInFlight(),
                maxTenantInFlight(),
                maxTargetServiceInFlight(),
                Boolean.TRUE.equals(properties.getCircuitBreakerEnabled()),
                circuitOpen,
                circuitOpenUntil,
                health.consecutiveFailures(),
                List.copyOf(issueCodes),
                List.copyOf(reasons),
                List.copyOf(actions)
        );
    }

    private TargetServiceHealth health(String targetService) {
        return targetServiceHealth.computeIfAbsent(targetService, key -> new TargetServiceHealth());
    }

    private boolean ignoredFailureCode(String errorCode) {
        if (errorCode == null || errorCode.isBlank()) {
            return false;
        }
        String normalized = normalize(errorCode);
        List<String> ignoredCodes = properties.getIgnoredCircuitFailureCodes() == null
                ? List.of()
                : properties.getIgnoredCircuitFailureCodes();
        return ignoredCodes.stream()
                .map(this::normalize)
                .anyMatch(normalized::equals);
    }

    private String normalizeTargetService(String targetService) {
        if (targetService == null || targetService.isBlank()) {
            return UNKNOWN_TARGET_SERVICE;
        }
        return targetService.trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private void addIssue(List<String> issueCodes,
                          List<String> reasons,
                          List<String> actions,
                          String issueCode,
                          String reason,
                          String action) {
        issueCodes.add(issueCode);
        reasons.add(reason);
        actions.add(action);
    }

    private int counterValue(AtomicInteger counter) {
        return counter == null ? 0 : Math.max(0, counter.get());
    }

    private void decrement(AtomicInteger counter) {
        if (counter == null) {
            return;
        }
        counter.updateAndGet(value -> Math.max(0, value - 1));
    }

    private int maxGlobalInFlight() {
        return positive(properties.getMaxGlobalInFlight(), 50);
    }

    private int maxTenantInFlight() {
        return positive(properties.getMaxTenantInFlight(), 20);
    }

    private int maxTargetServiceInFlight() {
        return positive(properties.getMaxTargetServiceInFlight(), 10);
    }

    private int consecutiveFailureThreshold() {
        return positive(properties.getConsecutiveFailureThreshold(), 3);
    }

    private long circuitOpenSeconds() {
        Long configured = properties.getCircuitOpenSeconds();
        return Math.max(1L, configured == null ? 60L : configured);
    }

    private int positive(Integer configured, int fallback) {
        return Math.max(1, configured == null ? fallback : configured);
    }

    private record CountSnapshot(int globalInFlight, int tenantInFlight, int targetServiceInFlight) {
    }

    private static final class TargetServiceHealth {

        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private volatile Instant circuitOpenUntil;

        int consecutiveFailures() {
            return consecutiveFailures.get();
        }

        Instant circuitOpenUntil() {
            return circuitOpenUntil;
        }

        void recordSuccess() {
            consecutiveFailures.set(0);
            circuitOpenUntil = null;
        }

        int recordFailure() {
            return consecutiveFailures.incrementAndGet();
        }

        void openCircuit(Instant openUntil) {
            circuitOpenUntil = openUntil;
        }
    }
}

/**
 * @Author : Cui
 * @Date: 2026/06/02 00:26
 * @Description DataSmart Govern Backend - AgentAsyncToolWorkerMetricsService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Agent 异步工具 worker 聚合指标服务。
 *
 * <p>该组件负责把 worker 的执行结果和安全保护原因写入 Micrometer。它与 runtime event 的定位粒度不同：
 * runtime event 用于回答“某一个 Agent 会话为什么被阻断”，指标用于回答“整个实例最近是否持续触发容量保护、
 * 权限拒绝或控制面依赖不可用”。生产环境可以基于这些聚合指标配置 Prometheus 告警和 Grafana 趋势图。</p>
 *
 * <p>标签必须保持低基数。tenantId、projectId、taskId、sessionId、runId、traceId 和原始 toolCode 都不应直接成为
 * Prometheus 标签，否则随着租户、任务和会话数量增长，会生成大量时序并拖慢监控系统。单条任务排障应继续使用
 * runtime event、任务审计记录和结构化日志；如果后续确实需要租户维度指标，应通过受控租户分组、Top-N 聚合或
 * exemplar 关联 trace，而不是直接把业务主键放进标签。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentAsyncToolWorkerMetricsService {

    private static final String METRIC_PREFIX = "datasmart_task_agent_async_worker";
    private static final String OTHER = "OTHER";

    /**
     * dispatch outcome 是固定枚举集合，不能直接接受任意异常 message。
     *
     * <p>保留白名单而不是只做字符串格式校验，是因为格式合法的动态字符串仍然可能制造无限时序。
     * 新增业务结果时，应在代码评审中显式扩展白名单，并同步补充仪表盘含义。</p>
     */
    private static final Set<String> ALLOWED_DISPATCH_OUTCOMES = Set.of(
            "NO_TASK",
            "CAPACITY_LIMITED",
            "COMPLETED",
            "DEFERRED",
            "FAILED"
    );

    /**
     * guardrail 原因码白名单。
     *
     * <p>本集合覆盖当前本地容量保护、执行前安全复核和复核依赖不可用三类原因。
     * 后续接入租户配额、工具级熔断或模型预算控制时，应为新保护门增加稳定 reason code，而不是把下游异常文本作为标签。</p>
     */
    private static final Set<String> ALLOWED_GUARDRAIL_REASON_CODES = Set.of(
            "LOCAL_CONCURRENCY_LIMIT",
            "LOCAL_RATE_LIMIT",
            AgentAsyncToolGuardrailEventSupport.CODE_PRECHECK_REJECTED,
            AgentAsyncToolGuardrailEventSupport.CODE_AUDIT_STATE_REJECTED,
            AgentAsyncToolGuardrailEventSupport.CODE_TOOL_NOT_WHITELISTED,
            AgentAsyncToolGuardrailEventSupport.CODE_CONFIRMATION_REJECTED,
            AgentAsyncToolGuardrailEventSupport.CODE_PERMISSION_DENIED,
            AgentAsyncToolGuardrailEventSupport.CODE_APPROVAL_REQUIRED,
            AgentAsyncToolGuardrailEventSupport.CODE_POLICY_VERSION_DRIFT,
            AgentAsyncToolGuardrailEventSupport.CODE_CONFIRMATION_UNAVAILABLE,
            AgentAsyncToolGuardrailEventSupport.CODE_PERMISSION_UNAVAILABLE,
            AgentAsyncToolGuardrailEventSupport.CODE_PRECHECK_UNAVAILABLE
    );

    private final MeterRegistry meterRegistry;

    /**
     * 记录一次 dispatch-once 的最终调度结果。
     *
     * @param outcome 固定结果枚举，例如 COMPLETED、DEFERRED 或 CAPACITY_LIMITED。
     */
    public void recordDispatchOutcome(String outcome) {
        Counter.builder(METRIC_PREFIX + "_dispatch_total")
                .description("task-management Agent 异步工具 worker 单次调度结果次数")
                .tag("outcome", normalize(outcome, ALLOWED_DISPATCH_OUTCOMES))
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录 claim 之前的实例级容量保护。
     *
     * <p>此时 worker 尚未认领任务，因此没有可靠的 task/session/run 上下文。指标只记录 LOCAL_JVM 范围和稳定原因码，
     * 用于判断单实例并发配置或调度频率是否过紧。</p>
     *
     * @param reasonCode 入场保护拒绝原因，例如 LOCAL_CONCURRENCY_LIMIT。
     */
    public void recordAdmissionRejected(String reasonCode) {
        recordGuardrail("LOCAL_JVM", "BLOCKED", reasonCode);
    }

    /**
     * 记录 claim 之后、真实工具副作用发生之前的安全复核阻断。
     *
     * @param reasonCode 权限拒绝、确认记录不一致、策略漂移等稳定原因码。
     */
    public void recordPreCheckRejected(String reasonCode) {
        recordGuardrail("EXECUTION_PRECHECK", "BLOCKED", reasonCode);
    }

    /**
     * 记录执行前复核依赖暂不可用导致的退避。
     *
     * @param reasonCode confirmation 或 permission-admin 等依赖不可用原因码。
     */
    public void recordPreCheckUnavailable(String reasonCode) {
        recordGuardrail("PRECHECK_DEPENDENCY", "DEFERRED", reasonCode);
    }

    private void recordGuardrail(String scope, String decision, String reasonCode) {
        Counter.builder(METRIC_PREFIX + "_guardrail_total")
                .description("task-management Agent 异步工具 worker 安全保护触发次数")
                .tag("scope", scope)
                .tag("decision", decision)
                .tag("reasonCode", normalize(reasonCode, ALLOWED_GUARDRAIL_REASON_CODES))
                .register(meterRegistry)
                .increment();
    }

    private String normalize(String value, Set<String> allowedValues) {
        if (value == null || !allowedValues.contains(value)) {
            return OTHER;
        }
        return value;
    }
}

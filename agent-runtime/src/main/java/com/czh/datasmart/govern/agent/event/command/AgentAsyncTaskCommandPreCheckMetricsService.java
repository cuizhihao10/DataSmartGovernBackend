/**
 * @Author : Cui
 * @Date: 2026/06/04 01:05
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandPreCheckMetricsService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.event.command;

import com.czh.datasmart.govern.agent.service.execution.AgentAsyncTaskCommandPreCheckVerdict;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Agent 异步 command 执行前复核指标服务。
 *
 * <p>runtime event 解决“某一次 Agent Run 为什么被阻断/暂缓”的明细解释问题；
 * Prometheus 指标解决“整个平台最近是否持续发生确认过期、容量暂缓、策略缺失”的聚合运营问题。
 * 两者不能互相替代：runtime event 可以保留 runId、commandId 等排障上下文，指标则必须严格低基数。</p>
 *
 * <p>本组件专门封装 Micrometer 写入逻辑，避免 dispatcher 直接拼 Counter：
 * - dispatcher 继续表达 outbox 投递状态机；
 * - metrics service 负责标签白名单、指标命名和低基数约束；
 * - Prometheus/Grafana/Alertmanager 后续只依赖稳定指标契约。</p>
 *
 * <p>标签设计原则：
 * 1. `decision` 只允许 ALLOW_EXECUTION、BLOCKED、DEFERRED、OTHER；
 * 2. `issueCode` 只允许 pre-check 服务显式产出的稳定枚举，未知值归并 OTHER；
 * 3. `targetService` 只允许平台已知服务名，未知目标归并 OTHER；
 * 4. 严禁 commandId、outboxId、runId、sessionId、traceId、tenantId、projectId 进入标签。</p>
 */
@Component
@RequiredArgsConstructor
public class AgentAsyncTaskCommandPreCheckMetricsService {

    private static final String METRIC_PREFIX = "datasmart_agent_runtime_async_command_precheck";
    private static final String ALLOW_EXECUTION = "ALLOW_EXECUTION";
    private static final String OTHER = "OTHER";

    /**
     * decision 是复核状态机的稳定输出。新增决策类型时必须显式扩展该白名单和告警规则。
     */
    private static final Set<String> ALLOWED_DECISIONS = Set.of(
            ALLOW_EXECUTION,
            "BLOCKED",
            "DEFERRED",
            OTHER
    );

    /**
     * issueCode 白名单来自 AgentAsyncTaskCommandPreCheckService 当前可产生的稳定机器原因。
     *
     * <p>这里不用异常 message 作为标签，因为 message 往往包含动态 ID、状态文本或下游错误摘要，
     * 会快速膨胀为高基数时序。未知 issueCode 统一归并 OTHER，并由告警提示维护者补充白名单。</p>
     */
    private static final Set<String> ALLOWED_ISSUE_CODES = Set.of(
            "COMMAND_RECORD_MISSING",
            "CONFIRMATION_ID_MISSING",
            "CONFIRMATION_NOT_FOUND",
            "CONFIRMATION_NOT_CONFIRMED",
            "CONFIRMATION_EXPIRED",
            "AUDIT_ID_NOT_IN_CONFIRMATION",
            "COMMAND_ID_NOT_IN_CONFIRMATION",
            "POLICY_VERSION_EVIDENCE_MISMATCH",
            "CURRENT_POLICY_ITEM_MISSING",
            "CURRENT_POLICY_NOT_ASYNC_EXECUTABLE",
            "SANDBOX_REJECTED_BEFORE_WORKER",
            "RUNTIME_PROTECTION_DEFERRED_BEFORE_WORKER",
            OTHER
    );

    /**
     * targetService 只记录平台服务分组，不记录任意 URL、endpoint 或客户自定义动态服务名。
     */
    private static final Set<String> ALLOWED_TARGET_SERVICES = Set.of(
            "task-management",
            "data-sync",
            "datasource-management",
            "data-quality",
            "permission-admin",
            "agent-runtime",
            "unknown",
            OTHER
    );

    private final MeterRegistry meterRegistry;

    /**
     * 记录一次 pre-check verdict。
     *
     * <p>该方法会写入两类指标：</p>
     * <p>1. `*_verdict_total`：每条 command 只计一次，用于计算通过率、阻断率和暂缓率；</p>
     * <p>2. `*_issue_total`：每个 issueCode 计一次，用于观察确认过期、策略缺失、容量暂缓等原因分布。</p>
     *
     * @param record 触发复核的 outbox record，只读取 targetService 这类低基数业务分组。
     * @param verdict pre-check 输出的稳定决策。
     */
    public void recordVerdict(AgentAsyncTaskCommandOutboxRecord record,
                              AgentAsyncTaskCommandPreCheckVerdict verdict) {
        if (verdict == null) {
            return;
        }
        String decision = decision(verdict);
        String targetService = targetService(record);
        Counter.builder(METRIC_PREFIX + "_verdict_total")
                .description("agent-runtime 异步 command dispatcher 执行前复核 verdict 次数")
                .tag("decision", decision)
                .tag("targetService", targetService)
                .register(meterRegistry)
                .increment();
        recordIssues(decision, targetService, verdict.issueCodes());
    }

    private void recordIssues(String decision, String targetService, List<String> issueCodes) {
        if (ALLOW_EXECUTION.equals(decision)) {
            return;
        }
        if (issueCodes == null || issueCodes.isEmpty()) {
            recordIssue(decision, targetService, OTHER);
            return;
        }
        for (String issueCode : issueCodes) {
            recordIssue(decision, targetService, issueCode);
        }
    }

    private void recordIssue(String decision, String targetService, String issueCode) {
        Counter.builder(METRIC_PREFIX + "_issue_total")
                .description("agent-runtime 异步 command dispatcher 执行前复核 issueCode 次数")
                .tag("decision", decision)
                .tag("issueCode", issueCode(issueCode))
                .tag("targetService", targetService)
                .register(meterRegistry)
                .increment();
    }

    private String decision(AgentAsyncTaskCommandPreCheckVerdict verdict) {
        if (Boolean.TRUE.equals(verdict.allowed())) {
            return ALLOW_EXECUTION;
        }
        String normalized = upper(verdict.decision());
        return ALLOWED_DECISIONS.contains(normalized) ? normalized : OTHER;
    }

    private String issueCode(String issueCode) {
        String normalized = upper(issueCode);
        return ALLOWED_ISSUE_CODES.contains(normalized) ? normalized : OTHER;
    }

    private String targetService(AgentAsyncTaskCommandOutboxRecord record) {
        if (record == null || record.targetService() == null || record.targetService().isBlank()) {
            return "unknown";
        }
        String normalized = record.targetService().trim().toLowerCase(Locale.ROOT);
        return ALLOWED_TARGET_SERVICES.contains(normalized) ? normalized : OTHER;
    }

    private String upper(String value) {
        if (value == null || value.isBlank()) {
            return OTHER;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}

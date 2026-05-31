/**
 * @Author : Cui
 * @Date: 2026/05/31 18:16
 * @Description DataSmart Govern Backend - AgentToolPlanArgumentsPayloadService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionAuditView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolPlanArgumentsPayloadView;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Agent 工具计划参数载荷解析服务。
 *
 * <p>该服务把工具审计快照中的 planArguments 转换为一个专门给内部执行侧读取的 payload 视图。
 * 它是 Agent 异步工具任务化链路中的“参数回读边界”：</p>
 *
 * <p>1. agent-runtime 在生成 outbox command 时只下发 payloadReference；</p>
 * <p>2. task-management 创建任务时只保存参数名和安全摘要；</p>
 * <p>3. worker 准备执行时再通过本服务读取真实参数；</p>
 * <p>4. 读取后还必须由 task-management 做租户、项目、字段名、大小和工具适配器校验。</p>
 *
 * <p>这样做比把参数值直接塞进 Kafka 更接近商业化 Agent 平台的安全实践：
 * Kafka、task 表、执行日志和运维列表不会复制敏感参数；真实参数只在受控服务间接口上按需读取。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolPlanArgumentsPayloadService {

    /**
     * 当前唯一支持的载荷类型。
     *
     * <p>使用常量而不是散落字符串，是为了让后续扩展 output-summary、artifact-manifest、
     * secret-reference 等类型时，可以清楚看到协议入口。</p>
     */
    public static final String PAYLOAD_KIND_PLAN_ARGUMENTS = "plan-arguments";

    private final AgentToolExecutionAuditService auditService;

    /**
     * 读取某个工具审计记录的计划参数快照。
     *
     * @param sessionId Agent 会话 ID，必须与审计记录一致。
     * @param runId Agent Run ID，必须与审计记录一致。
     * @param auditId 工具执行审计 ID。
     * @return 受控参数载荷视图。
     */
    public AgentToolPlanArgumentsPayloadView getPlanArgumentsPayload(String sessionId, String runId, String auditId) {
        AgentToolExecutionAuditView audit = auditService.getExecutionAudit(sessionId, runId, auditId);
        return new AgentToolPlanArgumentsPayloadView(
                payloadReference(audit.sessionId(), audit.runId(), audit.auditId()),
                PAYLOAD_KIND_PLAN_ARGUMENTS,
                audit.sessionId(),
                audit.runId(),
                audit.auditId(),
                audit.toolCode(),
                audit.targetService(),
                audit.targetEndpoint(),
                audit.tenantId(),
                audit.projectId(),
                audit.workspaceId(),
                audit.actorId(),
                audit.traceId(),
                audit.executionMode(),
                audit.state(),
                sortedArgumentNames(audit.planArguments()),
                sensitiveArgumentNames(audit.governanceHints()),
                audit.planArguments(),
                audit.governanceHints(),
                audit.parameterValidation(),
                LocalDateTime.now()
        );
    }

    /**
     * 校验请求的 payload kind。
     *
     * <p>当前 Controller 使用固定路由 `/plan-arguments`，看似不需要这个方法。
     * 但把校验放在服务里有两个好处：
     * 1. 后续如果增加通用 `/{payloadKind}` 路由，不会忘记服务层白名单；
     * 2. 单元测试和其他内部调用也能复用同一条协议规则。</p>
     */
    public void validatePayloadKind(String payloadKind) {
        if (!PAYLOAD_KIND_PLAN_ARGUMENTS.equals(payloadKind)) {
            throw new PlatformBusinessException(PlatformErrorCode.BAD_REQUEST,
                    "不支持的 Agent 工具载荷类型: " + payloadKind);
        }
    }

    private String payloadReference(String sessionId, String runId, String auditId) {
        return "agent-tool-audit://" + sessionId + "/" + runId + "/" + auditId + "/" + PAYLOAD_KIND_PLAN_ARGUMENTS;
    }

    private List<String> sortedArgumentNames(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return List.of();
        }
        return arguments.keySet().stream()
                .map(String::valueOf)
                .filter(value -> !value.isBlank())
                .sorted()
                .toList();
    }

    /**
     * 从治理提示中抽取敏感参数名。
     *
     * <p>这里兼容 `sensitiveArgumentNames` 和 `sensitiveFields` 两种字段，是因为 Python AgentPlan、
     * Java 工具目录和未来前端配置中心可能在早期使用不同命名。服务层统一归一化，执行侧就不需要理解历史别名。</p>
     */
    private List<String> sensitiveArgumentNames(Map<String, Object> governanceHints) {
        if (governanceHints == null || governanceHints.isEmpty()) {
            return List.of();
        }
        Object raw = governanceHints.get("sensitiveArgumentNames");
        if (raw == null) {
            raw = governanceHints.get("sensitiveFields");
        }
        if (raw instanceof Collection<?> collection) {
            return collection.stream()
                    .map(String::valueOf)
                    .filter(value -> !value.isBlank())
                    .sorted()
                    .toList();
        }
        if (raw != null && !String.valueOf(raw).isBlank()) {
            return List.of(String.valueOf(raw));
        }
        return List.of();
    }
}

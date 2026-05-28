/**
 * @Author : Cui
 * @Date: 2026/05/14 19:12
 * @Description DataSmart Govern Backend - AgentToolExecutionService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionAuditView;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionResultView;
import com.czh.datasmart.govern.agent.service.audit.AgentToolExecutionAuditRecord;
import com.czh.datasmart.govern.agent.service.session.AgentRunRecord;
import com.czh.datasmart.govern.agent.service.session.AgentSessionRecord;
import com.czh.datasmart.govern.agent.service.tool.AgentToolAdapter;
import com.czh.datasmart.govern.agent.service.tool.AgentToolExecutionContext;
import com.czh.datasmart.govern.agent.service.tool.AgentToolExecutionGuard;
import com.czh.datasmart.govern.agent.service.tool.AgentToolExecutionOutcome;
import com.czh.datasmart.govern.agent.service.tool.AgentToolExecutionOutputStore;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Agent 工具执行服务。
 *
 * <p>该服务是“工具执行框架”的第一版核心编排层。
 * 它不关心某个工具如何调用 datasource-management 或 task-management，而是统一处理：
 * 1. 工具计划必须处于 PLANNED 才能执行；
 * 2. 根据 toolCode 选择对应适配器；
 * 3. 执行前将审计推进到 EXECUTING；
 * 4. 执行成功后写入 SUCCEEDED；
 * 5. 执行失败后写入 FAILED；
 * 6. 返回统一结果视图。
 *
 * <p>为什么需要这一层：
 * 如果每个 Controller 或每个工具适配器都自己推进审计状态，后续会很容易出现状态不一致。
 * 例如某个工具忘记写 FAILED，前端就会一直看到 EXECUTING。
 * 因此审计状态推进必须集中在执行框架中完成。
 */
@Service
@RequiredArgsConstructor
public class AgentToolExecutionService {

    private final AgentToolExecutionAuditService auditService;
    private final List<AgentToolAdapter> adapters;
    private final AgentToolExecutionGuard executionGuard;
    private final AgentToolExecutionOutputStore outputStore;

    /**
     * 执行某个工具计划。
     *
     * @param session 当前 Agent 会话，提供租户、项目、工作空间和操作者上下文。
     * @param run 当前 Agent Run，提供变量和运行上下文。
     * @param auditId 工具执行审计 ID，定位要执行的具体工具计划。
     * @param traceId 链路追踪 ID，用于透传到下游服务。
     */
    public AgentToolExecutionResultView execute(AgentSessionRecord session,
                                                AgentRunRecord run,
                                                String auditId,
                                                String traceId) {
        AgentToolExecutionAuditRecord plannedAudit = auditService.requirePlannedExecutionAudit(
                session.getSessionId(),
                run.getRunId(),
                auditId
        );
        executionGuard.validateBeforeExecution(session, run, plannedAudit);
        AgentToolExecutionAuditRecord audit = auditService.startExecution(session.getSessionId(), run.getRunId(), auditId);
        AgentToolExecutionContext context = new AgentToolExecutionContext(
                session,
                run,
                audit,
                run.getVariables() == null ? Map.of() : run.getVariables(),
                traceId
        );
        try {
            AgentToolAdapter adapter = findAdapter(audit.getToolCode());
            AgentToolExecutionOutcome outcome = adapter.execute(context);
            if (outcome.success()) {
                outputStore.save(
                        new AgentToolExecutionOutputStore.AgentToolExecutionAuditSnapshot(
                                audit.getSessionId(),
                                audit.getRunId(),
                                audit.getAuditId(),
                                audit.getToolCode()
                        ),
                        outcome.output()
                );
                AgentToolExecutionAuditView auditView = auditService.succeedExecution(
                        audit,
                        outcome.message(),
                        summarize(outcome.output())
                );
                return new AgentToolExecutionResultView(auditView, outcome.output());
            }
            AgentToolExecutionAuditView auditView = auditService.failExecution(
                    audit,
                    outcome.errorCode(),
                    outcome.message()
            );
            return new AgentToolExecutionResultView(auditView, outcome.output());
        } catch (Exception ex) {
            AgentToolExecutionAuditView auditView = auditService.failExecution(
                    audit,
                    "TOOL_ADAPTER_EXCEPTION",
                    "工具适配器执行异常: " + ex.getMessage()
            );
            return new AgentToolExecutionResultView(auditView, Map.of());
        }
    }

    /**
     * 读取某个工具执行的当前结果快照。
     *
     * <p>该方法不会推进状态，也不会触发工具适配器调用，只把 Java 控制面已经知道的事实返回给调用方。
     * 这是 Python AI Runtime 二轮模型推理的重要查询接口：Python 可以根据 auditId 读取
     * SUCCEEDED/FAILED/WAITING_APPROVAL/SKIPPED 等状态，再把安全摘要回填给模型。</p>
     *
     * <p>当前 output 只在工具 SUCCEEDED 且执行框架保存过结构化输出时返回。
     * 对于 PLANNED、EXECUTING、WAITING_APPROVAL、FAILED、SKIPPED 等状态，调用方仍应读取 audit
     * 中的 message、errorCode、outputSummary，而不应假设存在可消费的结构化结果。</p>
     *
     * @param sessionId Agent 会话 ID。
     * @param runId Agent Run ID。
     * @param auditId 工具执行审计 ID。
     * @return 审计视图 + 已保存的安全结构化输出。
     */
    public AgentToolExecutionResultView getResult(String sessionId, String runId, String auditId) {
        AgentToolExecutionAuditView auditView = auditService.getExecutionAudit(sessionId, runId, auditId);
        Map<String, Object> output = outputStore.findByAuditId(sessionId, runId, auditId)
                .map(record -> record.output() == null ? Map.<String, Object>of() : record.output())
                .orElseGet(Map::of);
        return new AgentToolExecutionResultView(auditView, output);
    }

    private AgentToolAdapter findAdapter(String toolCode) {
        return adapters.stream()
                .filter(adapter -> adapter.supports(toolCode))
                .findFirst()
                .orElseThrow(() -> new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT,
                        "未找到可执行该工具的适配器，toolCode=" + toolCode));
    }

    /**
     * 生成适合写入审计记录的输出摘要。
     *
     * <p>审计记录不适合保存完整大结果，尤其是元数据、样本数据、质量异常明细这类可能很大的对象。
     * 当前只保存一个短摘要，真实结果仍通过接口响应返回；后续可把大结果写入 MinIO 或审计归档表。
     */
    private String summarize(Map<String, Object> output) {
        if (output == null || output.isEmpty()) {
            return "工具执行成功，无结构化输出。";
        }
        return "工具执行成功，输出字段: " + String.join(",", output.keySet());
    }
}

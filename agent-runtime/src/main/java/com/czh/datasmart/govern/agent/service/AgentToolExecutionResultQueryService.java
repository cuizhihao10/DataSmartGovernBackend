/**
 * @Author : Cui
 * @Date: 2026/05/29 00:00
 * @Description DataSmart Govern Backend - AgentToolExecutionResultQueryService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.AgentToolExecutionResultView;
import com.czh.datasmart.govern.agent.service.session.AgentSessionMemoryStore;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agent 工具执行结果只读查询服务。
 *
 * <p>这个服务专门负责“读取工具执行事实”，不负责审批、不负责执行、不负责推进状态。
 * 之所以单独拆出来，而不是继续往 {@link AgentSessionService} 中增加方法，是为了控制单类复杂度：
 * 会话服务已经承担创建会话、绑定工具、启动运行、审批、拒绝、执行等职责，如果再加入批量结果查询、
 * 过滤、分页、权限上下文和结果脱敏，未来很容易形成一个难维护的巨型 Service。</p>
 *
 * <p>产品场景：
 * 1. Python AI Runtime 提交 AgentPlan 后，需要一次性读取本次 Run 所有工具反馈，再判断是否进入二轮推理；
 * 2. 前端审计页刷新整次 Run 的工具状态，不希望为每个 auditId 发一次请求；
 * 3. 运维排障时需要快速确认哪些工具成功、失败、等待审批或尚未执行。</p>
 *
 * <p>当前仍基于内存会话仓储校验 session/run 是否存在。后续迁移到 MySQL 时，本服务可以自然演进为：
 * 按租户、项目、runId、状态、时间范围分页查询；同时接入 permission-admin 的数据范围校验。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentToolExecutionResultQueryService {

    private final AgentRuntimeProperties properties;
    private final AgentSessionMemoryStore sessionMemoryStore;
    private final AgentToolExecutionService toolExecutionService;

    /**
     * 批量读取某个 Run 下所有工具执行结果快照。
     *
     * <p>该方法先校验 Runtime 是否启用、session 是否存在、run 是否属于该 session，再委托执行服务读取
     * 审计和输出快照。这里的校验是边界保护：即使调用方猜到了 runId，也不能绕过 sessionId 读取其他会话结果。</p>
     *
     * @param sessionId Agent 会话 ID。
     * @param runId Agent Run ID。
     * @return Run 下所有工具执行结果快照；未执行成功的工具 output 为空。
     */
    public List<AgentToolExecutionResultView> listRunToolExecutionResults(String sessionId, String runId) {
        ensureRuntimeEnabled();
        var session = sessionMemoryStore.findById(sessionId)
                .orElseThrow(() -> new PlatformBusinessException(
                        PlatformErrorCode.NOT_FOUND,
                        "Agent 会话不存在，sessionId=" + sessionId
                ));
        boolean runExists = session.getRuns().stream().anyMatch(run -> run.getRunId().equals(runId));
        if (!runExists) {
            throw new PlatformBusinessException(
                    PlatformErrorCode.NOT_FOUND,
                    "Agent Run 不属于当前会话或不存在，runId=" + runId
            );
        }
        return toolExecutionService.listResultsByRun(sessionId, runId);
    }

    private void ensureRuntimeEnabled() {
        if (!Boolean.TRUE.equals(properties.getEnabled())) {
            throw new PlatformBusinessException(PlatformErrorCode.BUSINESS_STATE_CONFLICT, "Agent Runtime 当前未启用");
        }
    }
}

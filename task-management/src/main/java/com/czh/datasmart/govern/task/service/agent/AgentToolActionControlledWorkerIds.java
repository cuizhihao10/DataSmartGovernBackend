/**
 * @Author : Cui
 * @Date: 2026/06/28 18:50
 * @Description DataSmart Govern Backend - AgentToolActionControlledWorkerIds.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

import com.czh.datasmart.govern.task.config.AgentAsyncToolWorkerProperties;

/**
 * 受控工具动作 worker 身份生成工具。
 *
 * <p>task-management 在同一个服务内已经存在多条 Agent 工具执行链路：
 * 历史 `AGENT_ASYNC_TOOL` 使用通用 executorId；新的 `AGENT_TOOL_ACTION_CONTROLLED`
 * 会经历 dry-run、审批事实回查、Host 提交、worker receipt 等阶段。为了避免不同阶段手写不同后缀，
 * 这里集中生成当前任务租约和 command worker lease 使用的低敏 executorId。</p>
 *
 * <p>注意：这里生成的是逻辑执行器身份，不是主机名、IP、路径或线程名，因此可以安全进入任务日志、
 * agent-runtime receipt 和 command worker lease 事实。</p>
 */
final class AgentToolActionControlledWorkerIds {

    private AgentToolActionControlledWorkerIds() {
    }

    /**
     * 返回 `AGENT_TOOL_ACTION_CONTROLLED` 专用执行器身份。
     *
     * <p>当前仍保留 `dry-run` 后缀，是为了兼容已经写入的任务租约、测试断言和运维认知；
     * 但它现在代表“受控动作 worker 主链路”，不再只表示纯 dry-run。后续如果统一重命名，应通过迁移说明
     * 一次性调整配置、日志和 dashboard，而不是在多个类中各自替换字符串。</p>
     */
    static String controlledExecutorId(AgentAsyncToolWorkerProperties properties) {
        return properties.getExecutorId() + "-tool-action-controlled-dry-run";
    }
}

/**
 * @Author : Cui
 * @Date: 2026/05/13 22:40
 * @Description DataSmart Govern Backend - AgentRunState.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.model;

/**
 * Agent 单次运行状态。
 *
 * <p>一个 Agent 会话可以包含多次运行，例如用户连续追问、重新生成治理方案、让 Agent 继续执行下一步。
 * 会话描述“上下文容器”，运行描述“某一次实际编排过程”。将二者拆开后，后续才能支持：
 * 1. 同一会话下多轮自然语言交互；
 * 2. 某一次运行失败后只重试该运行，而不是重建整个会话；
 * 3. 在审计报表中区分“会话生命周期”和“具体模型/工具调用生命周期”。
 */
public enum AgentRunState {

    /**
     * 运行记录已创建，但还没有进入规划阶段。
     * 该状态通常只在持久化成功后的极短时间内存在，用于表达“请求已被接收”。
     */
    CREATED,

    /**
     * Agent 正在理解用户目标、选择模型工作负载、准备工具调用计划。
     * 当前版本没有真实编排器，因此创建运行后会停留在 PLANNING，用于占位未来 LangGraph/OpenClaw 状态机。
     */
    PLANNING,

    /**
     * Agent 正在等待模型返回。
     * 后续接入 vLLM、SGLang、OpenAI-compatible 或 Python Agent Service 后，模型请求发出到响应返回之间会处于该状态。
     */
    WAITING_MODEL,

    /**
     * Agent 正在调用平台工具。
     * 例如读取数据源元数据、生成质量规则、创建同步任务、查询任务执行历史等。
     */
    TOOL_CALLING,

    /**
     * Agent 已触发高风险动作，需要人工审批或确认。
     * 例如跨项目数据访问、生成可执行 SQL、批量恢复失败任务等商业化场景，不应让模型直接自动执行。
     */
    WAITING_HUMAN,

    /**
     * 运行已成功完成。
     * 成功不一定意味着所有工具都执行了写操作，也可能只是生成了建议、计划或只读分析结果。
     */
    SUCCEEDED,

    /**
     * 运行被人工拒绝。
     *
     * <p>它和 FAILED 的语义不同：
     * FAILED 表示系统、模型或工具执行出现错误；
     * REJECTED 表示用户、项目负责人或管理员在高风险动作执行前主动拒绝继续。
     * 商业化审计报表需要区分这两类结果，否则会把“安全拦截成功”误统计为“系统失败”。
     */
    REJECTED,

    /**
     * 运行失败。
     * 失败原因后续应写入审计和运行详情，例如模型超时、工具权限不足、下游服务不可用或业务状态冲突。
     */
    FAILED,

    /**
     * 运行被用户、运营人员或系统取消。
     * 取消是终态，后续不能再把该 run 恢复为运行中；如需继续，应创建新的 run。
     */
    CANCELLED;

    /**
     * 判断当前状态是否为终态。
     *
     * <p>终态用于防止重复取消、重复完成、失败后再次写进度等状态回退问题。
     * 商业化任务系统必须显式管理终态，否则在并发回调、重试和人工介入同时发生时很容易出现数据错乱。
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == REJECTED || this == FAILED || this == CANCELLED;
    }
}

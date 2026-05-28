/**
 * @Author : Cui
 * @Date: 2026/05/13 23:18
 * @Description DataSmart Govern Backend - AgentToolExecutionMode.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.model;

/**
 * Agent 工具执行模式。
 *
 * <p>执行模式回答“工具调用如何发生”：
 * 有些工具可以同步读取，有些工具应该转成任务异步执行，有些工具只允许生成草稿等待人工确认。
 * 先把执行模式纳入工具目录，可以避免后续 Agent 编排器把所有工具都当成普通 HTTP 调用处理。
 */
public enum AgentToolExecutionMode {

    /**
     * 同步只读或轻量调用。
     * 适合元数据查询、规则解释、状态查询等低延迟接口。
     */
    SYNC,

    /**
     * 异步任务式调用。
     * 适合耗时扫描、批量生成、同步任务创建、长时间质量检测等场景。
     */
    ASYNC_TASK,

    /**
     * 草稿模式。
     * Agent 只生成建议或草稿，不直接提交写操作，等待用户确认后再进入下一步。
     */
    DRAFT_ONLY,

    /**
     * 审批后执行。
     * 适合高风险或严重风险动作，必须先创建审批/确认节点。
     */
    APPROVAL_REQUIRED
}

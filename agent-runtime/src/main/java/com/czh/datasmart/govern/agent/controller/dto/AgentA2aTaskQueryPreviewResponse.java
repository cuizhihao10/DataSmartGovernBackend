/**
 * @Author : Cui
 * @Date: 2026/06/06 13:03
 * @Description DataSmart Govern Backend - AgentA2aTaskQueryPreviewResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.List;

/**
 * A2A Task 查询预览响应。
 *
 * <p>5.28 固定了状态机，5.29 固定了状态变化事件契约；本响应进一步演示“如果未来实现只读 task 查询，
 * DataSmart 应该如何从 task fact 和 runtime event history 恢复一个 A2A 风格任务视图”。它是控制面预览，
 * 不是 `tasks/get` 的真实实现，不读取数据库，也不返回真实客户任务。</p>
 *
 * <p>为什么要先做查询预览：真实 `message:send` 一旦创建 task，客户端马上会需要 `tasks/get`、history、
 * streaming replay、push 配置查询和 artifact 引用。先把查询形态定清楚，可以避免后续为了兼容前端或外部 Agent
 * 反复修改 task 表、事件表和 artifact 表。</p>
 *
 * @param schemaVersion 响应 schema 版本
 * @param generatedAt 预览生成时间
 * @param protocolFamily 协议族，当前固定为 A2A
 * @param protocolVersion 当前对齐的 A2A 主版本
 * @param previewOnly 是否只读预览。true 表示不会查询真实 task，也不会改变任何状态
 * @param taskEndpointEnabled 真实 task 查询端点是否已启用。当前为 false
 * @param scenario 当前预览场景，例如 completed、input-required、auth-required、failed、canceled、working
 * @param payloadPolicy 载荷策略，明确只返回低敏状态摘要与 artifact 引用
 * @param queryContract 查询接口契约草案，说明路径、参数、权限、错误映射和当前边界
 * @param task A2A task 风格低敏任务视图
 * @param historyEvents 按 sequence 排序的低敏历史事件
 * @param artifactReferences 任务产生的 artifact 引用列表，不包含正文
 * @param streamReplay 断线恢复和 stream replay 预览
 * @param pushPreview push notification 查询与投递边界预览
 * @param missingProductionRequirements 进入真实查询前仍缺失的生产化能力
 * @param nextSteps 下一步建议
 */
public record AgentA2aTaskQueryPreviewResponse(
        String schemaVersion,
        Instant generatedAt,
        String protocolFamily,
        String protocolVersion,
        boolean previewOnly,
        boolean taskEndpointEnabled,
        String scenario,
        String payloadPolicy,
        AgentA2aTaskQueryContractView queryContract,
        AgentA2aTaskPreviewView task,
        List<AgentA2aTaskHistoryEventView> historyEvents,
        List<AgentA2aTaskArtifactReferenceView> artifactReferences,
        AgentA2aTaskStreamReplayView streamReplay,
        AgentA2aTaskPushPreviewView pushPreview,
        List<String> missingProductionRequirements,
        List<String> nextSteps
) {
}

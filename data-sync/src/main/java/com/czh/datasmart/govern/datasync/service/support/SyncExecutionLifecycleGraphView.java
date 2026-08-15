/**
 * @Author : Cui
 * @Date: 2026/08/15
 * @Description DataSmart Govern Backend - SyncExecutionLifecycleGraphView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面向运维人员的一次同步执行全链路状态投影。
 *
 * <p>该 DTO 只把不同服务已经持久化的事实按同一 execution 排序展示，不创建新的执行状态，也不参与任何
 * 状态迁移。节点和边只包含枚举状态、低敏标识、来源、时间与证据引用；不会暴露 prompt、SQL、工具参数、
 * 原始日志、样本数据、连接串、凭据或内部服务令牌。</p>
 *
 * @param schemaVersion 图契约版本，便于前端和外部运维工具兼容演进
 * @param graphType 固定为 SYNC_EXECUTION_LIFECYCLE
 * @param available execution 存在并通过可见性校验后始终为 true
 * @param syncTaskId 同步任务 ID
 * @param rootExecutionId 恢复链路的根 execution；无恢复时等于请求 execution
 * @param currentExecutionId 当前真正由 worker 处理的 execution
 * @param overallState 根据 worker 与 Recovery 事实计算的只读汇总状态
 * @param sourceStatus COMPLETE、PARTIAL 或 NOT_LINKED
 * @param missingReason 来源不完整时的稳定原因码
 * @param nodes 按业务链路顺序排列的节点
 * @param edges 节点之间的有向关系
 * @param evidence 统一证据索引，节点通过 evidenceId 引用
 * @param generatedAt 本次投影生成时间
 */
public record SyncExecutionLifecycleGraphView(
        String schemaVersion,
        String graphType,
        boolean available,
        Long syncTaskId,
        Long rootExecutionId,
        Long currentExecutionId,
        String overallState,
        String sourceStatus,
        String missingReason,
        List<LifecycleNode> nodes,
        List<LifecycleEdge> edges,
        List<LifecycleEvidence> evidence,
        LocalDateTime generatedAt) {

    /**
     * 图中的一个低敏状态节点。
     *
     * @param nodeId 本图内稳定节点 ID
     * @param nodeType USER_GOAL、AGENT、COMMAND_DISPATCH、KAFKA_EVENT、JAVA_AUDIT、WORKER、RECOVERY 或 FINAL_VERIFICATION
     * @param role Agent 节点的 Specialist 角色；非 Agent 节点为空
     * @param state 来源状态或明确的 NOT_RECORDED/NOT_APPLICABLE/UNAVAILABLE
     * @param title 中文展示名称
     * @param source 状态事实的所有者
     * @param evidenceId 关联证据 ID
     * @param occurredAt 该节点最近一次可证明的发生时间
     * @param reasonCode 对未完成、阻断或来源缺失的稳定说明码
     */
    public record LifecycleNode(
            String nodeId,
            String nodeType,
            String role,
            String state,
            String title,
            String source,
            String evidenceId,
            LocalDateTime occurredAt,
            String reasonCode) {
    }

    /**
     * 图中的有向关系。关系本身不驱动状态，只表达运维阅读顺序。
     *
     * @param fromNodeId 起点
     * @param toNodeId 终点
     * @param relation 关系语义
     * @param state COMPLETED、WAITING、BLOCKED 或 NOT_APPLICABLE
     * @param evidenceId 可证明该关系已推进的证据 ID
     */
    public record LifecycleEdge(
            String fromNodeId,
            String toNodeId,
            String relation,
            String state,
            String evidenceId) {
    }

    /**
     * 证据统一索引。reference 只使用可授权查询的资源标识，不包含原始正文。
     *
     * @param evidenceId 图内证据 ID
     * @param source 证据所有服务
     * @param kind 证据种类
     * @param state 证据自身状态
     * @param occurredAt 证据时间
     * @param confidence AUTHORITATIVE、PARTIAL 或 UNAVAILABLE，表达来源可信度而非模型主观分数
     * @param reference 可进一步查询的低敏资源引用
     */
    public record LifecycleEvidence(
            String evidenceId,
            String source,
            String kind,
            String state,
            LocalDateTime occurredAt,
            String confidence,
            String reference) {
    }
}

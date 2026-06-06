/**
 * @Author : Cui
 * @Date: 2026/06/06 13:03
 * @Description DataSmart Govern Backend - AgentA2aTaskPreviewView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.List;

/**
 * A2A Task 低敏任务视图。
 *
 * <p>真实 A2A Task 可能包含 status、artifacts、history 和 metadata。DataSmart 的查询视图必须先遵守低敏原则：
 * 对外可以展示 task id、context id、状态、阶段、序号、是否终态、可执行客户端动作和结果引用，但不能回显消息正文、
 * 工具输入正文、模型输出正文或内部执行细节。</p>
 *
 * @param taskPublicId 不可枚举的外部 task id，占位值仅用于 preview
 * @param contextPublicId A2A context id，占位值仅用于 preview
 * @param tenantRoutingMode 租户路由方式说明；真实 tenant 不进入 preview
 * @param currentState 当前 A2A TaskState
 * @param internalPhase 当前 DataSmart 内部治理阶段
 * @param terminal 是否终态
 * @param interrupted 是否中断态，例如等待输入或等待授权
 * @param cancelRequested 是否存在取消请求但尚未进入 canceled 终态
 * @param sequence 最新事件序号
 * @param lifecycleVersion 生命周期契约版本
 * @param createdAt 任务创建时间
 * @param updatedAt 最近状态更新时间
 * @param completedAt 终态完成时间，非终态为空
 * @param statusSummary 低敏状态摘要
 * @param reasonCode 状态原因类别
 * @param allowedClientOperations 当前状态下允许客户端尝试的动作
 * @param governanceSummary 权限、审批、worker、outbox 等治理摘要
 */
public record AgentA2aTaskPreviewView(
        String taskPublicId,
        String contextPublicId,
        String tenantRoutingMode,
        String currentState,
        String internalPhase,
        boolean terminal,
        boolean interrupted,
        boolean cancelRequested,
        long sequence,
        String lifecycleVersion,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        String statusSummary,
        String reasonCode,
        List<String> allowedClientOperations,
        List<String> governanceSummary
) {
}

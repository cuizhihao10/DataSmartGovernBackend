/**
 * @Author : Cui
 * @Date: 2026/06/06 12:40
 * @Description DataSmart Govern Backend - AgentA2aTaskStateView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * A2A Task 单个状态的解释视图。
 *
 * <p>A2A 对外状态必须保持标准化，否则外部 Agent、SDK、网关或任务订阅方无法正确理解任务生命周期。
 * DataSmart 可以拥有更细的内部阶段，例如审批等待、outbox 待投递、worker pre-check、DLQ，但这些内部阶段
 * 不应该直接变成外部 TaskState，而应该通过本视图中的说明映射回标准状态。</p>
 *
 * @param state A2A 标准状态常量，例如 TASK_STATE_SUBMITTED
 * @param wireValue 协议线缆/JSON 中常见的小写值，例如 submitted；便于兼容不同 SDK 的表达习惯
 * @param category 状态类别：IN_PROGRESS、INTERRUPTED、TERMINAL 或 DIAGNOSTIC
 * @param terminal 是否终态。终态任务不能重新进入 working，也不能被再次执行
 * @param interrupted 是否中断态。中断态表示等待用户输入或授权，不代表失败
 * @param description A2A 协议层语义说明
 * @param datasmartMeaning DataSmart 产品中的业务含义，解释该状态如何影响权限、审批、worker 和前端展示
 * @param allowedClientOperations 客户端在该状态下可以尝试的操作，例如 get、cancel、resume-with-input
 * @param externalVisibility 对外可见性说明，提醒哪些信息可以展示给外部 Agent，哪些必须留在内部控制面
 * @param controlPlaneNotes 控制面实现注意事项，例如状态持久化、runtime event、指标或 worker 处理要求
 */
public record AgentA2aTaskStateView(
        String state,
        String wireValue,
        String category,
        boolean terminal,
        boolean interrupted,
        String description,
        String datasmartMeaning,
        List<String> allowedClientOperations,
        String externalVisibility,
        List<String> controlPlaneNotes
) {
}

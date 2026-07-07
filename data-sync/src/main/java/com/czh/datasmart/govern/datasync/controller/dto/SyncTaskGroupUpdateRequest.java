/**
 * @Author : Cui
 * @Date: 2026/07/07 18:42
 * @Description DataSmart Govern Backend - SyncTaskGroupUpdateRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import lombok.Data;

/**
 * 同步任务分组调整请求。
 *
 * <p>该请求服务于“把一个任务移动到某个分组”或“从分组中移除”的管理动作。
 * 当前版本选择先做单任务移组，而不是一次性上批量移组，是为了让状态、权限、审计和数据模型先闭环：
 * 批量移组后续可以复用同一服务方法逐条执行，并追加批次号、失败明细和幂等键。</p>
 */
@Data
public class SyncTaskGroupUpdateRequest {

    /**
     * 目标任务分组编码。
     *
     * <p>为空表示从当前分组移除，任务变为未分组。非空时服务端会规范化为大写编码，并要求只包含字母、
     * 数字、下划线、短横线、点号或冒号。这样导入导出、Agent 工具调用和组级 API 都可以稳定引用同一分组。</p>
     */
    private String groupCode;

    /**
     * 目标任务分组展示名称。
     *
     * <p>当 groupCode 非空但 groupName 为空时，服务端使用 groupCode 兜底。展示名称允许运营人员写中文，
     * 用于任务列表、分组卡片和 Agent 回复；它不是唯一键。</p>
     */
    private String groupName;

    /**
     * 低敏操作原因。
     *
     * <p>原因会进入审计摘要。调用方不应在这里填写 SQL、连接串、密码、token、样本数据或完整 prompt。
     * 服务端会做基础敏感关键字兜底脱敏。</p>
     */
    private String reason;
}

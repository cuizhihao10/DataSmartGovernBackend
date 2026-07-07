/**
 * @Author : Cui
 * @Date: 2026/07/07 23:58
 * @Description DataSmart Govern Backend - SyncTransferModeOption.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

import java.util.List;

/**
 * 同步任务传输模式选项。
 *
 * <p>该 DTO 专门服务“新建同步模板/新建同步任务页面”的模式下拉框。它和连接器能力矩阵里的内部能力不同：
 * 前端只能把这里返回的模式作为用户可直接选择的一级传输模式，不能把失败回放、历史补数、离线导入导出等运行期能力
 * 混入同一个控件。</p>
 *
 * @param mode 稳定模式编码，前端提交到 CreateSyncTemplateRequest.syncMode
 * @param displayName 中文展示名，例如“定期全量”
 * @param transferChannel 技术通道，OFFLINE 表示有界离线作业，REALTIME 表示持续 CDC/事件流
 * @param scheduleRequired 是否必须在创建任务时提供 scheduleConfig
 * @param scheduleAllowed 是否允许在创建任务时提供 scheduleConfig
 * @param customSqlRequired 是否必须提供 customSqlConfig
 * @param defaultSyncScopeType 推荐的同步范围类型，前端可用它初始化范围配置
 * @param description 面向用户和 Agent 的低敏说明
 * @param recommendedActions 前端/Agent 在选择该模式后应引导用户补充的关键配置
 */
public record SyncTransferModeOption(
        String mode,
        String displayName,
        String transferChannel,
        boolean scheduleRequired,
        boolean scheduleAllowed,
        boolean customSqlRequired,
        String defaultSyncScopeType,
        String description,
        List<String> recommendedActions
) {
}

/**
 * @Author : Cui
 * @Date: 2026/07/06 23:35
 * @Description DataSmart Govern Backend - SyncObjectExecutionQueryCriteria.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.dto;

/**
 * 对象级执行账本查询条件。
 *
 * <p>这个 DTO 对应“某一次 data-sync execution 内部，每个对象或分片执行到什么状态”的运维查询入口。
 * 它和普通 execution 历史查询的区别是：</p>
 * <p>1. execution 历史回答“这一次同步整体成功、失败还是部分成功”；</p>
 * <p>2. object execution 明细回答“这一次同步里第几张表、哪个对象、尝试了几次、为什么失败”；</p>
 * <p>3. 该查询是后续选择性重试、失败对象诊断、DataX-style 分片恢复的事实基础。</p>
 *
 * <p>安全边界说明：该查询只用于已经授权的运行证据视图。Service 层会先校验 task/execution 的租户、
 * 项目和 SELF 数据范围，再允许读取对象级账本；普通日志、指标、跨服务 receipt 仍不应该直接暴露对象名、
 * SQL、where 条件、字段映射正文、连接串、凭据或样本数据。</p>
 *
 * @param syncTaskId 同步任务 ID，来自路径参数，用于限定父任务范围。
 * @param executionId 父 execution ID，来自路径参数，用于限定某一次真实运行。
 * @param objectState 可选对象状态筛选，例如 FAILED、SUCCEEDED、PENDING。
 * @param objectOrdinal 可选对象顺序号筛选，用于快速定位 objectMappingConfig.mappings 中的第 N 个对象。
 * @param current 当前页码，从 1 开始。
 * @param size 每页大小，Service 层会复用统一分页上限保护，避免一次拉取过多运行明细。
 */
public record SyncObjectExecutionQueryCriteria(
        Long syncTaskId,
        Long executionId,
        String objectState,
        Integer objectOrdinal,
        Long current,
        Long size
) {
}

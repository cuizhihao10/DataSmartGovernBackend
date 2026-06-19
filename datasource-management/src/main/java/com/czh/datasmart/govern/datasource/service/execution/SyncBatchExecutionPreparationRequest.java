/**
 * @Author : Cui
 * @Date: 2026/06/20 03:22
 * @Description DataSmart Govern Backend - SyncBatchExecutionPreparationRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution;

import com.czh.datasmart.govern.datasource.controller.dto.SyncBatchExecutionPlan;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批处理执行准备请求。
 *
 * <p>该请求是 worker 在真正执行前的“装配输入”。
 * `executionPlan` 来自控制面 claim，字段清单来自模板字段映射或元数据治理结果。
 * 之所以不把字段清单强行塞进 `SyncBatchExecutionPlan`，是为了保持 claim 响应低敏且稳定；
 * 字段映射后续可能包含脱敏、类型转换、重命名、表达式等复杂信息，应由专门模板能力管理。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SyncBatchExecutionPreparationRequest {

    /**
     * 控制面下发的批处理执行计划。
     */
    private SyncBatchExecutionPlan executionPlan;

    /**
     * 源端读取字段清单。
     * 为空时读取方言会退化为 `*`，真实生产建议显式传入字段清单。
     */
    private List<String> selectedColumns;

    /**
     * 目标端写入字段清单。
     * 写入 SQL 必须显式声明字段顺序，因此该字段不能为空。
     */
    private List<String> writeColumns;

    /**
     * 目标端主键/唯一键字段。
     * 如果为空，准备服务会尝试使用 executionPlan.writePlan.primaryKeyField。
     */
    private List<String> primaryKeyColumns;
}

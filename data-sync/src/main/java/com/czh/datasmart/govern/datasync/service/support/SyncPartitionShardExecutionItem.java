/**
 * @Author : Cui
 * @Date: 2026/07/07 23:20
 * @Description DataSmart Govern Backend - SyncPartitionShardExecutionItem.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.util.List;

/**
 * 单表大数据量离线同步中的一个可恢复分片执行单元。
 *
 * <p>DataX 的核心思想之一，是把一个大 Job 拆成多个更小的 Task/Channel 执行单元。对本项目来说，
 * 多表迁移已经通过 OBJECT_LIST 做到了“每张表一个可恢复对象”；本类进一步描述“同一张大表中的一个
 * ID range 分片”，让一张超大表也可以被拆成多个可并行、可重试的小任务。</p>
 *
 * <p>为什么分片条目不直接保存 SQL：</p>
 * <p>1. SQL 字符串难以审计，也容易把 where 条件、业务值或注入片段混入执行链路；</p>
 * <p>2. 当前项目要求 filter/partition 都以结构化条件进入 PreparedStatement，保证字段名可白名单校验，
 * 值只作为参数绑定；</p>
 * <p>3. 普通 API、日志、receipt 和指标只暴露分片编号与低敏状态，真实范围值只在 internal run-once
 * 请求内短暂流转。</p>
 *
 * @param ordinal 分片在当前父 execution 下的稳定序号，从 0 开始；也会写入 data_sync_object_execution.object_ordinal。
 * @param shardOrPartition 低敏分片标识，例如 id-range-0000；用于幂等键、checkpoint 维度和运维定位。
 * @param partitionStrategy 分片策略，当前生产闭环优先支持 ID_RANGE。
 * @param partitionField 源端分片字段，例如 id、customer_id；字段名会经过安全标识符校验。
 * @param filterConditions 当前分片附加到 run-once readPlan 的结构化过滤条件。
 * @param warnings 当前分片合同的低敏提示，不包含边界值、SQL 或样本行。
 */
public record SyncPartitionShardExecutionItem(
        int ordinal,
        String shardOrPartition,
        String partitionStrategy,
        String partitionField,
        List<SyncFilterExecutionCondition> filterConditions,
        List<String> warnings
) {

    public SyncPartitionShardExecutionItem {
        filterConditions = filterConditions == null ? List.of() : List.copyOf(filterConditions);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}

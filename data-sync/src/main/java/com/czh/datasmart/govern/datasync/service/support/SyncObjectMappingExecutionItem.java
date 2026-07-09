/**
 * @Author : Cui
 * @Date: 2026/07/05 16:19
 * @Description DataSmart Govern Backend - SyncObjectMappingExecutionItem.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

import java.util.List;

/**
 * 多对象同步中“单个对象映射”的内部执行条目。
 *
 * <p>该 record 不是前端 DTO，也不是普通审计载荷。它会携带源/目标对象名、schema 名和可选字段映射覆盖，
 * 因此只能在 data-sync 服务端内部用于 fan-out 拆分，不能直接写入日志、runtime event、Prometheus 标签或公开响应。</p>
 *
 * <p>为什么对象映射要单独建模：用户创建多表同步时通常会经历“选择源表 -> 选择目标表 -> 配置字段映射/过滤条件 -> 预检查”
 * 这几个步骤。如果执行阶段仍然只依赖模板上的 sourceObjectName/targetObjectName，就无法表达用户选择了多张表，
 * 更无法像 DataX 那样把一个 Job 拆成多个可执行 Channel。因此这里把 objectMappingConfig.mappings 的每个元素
 * 转换为一个受控条目，后续 fan-out 服务再逐条构造 SINGLE_OBJECT 子计划。</p>
 *
 * @param ordinal 配置中的顺序号，从 0 开始。用于故障定位和幂等键，不暴露对象名。
 * @param sourceSchemaName 源端 schema；为空时使用模板默认 sourceSchemaName。
 * @param sourceObjectName 源端对象名，例如表、视图或后续 connector 解释的逻辑对象。
 * @param targetSchemaName 目标端 schema；为空时使用模板默认 targetSchemaName。
 * @param targetObjectName 目标端对象名。
 * @param fieldMappingConfigOverride 当前对象自己的字段映射 JSON；为空时复用模板级 fieldMappingConfig。
 * @param fieldMappingOverridden 是否声明了对象级字段映射覆盖。
 * @param whereCondition 当前对象自己的 where 条件。该字段来自“对象映射”步骤中某一行的过滤条件，
 *                       只允许在 data-sync 内部被解析为结构化过滤条件后下发给受控执行器，不能直接拼接 SQL。
 *                       设计上它区别于模板级 filterConfig：filterConfig 是历史/导入兼容的任务级过滤配置，
 *                       whereCondition 才是当前创建向导里用户面向某张表配置的主要过滤入口。
 * @param warnings 当前条目的非阻断提示。只允许低敏原因码或学习说明，不包含 JSON 原文。
 */
public record SyncObjectMappingExecutionItem(
        int ordinal,
        String sourceSchemaName,
        String sourceObjectName,
        String targetSchemaName,
        String targetObjectName,
        String fieldMappingConfigOverride,
        boolean fieldMappingOverridden,
        String whereCondition,
        List<String> warnings
) {

    public SyncObjectMappingExecutionItem {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}

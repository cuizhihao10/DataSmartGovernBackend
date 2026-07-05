/**
 * @Author : Cui
 * @Date: 2026/07/05 15:58
 * @Description DataSmart Govern Backend - SyncDataXReaderContract.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

/**
 * DataX-style Reader 低敏执行合同。
 *
 * <p>Reader 是离线同步里的“抽取侧”。在 DataX 的思想里，Reader 插件负责从源端读取数据，
 * Writer 插件负责写入目标端，中间由框架负责通道、限速、错误处理和生命周期。我们这里先不直接生成 DataX JSON，
 * 而是把 Reader 需要遵守的执行边界建模出来，方便后续接入真实 DataX、自研 Java Runner、Flink batch 或 Spark batch。</p>
 *
 * <p>本对象只承载低敏策略：它不会出现表名清单、字段列表、where 正文、SQL 正文、连接串或账号密码。</p>
 *
 * @param readerFamily Reader 家族，例如 JDBC_READER、FILE_READER、OBJECT_STORAGE_READER。
 * @param connectorType 源端连接器类型，例如 MYSQL、POSTGRESQL、SQL_SERVER。
 * @param datasourceBindingPolicy 数据源绑定策略。正常情况下只允许 datasourceId 引用，凭据由执行面受控解析。
 * @param objectReadPolicy 对象读取策略，例如单对象扫描、多对象 fan-out、自定义 SQL 结果集或运行时发现。
 * @param splitPolicy 拆分策略，例如不拆分、按对象拆分、按分区拆分、按 checkpoint 范围拆分。
 * @param fetchPolicy 拉取策略，例如 limit/offset 批次、fetchSize、窗口读取或专用 Runner 运行时决定。
 * @param filterPolicy 过滤策略。必须表达是否存在结构化 filter，但不能暴露 filterConfig 原文。
 * @param customSqlPolicy 自定义 SQL 策略。只允许策略码，不允许 SQL 正文或 statementRef 真实值。
 * @param checkpointReadPolicy checkpoint 读取策略。说明读取边界是否依赖 checkpointRef/digest 或最终水位。
 * @param payloadPolicy 当前对象低敏载荷策略。
 */
public record SyncDataXReaderContract(
        String readerFamily,
        String connectorType,
        String datasourceBindingPolicy,
        String objectReadPolicy,
        String splitPolicy,
        String fetchPolicy,
        String filterPolicy,
        String customSqlPolicy,
        String checkpointReadPolicy,
        String payloadPolicy
) {
}

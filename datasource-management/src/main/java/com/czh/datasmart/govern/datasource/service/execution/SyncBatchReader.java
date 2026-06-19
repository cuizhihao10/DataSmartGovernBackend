/**
 * @Author : Cui
 * @Date: 2026/06/20 03:22
 * @Description DataSmart Govern Backend - SyncBatchReader.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution;

/**
 * 同步批处理读取器接口。
 *
 * <p>这是 datasource-management 真实 worker 执行链路的读取侧抽象。
 * 当前阶段先定义接口边界，不直接实现真实 JDBC 连接和 ResultSet 遍历，是为了把“控制面计划准备”
 * 与“运行时连接资源和数据搬运”解耦。</p>
 *
 * <p>后续 JDBC reader 的职责应包括：</p>
 * <p>1. 根据 `SyncBatchReadContext` 中的内部 PreparedStatement 模板创建真实 PreparedStatement；</p>
 * <p>2. 从 checkpoint 表读取上次水位，并用参数绑定方式传入 SQL；</p>
 * <p>3. 按 fetchSize 分批读取，不把整表一次性加载到内存；</p>
 * <p>4. 返回低敏统计、下一 checkpoint 候选值和批次完成信号，真实行数据只在 reader/writer 内部管道流转。</p>
 */
public interface SyncBatchReader {

    /**
     * 读取下一批数据。
     *
     * @param context 本批读取上下文，包含内部 SQL 模板、checkpoint 类型、fetchSize 和执行标识。
     * @return 读取结果摘要。该结果不应暴露样本行或业务字段值。
     */
    SyncBatchReadResult readNextBatch(SyncBatchReadContext context);
}

/**
 * @Author : Cui
 * @Date: 2026/06/22 10:30
 * @Description DataSmart Govern Backend - DataSyncWorkerExecutionReceiptEventType.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.support;

/**
 * DataSync worker 真实执行回执事件类型。
 *
 * <p>这个枚举描述的是 datasource-management Runner 在真正搬运数据时产生的低敏执行事实，
 * 不再是 task-management -> datasource-management 的“命令是否成功投递”。两者刻意分开：</p>
 * <p>1. outbox 状态回答“命令有没有被下游接收”；</p>
 * <p>2. execution receipt 事件回答“下游接收后是否真的读取、写入、保存检查点、完成或失败”；</p>
 * <p>3. 如果把这两类状态混进一个状态机，任务中心很容易把“下游已入队”误认为“数据同步已完成”。</p>
 */
public enum DataSyncWorkerExecutionReceiptEventType {

    /**
     * 普通进度事件，通常表示 Runner 完成了一批读取/写入并上报累计计数。
     */
    PROGRESS,

    /**
     * 检查点事件，表示 Runner 已把某个低敏检查点语义持久化。
     *
     * <p>注意：这里只允许保存 checkpointType 和 checkpointValueVisibility，
     * 不能保存真实 checkpointValue，因为真实值可能包含业务主键、时间窗口、分区路径或上游 offset。</p>
     */
    CHECKPOINT,

    /**
     * 完成事件，表示 datasource-management 认为本次 syncExecution 已经到达可结束状态。
     */
    COMPLETE,

    /**
     * 失败事件，表示 datasource-management Runner 在执行阶段失败。
     */
    FAILED;

    /**
     * 解析外部输入事件类型。
     *
     * <p>datasource-management 或未来 Kafka 事件里可能使用 FAIL 这样的短词，本方法把它归一化为 FAILED，
     * 避免数据库里出现多种语义等价的事件类型，影响诊断聚合。</p>
     *
     * @param value 外部传入的事件类型字符串。
     * @return 归一化后的事件类型。
     */
    public static DataSyncWorkerExecutionReceiptEventType parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("execution receipt eventType 不能为空");
        }
        String normalized = value.trim().toUpperCase();
        if ("FAIL".equals(normalized)) {
            return FAILED;
        }
        try {
            return valueOf(normalized);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("未知 DataSync worker execution receipt eventType: " + value);
        }
    }
}

/**
 * @Author : Cui
 * @Date: 2026/08/14
 * @Description DataSmart Govern Backend - SyncAutopilotRecoveryRepairReplanResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.service.support;

/**
 * 一次“修复未应用”后的有界重规划结果。
 *
 * <p>该结果只证明旧 recovery case 已经收敛，以及系统是否为下一轮写入了持久 outbox。
 * 它不表示下一轮模型已经完成规划，更不表示同步任务已经恢复成功。事件正文、授权快照、错误原文和
 * 模型输出均不会通过该对象返回给 Agent Runtime。</p>
 *
 * @param queued 是否已接受下一轮持久事件
 * @param eventId 下一轮事件 ID；未继续循环时为空
 * @param nextCycle 计算得到的下一轮编号，即使预算耗尽也保留该值用于审计
 */
public record SyncAutopilotRecoveryRepairReplanResult(
        boolean queued,
        String eventId,
        int nextCycle) {
}

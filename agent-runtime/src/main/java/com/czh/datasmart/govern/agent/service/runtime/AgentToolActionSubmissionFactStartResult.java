/**
 * @Author : Cui
 * @Date: 2026/06/28 21:45
 * @Description DataSmart Govern Backend - AgentToolActionSubmissionFactStartResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

/**
 * 受控工具提交事实登记结果。
 *
 * <p>start 操作必须能区分“本次调用成功登记了新的 SUBMITTING 事实”和“之前已经存在事实”。
 * 仅靠返回 record 不够，因为两个并发 worker 看到的 record 内容可能相似，但业务动作完全不同：
 * 新登记者才允许继续调用下游，命中旧事实者必须停止，防止重复副作用。</p>
 *
 * @param started true 表示本次调用获得提交资格，可以继续打下游。
 * @param record 当前事实记录；started=false 时它代表已有事实。
 */
public record AgentToolActionSubmissionFactStartResult(
        boolean started,
        AgentToolActionSubmissionFactRecord record
) {
}

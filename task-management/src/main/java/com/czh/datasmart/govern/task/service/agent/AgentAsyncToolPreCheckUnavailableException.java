/**
 * @Author : Cui
 * @Date: 2026/06/01 23:37
 * @Description DataSmart Govern Backend - AgentAsyncToolPreCheckUnavailableException.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.agent;

/**
 * Agent 异步工具执行前复核依赖暂时不可用异常。
 *
 * <p>执行前复核会逐步依赖 agent-runtime confirmation、permission-admin 策略 evaluate、租户配额服务、
 * 工具级限流器等多个控制面能力。依赖不可用和“复核明确不通过”是两种不同语义：</p>
 *
 * <p>1. 复核明确不通过，说明证据不一致、权限不足或状态不允许，应 fail-closed 并写入审计；</p>
 * <p>2. 依赖暂时不可用，说明当前无法安全判断，worker 不应执行副作用，也不应把任务永久失败，
 * 更合理的处理是 defer 回队列，等待依赖恢复后再次复核。</p>
 *
 * <p>单独定义该异常，是为了让调度编排层可以在 catch 分支中做“可重试退避”，避免把网络抖动、
 * agent-runtime 临时重启或控制面短暂不可达误处理成业务失败。</p>
 */
public class AgentAsyncToolPreCheckUnavailableException extends RuntimeException {

    public AgentAsyncToolPreCheckUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

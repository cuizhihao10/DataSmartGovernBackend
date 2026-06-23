/**
 * @Author : Cui
 * @Date: 2026/06/24 01:40
 * @Description DataSmart Govern Backend - AgentCommandWorkerLeaseState.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

/**
 * command worker lease 的低敏状态枚举。
 *
 * <p>该枚举用于 Java 控制面回答“某个 worker 是否拥有处理这条 command 的资格”。它不表达命令执行成功失败，
 * 也不保存命令行、stdout/stderr 或工具参数；这些内容都属于执行结果和 artifact 治理范围。</p>
 */
public enum AgentCommandWorkerLeaseState {

    /**
     * 当前 worker 首次领取成功，可以继续 worker precheck 或受控执行。
     */
    ACQUIRED,

    /**
     * 同一 worker 重复领取，按幂等重试返回已有 lease。
     */
    ALREADY_HELD_BY_CALLER,

    /**
     * 其他 worker 仍持有有效 lease，当前 worker 必须停止处理。
     */
    ALREADY_HELD_BY_OTHER,

    /**
     * receipt 写回时 lease 证据校验通过。
     */
    VALID_FOR_RECEIPT,

    /**
     * receipt 写回时没有找到 lease 事实。
     */
    NOT_FOUND,

    /**
     * lease 已过期，旧 worker 不能继续写回。
     */
    EXPIRED,

    /**
     * executor、token、version 或命令定位不匹配。
     */
    REJECTED
}

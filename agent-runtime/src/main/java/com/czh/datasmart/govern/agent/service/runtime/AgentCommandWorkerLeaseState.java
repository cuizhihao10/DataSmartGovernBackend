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
     * 当前持有者续租成功。
     *
     * <p>续租不会更换 fencingToken，也不会递增 leaseVersion；它只延长 leaseExpiresAt。
     * 这样 worker 可以在长耗时执行中保持同一份资格凭证，同时 receipt 必须携带最新过期时间，避免旧本地状态回写。</p>
     */
    RENEWED,

    /**
     * 当前持有者主动释放成功。
     *
     * <p>释放不是删除历史事实，而是把 lease 更新为立即过期。下一次 claim 会基于旧版本递增，
     * 保持 fencing 版本单调，便于排查重复投递、worker 抢占和旧结果回写。</p>
     */
    RELEASED,

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
     * 当前调用方提供的 fencingToken 与控制面事实不一致。
     */
    TOKEN_MISMATCH,

    /**
     * 当前调用方提供的 workerLeaseVersion 与控制面事实不一致。
     */
    VERSION_MISMATCH,

    /**
     * executor、token、version 或命令定位不匹配。
     */
    REJECTED
}

/**
 * @Author : Cui
 * @Date: 2026/06/26 20:27
 * @Description DataSmart Govern Backend - AgentToolActionArtifactBodyReadGrantStatus.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

/**
 * artifact 正文读取 grant 的服务端事实状态。
 *
 * <p>这里的状态只描述“服务端是否仍认可这条正文读取授权事实”，不描述对象是否真实存在、
 * MinIO 是否可读、DLP 是否通过，也不表示正文已经返回给调用方。真实读取仍必须继续经过
 * object-store adapter、final-check、审计和限流等后续控制面。</p>
 */
public enum AgentToolActionArtifactBodyReadGrantStatus {

    /**
     * grant 仍在有效期内，且尚未被管理员或策略撤销。
     */
    ACTIVE,

    /**
     * grant 已超过服务端签发时的 expiresAt。
     *
     * <p>过期不是删除历史事实，而是表示这条事实不能再被用于新的 final-check 或 probe。
     * 审计系统仍然可以根据该状态解释“为什么当时通过、现在拒绝”。</p>
     */
    EXPIRED,

    /**
     * grant 被服务端主动撤销。
     *
     * <p>撤销通常来自管理员操作、风险策略变更、artifact 被隔离、项目权限回收或安全扫描命中。
     * 撤销后即使 expiresAt 尚未到达，也必须 fail-closed。</p>
     */
    REVOKED
}

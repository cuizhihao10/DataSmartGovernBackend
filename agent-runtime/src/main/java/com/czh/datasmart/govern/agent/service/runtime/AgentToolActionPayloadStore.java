/**
 * @Author : Cui
 * @Date: 2026/06/11 00:00
 * @Description DataSmart Govern Backend - AgentToolActionPayloadStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.time.Instant;
import java.util.Optional;

/**
 * 工具动作 payload store 端口。
 *
 * <p>这里使用接口而不是让 writer 直接依赖内存 Map，是为了提前固定“控制面命令”和“载荷持久化”之间的边界。
 * 当前项目正在向 Codex/Claude Code 类 Agent Host 演进，工具调用会来自 MCP、A2A、前端确认页、自动规划器、
 * 人工审批和后台重放等多种入口。如果没有统一 payload store 端口，后续很容易出现每个入口各自保存参数、
 * 各自定义过期和权限规则，最终导致安全边界不可审计。</p>
 *
 * <p>本接口当前只要求最小能力：登记、按引用查询和清理过期记录。未来生产版可以替换为 MySQL 持久化、
 * Redis 短期租约、对象存储大载荷、KMS 加密或多级冷热存储，而 writer/verifier/executor 不需要知道底层细节。</p>
 */
public interface AgentToolActionPayloadStore {

    /**
     * 幂等登记一条 payload 记录。
     *
     * <p>同一个 payloadReference 在同一 run 内应当稳定指向同一份载荷信封。返回 true 表示首次登记，
     * false 表示引用已存在并复用旧记录。这样可以支持前端确认页、智能网关或 Agent loop 因网络重试重复调用 writer。</p>
     */
    boolean append(AgentToolActionPayloadRecord record);

    /**
     * 按受控引用查找 payload 记录。
     *
     * <p>调用方只能拿到服务端记录，不应把 {@code payloadBody} 暴露到 HTTP 响应、runtime event、outbox payload
     * 或 task params。当前 verifier 只使用元数据做存在性和归属校验。</p>
     */
    Optional<AgentToolActionPayloadRecord> findByReference(String payloadReference);

    /**
     * 清理过期记录。
     *
     * <p>内存实现用于测试和本地学习，清理动作可以由测试或未来定时任务触发；生产实现可以映射为 SQL delete、
     * Redis TTL 或对象存储生命周期规则。</p>
     */
    int removeExpired(Instant now);
}

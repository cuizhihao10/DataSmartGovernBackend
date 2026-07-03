/**
 * @Author : Cui
 * @Date: 2026/07/03 16:46
 * @Description DataSmart Govern Backend - AgentMcpDurableWorkerClientProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Java agent-runtime 调用 Python MCP Durable Worker 的客户端配置。
 *
 * <p>本配置块服务于“Java 控制面 outbox / dispatcher -> Python Runtime MCP Worker”的闭环。Java 侧仍然是
 * command/outbox/approval/lease/receipt 的控制面事实源，Python 侧负责真实连接 MCP Server、执行 tools/call、
 * 生成低敏 worker summary 与可选模型二轮反馈。把这一层抽成独立配置，是为了让后续从 HTTP 直连切换到
 * gateway、Nacos、服务网格、mTLS 或 Kafka worker 时，不需要重写 outbox 业务状态机。</p>
 *
 * <p>安全边界：</p>
 * <p>1. 该客户端只应该面向内网 Python Runtime 或受控内部网关，不应该配置公网地址；</p>
 * <p>2. 认证令牌必须来自环境变量、配置中心或 Secret，不允许写入日志、响应或 runtime event；</p>
 * <p>3. 请求参数是短生命周期执行入参，Java 只负责投递，不应该把参数正文写入低敏诊断结果；</p>
 * <p>4. 响应只消费 Python API 返回的 summary/receipt/modelFeedback 摘要，不接收 MCP 工具正文。</p>
 */
@Data
@ConfigurationProperties(prefix = "datasmart.agent-runtime.mcp-durable-worker")
public class AgentMcpDurableWorkerClientProperties {

    /**
     * 是否启用 Java -> Python MCP Durable Worker 调用。
     *
     * <p>默认关闭是为了保护本地学习环境：很多时候开发者只启动 agent-runtime 单服务，此时不应该因为 Python Runtime
     * 未启动而让 Java 服务启动失败。完整联调或生产环境应显式打开，并配合内网访问控制、服务账户认证和超时告警。</p>
     */
    private boolean enabled = false;

    /**
     * Python Runtime 的内部 baseUrl。
     *
     * <p>Compose 环境通常是 {@code http://python-ai-runtime:8090}；本地单机调试可以是
     * {@code http://localhost:8090}。不要在业务代码里硬编码地址，因为客户环境可能通过网关、服务发现或服务网格暴露
     * Python Runtime。</p>
     */
    private String baseUrl = "http://localhost:8090";

    /**
     * Python Runtime 暴露的 MCP Durable Worker 内部路由。
     *
     * <p>默认使用不带 {@code /api} 前缀的内部路径。Python 侧同时兼容
     * {@code /api/internal/agent/mcp/durable-worker/run}，保留该配置可以支持不同网关前缀或灰度路由。</p>
     */
    private String runPath = "/internal/agent/mcp/durable-worker/run";

    /**
     * 连接超时时间，单位毫秒。
     *
     * <p>MCP worker 调用位于后台 dispatcher 链路，连接失败应快速进入 outbox 重试，而不是长期占用 dispatcher 线程。
     * 生产环境可根据服务网格、跨机房网络和 Python Runtime 冷启动特征适度调大。</p>
     */
    private long connectTimeoutMs = 1500;

    /**
     * 读取响应超时时间，单位毫秒。
     *
     * <p>该超时覆盖 Python Runtime 侧 admission、MCP tools/call 和 summary 构造时间。真正耗时很长的工具不应无限同步等待，
     * 后续应演进为异步 worker、artifact 引用和状态回写。</p>
     */
    private long readTimeoutMs = 30000;

    /**
     * Java 调用 Python worker 时是否要求返回模型二轮反馈摘要。
     *
     * <p>开启后 Python 仍会执行自己的安全预算判断：只有短小、非敏感、未截断的结果才会进入 modelFeedback.result；
     * 大结果、敏感结果或失败结果只返回低敏 artifact/summary。Java 侧不要尝试覆盖这个安全策略。</p>
     */
    private boolean includeModelFeedback = true;

    /**
     * Python worker 完成后是否由 Python 直接回写 Java receipt。
     *
     * <p>当前阶段建议默认 false：Java dispatcher 先拿到 worker 响应，再由 Java 控制面统一写入 receipt、outbox 状态和任务投影。
     * 这样更容易保证幂等、lease fencing 和审计一致性。若未来某些部署希望 Python 直接回调 Java，可在灰度环境中显式开启。</p>
     */
    private boolean postToJava = false;

    /**
     * 服务间认证使用的 HTTP Header 名称。
     *
     * <p>默认使用 {@code Authorization}，并在 {@link #serviceAccountToken} 存在时发送
     * {@code Bearer <token>}。如果客户环境使用网关签名头、mTLS 旁路身份或自定义服务账户头，可以通过该字段替换。</p>
     */
    private String authHeaderName = "Authorization";

    /**
     * 服务账户令牌。
     *
     * <p>这是敏感配置，只能通过环境变量、Nacos Secret、K8s Secret 或企业密钥系统注入。客户端不会把它放入返回对象、
     * 日志消息、异常消息或测试断言输出。</p>
     */
    private String serviceAccountToken = "";
}

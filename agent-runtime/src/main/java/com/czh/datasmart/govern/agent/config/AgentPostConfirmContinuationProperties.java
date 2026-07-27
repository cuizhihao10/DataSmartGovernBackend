/**
 * @Author : Cui
 * @Date: 2026/07/27 00:00
 * @Description DataSmart Govern Backend - AgentPostConfirmContinuationProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Java 确认执行完成后调用 Python Durable Agent Loop 的内部客户端配置。
 *
 * <p>该调用不是新的用户操作，也不是绕过 Java 控制面执行工具。Java 只把已经固化的工具结果交给
 * Python 做下一轮模型决策；Python 后续产生的每个 ToolPlan 仍会重新进入 Java Run、审计、权限与审批。</p>
 */
@Data
@ConfigurationProperties(prefix = "datasmart.agent-runtime.post-confirm-continuation")
public class AgentPostConfirmContinuationProperties {

    /** 是否在成功确认批次后自动恢复模型与工具循环。 */
    private boolean enabled = false;

    /** Python AI Runtime 内部地址。 */
    private String baseUrl = "http://localhost:8090";

    /** 仅供服务间调用的内部路径，不应由浏览器直接调用。 */
    private String path = "/internal/agent/continuations/post-confirm";

    /** 模型多轮与只读工具链可能耗时较长，因此读取超时独立于普通工具调用。 */
    private int connectTimeoutMs = 1500;
    private int readTimeoutMs = 600000;

    /** 服务账号令牌只从环境或 Secret 注入，不进入日志、响应或运行事件。 */
    private String serviceAccountToken;
}

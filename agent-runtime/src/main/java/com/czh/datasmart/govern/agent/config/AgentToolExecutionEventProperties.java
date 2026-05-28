/**
 * @Author : Cui
 * @Date: 2026/05/28 00:58
 * @Description DataSmart Govern Backend - AgentToolExecutionEventProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 工具执行状态事件发布配置。
 *
 * <p>该配置面向 Java agent-runtime 的“出站事件”场景：当工具审计记录从 PLANNED 进入 EXECUTING、
 * 从 WAITING_APPROVAL 回到 PLANNED、从 EXECUTING 进入 SUCCEEDED/FAILED 等状态时，Java 控制面需要把这些
 * 事实发布给网关、前端实时事件、Python Runtime 二次推理控制器、审计中心或可观测性模块。</p>
 *
 * <p>这里刻意没有把配置塞回 {@code AgentRuntimeProperties}，原因是 Agent Runtime 后续会持续增长：
 * 模型路由、工具目录、Skill、长期记忆、短期记忆、工作区隔离、事件消费、事件生产、沙箱执行都会有独立配置。
 * 如果所有配置都压进一个类，会很快变成难以维护的“上帝配置类”，也不符合当前单文件尽量控制在 500 行内的设计规范。</p>
 */
@Data
@ConfigurationProperties(prefix = "datasmart.agent-runtime.tool-execution-events")
public class AgentToolExecutionEventProperties {

    /**
     * 是否启用真实事件发布。
     *
     * <p>默认关闭是为了保护本地学习和开发环境：很多时候开发者只想跑单元测试或启动服务验证 REST API，
     * 并没有启动 Kafka。如果默认打开，Spring Kafka producer 会尝试连接 broker，导致本地日志噪音和启动失败。
     * 生产环境准备好 topic、权限、告警、重试和消费侧幂等策略后，再显式打开该开关。</p>
     */
    private boolean enabled = false;

    /**
     * 工具执行状态事件 topic。
     *
     * <p>该 topic 专门承载“工具执行审计状态变化”，不要和 Python Runtime 的 token stream、高频模型事件、
     * 网关 WebSocket 心跳等混在一起。商业化系统里 topic 拆分很重要：它决定了保留时间、消费组职责、
     * 告警阈值、重放范围和权限边界。</p>
     */
    private String topic = "datasmart.agent-runtime.tool-execution-events";

    /**
     * 事件来源服务名。
     *
     * <p>消费者不能只凭 topic 判断事件来自哪里。后续如果引入独立 tool-runner、沙箱执行器或批处理 worker，
     * 它们也可能发布同类事件，因此 payload 内部必须携带 source 字段，方便审计和排障。</p>
     */
    private String source = "agent-runtime";
}

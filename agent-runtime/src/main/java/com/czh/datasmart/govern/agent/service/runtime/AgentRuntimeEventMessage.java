/**
 * @Author : Cui
 * @Date: 2026/05/27 00:00
 * @Description DataSmart Govern Backend - AgentRuntimeEventMessage.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Python AI Runtime 发布到 Kafka 的 Agent runtime event 消息体。
 *
 * <p>该类是 Java 侧的“入站契约”。Python 侧 payload 使用 lowerCamelCase：
 * `schemaVersion/source/publishedAt/eventType/stage/message/severity/...`，
 * Java 侧保持同名字段，避免消费端再维护一套蛇形命名到驼峰命名的映射规则。</p>
 *
 * <p>为什么加 `@JsonIgnoreProperties(ignoreUnknown = true)`：
 * 运行时事件是一个会持续演进的跨语言契约。Python 侧后续很可能增加 tokenUsage、modelRoute、
 * toolCallId、approvalId、latencyMs 等字段。Java 当前控制面不应该因为新增字段就消费失败；
 * 只要核心字段仍在，就可以先忽略未知字段，后续再按 `schemaVersion` 做显式升级。</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentRuntimeEventMessage {

    /** 契约版本，例如 `agent-runtime-event.v1`，用于未来兼容升级。 */
    private String schemaVersion;

    /** 事件来源服务，例如 `python-ai-runtime`，后续也可能是 Java worker 或独立 tool-runner。 */
    private String source;

    /** Python publisher 把事件交给 producer 的时间。 */
    private Instant publishedAt;

    /** 事件类型，例如 `tool_planned`、`approval_waiting`、`model_gateway_routed`。 */
    private String eventType;

    /** 事件所处业务阶段，例如 `build_context`、`plan_tools`。 */
    private String stage;

    /** 面向用户、运营、审计人员可读的事件说明。 */
    private String message;

    /** 严重级别：info/warning/error/audit。 */
    private String severity;

    /** 多租户边界字段，用于消费侧投影、审计和未来权限过滤。 */
    private String tenantId;

    /** 项目边界字段，用于项目级事件视图和审计范围隔离。 */
    private String projectId;

    /** 触发本次 Agent 运行的操作者。 */
    private String actorId;

    /** 同步 HTTP 请求 ID，适合定位一次 `/agent/plans` 调用。 */
    private String requestId;

    /** 长任务或多步骤 Agent 运行 ID，消费侧优先用它聚合事件。 */
    private String runId;

    /** 会话 ID，适合 UI 会话窗口、WebSocket 和多轮交互聚合。 */
    private String sessionId;

    /** 同一 run/session/request 内的业务序号，用于前端 replay 和消费端排序。 */
    private Long sequence;

    /** Python 侧事件产生时间。 */
    private Instant createdAt;

    /**
     * 扩展属性。
     *
     * <p>这里保留 Map 而不是提前拆成几十个字段，是为了让不同事件类型可以携带不同上下文。
     * 例如 context 事件携带 tokenEstimate，tool 事件携带 toolName，approval 事件携带 riskLevel。
     * 消费端真正落审计表或构建详情页时，再按 eventType 做细分解释。</p>
     */
    private Map<String, Object> attributes = new LinkedHashMap<>();
}

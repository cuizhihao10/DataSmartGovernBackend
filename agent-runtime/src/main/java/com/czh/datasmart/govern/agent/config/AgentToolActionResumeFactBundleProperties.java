/**
 * @Author : Cui
 * @Date: 2026/06/16 00:00
 * @Description DataSmart Govern Backend - AgentToolActionResumeFactBundleProperties.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 工具动作恢复事实包配置。
 *
 * <p>“恢复事实包”是 agent-runtime 面向 Python AI Runtime、智能网关和后续 Agent Host 的低敏控制面查询能力。
 * 它不执行工具、不写 outbox、不恢复 LangGraph/OpenClaw 执行图，只负责把 permission-admin 审批事实、
 * command outbox 写入事实、worker/dry-run receipt 投影等控制面证据聚合成“事实类型是否可用”的安全视图。</p>
 *
 * <p>为什么需要独立配置类：
 * 1. 该能力虽然也会调用 permission-admin，但它回答的是“某个恢复事实是否真实存在”，不是通用 RBAC 授权；
 * 2. 后续可能继续接入 clarification store、checkpoint store、worker receipt store、Redis/MySQL durable store；
 * 3. 独立配置可以避免 {@code AgentRuntimeProperties} 继续膨胀，也便于生产环境对恢复链路单独设置 fail-closed 策略。</p>
 */
@Data
@ConfigurationProperties(prefix = "datasmart.agent-runtime.tool-action-resume-facts")
public class AgentToolActionResumeFactBundleProperties {

    /**
     * checkpoint/thread 恢复定位索引的承载介质。
     *
     * <p>locator index 的职责，是把 Python Runtime 或智能网关传来的 checkpointId/threadId，
     * 映射到 Java 控制面可以回查的 commandId、outboxId、approvalFactId、clarificationFactId、toolCode
     * 和 policyVersion 等低敏定位符。它不是工具参数仓库，也不是 checkpoint 正文仓库，因此不能保存
     * prompt、SQL、arguments、payload body、模型输出、样本数据、密钥或内部 endpoint。</p>
     *
     * <p>当前支持两种模式：</p>
     * <p>1. memory：默认值，适合本地学习、单元测试和单实例联调；服务重启或多实例部署时不会共享数据。</p>
     * <p>2. mysql：写入 agent_tool_action_resume_locator_index 表，适合跨 JVM 重启、多实例控制面、审计追溯和未来真实 resume 前置校验。</p>
     *
     * <p>切换到 mysql 时必须同时设置 datasmart.agent-runtime.persistence.database-enabled=true，
     * 并先执行对应 migration。双开关设计可以避免开发者只改了 store 字符串，就让本地应用在没有 MySQL 的情况下启动失败。</p>
     */
    private String locatorIndexStore = "memory";

    /**
     * 是否把每次恢复事实包查询写成低敏 runtime event 诊断快照。
     *
     * <p>该开关默认开启，是为了让管理台和审计台可以在统一 timeline 中看到：
     * checkpoint/thread 是否命中 locator index、哪些事实类型缺失、哪些事实被服务端拒绝、当前请求采用了什么数据范围。
     * 这类事件只保存事实类型、状态和计数，不保存 approvalFactId、outboxId、payloadReference、SQL、prompt 或工具参数。</p>
     *
     * <p>如果本地压测只关心接口吞吐、暂时不希望 projection store 写入额外诊断事件，可以关闭该开关。
     * 关闭后主查询仍会返回 fact bundle 响应，但 WebSocket/HTTP replay 将无法看到本次恢复预检的独立时间线记录。</p>
     */
    private Boolean diagnosticEventEnabled = true;

    /**
     * 是否启用 permission-admin 审批事实远程评估。
     *
     * <p>默认关闭，保证本地学习环境不强依赖 permission-admin 已启动。
     * 关闭时事实包仍会返回审批事实的 NOT_EVALUATED/MISSING 状态，明确提示调用方当前没有服务端验真依据；
     * 它不会把调用方传入的 approvalFactId 当成已通过事实。</p>
     */
    private Boolean approvalFactEvaluationEnabled = false;

    /**
     * permission-admin 审批事实评估接口地址。
     *
     * <p>本地默认指向 8085。生产环境建议通过内部 gateway、Nacos 服务发现、服务网格或 mTLS 入口访问，
     * 并配合服务账号令牌，避免普通外部客户端直接调用审批事实验真接口。</p>
     */
    private String approvalFactEvaluateUrl =
            "http://localhost:8085/permissions/agent/tool-action-approvals/evaluate";

    /**
     * 远程审批事实评估超时时间，单位毫秒。
     *
     * <p>恢复预检通常位于用户点击“继续执行”之后的关键路径，超时不宜过长。
     * 如果 permission-admin 不可用，应快速返回可重试/阻断事实，而不是长期占用 Agent worker 或 HTTP 线程。</p>
     */
    private Long approvalFactTimeoutMs = 1500L;

    /**
     * permission-admin 不可用时是否 fail-closed。
     *
     * <p>商业化生产环境建议保持 true：审批中心不可用时不能把高风险工具动作当作已审批通过。
     * 本配置只影响事实包如何表达“远端不可用”；真实执行入口仍应在 outbox writer、worker pre-check
     * 和 task-management 中再次复核，形成多层安全阀。</p>
     */
    private Boolean failClosedWhenApprovalRemoteUnavailable = true;

    /**
     * 单次事实包查询最多扫描多少条 receipt 投影。
     *
     * <p>当前 runtime event projection 仍是内存热窗口，按 run/session/eventType 过滤后再做命令匹配。
     * 限制扫描数量可以避免异常 run 产生大量事件时，恢复预检接口被一次请求拖慢。
     * 后续切到 MySQL/ClickHouse 后应下沉为 commandId/runId 索引查询。</p>
     */
    private Integer receiptProjectionQueryLimit = 50;
}

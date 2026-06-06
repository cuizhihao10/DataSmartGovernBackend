/**
 * @Author : Cui
 * @Date: 2026/06/06 12:40
 * @Description DataSmart Govern Backend - AgentA2aTaskStateMachinePreviewService.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import com.czh.datasmart.govern.agent.controller.dto.AgentA2aTaskInternalPhaseView;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aTaskPolicyView;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aTaskStateMachinePreviewResponse;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aTaskStateView;
import com.czh.datasmart.govern.agent.controller.dto.AgentA2aTaskTransitionView;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A2A Task 状态机只读预览服务。
 *
 * <p>本服务是 5.28 阶段的“协议执行前控制面合同”。它不依赖数据库、不读取真实会话、不创建任务、不写 outbox、
 * 不发布 Kafka，也不执行任何工具；它只把 DataSmart 将来实现 A2A task 时必须遵守的生命周期语义先固定下来。</p>
 *
 * <p>为什么状态机必须独立成 service，而不是写在 controller 或 discovery service 里：
 * discovery service 负责“能力能否被外部 Agent 发现”，状态机负责“外部 Agent 委派任务后如何安全推进”。
 * 两者虽然都属于 A2A 协议适配层，但业务风险完全不同。如果混在一起，后续很容易把 Agent Card 发现、task 创建、
 * 取消、审批、worker pre-check 和 outbox 写入挤进一个巨大 Impl，既不利于学习，也不利于商业化维护。</p>
 */
@Service
public class AgentA2aTaskStateMachinePreviewService {

    private static final String SCHEMA_VERSION =
            "datasmart.agent-runtime.a2a-task-state-machine-preview.v1";
    private static final String PROTOCOL_VERSION = "1.0";
    private static final String PAYLOAD_POLICY =
            "SUMMARY_ONLY_NO_RAW_MESSAGE_NO_TOOL_INPUT_BODY_NO_RESOURCE_BODY_NO_MODEL_RESULT_NO_CREDENTIAL";
    private static final String EVENT_POLICY =
            "LOW_SENSITIVE_STATUS_ONLY_NO_MESSAGE_BODY_NO_ARTIFACT_BODY_NO_TOOL_INPUT_BODY";

    /**
     * 构建 A2A Task 状态机预览。
     *
     * <p>该方法当前没有入参，是刻意设计：状态机属于平台级协议合同，不应该随着 domain/riskLevel 或某个 Skill
     * 临时变化。后续如果某些 Skill 不支持 streaming、push 或取消，也应该在 Skill capability 或 task policy
     * 中表达，而不是让基础状态机出现多份互相矛盾的版本。</p>
     *
     * @return 状态机、内部阶段、流转、策略、产品选项和性能可靠性需求
     */
    public AgentA2aTaskStateMachinePreviewResponse buildPreview() {
        return new AgentA2aTaskStateMachinePreviewResponse(
                SCHEMA_VERSION,
                Instant.now(),
                "A2A",
                PROTOCOL_VERSION,
                true,
                false,
                PAYLOAD_POLICY,
                buildStates(),
                buildInternalPhases(),
                buildTransitions(),
                buildPolicy(),
                buildProductOptions(),
                buildPerformanceReliabilityRequirements(),
                buildNextSteps()
        );
    }

    /**
     * 定义 A2A 标准状态以及 DataSmart 的业务解释。
     *
     * <p>这里包含 `TASK_STATE_UNSPECIFIED`，但把它标记为 DIAGNOSTIC，而不是正常业务流转状态。原因是 A2A
     * 最新规范中保留了 unspecified/unknown 类表达，但真实商业系统不应把“不知道状态”当作可执行状态推进。
     * 如果查询不到 task，应由 get/list 接口返回错误或诊断态，而不是让 worker 继续处理。</p>
     */
    private List<AgentA2aTaskStateView> buildStates() {
        return List.of(
                state("TASK_STATE_UNSPECIFIED", "unspecified", "DIAGNOSTIC", true, false,
                        "任务处于未知或未确定状态。",
                        "仅用于协议兼容和诊断兜底，不作为 DataSmart 正常任务生命周期的一环。",
                        List.of("get-diagnostics"),
                        "可以向管理端展示为状态缺失，但不能向外部 Agent 暴露内部错误细节。",
                        List.of("不得进入 worker 队列。", "应触发状态修复或审计排查，而不是自动重启。")),
                state("TASK_STATE_SUBMITTED", "submitted", "IN_PROGRESS", false, false,
                        "任务已被服务端接收并确认，但尚未开始主动处理。",
                        "DataSmart 已接收外部 Agent 委派，但还需要完成权限、幂等、租户边界、Skill readiness 和容量预检。",
                        List.of("get", "cancel-before-dispatch"),
                        "只展示任务已提交，不展示原始消息正文、模型提示词或工具参数。",
                        List.of("需要持久化 task header。", "需要记录 submission runtime event。", "需要校验 idempotency key。")),
                state("TASK_STATE_WORKING", "working", "IN_PROGRESS", false, false,
                        "任务正在被 Agent 主动处理，客户端可以等待、轮询、订阅或接收后续更新。",
                        "DataSmart 可能正在执行 policy pre-check、outbox 投递、worker pre-check、模型路由或工具运行。",
                        List.of("get", "subscribe", "request-cancel", "register-push-notification"),
                        "可展示低敏进度、阶段和状态，不展示执行路径、工具实参、下游 endpoint 或模型输出。",
                        List.of("需要 heartbeat。", "需要 worker receipt。", "需要低基数指标。", "需要 runtime event timeline。")),
                state("TASK_STATE_INPUT_REQUIRED", "input-required", "INTERRUPTED", false, true,
                        "Agent 需要客户端或用户补充输入才能继续。",
                        "DataSmart 发现必要字段缺失、业务目标不清、资源选择冲突或需要用户确认非敏感参数。",
                        List.of("get", "continue-with-input", "cancel"),
                        "只说明需要补充什么类型的信息，不回显敏感字段值或内部推理过程。",
                        List.of("需要 input timeout。", "补充输入必须重新经过参数校验和权限检查。")),
                state("TASK_STATE_AUTH_REQUIRED", "auth-required", "INTERRUPTED", false, true,
                        "Agent 需要额外认证或授权才能继续。",
                        "DataSmart 将该状态映射为 permission-admin scope 缺口、人类审批、租户授权或服务账号授权等待。",
                        List.of("get", "continue-after-authorization", "cancel"),
                        "不得通过 A2A 正文交换明文凭证，只展示授权类型和审批入口摘要。",
                        List.of("凭证应走网关或安全通道。", "审批结果要回写 task event。", "审批超时后进入 rejected 或 canceled。")),
                state("TASK_STATE_COMPLETED", "completed", "TERMINAL", true, false,
                        "任务成功完成，结果通常通过 artifacts 或 status message 获取。",
                        "DataSmart 已完成业务动作，并生成低敏结果摘要、artifact 引用或审计记录。",
                        List.of("get", "list-history"),
                        "可展示结果摘要和 artifact 引用，不展示未脱敏正文、模型完整输出或工具响应原文。",
                        List.of("终态不可重启。", "如需再次执行必须创建新 task。")),
                state("TASK_STATE_FAILED", "failed", "TERMINAL", true, false,
                        "任务因处理错误而终止。",
                        "DataSmart worker、模型网关、工具沙箱或下游系统发生不可继续的错误，或重试耗尽。",
                        List.of("get", "list-history", "create-new-task-from-template"),
                        "只展示错误类别、阶段和建议动作，不展示堆栈、内部 URL、密钥或下游响应正文。",
                        List.of("需要错误分类。", "需要 DLQ 或补偿入口。", "终态不可自动回到 working。")),
                state("TASK_STATE_CANCELED", "canceled", "TERMINAL", true, false,
                        "任务已取消。",
                        "取消请求已经被 DataSmart 接受，并确认尚未产生不可逆副作用，或补偿流程已完成。",
                        List.of("get", "list-history"),
                        "可展示取消原因摘要，不展示取消前的工具参数、模型提示词或内部执行路径。",
                        List.of("终态不可重启。", "副作用后的取消必须保留补偿证据。")),
                state("TASK_STATE_REJECTED", "rejected", "TERMINAL", true, false,
                        "Agent 拒绝执行任务。",
                        "DataSmart 因权限不足、Skill 不可用、风险策略、审批拒绝、租户隔离或能力不支持而拒绝任务。",
                        List.of("get", "list-history", "submit-corrected-task"),
                        "展示拒绝类别和改正建议，不展示内部策略细节或敏感权限表达式。",
                        List.of("拒绝是终态。", "修正后需要新 task 和新幂等键。"))
        );
    }

    private List<AgentA2aTaskInternalPhaseView> buildInternalPhases() {
        return List.of(
                phase("DISCOVERED", "TASK_STATE_SUBMITTED", "gateway/protocol-adapter", true, "NO_SIDE_EFFECT",
                        "外部 Agent 已通过 Agent Card 或目录发现能力，但尚未形成真实任务。",
                        List.of("discovery counter", "public well-known read event"),
                        List.of("Agent Card URL", "Skill full descriptor", "tool schema body")),
                phase("POLICY_PRECHECK", "TASK_STATE_SUBMITTED", "agent-runtime/permission-admin", true, "NO_SIDE_EFFECT",
                        "提交后先检查租户、项目、角色、Skill readiness、风险等级、幂等键和容量配额。",
                        List.of("precheck outcome", "policy version", "quota decision"),
                        List.of("permission expression", "raw message", "tool arguments")),
                phase("APPROVAL_WAITING", "TASK_STATE_AUTH_REQUIRED", "permission-admin/human-review", false, "NO_SIDE_EFFECT",
                        "高风险、破坏性或越权动作需要人类审批或额外授权。",
                        List.of("approval request id", "approval state", "timeout deadline"),
                        List.of("credential", "approval private comment", "sensitive business payload")),
                phase("INPUT_WAITING", "TASK_STATE_INPUT_REQUIRED", "gateway/user-channel", false, "NO_SIDE_EFFECT",
                        "用户需要补充缺失字段、确认资源范围或澄清业务目标。",
                        List.of("required field count", "input timeout deadline"),
                        List.of("原始用户消息", "样例数据", "模型提示词")),
                phase("OUTBOX_PENDING", "TASK_STATE_WORKING", "agent-runtime/outbox", true, "BEFORE_EXTERNAL_SIDE_EFFECT",
                        "已经通过前置治理，等待 command outbox 投递给 worker。",
                        List.of("outbox id", "dispatch status", "retry attempt"),
                        List.of("command payload body", "target endpoint", "tool arguments")),
                phase("WORKER_PRECHECK", "TASK_STATE_WORKING", "python-runtime/worker", true, "BEFORE_EXTERNAL_SIDE_EFFECT",
                        "worker 消费命令后再次检查指纹、权限版本、工具沙箱、模型网关健康和资源容量。",
                        List.of("worker id hash", "precheck result", "capacity decision"),
                        List.of("工作区真实路径", "凭证", "模型提示词")),
                phase("RUNNING", "TASK_STATE_WORKING", "python-runtime/tool-sandbox", false, "MAY_HAVE_EXTERNAL_SIDE_EFFECT",
                        "任务正在执行模型推理、工具调用、数据治理动作或异步下游操作。",
                        List.of("heartbeat", "stage progress", "low-cardinality outcome"),
                        List.of("工具响应正文", "模型输出正文", "查询语句", "样例数据")),
                phase("RESULT_READY", "TASK_STATE_COMPLETED", "agent-runtime/artifact-service", false, "SIDE_EFFECT_RECORDED",
                        "结果已经生成，控制面只保留摘要、artifact 引用和审计证据。",
                        List.of("artifact count", "summary available", "completion event"),
                        List.of("artifact body", "raw output", "customer data")),
                phase("EXPIRED", "TASK_STATE_FAILED", "agent-runtime/scheduler", false, "SIDE_EFFECT_DEPENDS_ON_PHASE",
                        "任务在提交、输入、审批或 worker heartbeat 阶段超过窗口。",
                        List.of("expired phase", "deadline", "recovery suggestion"),
                        List.of("raw payload", "credential", "internal endpoint")),
                phase("DEAD_LETTER", "TASK_STATE_FAILED", "agent-runtime/operator-console", false, "SIDE_EFFECT_DEPENDS_ON_PHASE",
                        "重试耗尽或毒性任务进入死信，等待管理员诊断、补偿或归档。",
                        List.of("dlq reason", "attempt count", "operator action required"),
                        List.of("完整堆栈", "凭证", "工具参数正文"))
        );
    }

    private List<AgentA2aTaskTransitionView> buildTransitions() {
        return List.of(
                transition("TASK_STATE_SUBMITTED", "TASK_STATE_WORKING", "task.accepted",
                        "permission-admin 已通过，Skill READY，幂等键有效，租户/项目/workspace 边界匹配，队列容量允许。",
                        List.of("POLICY_PRECHECK", "OUTBOX_PENDING", "WORKER_PRECHECK"),
                        "记录 submitted->working 状态事件；重复提交命中同一幂等键时返回既有 task。",
                        "submitted 超过等待窗口且未进入 outbox 时转为 failed 或 rejected。"),
                transition("TASK_STATE_SUBMITTED", "TASK_STATE_REJECTED", "policy.rejected",
                        "权限不足、Skill 不可用、风险策略拒绝、租户不可见或参数结构非法。",
                        List.of("POLICY_PRECHECK"),
                        "记录拒绝类别和策略版本，不记录具体权限表达式或敏感请求正文。",
                        "预检拒绝不重试；用户修正后必须创建新 task。"),
                transition("TASK_STATE_SUBMITTED", "TASK_STATE_CANCELED", "client.cancel.before-dispatch",
                        "任务尚未进入外部副作用边界，取消幂等键有效，提交方或管理员具备取消权限。",
                        List.of("POLICY_PRECHECK"),
                        "取消事件按 taskId + cancelRequestId 幂等；重复取消返回已取消状态。",
                        "提交后长时间未调度可由系统策略自动取消。"),
                transition("TASK_STATE_WORKING", "TASK_STATE_INPUT_REQUIRED", "agent.requires-input",
                        "worker 或规划器发现缺少必要业务输入，且该缺口不能由上下文安全填充。",
                        List.of("RUNNING", "INPUT_WAITING"),
                        "暂停 worker 或保留 checkpoint；补充输入后重新做参数校验和权限校验。",
                        "input-required 超时后转为 canceled 或 failed，具体取决于是否已产生副作用。"),
                transition("TASK_STATE_WORKING", "TASK_STATE_AUTH_REQUIRED", "agent.requires-authorization",
                        "需要额外授权、人类审批、服务账号 scope 或高风险操作确认。",
                        List.of("RUNNING", "APPROVAL_WAITING"),
                        "审批请求与 task 关联；审批结果通过低敏事件驱动状态继续或拒绝。",
                        "auth-required 超时或审批拒绝后进入 rejected；不得在 A2A 正文中传递明文凭证。"),
                transition("TASK_STATE_INPUT_REQUIRED", "TASK_STATE_WORKING", "client.input-provided",
                        "客户端补充输入通过 schema、权限、租户边界和敏感字段策略校验。",
                        List.of("INPUT_WAITING", "POLICY_PRECHECK", "WORKER_PRECHECK"),
                        "继续输入必须携带 messageId 或幂等键；重复消息不能触发重复副作用。",
                        "补充输入后重新计算超时窗口和 worker checkpoint。"),
                transition("TASK_STATE_AUTH_REQUIRED", "TASK_STATE_WORKING", "authorization.granted",
                        "审批通过或授权满足，且授权范围仍与原 task、租户、项目、workspace 和工具风险匹配。",
                        List.of("APPROVAL_WAITING", "POLICY_PRECHECK", "WORKER_PRECHECK"),
                        "审批结果写入 task event 和 outbox 来源证据；不复制审批私密备注或凭证。",
                        "授权有效期过短或策略版本变化时，需要重新进入 auth-required。"),
                transition("TASK_STATE_AUTH_REQUIRED", "TASK_STATE_REJECTED", "authorization.denied-or-expired",
                        "审批拒绝、授权过期、scope 不满足或租户管理员撤销授权。",
                        List.of("APPROVAL_WAITING"),
                        "记录拒绝类别、审批状态和策略版本；禁止记录审批私密意见或 token。",
                        "审批超时后进入 rejected，除非运营策略明确允许转为 canceled。"),
                transition("TASK_STATE_WORKING", "TASK_STATE_COMPLETED", "worker.result-ready",
                        "worker 返回成功 receipt，结果摘要和 artifact 引用通过脱敏校验，outbox 状态已闭环。",
                        List.of("RUNNING", "RESULT_READY"),
                        "完成事件只保存摘要、artifact 引用和计数；不保存 artifact 正文或模型完整输出。",
                        "完成后为终态，不允许重新回到 working。"),
                transition("TASK_STATE_WORKING", "TASK_STATE_FAILED", "worker.terminal-failure",
                        "重试耗尽、下游不可恢复、worker heartbeat 超时、schema 不兼容或安全策略阻断。",
                        List.of("RUNNING", "EXPIRED", "DEAD_LETTER"),
                        "失败事件保存阶段、错误类别和建议动作；详细堆栈仅进入受控日志或运维台。",
                        "失败后如需重跑必须新建 task 或走管理员补偿流程。"),
                transition("TASK_STATE_WORKING", "TASK_STATE_CANCELED", "client.cancel.accepted",
                        "worker 确认尚未产生不可逆副作用，或补偿流程已经完成；取消方具备权限且请求幂等。",
                        List.of("OUTBOX_PENDING", "WORKER_PRECHECK", "RUNNING"),
                        "取消请求先写入低敏事件；worker receipt 确认后才进入 canceled 终态。",
                        "副作用边界之后不能承诺立即取消，只能进入 cancel-requested 或补偿流程。"),
                transition("TASK_STATE_INPUT_REQUIRED", "TASK_STATE_CANCELED", "client.cancel.input-waiting",
                        "任务暂停等待输入，尚未继续进入副作用边界，取消方具备权限。",
                        List.of("INPUT_WAITING"),
                        "取消事件保留等待输入阶段和取消原因摘要；不记录原始输入缺口正文。",
                        "输入等待超过窗口时也可由系统自动取消。"),
                transition("TASK_STATE_AUTH_REQUIRED", "TASK_STATE_CANCELED", "client.cancel.auth-waiting",
                        "任务暂停等待授权，尚未继续进入副作用边界，取消方具备权限。",
                        List.of("APPROVAL_WAITING"),
                        "取消事件保留授权等待阶段和取消原因摘要；审批记录仍归 permission-admin 管理。",
                        "授权等待超过窗口时可按租户策略转为 canceled 或 rejected。")
        );
    }

    private AgentA2aTaskPolicyView buildPolicy() {
        return new AgentA2aTaskPolicyView(
                List.of(
                        "副作用发生前可以直接取消；进入 worker 运行后只能先写入 cancel-requested，再由 worker receipt 确认。",
                        "取消请求必须携带 cancelRequestId 或等价幂等键，重复取消不能重复写 outbox。",
                        "高风险数据修改、ETL 发布、合规脱敏等动作如果已经进入下游系统，需要补偿或人工确认后才能进入 canceled。"
                ),
                List.of(
                        "task submit 使用 tenantId + actorId + clientMessageId/requestId 形成幂等键。",
                        "继续输入、审批回调、取消请求都需要独立幂等键，避免断线重试造成重复副作用。",
                        "幂等命中时返回既有 task 状态，不重新执行工具或模型调用。"
                ),
                List.of(
                        "A2A 请求先由 gateway 完成认证，再由 permission-admin 判断角色、租户、项目、workspace 与 Skill scope。",
                        "TASK_STATE_AUTH_REQUIRED 表示授权缺口或审批等待，不表示允许在 A2A message 中传递明文凭证。",
                        "服务账号和外部 Agent 需要最小权限 scope，真实执行前仍要做 worker pre-check。"
                ),
                Map.of(
                        "submittedTimeoutSeconds", 60,
                        "inputRequiredTimeoutSeconds", 1800,
                        "authRequiredTimeoutSeconds", 3600,
                        "workerHeartbeatTimeoutSeconds", 120,
                        "outboxDispatchTimeoutSeconds", 300
                ),
                List.of(
                        "POLICY_PRECHECK、OUTBOX_PENDING、WORKER_PRECHECK 可以自动重试，但必须受最大次数和退避控制。",
                        "APPROVAL_WAITING、INPUT_WAITING 不自动重试，只等待外部输入或授权结果。",
                        "RUNNING 阶段是否重试取决于工具是否幂等、是否已产生副作用以及 worker receipt。"
                ),
                List.of(
                        "当前 streaming 未启用；后续只允许推送低敏 status update 和 artifact 引用。",
                        "stream event 不得携带模型提示词、消息正文、工具参数、模型输出或资源正文。",
                        "客户端断线后应可通过 get/list 和 runtime event projection 恢复状态。"
                ),
                List.of(
                        "当前 push notification 未启用；后续必须要求 webhook HTTPS、签名、重放保护和退避重试。",
                        "push payload 只包含 taskId、状态、阶段摘要和 artifact 引用，不包含 token 或业务正文。",
                        "push 配置需要随 task 完成或删除而清理，避免长尾通知泄露。"
                ),
                List.of(
                        "task header、status history、runtime event、command outbox、worker receipt 和 artifact metadata 应分表/分层保存。",
                        "通用 runtime event 保存低敏状态事实，长期审计表保存可恢复任务事实，敏感正文进入受控 artifact 或业务表。",
                        "状态更新需要乐观锁或版本号，防止多 worker、回调和取消请求互相覆盖。"
                ),
                List.of(
                        "禁止在状态机预览、runtime event、push payload、stream payload 中保存模型提示词、工具实参、资源正文、模型输出、查询语句、样例数据、凭证或内部 endpoint。",
                        "错误、拒绝、审批和取消只记录类别、阶段、计数、策略版本和建议动作。",
                        "外部 Agent 只能看到协议状态和低敏摘要，内部治理细节需要管理员权限。"
                ),
                Map.of(
                        "targetSubmitP99LatencyMs", 200,
                        "targetGetTaskP99LatencyMs", 100,
                        "targetStatusUpdateFanoutSeconds", 2,
                        "targetConcurrentWorkingTasksPerInstance", 500,
                        "targetOutboxBacklogAlertThreshold", 10000
                )
        );
    }

    private List<String> buildProductOptions() {
        return List.of(
                "是否先实现只读 `tasks/get`/`tasks/list` 诊断端点，再开放 `message:send` 创建任务。",
                "是否把 A2A task 与现有 task-management 微服务打通，形成统一任务台，而不是 agent-runtime 自己维护孤立任务。",
                "是否为高风险 Skill 增加租户级策略：只允许人工审批后进入 working，默认直接 auth-required。",
                "是否支持 streaming 与 push notification 分阶段灰度：先管理端可见，再开放给可信外部 Agent。",
                "是否把 A2A cancel 与现有 command outbox 的 worker pre-check/cancel-requested receipt 合并，避免出现两套取消语义。"
        );
    }

    private List<String> buildPerformanceReliabilityRequirements() {
        return List.of(
                "提交接口需要支持高并发幂等，目标 P99 小于 200ms，任务真正执行通过 outbox 异步化。",
                "任务状态查询需要低延迟，目标 P99 小于 100ms；热状态可用 Redis 或内存 projection，长期事实落关系型事实库。",
                "worker heartbeat 超时、outbox backlog、审批等待超时、push 重试失败都需要低基数指标和告警。",
                "状态更新必须具备版本控制，避免取消、审批回调、worker 完成事件并发写入造成终态覆盖。",
                "终态任务不可重启；补偿、重跑和纠错必须创建新 task 或管理员补偿记录。"
        );
    }

    private List<String> buildNextSteps() {
        return List.of(
                "下一步优先实现 A2A task runtime event schema 或只读 task preview，不直接开放真实 task 创建。",
                "真实 `message:send` 前必须接 permission-admin、task-management、confirmation/outbox、worker pre-check、限流和幂等。",
                "如果继续协议互联主线，可设计 MCP tools/call 与 A2A task 共用的 durable action contract。",
                "为保持项目整体节奏，完成状态机后应评估是否切回 Python Runtime 编排、长期记忆治理或模型网关真实推理遥测。"
        );
    }

    private AgentA2aTaskStateView state(String state,
                                        String wireValue,
                                        String category,
                                        boolean terminal,
                                        boolean interrupted,
                                        String description,
                                        String datasmartMeaning,
                                        List<String> allowedClientOperations,
                                        String externalVisibility,
                                        List<String> controlPlaneNotes) {
        return new AgentA2aTaskStateView(
                state,
                wireValue,
                category,
                terminal,
                interrupted,
                description,
                datasmartMeaning,
                allowedClientOperations,
                externalVisibility,
                controlPlaneNotes
        );
    }

    private AgentA2aTaskInternalPhaseView phase(String phaseCode,
                                                String mapsToA2aState,
                                                String responsibleLayer,
                                                boolean retryable,
                                                String sideEffectBoundary,
                                                String description,
                                                List<String> observabilityRequirement,
                                                List<String> hiddenPayloads) {
        return new AgentA2aTaskInternalPhaseView(
                phaseCode,
                mapsToA2aState,
                responsibleLayer,
                retryable,
                sideEffectBoundary,
                description,
                observabilityRequirement,
                hiddenPayloads
        );
    }

    private AgentA2aTaskTransitionView transition(String fromState,
                                                  String toState,
                                                  String trigger,
                                                  String guardrail,
                                                  List<String> datasmartInternalPhases,
                                                  String replayPolicy,
                                                  String timeoutPolicy) {
        return new AgentA2aTaskTransitionView(
                fromState,
                toState,
                trigger,
                true,
                guardrail,
                datasmartInternalPhases,
                replayPolicy,
                timeoutPolicy,
                EVENT_POLICY,
                List.of("当前为状态机预览，不创建任务、不执行工具、不写 outbox、不发布真实 task 事件。")
        );
    }
}

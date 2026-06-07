/**
 * @Author : Cui
 * @Date: 2026/05/31 16:42
 * @Description DataSmart Govern Backend - AgentAsyncTaskCommandRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.controller.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * Agent Runtime 下发给 task-management 的异步工具命令契约。
 *
 * <p>这份 DTO 是未来 Kafka message payload 和当前内部 HTTP 联调接口共用的协议。
 * DTO 刻意不包含原始工具参数值，只携带 payloadReference、参数名和敏感参数名。
 * 真正执行任务的 worker 必须通过受控引用解析器读取载荷，并在读取时再次做权限、工作空间、
 * 密钥引用、字段脱敏和 schema 校验。</p>
 *
 * <p>为什么不把完整 arguments JSON 放进 Kafka：</p>
 * <p>1. 消息队列、死信、日志和运维控制台都可能复制消息，原始密钥或 SQL 会扩大泄漏面；</p>
 * <p>2. 大型扫描参数、文件清单和样本内容会让消息膨胀，降低吞吐和重放效率；</p>
 * <p>3. 使用安全引用后，载荷读取权限可以在执行时动态撤销，而不是消息发出后永久失控。</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentAsyncTaskCommandRequest {

    /**
     * 协议版本。消费者只接受明确支持的版本，避免字段语义升级后静默误执行。
     */
    @NotBlank(message = "schemaVersion 不能为空")
    private String schemaVersion;

    /**
     * 稳定命令 ID。Agent Runtime 应根据 sessionId/runId/auditId 稳定生成。
     */
    @NotBlank(message = "commandId 不能为空")
    private String commandId;

    /**
     * 跨服务幂等键。重复投递必须复用相同值。
     */
    @NotBlank(message = "idempotencyKey 不能为空")
    private String idempotencyKey;

    /**
     * 命令类型。
     *
     * <p>当前支持两条产品路径：</p>
     * <p>1. `AGENT_TOOL_ASYNC_TASK_REQUESTED`：历史异步工具任务，会创建可被现有 worker 认领的 `AGENT_ASYNC_TOOL`；</p>
     * <p>2. `AGENT_TOOL_ACTION_CONTROLLED_COMMAND`：新工具动作控制面命令，只进入 Inbox 和任务台账，
     * 等待后续专用 tool-action executor，不会被旧 worker 直接执行。</p>
     */
    @NotBlank(message = "commandType 不能为空")
    private String commandType;

    /**
     * Agent 工具执行审计 ID，是回查受控参数快照和回写工具状态的关键引用。
     */
    @NotBlank(message = "auditId 不能为空")
    private String auditId;

    /**
     * Agent 会话 ID。
     */
    @NotBlank(message = "sessionId 不能为空")
    private String sessionId;

    /**
     * Agent Run ID。
     */
    @NotBlank(message = "runId 不能为空")
    private String runId;

    /**
     * 工具编码，例如 data-sync.execute、quality.scan.start。
     *
     * <p>新工具动作 writer 的上游图字段叫 toolName，语义上等价于这里的 toolCode。
     * 使用 JsonAlias 是为了让 task-management 可以兼容两种低敏信封命名，而不要求 agent-runtime
     * 和 task-management 在同一次迭代里完全同步字段名。</p>
     */
    @NotBlank(message = "toolCode 不能为空")
    @JsonAlias("toolName")
    private String toolCode;

    /**
     * 工具最终面向的业务模块。
     */
    @NotBlank(message = "targetService 不能为空")
    private String targetService;

    /**
     * 工具目录声明的目标端点模板。
     *
     * <p>该字段对历史 `AGENT_TOOL_ASYNC_TASK_REQUESTED` 必填，worker 会用它和 agent-runtime 参数快照做一致性校验。
     * 对新 `AGENT_TOOL_ACTION_CONTROLLED_COMMAND` 则必须为空，因为新命令不能让外部调用方携带内部端点，
     * 真实目标应由后续受控执行器基于 toolCode、payload store、权限策略重新解析。</p>
     */
    private String targetEndpoint;

    /**
     * 租户边界。异步自动化动作禁止缺失租户上下文。
     */
    @Min(value = 1, message = "tenantId 必须大于 0")
    private Long tenantId;

    /**
     * 项目边界。用于项目级队列、公平性、权限和成本归集。
     */
    @Min(value = 1, message = "projectId 必须大于 0")
    private Long projectId;

    /**
     * Agent 工作空间边界。用于研发、测试、生产空间隔离和产物解析。
     *
     * <p>历史 async-task worker 需要该字段做参数快照一致性校验。新工具动作控制面命令当前可以为空，
     * 因为它可能只绑定 run/project，workspace 需要在后续 payload store 或真实执行器中再物化。</p>
     */
    @Min(value = 1, message = "workspaceId 必须大于 0")
    private Long workspaceId;

    /**
     * 原始动作发起者。允许是用户、服务账号或 Agent 字符串身份。
     */
    @NotBlank(message = "actorId 不能为空")
    private String actorId;

    /**
     * 全链路追踪 ID。
     */
    @NotBlank(message = "traceId 不能为空")
    private String traceId;

    /**
     * 受控载荷引用。
     *
     * <p>历史 async-task 使用 `agent-tool-audit://.../plan-arguments`，表示 worker 后续回到受治理审计存储读取参数快照。
     * 新工具动作使用 `agent-payload:{runId}/{payloadKey}`，表示后续专用执行器需要回到独立 payload store 读取载荷。
     * 两者都不允许把参数值直接塞进消息或任务主表。</p>
     */
    @NotBlank(message = "payloadReference 不能为空")
    private String payloadReference;

    /**
     * 参数名快照，不包含参数值。
     */
    private List<String> argumentNames;

    /**
     * 敏感参数名快照。消费者会校验它必须是 argumentNames 的子集。
     */
    private List<String> sensitiveArgumentNames;

    /**
     * selected-node 确认 ID。
     *
     * <p>该字段来自 agent-runtime confirmation 证据层，用于说明这条 command 是由哪次 human-in-the-loop
     * 或受控策略确认动作产生的。Run 级兼容入口可能为空；生产推荐的 selected-node 入口应携带该字段。</p>
     */
    private String confirmationId;

    /**
     * 入箱时关联的 permission-admin 策略版本快照。
     *
     * <p>task-management 当前只做格式与长度校验，后续 worker 执行前应回到 permission-admin 复核策略是否仍然有效。
     * 这里先把版本放入任务安全参数，避免下游只看到“服务账号创建了任务”，却看不到当时依据哪版策略。</p>
     */
    private List<String> policyVersions;

    /**
     * 服务账号委托证据摘要。
     *
     * <p>该字段只允许保存低敏审计摘要，不能包含工具参数、SQL、prompt 或样本数据。
     * 消费侧会做长度和危险片段校验，避免把原始上下文误塞进 task.params。</p>
     */
    private List<String> delegationEvidence;

    /**
     * 任务优先级。为空时使用任务中心默认值 MEDIUM。
     */
    private String priority;

    /**
     * 最大业务失败重试次数。
     */
    @Min(value = 0, message = "maxRetryCount 不能小于 0")
    private Integer maxRetryCount;

    /**
     * 最大连续退避次数。
     */
    @Min(value = 0, message = "maxDeferCount 不能小于 0")
    private Integer maxDeferCount;
}

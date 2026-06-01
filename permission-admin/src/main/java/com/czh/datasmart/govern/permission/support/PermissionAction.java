/**
 * @Author : Cui
 * @Date: 2026/04/25 23:00
 * @Description DataSmart Govern Backend - PermissionAction.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.support;

/**
 * 权限动作。
 *
 * <p>动作粒度决定权限系统能否从“能进页面”升级到“能点哪个按钮、能执行哪个高风险操作”。
 * 当前先定义通用动作，后续每个业务域可以在不破坏平台模型的前提下扩展更细动作。
 */
public enum PermissionAction {
    VIEW,
    CREATE,
    UPDATE,
    DELETE,
    EXECUTE,
    APPROVE,
    EXPORT,
    /**
     * 批量导入。
     *
     * <p>项目成员、组织用户、数据源连接器等后台对象常常需要从外部系统或文件批量导入，
     * 它与单条 CREATE 的风险不同，后续可以单独挂审批、限流和审计导出。
     */
    IMPORT,
    /**
     * 启用资源。
     *
     * <p>启用往往意味着权限、路由、任务或连接器重新生效，应和普通 UPDATE 区分审计。
     */
    ENABLE,
    /**
     * 禁用资源。
     *
     * <p>禁用通常保留历史记录但停止生效，适合成员离开项目、连接器下线或策略暂挂。
     */
    DISABLE,
    VALIDATE,
    RUN,
    PAUSE,
    RESUME,
    RETRY,
    CANCEL,
    ARCHIVE,
    ACKNOWLEDGE,
    ASSIGN,
    RESOLVE,
    CLOSE,
    CLAIM,
    HEARTBEAT,
    DEFER,
    CALLBACK,
    RECOVER,
    FORCE_RETRY,
    FORCE_CANCEL,
    PRIORITY_OVERRIDE,
    CONFIGURE,
    AUDIT,
    /**
     * 查看 Agent 运行时事件。
     *
     * <p>该动作专门保护 Agent Runtime 已消费并投影出来的运行事件，例如规划阶段、工具调用、审批等待、
     * 异常原因、runId/sessionId/requestId 关联链路等。它比普通 VIEW 更敏感，因为事件流能还原一次
     * Agent 执行的完整过程，未来应按“本人事件、项目事件、租户事件、审计事件”继续细分数据范围。</p>
     */
    VIEW_EVENTS,
    /**
     * 查看 Agent DAG selected-node 确认记录。
     *
     * <p>确认记录描述的是“某个用户或服务账号确认了哪一版 dry-run 预案，并把哪些异步工具节点送入 outbox”。
     * 它和 runtime event 不完全相同：event 更偏执行时间线，confirmation 更偏 human-in-the-loop 与 durable action
     * 的授权证据。单独建模后，审计员可以查看确认链路，普通事件查看权限则不会自动获得确认详情。</p>
     */
    VIEW_TOOL_CONFIRMATIONS,
    /**
     * 诊断 Agent Runtime 运行时组件。
     *
     * <p>诊断类接口通常会暴露 consumer 是否启用、topic/groupId、投影窗口大小、拒绝原因计数和处理耗时。
     * 这些信息面向运维和平台管理员，不应和普通事件查看权限混在一起，否则普通用户可能通过诊断信息推断
     * 平台拓扑、消息积压、异常 payload 类型或内部运行状态。</p>
     */
    DIAGNOSE,
    /**
     * 订阅实时事件。
     *
     * <p>WebSocket 握手在 HTTP 层表现为 GET，但业务含义是建立一条持续占用网关、Python Runtime、
     * Redis/Kafka 事件链路和浏览器资源的长连接，因此不能简单映射为 VIEW。单独建模 SUBSCRIBE 后，
     * 后续可以按租户套餐、用户角色、连接数配额和事件类型白名单做更细的治理。</p>
     */
    SUBSCRIBE,
    /**
     * 查看 Agent 工具执行事件 outbox。
     *
     * <p>outbox 记录描述的是“工具状态事件是否可靠进入投递链路”，常用于排查实时事件丢失、
     * dispatcher 堆积、Kafka 短暂不可用或 payload 被阻断等问题。它比普通 VIEW 更偏运维与审计，
     * 因为记录里可能包含 runId、sessionId、工具审计 ID、错误摘要和投递状态。</p>
     */
    VIEW_OUTBOX_EVENTS,
    /**
     * 人工重新入队 Agent 工具执行事件 outbox。
     *
     * <p>该动作会把 FAILED/BLOCKED 记录重新转为 PENDING，让 dispatcher 再次尝试投递。
     * 这是一类恢复动作，不是普通创建动作；如果授权过宽，用户可能反复触发历史事件重放，造成重复通知、
     * 审计误判或下游事件消费者压力上升。</p>
     */
    REQUEUE_OUTBOX,
    /**
     * 人工忽略 Agent 工具执行事件 outbox。
     *
     * <p>忽略表示管理员确认该事件无需继续自动补偿，并将其从异常待处理队列中移出。
     * 它不等同于投递成功，必须独立审计，避免事后排障时把“人工归档”误认为“系统已送达”。</p>
     */
    IGNORE_OUTBOX,
    /**
     * 追加 Agent 工具执行事件 outbox 处理备注。
     *
     * <p>备注用于记录排障判断、客户确认、下游修复计划或恢复前置条件。
     * 虽然它不直接改变投递状态，但会影响后续运维人员是否重放或忽略该事件，因此需要被权限系统识别。</p>
     */
    ANNOTATE_OUTBOX,
    /**
     * 查看 Agent 长期记忆写入候选。
     *
     * <p>长期记忆候选不是普通的只读列表：它描述的是“哪些工具结果、运行经验或治理结论准备进入长期记忆”。
     * 一旦候选被批准并写入 Chroma、Neo4j、MySQL 或对象存储，后续 Agent 可能在多个会话中反复检索和复用它。
     * 因此查看候选也要独立建模，方便后续把审计员的只读复核、运营人员的排障查看、项目负责人的项目内查看拆成不同数据范围。</p>
     */
    VIEW_MEMORY_WRITE_CANDIDATES,
    /**
     * 批准 Agent 长期记忆写入候选。
     *
     * <p>批准动作意味着候选内容未来可以进入长期记忆持久层，并影响模型后续规划、工具选择和上下文补全。
     * 它的风险高于普通 APPROVE，因为被批准的内容可能长期存在、跨会话复用，并可能参与 RAG 检索或图谱关联。
     * 单独的动作编码可以让 permission-admin 对它配置更严格的角色、审批链、审计和二次确认策略。</p>
     */
    APPROVE_MEMORY_WRITE,
    /**
     * 拒绝 Agent 长期记忆写入候选。
     *
     * <p>拒绝动作不会把内容写入长期记忆，但它会形成治理事实：说明某条候选因敏感、过期、质量不足、跨项目或策略不允许而被阻断。
     * 后续审计、模型安全评估和策略优化都需要区分“批准写入”和“拒绝写入”，所以这里不复用普通 UPDATE 或 APPROVE。</p>
     */
    REJECT_MEMORY_WRITE,
    /**
     * 接入 Agent 计划。
     *
     * <p>该动作专门保护 Python AI Runtime -> Java agent-runtime 的计划接入口。
     * 它不同于普通 CREATE：调用方不是创建一个业务对象，而是把模型规划、工具计划、记忆检索和模型网关治理摘要
     * 提交给 Java 控制面，形成 Run 与工具审计计划。因此它必须默认面向服务账号，并保留独立审计语义。
     */
    INGEST_PLAN,
    /**
     * 确认 DAG 中已选中的异步工具节点进入命令 outbox。
     *
     * <p>该动作比普通 EXECUTE 更敏感：它不是“执行一个工具”，而是把经过 dry-run 指纹复核的一批节点
     * 推进到可靠异步执行轨道。单独建模后，permission-admin 可以把它限制给 SERVICE_ACCOUNT、项目负责人
     * 或审批后的入口，而不是被 `/api/agent/sessions/**` 的宽泛会话权限误放行。</p>
     */
    ENQUEUE_SELECTED_ASYNC_TOOL,
    /**
     * 执行已经通过 DAG selected-node 确认并进入任务中心的异步 Agent 工具。
     *
     * <p>这个动作刻意不复用 {@link #ENQUEUE_SELECTED_ASYNC_TOOL}。
     * 入箱动作回答的是“是否允许把某个已确认节点写入可靠命令队列”，而本动作回答的是
     * “task-management worker 在副作用发生前，是否仍允许代表上游 actor 执行这个工具”。
     * 两者拆开后，商业化环境可以分别配置策略：例如允许服务账号入箱，但在执行阶段因为策略撤销、
     * 租户冻结、项目越权、审批过期或工具临时下线而拒绝执行。</p>
     */
    EXECUTE_CONFIRMED_ASYNC_TOOL,
    /**
     * Run 级异步工具批量入箱动作。
     *
     * <p>该入口粒度比 selected-node 更粗，生产上更适合作为管理员补偿、内部联调或事故恢复能力。独立动作
     * 能让权限矩阵明确标记“谁可以一次性推进整个 Run 的异步工具”，避免普通用户误触发大批量后台任务。</p>
     */
    ENQUEUE_RUN_ASYNC_TOOLS
}

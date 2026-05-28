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
     * 接入 Agent 计划。
     *
     * <p>该动作专门保护 Python AI Runtime -> Java agent-runtime 的计划接入口。
     * 它不同于普通 CREATE：调用方不是创建一个业务对象，而是把模型规划、工具计划、记忆检索和模型网关治理摘要
     * 提交给 Java 控制面，形成 Run 与工具审计计划。因此它必须默认面向服务账号，并保留独立审计语义。
     */
    INGEST_PLAN
}

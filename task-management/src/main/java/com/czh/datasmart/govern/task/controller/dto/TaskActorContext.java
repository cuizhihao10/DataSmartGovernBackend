package com.czh.datasmart.govern.task.controller.dto;

import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/04/27 00:55
 * @Description DataSmart Govern Backend - TaskActorContext.java
 * @Version:1.0.0
 *
 * 任务操作上下文。
 *
 * <p>任务中心不是孤立 CRUD 服务，它会被普通用户、项目负责人、运营人员、租户管理员、平台管理员、
 * 调度器、执行器以及未来的 Agent Runtime 调用。
 * 如果只在 Controller 里接收一个 taskId，然后 Service 直接改状态，后续就很难回答：
 * - 是谁暂停了任务？
 * - 是哪个租户的管理员强制取消了任务？
 * - 这次操作对应 gateway 的哪条 traceId？
 * - 是人类操作，还是服务账号、调度器、Agent 自动动作？
 *
 * <p>因此这里把来自 gateway 的平台上下文封装为一个 record，作为服务层状态变更和执行日志的输入。
 * 目前 task 表还没有 tenant_id / owner_id 字段，所以租户隔离先记录在日志中；等后续表结构升级后，
 * 同一个上下文可以继续用于服务层数据范围校验。
 *
 * @param tenantId 当前操作者所属租户 ID。
 * @param actorId 当前操作者 ID。
 * @param actorRole 当前操作者角色，例如 OPERATOR、TENANT_ADMINISTRATOR、PLATFORM_ADMINISTRATOR、SERVICE_ACCOUNT。
 * @param traceId 当前请求链路追踪 ID。
 * @param dataScopeLevel gateway 透传的数据范围级别，例如 SELF、PROJECT、TENANT、PLATFORM。
 * @param authorizedProjectIds gateway 透传的可见项目集合；为空时表示 PROJECT 范围下没有任何可见项目。
 */
public record TaskActorContext(
        Long tenantId,
        Long actorId,
        String actorRole,
        String traceId,
        String dataScopeLevel,
        List<Long> authorizedProjectIds
) {

    /**
     * 当前请求是否需要按 PROJECT 范围强制收口。
     *
     * <p>task-management 不解析 permission-admin 的内部数据结构，只消费 gateway 透传的标准数据范围 Header。
     * 当数据范围明确是 PROJECT 时，任务列表、队列视图、详情、生命周期动作和管理员强控都应该按项目集合收口。</p>
     */
    public boolean projectScopeEnforced() {
        return dataScopeLevel != null && "PROJECT".equalsIgnoreCase(dataScopeLevel.trim());
    }

    /**
     * 返回当前请求实际可见的项目集合。
     *
     * <p>为了让业务层更容易直接使用，这里保证空值永远被规整成空列表，而不是 null。
     * 这也让“PROJECT 范围但无授权项目”这种安全场景可以被明确识别并统一处理。</p>
     */
    public List<Long> safeAuthorizedProjectIds() {
        return authorizedProjectIds == null ? List.of() : List.copyOf(authorizedProjectIds);
    }
}

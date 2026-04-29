package com.czh.datasmart.govern.task.controller.dto;

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
 */
public record TaskActorContext(
        Long tenantId,
        Long actorId,
        String actorRole,
        String traceId
) {
}

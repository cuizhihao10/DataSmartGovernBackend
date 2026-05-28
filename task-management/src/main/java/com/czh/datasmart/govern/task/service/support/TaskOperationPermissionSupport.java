package com.czh.datasmart.govern.task.service.support;

import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * @Author : Cui
 * @Date: 2026/05/05 23:48
 * @Description DataSmart Govern Backend - TaskOperationPermissionSupport.java
 * @Version:1.0.0
 *
 * 任务操作权限支持组件。
 *
 * <p>这个组件专门承载 task-management 模块内部的“服务层二次鉴权”。
 * 在真实商业系统中，权限通常不只依赖网关或前端按钮隐藏：
 * 1. 网关负责第一道入口鉴权，防止未登录或明显无权限的请求进入服务；
 * 2. 服务层仍要保护高风险业务动作，避免内部调用、网关配置错误、脚本调用绕过关键限制；
 * 3. 执行日志需要记录可信的操作者上下文，便于审计、事故复盘和合规导出。
 *
 * <p>当前 task 表还没有 tenant_id / owner_id，因此这里只能先做角色级保护。
 * 后续补齐租户字段后，本组件应继续扩展“角色 + 数据范围”的双重校验，例如：
 * 平台管理员可跨租户处理，租户管理员只能处理本租户任务，普通用户只能查看和操作自己拥有的任务。
 */
@Component
public class TaskOperationPermissionSupport {

    /**
     * 允许执行任务运维强控动作的角色集合。
     *
     * <p>强控动作包括强制暂停、强制取消、强制重试、优先级覆盖、租约恢复等。
     * 这些动作会直接改变任务生命周期，可能影响用户数据处理结果和下游系统调用，
     * 因此不应开放给普通用户或普通机器账号。
     */
    private static final Set<String> ADMIN_OPERATION_ROLES = Set.of(
            "OPERATOR",
            "TENANT_ADMINISTRATOR",
            "PLATFORM_ADMINISTRATOR"
    );

    /**
     * 允许执行任务认领、心跳续租等执行器动作的角色集合。
     *
     * <p>SERVICE_ACCOUNT 表示真实 worker、调度器、Agent Runtime 等机器身份。
     * OPERATOR 和 PLATFORM_ADMINISTRATOR 主要服务于本地联调、故障演练和人工补偿。
     */
    private static final Set<String> EXECUTOR_OPERATION_ROLES = Set.of(
            "SERVICE_ACCOUNT",
            "OPERATOR",
            "PLATFORM_ADMINISTRATOR"
    );

    /**
     * 校验任务运维动作权限。
     *
     * @param actorContext 调用方身份上下文，通常由 Controller 从请求头或认证上下文组装。
     * @throws IllegalStateException 当缺少角色或角色不在允许集合内时抛出。
     */
    public void validateAdminOperationPermission(TaskActorContext actorContext) {
        String actorRole = normalizeRequiredRole(actorContext, "任务运维动作");
        if (!ADMIN_OPERATION_ROLES.contains(actorRole)) {
            throw new IllegalStateException("当前角色无权执行任务运维动作，actorRole=" + actorRole);
        }
    }

    /**
     * 校验执行器动作权限。
     *
     * <p>执行器动作看起来只是“认领任务”或“上报心跳”，但它们会决定哪个 worker 真正持有执行租约。
     * 如果缺少服务层保护，恶意或错误调用可能伪造 executorId 抢占任务、污染进度或延长租约。
     */
    public void validateExecutorOperationPermission(TaskActorContext actorContext) {
        String actorRole = normalizeRequiredRole(actorContext, "任务执行器动作");
        if (!EXECUTOR_OPERATION_ROLES.contains(actorRole)) {
            throw new IllegalStateException("当前角色无权执行任务认领或心跳动作，actorRole=" + actorRole);
        }
    }

    /**
     * 读取并规范化角色。
     *
     * <p>角色统一转成大写，是为了避免调用方传入 operator、Operator、OPERATOR 时产生语义差异。
     * 这种归一化属于服务边界上的输入保护，能减少前后端、脚本和内部服务之间的契约摩擦。
     */
    private String normalizeRequiredRole(TaskActorContext actorContext, String actionName) {
        if (actorContext == null || actorContext.actorRole() == null || actorContext.actorRole().isBlank()) {
            throw new IllegalStateException("缺少可信操作角色，不能执行" + actionName);
        }
        return actorContext.actorRole().trim().toUpperCase(Locale.ROOT);
    }
}

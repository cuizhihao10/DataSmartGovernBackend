/**
 * @Author : Cui
 * @Date: 2026/05/23 18:02
 * @Description DataSmart Govern Backend - TaskDataScopeSupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import com.czh.datasmart.govern.task.controller.dto.TaskQueueInspectionRequest;
import com.czh.datasmart.govern.task.entity.Task;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务数据范围支持组件测试。
 *
 * <p>task-management 以前只做到了 tenant/owner 级别的收口，这在商业化产品里还不够。
 * 真正进入 PROJECT 授权场景后，任务列表、队列和强控动作都必须按 gateway 传来的项目集合收口，
 * 否则就会把数据治理项目之间的边界重新放松。</p>
 */
class TaskDataScopeSupportTest {

    private final TaskDataScopeSupport support = new TaskDataScopeSupport();

    @Test
    void resolveProjectIdForCreateShouldAllowAuthorizedProject() {
        TaskActorContext context = projectScopeContext(List.of(101L, 102L));

        assertEquals(101L, support.resolveProjectIdForCreate(101L, context));
    }

    @Test
    void resolveProjectIdForCreateShouldRejectMissingProjectInProjectScope() {
        TaskActorContext context = projectScopeContext(List.of(101L, 102L));

        assertThrows(IllegalStateException.class, () -> support.resolveProjectIdForCreate(null, context));
    }

    @Test
    void resolveProjectIdForCreateShouldRejectUnauthorizedProject() {
        TaskActorContext context = projectScopeContext(List.of(101L, 102L));

        assertThrows(IllegalStateException.class, () -> support.resolveProjectIdForCreate(999L, context));
    }

    @Test
    void applyListScopeShouldAppendProjectConstraintForProjectScope() {
        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();
        assertDoesNotThrow(() -> support.applyListScope(wrapper, null, null, null,
                projectScopeContext(List.of(101L, 102L))));
    }

    @Test
    void applyQueueScopeShouldReturnNoRowsWhenProjectScopeHasNoAuthorizedProject() {
        LambdaQueryWrapper<Task> wrapper = new LambdaQueryWrapper<>();
        TaskQueueInspectionRequest request = new TaskQueueInspectionRequest();

        assertDoesNotThrow(() -> support.applyQueueScope(wrapper, request, projectScopeContext(List.of())));
    }

    @Test
    void validateTaskInActorScopeShouldRejectUnauthorizedProjectTask() {
        Task task = new Task();
        task.setTenantId(10L);
        task.setOwnerId(1001L);
        task.setProjectId(888L);

        assertThrows(IllegalStateException.class,
                () -> support.validateTaskInActorScope(task, projectScopeContext(List.of(101L, 102L)), "查询任务详情"));
    }

    private TaskActorContext projectScopeContext(List<Long> authorizedProjectIds) {
        return new TaskActorContext(10L, 1001L, "PROJECT_OWNER", "trace-001", "PROJECT", authorizedProjectIds);
    }
}

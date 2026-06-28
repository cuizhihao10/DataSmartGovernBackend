/**
 * @Author : Cui
 * @Date: 2026/06/28 22:06
 * @Description DataSmart Govern Backend - TaskCreationIdempotencySupportTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.support;

import com.czh.datasmart.govern.task.entity.Task;
import com.czh.datasmart.govern.task.mapper.TaskMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * 任务创建幂等组件测试。
 *
 * <p>这里不启动 Spring 容器和真实数据库，因为这组用例保护的是幂等键的领域规则：</p>
 * <p>1. 幂等键必须是低敏、短文本、机器可识别的稳定标识；</p>
 * <p>2. 同一个键重复提交时，只能复用同一租户、同一项目、同一负责人、同一任务类型的既有任务；</p>
 * <p>3. 同键不同身份必须 fail-closed，避免把旧任务误返回给新的业务意图。</p>
 *
 * <p>真正的并发唯一性由 MySQL 唯一索引和 {@link TaskLifecycleSupport} 中的 DuplicateKeyException 兜底逻辑保护，
 * 本测试主要固定“复用前必须校验身份”的业务语义。</p>
 */
class TaskCreationIdempotencySupportTest {

    @Test
    void normalizeShouldTrimAndAcceptLowSensitiveMachineKey() {
        TaskCreationIdempotencySupport support = new TaskCreationIdempotencySupport(mock(TaskMapper.class));

        String key = support.normalize("  tool-action:proposal:run-001:command-002  ");

        assertEquals("tool-action:proposal:run-001:command-002", key);
    }

    @Test
    void normalizeShouldRejectSensitiveOrFreeTextKey() {
        TaskCreationIdempotencySupport support = new TaskCreationIdempotencySupport(mock(TaskMapper.class));

        assertThrows(IllegalArgumentException.class,
                () -> support.normalize("select * from customer where password=secret"));
        assertThrows(IllegalArgumentException.class,
                () -> support.normalize("https://internal.service/tasks/1"));
    }

    @Test
    void reuseExistingShouldReturnExistingTaskWhenIdentityMatches() {
        TaskCreationIdempotencySupport support = new TaskCreationIdempotencySupport(mock(TaskMapper.class));
        Task existing = task("DATA_QUALITY_REMEDIATION", 10L, 20L, 1001L);
        existing.setId(9001L);
        Task candidate = task("DATA_QUALITY_REMEDIATION", 10L, 20L, 1001L);

        Task reused = support.reuseExisting(existing, candidate);

        assertEquals(9001L, reused.getId());
    }

    @Test
    void reuseExistingShouldRejectWhenSameKeyRepresentsDifferentTaskIdentity() {
        TaskCreationIdempotencySupport support = new TaskCreationIdempotencySupport(mock(TaskMapper.class));
        Task existing = task("DATA_QUALITY_REMEDIATION", 10L, 20L, 1001L);
        Task candidate = task("DATA_SYNC", 10L, 20L, 1001L);

        assertThrows(IllegalStateException.class, () -> support.reuseExisting(existing, candidate));
    }

    private Task task(String type, Long tenantId, Long projectId, Long ownerId) {
        Task task = new Task();
        task.setType(type);
        task.setTenantId(tenantId);
        task.setProjectId(projectId);
        task.setOwnerId(ownerId);
        return task;
    }
}

/**
 * @Author : Cui
 * @Date: 2026/06/20 23:26
 * @Description DataSmart Govern Backend - DataSyncAgentTaskExecutionServiceTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.agent;

import com.czh.datasmart.govern.datasource.controller.dto.CreateSyncTaskRequest;
import com.czh.datasmart.govern.datasource.controller.dto.DataSyncAgentExecuteRequest;
import com.czh.datasmart.govern.datasource.controller.dto.DataSyncAgentExecuteResponse;
import com.czh.datasmart.govern.datasource.controller.dto.SyncActionRequest;
import com.czh.datasmart.govern.datasource.entity.DataSyncAgentCommandReceipt;
import com.czh.datasmart.govern.datasource.entity.SyncTask;
import com.czh.datasmart.govern.datasource.service.SyncTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent data-sync.execute 命令落地服务测试。
 *
 * <p>这组测试不启动 Spring，也不连接 MySQL，因为目标不是验证 MyBatis，而是验证跨服务幂等编排规则：</p>
 * <p>1. 第一次命令应先写 receipt，再创建同步任务，再入队。</p>
 * <p>2. 重复 commandId/idempotencyKey 应复用已有 syncTaskId，不再次调用 SyncTaskService。</p>
 * <p>3. commandId 与 idempotencyKey 绑定不一致时必须 fail-closed。</p>
 * <p>4. 非 data-sync.execute 工具不能借这个内部入口触发任意副作用。</p>
 *
 * <p>测试替身使用小接口和 JDK 动态代理，避免为 IService 的大量无关方法写空实现。
 * 这也体现了本次实现中引入 DataSyncAgentCommandReceiptStore 端口的价值：服务层可以被轻量验证。</p>
 */
class DataSyncAgentTaskExecutionServiceTest {

    @Test
    void executeShouldCreateTaskAfterReceiptClaimAndEnqueueIt() {
        InMemoryReceiptStore receiptStore = new InMemoryReceiptStore();
        RecordingSyncTaskService taskService = new RecordingSyncTaskService();
        DataSyncAgentTaskExecutionService service =
                new DataSyncAgentTaskExecutionService(receiptStore, taskService.proxy());

        DataSyncAgentExecuteResponse response = service.execute(command("cmd-001", "idem-001"));

        assertEquals("cmd-001", response.commandId());
        assertEquals(7001L, response.syncTaskId());
        assertEquals("QUEUED", response.state());
        assertTrue(response.created());
        assertTrue(response.queued());
        assertFalse(response.duplicate());

        assertEquals(1, taskService.createCount);
        assertEquals(1, taskService.enqueueCount);
        assertEquals(1002L, taskService.lastCreateRequest.getTemplateId());
        assertEquals("SERVICE_ACCOUNT", taskService.lastCreateRequest.getActorRole());
        assertEquals(1001L, taskService.lastCreateRequest.getCreatedBy());
        assertEquals(2001L, taskService.lastCreateRequest.getOwnerId());
        assertEquals("MEDIUM", taskService.lastCreateRequest.getPriority());
        assertEquals("MANUAL", taskService.lastCreateRequest.getRunMode());
        assertEquals(Boolean.FALSE, taskService.lastCreateRequest.getApprovalRequired());
        assertTrue(taskService.lastCreateRequest.getName().contains("agent cmd001"));
        assertFalse(taskService.lastCreateRequest.getDescription().contains("原始 description"));

        assertEquals("SERVICE_ACCOUNT", taskService.lastEnqueueRequest.getActorRole());
        assertEquals(1001L, taskService.lastEnqueueRequest.getActorId());

        DataSyncAgentCommandReceipt stored = receiptStore.byCommand.get("cmd-001");
        assertNotNull(stored);
        assertEquals("QUEUED", stored.getStatus());
        assertEquals(7001L, stored.getSyncTaskId());
        assertTrue(stored.getSideEffectStarted());
        assertTrue(stored.getSideEffectExecuted());
    }

    @Test
    void executeShouldReuseExistingReceiptWhenCommandIsRepeated() {
        InMemoryReceiptStore receiptStore = new InMemoryReceiptStore();
        DataSyncAgentCommandReceipt existing = queuedReceipt("cmd-002", "idem-002", 8002L);
        receiptStore.insert(existing);
        RecordingSyncTaskService taskService = new RecordingSyncTaskService();
        DataSyncAgentTaskExecutionService service =
                new DataSyncAgentTaskExecutionService(receiptStore, taskService.proxy());

        DataSyncAgentExecuteResponse response = service.execute(command("cmd-002", "idem-002"));

        assertEquals(8002L, response.syncTaskId());
        assertFalse(response.created());
        assertTrue(response.duplicate());
        assertEquals(0, taskService.createCount);
        assertEquals(0, taskService.enqueueCount);
    }

    @Test
    void executeShouldRejectIdempotencyBindingConflict() {
        InMemoryReceiptStore receiptStore = new InMemoryReceiptStore();
        receiptStore.insert(queuedReceipt("cmd-003", "idem-003", 8003L));
        RecordingSyncTaskService taskService = new RecordingSyncTaskService();
        DataSyncAgentTaskExecutionService service =
                new DataSyncAgentTaskExecutionService(receiptStore, taskService.proxy());

        DataSyncAgentExecuteRequest request = command("cmd-003", "idem-other");

        assertThrows(IllegalStateException.class, () -> service.execute(request));
        assertEquals(0, taskService.createCount);
    }

    @Test
    void executeShouldRejectUnsupportedToolCodeBeforeSideEffect() {
        InMemoryReceiptStore receiptStore = new InMemoryReceiptStore();
        RecordingSyncTaskService taskService = new RecordingSyncTaskService();
        DataSyncAgentTaskExecutionService service =
                new DataSyncAgentTaskExecutionService(receiptStore, taskService.proxy());
        DataSyncAgentExecuteRequest request = command("cmd-004", "idem-004");
        request.setToolCode("data-quality.scan");

        assertThrows(IllegalArgumentException.class, () -> service.execute(request));
        assertEquals(0, receiptStore.insertCount);
        assertEquals(0, taskService.createCount);
    }

    @Test
    void executeShouldFallbackToOwnerWhenActorIdIsNotNumeric() {
        InMemoryReceiptStore receiptStore = new InMemoryReceiptStore();
        RecordingSyncTaskService taskService = new RecordingSyncTaskService();
        DataSyncAgentTaskExecutionService service =
                new DataSyncAgentTaskExecutionService(receiptStore, taskService.proxy());
        DataSyncAgentExecuteRequest request = command("cmd-005", "idem-005");
        request.setActorId("external-user-01");

        service.execute(request);

        assertEquals(2001L, taskService.lastCreateRequest.getCreatedBy());
        assertEquals(2001L, taskService.lastEnqueueRequest.getActorId());
    }

    private DataSyncAgentExecuteRequest command(String commandId, String idempotencyKey) {
        DataSyncAgentExecuteRequest request = new DataSyncAgentExecuteRequest();
        request.setCommandId(commandId);
        request.setIdempotencyKey(idempotencyKey);
        request.setAuditId("audit-001");
        request.setSessionId("session-001");
        request.setRunId("run-001");
        request.setToolCode("data-sync.execute");
        request.setTenantId(1L);
        request.setProjectId(10L);
        request.setWorkspaceId(20L);
        request.setActorId("1001");
        request.setTraceId("trace-001");
        request.setTemplateId(1001L);
        request.setSyncTemplateId(1002L);
        request.setName("订单同步任务");
        request.setDescription("原始 description 不应该原样进入任务描述");
        request.setOwnerId(2001L);
        return request;
    }

    private DataSyncAgentCommandReceipt queuedReceipt(String commandId, String idempotencyKey, Long syncTaskId) {
        DataSyncAgentCommandReceipt receipt = new DataSyncAgentCommandReceipt();
        receipt.setReceiptId("datasource-agent-receipt:" + commandId);
        receipt.setCommandId(commandId);
        receipt.setIdempotencyKey(idempotencyKey);
        receipt.setToolCode("data-sync.execute");
        receipt.setTenantId(1L);
        receipt.setResolvedTemplateId(1002L);
        receipt.setSyncTaskId(syncTaskId);
        receipt.setStatus("QUEUED");
        receipt.setDownstreamState("QUEUED");
        receipt.setSideEffectStarted(true);
        receipt.setSideEffectExecuted(true);
        receipt.setDuplicate(false);
        receipt.setMessage("同步任务已创建并进入待执行队列");
        return receipt;
    }

    /**
     * 内存 receipt 存储替身。
     *
     * <p>它同时维护 commandId 和 idempotencyKey 两个索引，用于模拟数据库唯一键。
     * 如果插入重复键，抛出 DuplicateKeyException，验证服务层的并发重试保护分支。</p>
     */
    private static class InMemoryReceiptStore implements DataSyncAgentCommandReceiptStore {

        private final Map<String, DataSyncAgentCommandReceipt> byCommand = new HashMap<>();
        private final Map<String, DataSyncAgentCommandReceipt> byIdempotency = new HashMap<>();
        private int insertCount;

        @Override
        public Optional<DataSyncAgentCommandReceipt> findByCommandOrIdempotencyKey(String commandId, String idempotencyKey) {
            DataSyncAgentCommandReceipt byCommandResult = byCommand.get(commandId);
            if (byCommandResult != null) {
                return Optional.of(byCommandResult);
            }
            return Optional.ofNullable(byIdempotency.get(idempotencyKey));
        }

        @Override
        public void insert(DataSyncAgentCommandReceipt receipt) {
            if (byCommand.containsKey(receipt.getCommandId())
                    || byIdempotency.containsKey(receipt.getIdempotencyKey())) {
                throw new DuplicateKeyException("重复 receipt");
            }
            insertCount++;
            byCommand.put(receipt.getCommandId(), receipt);
            byIdempotency.put(receipt.getIdempotencyKey(), receipt);
        }

        @Override
        public void updateById(DataSyncAgentCommandReceipt receipt) {
            byCommand.put(receipt.getCommandId(), receipt);
            byIdempotency.put(receipt.getIdempotencyKey(), receipt);
        }
    }

    /**
     * SyncTaskService 调用记录器。
     *
     * <p>服务层只依赖 createTask 与 enqueue 两个动作，因此测试替身只拦截这两个方法。
     * 其它 IService 方法返回默认值，避免测试关注点被 MyBatis-Plus 接口噪声淹没。</p>
     */
    private static class RecordingSyncTaskService implements InvocationHandler {

        private int createCount;
        private int enqueueCount;
        private CreateSyncTaskRequest lastCreateRequest;
        private SyncActionRequest lastEnqueueRequest;

        private SyncTaskService proxy() {
            return (SyncTaskService) Proxy.newProxyInstance(
                    SyncTaskService.class.getClassLoader(),
                    new Class[]{SyncTaskService.class},
                    this
            );
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "createTask" -> {
                    createCount++;
                    lastCreateRequest = (CreateSyncTaskRequest) args[0];
                    SyncTask task = new SyncTask();
                    task.setId(7001L);
                    task.setTenantId(lastCreateRequest.getTenantId());
                    task.setTemplateId(lastCreateRequest.getTemplateId());
                    task.setCurrentState("CONFIGURED");
                    yield task;
                }
                case "enqueue" -> {
                    enqueueCount++;
                    lastEnqueueRequest = (SyncActionRequest) args[1];
                    SyncTask task = new SyncTask();
                    task.setId((Long) args[0]);
                    task.setCurrentState("QUEUED");
                    yield task;
                }
                default -> defaultReturnValue(method);
            };
        }

        private Object defaultReturnValue(Method method) {
            Class<?> returnType = method.getReturnType();
            if (returnType == Boolean.TYPE) {
                return false;
            }
            if (returnType == Integer.TYPE || returnType == Long.TYPE || returnType == Short.TYPE || returnType == Byte.TYPE) {
                return 0;
            }
            return null;
        }
    }
}

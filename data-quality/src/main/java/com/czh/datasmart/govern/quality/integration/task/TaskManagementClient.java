/**
 * @Author : Cui
 * @Date: 2026/04/27 22:20
 * @Description DataSmart Govern Backend - TaskManagementClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.integration.task;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.quality.config.TaskManagementIntegrationProperties;
import com.czh.datasmart.govern.quality.integration.datasource.RemoteApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/**
 * task-management 远程调用客户端。
 *
 * <p>这个客户端是 data-quality 与 task-management 的 HTTP/JSON 集成边界。
 * 它不直接引用 task-management 的 Java DTO 和实体，而是在 data-quality 内部定义一组“局部合同模型”。
 * 这样做的价值是：
 * 1. 保持微服务编译期解耦；
 * 2. 明确 data-quality 到底消费了 task-management 的哪些字段；
 * 3. 后续从直连 localhost 切换到 gateway、服务发现、OpenFeign 或 Kafka 命令事件时，只需要改这一层；
 * 4. 避免业务服务里到处散落 RestClient 调用，导致重试、鉴权 Header、fail-open 策略难以统一。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskManagementClient {

    private final TaskManagementIntegrationProperties properties;

    private final RestClient.Builder restClientBuilder;

    /**
     * 创建任务。
     *
     * <p>task-management 创建任务后任务默认进入 PENDING，后续由执行器通过 claim 接口认领。
     */
    public TaskCreateResponse createTask(TaskCreateRequest request) {
        try {
            RemoteApiResponse<TaskCreateResponse> response = restClient()
                    .post()
                    .uri("/tasks")
                    .headers(this::applyPlatformHeaders)
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (response == null) {
                throw new IllegalStateException("task-management 返回空响应");
            }
            if (!response.successful()) {
                throw new IllegalStateException("task-management 创建任务失败: " + response.getMessage());
            }
            return response.getData();
        } catch (Exception ex) {
            log.warn("调用 task-management 创建质量检测任务失败，taskType={}, failOpen={}",
                    request.getType(), properties.isFailOpen(), ex);
            if (properties.isFailOpen()) {
                return null;
            }
            throw ex;
        }
    }

    /**
     * 认领下一条质量扫描任务。
     *
     * <p>这是未来质量执行器 coordinator 的入口动作。
     * data-quality 会按 `DATA_QUALITY_SCAN` 类型向 task-management 申请任务，
     * task-management 负责并发竞争控制、状态改为 RUNNING、创建 task_execution_run 并返回 runId。
     *
     * <p>如果当前没有任务，task-management 会返回 claimed=false，这不是异常；
     * 执行器应退避一小段时间后再尝试认领，避免空队列时疯狂轮询。
     */
    public TaskExecutionClaimResult claimNextQualityTask(String executorId) {
        TaskExecutionClaimRequest request = new TaskExecutionClaimRequest();
        request.setExecutorId(hasText(executorId) ? executorId : properties.getExecutorId());
        request.setTaskType(properties.getTaskType());
        request.setLeaseSeconds(properties.getExecutorLeaseSeconds());
        try {
            RemoteApiResponse<TaskExecutionClaimResult> response = restClient()
                    .post()
                    .uri("/tasks/executions/claim")
                    .headers(this::applyPlatformHeaders)
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (response == null) {
                throw new IllegalStateException("task-management 返回空认领响应");
            }
            if (!response.successful()) {
                throw new IllegalStateException("task-management 认领任务失败: " + response.getMessage());
            }
            return response.getData();
        } catch (Exception ex) {
            log.warn("调用 task-management 认领质量任务失败，executorId={}, taskType={}, failOpen={}",
                    request.getExecutorId(), request.getTaskType(), properties.isFailOpen(), ex);
            if (properties.isFailOpen()) {
                return new TaskExecutionClaimResult(false, "task-management 认领调用失败，fail-open 返回未认领", null, null);
            }
            throw ex;
        }
    }

    /**
     * 上报任务执行心跳。
     *
     * <p>质量扫描可能是长任务，尤其是后续支持大表采样、分片扫描、文件扫描或 Kafka 窗口扫描后，
     * 单次执行可能持续数分钟甚至更久。心跳用于告诉 task-management：
     * 1. 执行器还活着；
     * 2. 当前进度是多少；
     * 3. 租约需要续期；
     * 4. 出现故障时可以从哪个 checkpoint 复盘或恢复。
     */
    public TaskExecutionRunResponse heartbeatExecution(Long runId, String executorId, Integer progress,
                                                       String checkpoint) {
        TaskExecutionHeartbeatRequest request = new TaskExecutionHeartbeatRequest();
        request.setExecutorId(hasText(executorId) ? executorId : properties.getExecutorId());
        request.setProgress(progress);
        request.setCheckpoint(checkpoint);
        request.setLeaseSeconds(properties.getExecutorLeaseSeconds());
        try {
            RemoteApiResponse<TaskExecutionRunResponse> response = restClient()
                    .post()
                    .uri("/tasks/executions/{runId}/heartbeat", runId)
                    .headers(this::applyPlatformHeaders)
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (response == null) {
                throw new IllegalStateException("task-management 返回空心跳响应");
            }
            if (!response.successful()) {
                throw new IllegalStateException("task-management 心跳续租失败: " + response.getMessage());
            }
            return response.getData();
        } catch (Exception ex) {
            log.warn("调用 task-management 心跳续租失败，runId={}, executorId={}, failOpen={}",
                    runId, request.getExecutorId(), properties.isFailOpen(), ex);
            if (properties.isFailOpen()) {
                return null;
            }
            throw ex;
        }
    }

    /**
     * 标记任务完成。
     *
     * <p>质量执行器成功完成 data-quality 回调并生成质量报告后，需要再调用 task-management complete。
     * 这是跨模块一致性闭环：报告系统显示执行成功，任务中心也应该显示 SUCCESS。
     */
    public TaskResponse completeTask(Long taskId, String result) {
        TaskCompleteRequest request = new TaskCompleteRequest();
        request.setResult(result);
        try {
            RemoteApiResponse<TaskResponse> response = restClient()
                    .post()
                    .uri("/tasks/{taskId}/complete", taskId)
                    .headers(this::applyPlatformHeaders)
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (response == null) {
                throw new IllegalStateException("task-management 返回空完成响应");
            }
            if (!response.successful()) {
                throw new IllegalStateException("task-management 标记任务完成失败: " + response.getMessage());
            }
            return response.getData();
        } catch (Exception ex) {
            log.warn("调用 task-management 标记任务完成失败，taskId={}, failOpen={}",
                    taskId, properties.isFailOpen(), ex);
            if (properties.isFailOpen()) {
                return null;
            }
            throw ex;
        }
    }

    /**
     * 标记任务失败。
     *
     * <p>如果质量任务 payload 无效、data-quality 回调失败、扫描器不支持当前策略或源系统异常，
     * 执行器应调用该方法让 task-management 进入 FAILED，并触发后续重试、人工关注或告警。
     */
    public TaskResponse failTask(Long taskId, String errorMessage) {
        TaskFailRequest request = new TaskFailRequest();
        request.setErrorMessage(errorMessage);
        try {
            RemoteApiResponse<TaskResponse> response = restClient()
                    .post()
                    .uri("/tasks/{taskId}/fail", taskId)
                    .headers(this::applyPlatformHeaders)
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (response == null) {
                throw new IllegalStateException("task-management 返回空失败响应");
            }
            if (!response.successful()) {
                throw new IllegalStateException("task-management 标记任务失败失败: " + response.getMessage());
            }
            return response.getData();
        } catch (Exception ex) {
            log.warn("调用 task-management 标记任务失败失败，taskId={}, failOpen={}",
                    taskId, properties.isFailOpen(), ex);
            if (properties.isFailOpen()) {
                return null;
            }
            throw ex;
        }
    }

    /**
     * 延迟任务并放回 task-management 可认领队列。
     *
     * <p>该方法用于“执行器容量不足”的背压场景，而不是业务失败场景。
     * 例如本实例全局并发、单租户并发或单数据源并发已达上限时，data-quality 应调用 defer，
     * 让 task-management 结束当前 run 并在延迟到期后重新允许认领。
     *
     * <p>这里继续沿用 failOpen 策略：
     * - failOpen=false：defer 调用失败会抛异常，让调度器尽早暴露跨服务一致性问题；
     * - failOpen=true：本地联调时返回 null，避免 task-management 未启动导致 data-quality 完全不可调试。
     */
    public TaskResponse deferTask(Long taskId, String reason, Integer delaySeconds) {
        TaskDeferRequest request = new TaskDeferRequest();
        request.setReason(reason);
        request.setDelaySeconds(delaySeconds);
        try {
            RemoteApiResponse<TaskResponse> response = restClient()
                    .post()
                    .uri("/tasks/{taskId}/defer", taskId)
                    .headers(this::applyPlatformHeaders)
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (response == null) {
                throw new IllegalStateException("task-management 返回空延迟响应");
            }
            if (!response.successful()) {
                throw new IllegalStateException("task-management 延迟任务失败: " + response.getMessage());
            }
            return response.getData();
        } catch (Exception ex) {
            log.warn("调用 task-management 延迟任务失败，taskId={}, delaySeconds={}, failOpen={}",
                    taskId, delaySeconds, properties.isFailOpen(), ex);
            if (properties.isFailOpen()) {
                return null;
            }
            throw ex;
        }
    }

    /**
     * 构建 RestClient。
     *
     * <p>当前每次调用基于 builder 构建客户端，代码简单且足够满足本地开发。
     * 后续如果要统一连接池、超时、重试、熔断和指标，可以在这里收口增强。
     */
    private RestClient restClient() {
        return restClientBuilder.baseUrl(properties.getBaseUrl()).build();
    }

    /**
     * 写入平台上下文 Header。
     *
     * <p>task-management 的执行器接口会校验 ActorRole。
     * data-quality 作为机器执行器调用时，默认以 SERVICE_ACCOUNT 身份访问。
     * 这些 Header 未来应由服务账号签名、网关或服务间认证机制生成；当前先由配置显式声明，
     * 方便本地联调并保留清晰的安全边界。
     */
    private void applyPlatformHeaders(HttpHeaders headers) {
        headers.set(PlatformContextHeaders.TENANT_ID, String.valueOf(properties.getExecutorActorTenantId()));
        headers.set(PlatformContextHeaders.ACTOR_ID, String.valueOf(properties.getExecutorActorId()));
        headers.set(PlatformContextHeaders.ACTOR_ROLE, properties.getExecutorActorRole());
        headers.set(PlatformContextHeaders.ACTOR_TYPE, "SERVICE_ACCOUNT");
        headers.set(PlatformContextHeaders.SOURCE_SERVICE, properties.getSourceService());
        headers.set(PlatformContextHeaders.TRACE_ID, properties.getSourceService() + "-" + UUID.randomUUID());
    }

    /**
     * 判断字符串是否有真实内容。
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

/**
 * @Author : Cui
 * @Date: 2026/06/29 12:54
 * @Description DataSmart Govern Backend - HttpDatasourceRunOnceClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.integration.datasource.runonce;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.datasync.config.DataSyncDatasourceRunOnceProperties;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

/**
 * 基于 HTTP 的 datasource-management run-once 客户端。
 *
 * <p>本类是 data-sync 与 datasource-management 之间的“执行侧跨服务适配器”。
 * 它只负责把本地 {@link DatasourceRunOnceRequest} 发送到 datasource-management 的
 * {@code /internal/sync-batch-runs/run-once}，并把远端 envelope 解包为低敏结果；
 * 它不负责判断当前 execution 是否能运行，也不负责 complete/fail 状态流转，这些业务决策全部放在
 * data-sync 的调度服务中。</p>
 *
 * <p>安全边界非常重要：</p>
 * <p>1. 日志只记录 taskId、executionId、traceId 和异常类型，不记录 baseUrl、完整 URI、请求体、响应体或远端 message；</p>
 * <p>2. 请求体中可能包含对象定位、字段清单和 checkpoint 起点，只允许通过 internal 服务账号链路传输；</p>
 * <p>3. 响应只解析低敏摘要，不期待也不允许回传真实行数据、SQL、连接串、账号、密码、字段值或 checkpoint 原始值；</p>
 * <p>4. 远端不可用时抛出平台外部依赖异常，让上层执行调度 fail-closed，而不是让 execution 长时间停留在 RUNNING。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpDatasourceRunOnceClient implements DatasourceRunOnceClient {

    /**
     * Spring Boot 注入的 RestClient 构建器。
     *
     * <p>RestClient 本身负责 HTTP 请求创建、序列化和反序列化；本类只配置 baseUrl、超时、Header 和响应解包规则。</p>
     */
    private final RestClient.Builder restClientBuilder;

    /**
     * run-once 调用配置。
     */
    private final DataSyncDatasourceRunOnceProperties properties;

    /**
     * 调用 datasource-management 执行一次单批读写。
     *
     * @param request 内部执行请求。禁止在日志里打印完整对象。
     * @param actorContext 调用上下文，用于透传服务追踪和租户事实。
     * @return 远端低敏执行摘要。
     */
    @Override
    public DatasourceRunOnceResponse runOnce(DatasourceRunOnceRequest request, SyncActorContext actorContext) {
        if (request == null || request.getExecutionPlan() == null) {
            throw new PlatformBusinessException(PlatformErrorCode.VALIDATION_ERROR,
                    "datasource run-once 调用缺少 executionPlan，执行器派发已终止");
        }
        Long taskId = request.getExecutionPlan().getTaskId();
        Long executionId = request.getExecutionPlan().getExecutionId();
        try {
            RestClient client = restClientBuilder
                    .baseUrl(properties.getBaseUrl())
                    .requestFactory(requestFactory())
                    .build();
            DatasourceRunOnceEnvelope response = client.post()
                    .uri(properties.getRunOncePath())
                    .headers(headers -> applyInternalHeaders(headers, actorContext))
                    .body(request)
                    .retrieve()
                    .body(DatasourceRunOnceEnvelope.class);
            return unwrap(taskId, executionId, response);
        } catch (RestClientException exception) {
            log.warn("调用 datasource-management run-once 失败: taskId={}, executionId={}, traceId={}, exceptionType={}",
                    taskId, executionId, traceId(actorContext), exception.getClass().getSimpleName());
            throw new PlatformBusinessException(PlatformErrorCode.EXTERNAL_DEPENDENCY_FAILED,
                    "datasource-management run-once 暂不可用，data-sync 已按 fail-closed 策略终止本次派发，executionId=" + executionId);
        }
    }

    /**
     * 为 run-once 请求配置连接与读取超时。
     *
     * <p>单批执行会触发真实 IO，读取超时通常应高于能力快照查询；但它仍然不能无限等待。
     * 如果外部调用长时间不返回，data-sync worker 租约会过期，状态机也会变得不可解释。
     * 因此这里使用配置化超时，并在上层把失败转换为 execution fail。</p>
     */
    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(Math.max(1L, properties.getConnectTimeoutMs())));
        requestFactory.setReadTimeout(Duration.ofMillis(Math.max(1L, properties.getReadTimeoutMs())));
        return requestFactory;
    }

    /**
     * 写入 datasource-management internal 路由需要的服务上下文 Header。
     *
     * <p>当前最小契约依赖 {@code X-DataSmart-Source-Service=data-sync} 与
     * {@code X-DataSmart-Actor-Role=SERVICE_ACCOUNT} 做服务账号白名单校验。tenantId、actorId、traceId
     * 主要用于后续审计、排障和链路追踪；如果上游上下文缺失，则使用受控默认值，避免把 null 字符串透传给下游。</p>
     */
    private void applyInternalHeaders(HttpHeaders headers, SyncActorContext actorContext) {
        headers.set(PlatformContextHeaders.SOURCE_SERVICE, properties.getSourceService());
        headers.set(PlatformContextHeaders.ACTOR_ROLE, properties.getActorRole());
        headers.set(PlatformContextHeaders.ACTOR_TYPE, "SERVICE_ACCOUNT");
        headers.set(PlatformContextHeaders.TRACE_ID, traceId(actorContext));
        if (actorContext != null && actorContext.tenantId() != null) {
            headers.set(PlatformContextHeaders.TENANT_ID, String.valueOf(actorContext.tenantId()));
        }
        if (actorContext != null && actorContext.actorId() != null) {
            headers.set(PlatformContextHeaders.ACTOR_ID, String.valueOf(actorContext.actorId()));
        }
    }

    /**
     * 解包远端响应。
     *
     * <p>失败时不把远端 message 原样返回，是为了避免未来 datasource-management 排障描述中出现内部端点、
     * 连接器名称、表名、字段名或异常栈片段。data-sync 只暴露“run-once 响应不可用”这一标准化结论。</p>
     */
    private DatasourceRunOnceResponse unwrap(Long taskId, Long executionId, DatasourceRunOnceEnvelope response) {
        if (response == null || response.getCode() == null || response.getCode() != 0 || response.getData() == null) {
            throw new PlatformBusinessException(PlatformErrorCode.EXTERNAL_DEPENDENCY_FAILED,
                    "datasource-management run-once 响应不可用，执行器派发已终止，executionId=" + executionId);
        }
        return response.getData();
    }

    private String traceId(SyncActorContext actorContext) {
        if (actorContext == null || actorContext.traceId() == null || actorContext.traceId().isBlank()) {
            return "data-sync-datasource-run-once";
        }
        return actorContext.traceId();
    }
}

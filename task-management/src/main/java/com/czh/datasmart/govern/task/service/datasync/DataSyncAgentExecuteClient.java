/**
 * @Author : Cui
 * @Date: 2026/06/20 21:43
 * @Description DataSmart Govern Backend - DataSyncAgentExecuteClient.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.service.datasync;

import com.czh.datasmart.govern.task.service.agent.DataSyncAgentExecuteRequest;
import com.czh.datasmart.govern.task.service.agent.DataSyncAgentExecuteResponse;

/**
 * DataSync Agent 内部执行客户端抽象。
 *
 * <p>这个接口是 task-management 到 datasource-management/data-sync 的出站边界。
 * 之所以单独抽象，而不是在 outbox dispatcher 里直接写 RestClient，主要有三个原因：</p>
 * <p>1. 解耦：outbox dispatcher 负责命令生命周期和幂等状态，HTTP/gateway/服务发现细节由客户端实现负责；</p>
 * <p>2. 可测试：服务层单元测试可以注入 fake client，验证状态流转而不启动真实 datasource-management；</p>
 * <p>3. 可替换：后续如果改成 OpenFeign、gRPC、Kafka publisher 或服务网格调用，只需要替换实现类，不需要改 outbox 状态机。</p>
 *
 * <p>安全边界：</p>
 * <p>请求 DTO 只允许携带低敏控制字段和 ID，不应该携带 SQL、连接串、凭据、样本数据、prompt、模型输出或工具实参正文。
 * 这些限制由上游 outbox 白名单序列化和下游 datasource-management 再校验共同保证。</p>
 */
public interface DataSyncAgentExecuteClient {

    /**
     * 调用 datasource-management 的内部 Agent data-sync.execute 入口。
     *
     * @param request 低敏执行请求，字段名与 datasource-management 内部 JSON 契约保持一致。
     * @return datasource-management 返回的低敏执行结果，通常包含 syncTaskId、syncExecutionId 和状态摘要。
     */
    DataSyncAgentExecuteResponse execute(DataSyncAgentExecuteRequest request);
}

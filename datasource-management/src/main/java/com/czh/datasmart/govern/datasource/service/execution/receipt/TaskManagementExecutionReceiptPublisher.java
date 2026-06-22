/**
 * @Author : Cui
 * @Date: 2026/06/22 10:42
 * @Description DataSmart Govern Backend - TaskManagementExecutionReceiptPublisher.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.execution.receipt;

import com.czh.datasmart.govern.datasource.service.execution.SyncBatchExecutionRunRequest;
import com.czh.datasmart.govern.datasource.service.execution.SyncBatchExecutionRunResult;

/**
 * DataSync Runner 执行回执发布端口。
 *
 * <p>Runner 只依赖这个接口，而不直接依赖 HTTP、Kafka、gRPC 或 task-management 的 Java 类型。
 * 这样 datasource-management 与 task-management 之间保持“协议级耦合”，不会因为一个模块的 DTO 包名变化
 * 直接影响另一个模块编译。</p>
 *
 * <p>后续演进路径：</p>
 * <p>1. 当前实现可以用 HTTP 直连 task-management；</p>
 * <p>2. 如果吞吐更高或需要削峰，可以替换为 Kafka outbox publisher；</p>
 * <p>3. 如果需要强一致服务间调用，可以替换为 gRPC；</p>
 * <p>4. Runner 主流程不需要随通信技术变化而重写。</p>
 */
public interface TaskManagementExecutionReceiptPublisher {

    /**
     * 发布一次单批 Runner 的低敏执行回执。
     *
     * @param request Runner 输入请求，用于取得执行器身份和租户上下文。
     * @param result Runner 低敏执行结果，不包含 SQL、真实行数据或 checkpoint 原始值。
     */
    void publish(SyncBatchExecutionRunRequest request, SyncBatchExecutionRunResult result);

    /**
     * 测试或禁用场景使用的空发布器。
     */
    static TaskManagementExecutionReceiptPublisher noop() {
        return (request, result) -> {
        };
    }
}

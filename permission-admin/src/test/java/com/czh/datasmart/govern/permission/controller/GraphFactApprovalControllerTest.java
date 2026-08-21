/**
 * @Author : Cui
 * @Date: 2026/08/21 17:20
 * @Description DataSmart Govern Backend - GraphFactApprovalControllerTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.permission.controller.dto.GraphFactApprovalEvaluateRequest;
import com.czh.datasmart.govern.permission.service.GraphFactApprovalService;
import com.czh.datasmart.govern.permission.service.support.AgentApprovalFactTrustedRegistrationGuard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 图事实审批回查入口的内部服务认证回归测试。
 *
 * <p>Kafka 消息只能证明“某个事件到达了 worker”，不能证明发送者仍有权触发 Neo4j 写入。因此 Python worker
 * 在摄取前必须回查 permission-admin，而这个 evaluate 入口本身也必须拒绝浏览器、伪造来源服务或错误共享令牌。
 * 测试直接调用 Controller，固定最重要的执行顺序：先验证内部来源/token，再允许 Service 查询审批事实。</p>
 */
class GraphFactApprovalControllerTest {

    /**
     * 内部信任校验失败时，不得查询审批事实，更不能让调用方把 Kafka payload 直接解释成 Neo4j 写权限。
     *
     * <p>这里由 mock guard 模拟错误 token。断言 Service 从未被调用，可以证明拒绝发生在任何数据库读取、
     * 审批结果构造或后续摄取之前；traceId 只用于关联审计，不参与放宽权限。</p>
     */
    @Test
    void evaluateMustAuthenticateWorkerBeforeReadingApprovalFact() {
        GraphFactApprovalService service = mock(GraphFactApprovalService.class);
        AgentApprovalFactTrustedRegistrationGuard guard = mock(AgentApprovalFactTrustedRegistrationGuard.class);
        GraphFactApprovalController controller = new GraphFactApprovalController(service, guard);
        GraphFactApprovalEvaluateRequest request = new GraphFactApprovalEvaluateRequest();

        doThrow(new PlatformBusinessException(PlatformErrorCode.FORBIDDEN, "internal authentication denied"))
                .when(guard)
                .requireTrusted("python-ai-runtime", "wrong-token");

        assertThrows(PlatformBusinessException.class, () -> controller.evaluate(
                request,
                "python-ai-runtime",
                "wrong-token",
                "trace-graph-evaluate"
        ));

        verify(guard).requireTrusted("python-ai-runtime", "wrong-token");
        verify(service, never()).evaluate(request);
    }
}

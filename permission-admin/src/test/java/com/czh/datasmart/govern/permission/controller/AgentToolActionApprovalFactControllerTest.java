/**
 * @Author : Cui
 * @Date: 2026/08/08 00:00
 * @Description DataSmart Govern Backend - AgentToolActionApprovalFactControllerTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller;

import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import com.czh.datasmart.govern.common.error.PlatformErrorCode;
import com.czh.datasmart.govern.permission.controller.dto.AgentToolActionApprovalFactRegisterRequest;
import com.czh.datasmart.govern.permission.service.AgentToolActionApprovalFactService;
import com.czh.datasmart.govern.permission.service.support.AgentApprovalFactTrustedRegistrationGuard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 审批事实 HTTP 入口的职责分离回归测试。
 *
 * <p>守卫本身已经验证来源服务和状态是否匹配，但该保护只有在 Controller 的持久化入口实际调用守卫时
 * 才有意义。本测试故意模拟一个持有有效内部凭据的 agent-runtime；它可以通过基础身份认证，却不能
 * 把待审批动作伪造成 APPROVED。断言业务 Service 从未被调用，可以确保拒绝发生在数据库写入之前。</p>
 */
class AgentToolActionApprovalFactControllerTest {

    /**
     * Agent Runtime 不具备审批决策权时，控制器必须阻止 APPROVED 请求进入持久化服务。
     *
     * <p>测试不通过 WebMvc 启动完整 Spring 上下文，而是直接调用 Controller，专注验证 HTTP Header
     * 到控制面服务之间最关键的安全顺序：先校验内部来源，再校验状态职责，最后才允许登记事实。</p>
     */
    @Test
    void runtimeMustNotPersistApprovedFactBeforeDecisionAuthorityIsVerified() {
        AgentToolActionApprovalFactService service = mock(AgentToolActionApprovalFactService.class);
        AgentApprovalFactTrustedRegistrationGuard guard = mock(AgentApprovalFactTrustedRegistrationGuard.class);
        AgentToolActionApprovalFactController controller = new AgentToolActionApprovalFactController(service, guard);
        AgentToolActionApprovalFactRegisterRequest request = new AgentToolActionApprovalFactRegisterRequest();
        request.setStatus("APPROVED");

        doThrow(new PlatformBusinessException(PlatformErrorCode.FORBIDDEN, "decision authority denied"))
                .when(guard)
                .requireDecisionAuthority("agent-runtime", "APPROVED");

        assertThrows(PlatformBusinessException.class, () -> controller.register(
                request,
                "agent-runtime",
                "trusted-token",
                "trace-approval-guard"
        ));

        verify(guard).requireTrusted("agent-runtime", "trusted-token");
        verify(guard).requireDecisionAuthority("agent-runtime", "APPROVED");
        verify(service, never()).register(request);
    }
}

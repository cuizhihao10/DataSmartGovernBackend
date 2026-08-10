/**
 * @Author : Cui
 * @Date: 2026/08/05 00:00
 * @Description DataSmart Govern Backend - SpecialistTurnFactControllerTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.specialist;

import com.czh.datasmart.govern.agent.service.session.AgentSessionAccessContext;
import com.czh.datasmart.govern.common.api.PlatformApiResponse;
import com.czh.datasmart.govern.common.context.PlatformAuthorizedProjectRole;
import com.czh.datasmart.govern.common.error.PlatformBusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 专业 Agent turn 事实 Controller 边界测试。
 *
 * <p>这里不启动完整 Spring MVC，而是直接验证 Controller 的路由方法契约：登记时上下文只能进一步收口事实，
 * 查询时 Controller 只把可信 Header 组装为访问上下文，不接受任何请求体或查询参数来替换租户、项目和 actor。</p>
 */
class SpecialistTurnFactControllerTest {

    /**
     * 事实 Controller 只暴露 agent-runtime 内部路径。
     *
     * <p>外部 {@code /api/agent/specialist-turn-facts/**} 必须先经过 Gateway 的认证、授权和上下文重建，
     * 再由 Gateway Rewrite 到这里。如果保留一个 {@code /api/agent/**} 的 Controller 别名，直连
     * agent-runtime 端口的请求就可能绕开 Gateway 授权过滤器；这个测试把“无直达别名”固定成安全契约。</p>
     */
    @Test
    void shouldExposeOnlyGatewayRewrittenInternalPath() {
        RequestMapping mapping = SpecialistTurnFactController.class.getAnnotation(RequestMapping.class);

        assertArrayEquals(new String[]{"/agent-runtime/specialist-turn-facts"}, mapping.value());
    }

    /** 带完整可信上下文时，登记请求应原样交给 Service，并返回低敏事实。 */
    @Test
    void shouldRegisterFactWithTrustedContext() {
        SpecialistTurnFactService service = mock(SpecialistTurnFactService.class);
        SpecialistTurnFactController controller = new SpecialistTurnFactController(service);
        SpecialistTurnFact fact = fact();
        when(service.register(fact, "python-ai-runtime", "trusted-secret")).thenReturn(fact);

        PlatformApiResponse<SpecialistTurnFact> response = controller.register(
                fact, "python-ai-runtime", "trusted-secret", 10L, 10010L, 20L, "user-a",
                "knowledge-agent", null, "trace-a"
        );

        assertEquals(fact, response.getData());
        assertEquals("trace-a", response.getTraceId());
        verify(service).register(fact, "python-ai-runtime", "trusted-secret");
    }

    /** 请求 Header 的租户/项目/actor 与事实不一致时，Controller 必须在 Service 前拒绝。 */
    @Test
    void shouldRejectRegistrationContextMismatch() {
        SpecialistTurnFactService service = mock(SpecialistTurnFactService.class);
        SpecialistTurnFactController controller = new SpecialistTurnFactController(service);

        assertThrows(PlatformBusinessException.class, () -> controller.register(
                fact(), "python-ai-runtime", "trusted-secret", 99L, 10010L, 20L, "user-a",
                "knowledge-agent", null, "trace-a"
        ));
    }

    /**
     * 应用层不能只依赖项目编号间接推断。受信服务若把另一个应用的 Header 与当前事实混用，
     * Controller 必须在事实写入前拒绝，避免同租户多应用下的审计记录串流。
     */
    @Test
    void shouldRejectRegistrationContextWithMismatchedApplication() {
        SpecialistTurnFactService service = mock(SpecialistTurnFactService.class);
        SpecialistTurnFactController controller = new SpecialistTurnFactController(service);

        assertThrows(PlatformBusinessException.class, () -> controller.register(
                fact(), "python-ai-runtime", "trusted-secret", 10L, 10011L, 20L, "user-a",
                "knowledge-agent", null, "trace-a"
        ));
        verifyNoInteractions(service);
    }

    /** 只传一个范围 Header 不能被解释成完整授权范围，必须 fail-closed。 */
    @Test
    void shouldRejectPartialRegistrationContext() {
        SpecialistTurnFactController controller = new SpecialistTurnFactController(
                mock(SpecialistTurnFactService.class));

        assertThrows(PlatformBusinessException.class, () -> controller.register(
                fact(), "python-ai-runtime", "trusted-secret", 10L, null, 20L, "user-a",
                null, null, "trace-a"
        ));
    }

    /**
     * Agent 身份是与用户身份并列的第二主体，不能因为事实 body 自带 agentId 就允许 Header 缺失。
     *
     * <p>这条回归测试覆盖一个容易被忽略的边界：受信服务的 token 只证明“哪个服务在调用”，不能证明
     * “哪一个专业 Agent 完成了这次 turn”。没有 Header 对照时，故障重放或请求对象复用可能把六 Agent
     * 中任意一个的事实错误归属给另一个 Agent。</p>
     */
    @Test
    void shouldRejectRegistrationWithoutAgentIdentityHeader() {
        SpecialistTurnFactService service = mock(SpecialistTurnFactService.class);
        SpecialistTurnFactController controller = new SpecialistTurnFactController(service);

        assertThrows(PlatformBusinessException.class, () -> controller.register(
                fact(), "python-ai-runtime", "trusted-secret", 10L, 10010L, 20L, "user-a",
                null, null, "trace-a"
        ));
        verifyNoInteractions(service);
    }

    /** 完整 Header 也必须代表同一个专业 Agent，不能把一个 Agent 的 fact 伪装成另一个 Agent 的工作。 */
    @Test
    void shouldRejectRegistrationWithMismatchedAgentIdentityHeader() {
        SpecialistTurnFactService service = mock(SpecialistTurnFactService.class);
        SpecialistTurnFactController controller = new SpecialistTurnFactController(service);

        assertThrows(PlatformBusinessException.class, () -> controller.register(
                fact(), "python-ai-runtime", "trusted-secret", 10L, 10010L, 20L, "user-a",
                "recovery-agent", null, "trace-a"
        ));
        verifyNoInteractions(service);
    }

    /**
     * 有 delegation 的事实必须由同一个 delegation Header 证明，不能把委托责任链在 HTTP 转发时丢掉。
     */
    @Test
    void shouldRejectDelegatedFactWithoutMatchingDelegationHeader() {
        SpecialistTurnFactService service = mock(SpecialistTurnFactService.class);
        SpecialistTurnFactController controller = new SpecialistTurnFactController(service);

        assertThrows(PlatformBusinessException.class, () -> controller.register(
                fact("SUCCEEDED", "delegation-1"), "python-ai-runtime", "trusted-secret",
                10L, 10010L, 20L, "user-a", "knowledge-agent", null, "trace-a"
        ));
        verifyNoInteractions(service);
    }

    /** 无 delegation 的事实也不能接收额外 Header，避免调用链伪造并不存在的授权来源。 */
    @Test
    void shouldRejectUnexpectedDelegationHeaderForUndelegatedFact() {
        SpecialistTurnFactService service = mock(SpecialistTurnFactService.class);
        SpecialistTurnFactController controller = new SpecialistTurnFactController(service);

        assertThrows(PlatformBusinessException.class, () -> controller.register(
                fact(), "python-ai-runtime", "trusted-secret", 10L, 10010L, 20L, "user-a",
                "knowledge-agent", "delegation-1", "trace-a"
        ));
        verifyNoInteractions(service);
    }

    /**
     * 即使请求体把状态伪造为 APPROVED，缺少完整受信归属上下文也必须在 Controller 入口拒绝。
     *
     * <p>最终的 source-service + internal-token 守卫仍由 Service 执行；本测试固定另一条必要边界：
     * 任何没有 tenant/project/actor 责任链的事实都不能进入 Service，更不能因为状态字段看起来成功而入库。</p>
     */
    @Test
    void shouldRejectApprovedFactWithoutCompleteTrustedContext() {
        SpecialistTurnFactService service = mock(SpecialistTurnFactService.class);
        SpecialistTurnFactController controller = new SpecialistTurnFactController(service);

        assertThrows(PlatformBusinessException.class, () -> controller.register(
                fact("APPROVED"), "python-ai-runtime", "trusted-secret",
                null, null, null, null, null, null, "trace-a"
        ));
        verifyNoInteractions(service);
    }

    /** 查询接口应使用 Header 组装访问上下文，返回值保持为低敏事实列表。 */
    @Test
    void shouldQuerySessionUsingCurrentAccessContext() {
        SpecialistTurnFactService service = mock(SpecialistTurnFactService.class);
        SpecialistTurnFactController controller = new SpecialistTurnFactController(service);
        AgentSessionAccessContext access = new AgentSessionAccessContext(
                10L, 10010L, 20L, "user-a", "ORDINARY_USER", "SELF", List.of(20L),
                List.of(new PlatformAuthorizedProjectRole(20L, "READER")));
        when(service.findBySession("session-a", access, 100)).thenReturn(List.of(fact()));

        PlatformApiResponse<List<SpecialistTurnFact>> response = controller.findBySession(
                "session-a", 10L, 10010L, 20L, "user-a", "ORDINARY_USER", "SELF",
                "20", "20:READER", "trace-a");

        assertEquals(1, response.getData().size());
        assertEquals("user-a", response.getData().getFirst().userId());
        verify(service).findBySession("session-a", access, 100);
    }

    /** 构造一条合法的低敏事实。 */
    private SpecialistTurnFact fact() {
        return fact("SUCCEEDED");
    }

    /** 构造指定状态的事实，用于验证 APPROVED 不能脱离可信服务责任链被伪造。 */
    private SpecialistTurnFact fact(String status) {
        return fact(status, null);
    }

    /** 构造可选 delegation 的合法事实，用于验证用户、Agent 和委托三段责任链。 */
    private SpecialistTurnFact fact(String status, String delegationId) {
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        return new SpecialistTurnFact(
                "user-a", 10L, 10010L, 20L, "session-a", "run-a", "turn-a", "idem-a-" + status,
                "knowledge-agent", "KNOWLEDGE_AGENT", delegationId, status, "safe summary",
                "provider-call-1", "gpt-5.6-sol", List.of("tool.summary:1"), List.of("rag.case:1"),
                10L, now, now, now, now
        );
    }
}

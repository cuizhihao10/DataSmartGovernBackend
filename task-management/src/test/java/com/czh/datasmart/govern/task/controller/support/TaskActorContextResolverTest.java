/**
 * @Author : Cui
 * @Date: 2026/05/23 18:02
 * @Description DataSmart Govern Backend - TaskActorContextResolverTest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.task.controller.support;

import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.task.controller.dto.TaskActorContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务操作上下文解析器测试。
 *
 * <p>这个测试用来固定一个很重要但很容易被遗漏的事实：
 * gateway 透传的项目授权集合不是只有 quality 模块才消费，task 模块也必须把它读进自己的操作上下文。
 * 如果这里解析错了，后面的任务列表、队列和强控动作就会丢失 PROJECT 范围语义。</p>
 */
class TaskActorContextResolverTest {

    private final TaskActorContextResolver resolver = new TaskActorContextResolver();

    @Test
    void resolveShouldParseDataScopeHeadersFromHttpRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(PlatformContextHeaders.TENANT_ID, "10");
        request.addHeader(PlatformContextHeaders.ACTOR_ID, "1001");
        request.addHeader(PlatformContextHeaders.ACTOR_ROLE, "PROJECT_OWNER");
        request.addHeader(PlatformContextHeaders.TRACE_ID, "trace-001");
        request.addHeader(PlatformContextHeaders.DATA_SCOPE_LEVEL, "PROJECT");
        request.addHeader(PlatformContextHeaders.AUTHORIZED_PROJECT_IDS, "101, 102, bad, 102");

        TaskActorContext context = resolver.resolve(request);

        assertEquals(10L, context.tenantId());
        assertEquals(1001L, context.actorId());
        assertEquals("PROJECT_OWNER", context.actorRole());
        assertEquals("trace-001", context.traceId());
        assertTrue(context.projectScopeEnforced());
        assertEquals(List.of(101L, 102L), context.safeAuthorizedProjectIds());
    }
}

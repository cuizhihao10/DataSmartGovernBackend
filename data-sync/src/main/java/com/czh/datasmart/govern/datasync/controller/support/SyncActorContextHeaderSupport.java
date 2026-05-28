/**
 * @Author : Cui
 * @Date: 2026/05/10 13:33
 * @Description DataSmart Govern Backend - SyncActorContextHeaderSupport.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasync.controller.support;

import com.czh.datasmart.govern.common.context.PlatformAuthorizedProjectHeaderSupport;
import com.czh.datasmart.govern.common.context.PlatformContextHeaders;
import com.czh.datasmart.govern.datasync.controller.dto.SyncActorContext;
import org.springframework.http.HttpHeaders;

/**
 * data-sync 控制器上下文 Header 解析工具。
 *
 * <p>多个 Controller 都需要把 gateway 透传的租户、操作者、数据范围、审批要求和授权项目集合转换为
 * `SyncActorContext`。如果每个 Controller 都复制一份解析逻辑，后续新增 Header 或修复解析规则时很容易漏改。
 * 因此这里把“HTTP Header -> 领域上下文”的协议适配集中起来，Controller 只负责声明路由和调用 Service。
 */
public final class SyncActorContextHeaderSupport {

    private SyncActorContextHeaderSupport() {
        throw new UnsupportedOperationException("SyncActorContextHeaderSupport 是工具类，不允许实例化");
    }

    /**
     * 根据基础身份参数和完整 Header 构造 data-sync 业务上下文。
     *
     * @param tenantId 网关注入的租户 ID
     * @param actorId 网关注入的操作者 ID
     * @param actorRole 网关注入的角色编码
     * @param traceId 链路追踪 ID
     * @param headers 完整请求 Header，用于读取 data scope、审批标识和授权项目集合
     * @return 可交给 Service 层使用的领域上下文
     */
    public static SyncActorContext fromHeaders(Long tenantId,
                                               Long actorId,
                                               String actorRole,
                                               String traceId,
                                               HttpHeaders headers) {
        return new SyncActorContext(
                tenantId,
                actorId,
                actorRole,
                traceId,
                headers.getFirst(PlatformContextHeaders.DATA_SCOPE_LEVEL),
                headers.getFirst(PlatformContextHeaders.DATA_SCOPE_EXPRESSION),
                parseAuthorizedProjectIds(headers.getFirst(PlatformContextHeaders.AUTHORIZED_PROJECT_IDS)),
                Boolean.valueOf(headers.getFirst(PlatformContextHeaders.APPROVAL_REQUIRED)));
    }

    /**
     * 解析 gateway 透传的项目授权集合。
     *
     * <p>具体编解码规则放在 platform-common 的 `PlatformAuthorizedProjectHeaderSupport` 中。
     * 这样 datasource-management、data-quality 后续接入相同 Header 时，可以复用同一套安全规则，
     * 避免不同模块对坏片段、重复 ID、空集合的理解不一致。
     */
    private static java.util.List<Long> parseAuthorizedProjectIds(String value) {
        return PlatformAuthorizedProjectHeaderSupport.parse(value);
    }
}

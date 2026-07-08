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
 * data-sync 控制器请求上下文 Header 解析工具。
 *
 * <p>这个类是“HTTP 层协议字段”和“data-sync 领域上下文”之间的适配器。网关、企业 IdP、权限中心和服务间调用链路会把
 * 租户、项目、工作空间、操作者、数据范围等信息放到统一的 {@code X-DataSmart-*} Header 中；Controller 不应该在每个接口里
 * 重复解析这些 Header，而是统一委托给这里生成 {@link SyncActorContext}。</p>
 *
 * <p>为什么现在要把 projectId 纳入上下文，而不再读取 workspaceId：</p>
 * <p>1. 对用户来说，“当前在哪个项目/工作空间下创建数据源或同步任务”应由页面顶部项目切换器和登录上下文决定，不应该在业务表单里手工填写数字 ID；</p>
 * <p>2. 对后端来说，项目属于权限边界，必须优先相信网关/权限中心注入的可信上下文，而不是浏览器随意提交的 request body 字段；</p>
 * <p>3. 工作空间已经从 FlashSync 数据同步产品层级退场。网关、OIDC 或开发期身份仍可能因为历史 claim
 * 带上 {@code X-DataSmart-Workspace-Id=workspace-a} 这类字符串，但 data-sync 用户侧接口必须把它视为 legacy no-op。
 * 否则页面已经只展示“项目”，后端却仍按不可见的工作空间校验，会导致创建向导、任务列表和分组树出现难以理解的错误。</p>
 */
public final class SyncActorContextHeaderSupport {

    private SyncActorContextHeaderSupport() {
        throw new UnsupportedOperationException("SyncActorContextHeaderSupport 是工具类，不允许实例化");
    }

    /**
     * 根据基础身份参数和完整 Header 构造 data-sync 业务上下文。
     *
     * @param tenantId 网关注入的租户 ID；为空时服务层会按 FlashSync 本地默认开租数据兜底
     * @param actorId 网关注入的操作者 ID；用于 owner、审计、SELF 范围过滤
     * @param actorRole 网关注入的角色编码；用于本地兜底权限判断
     * @param traceId 链路追踪 ID；贯穿日志、审计和响应 envelope
     * @param headers 完整请求 Header；用于读取项目、数据范围、审批标记和授权项目集合；工作空间 Header 只做历史兼容忽略
     * @return 可交给 Service 层使用的领域上下文
     */
    public static SyncActorContext fromHeaders(Long tenantId,
                                               Long actorId,
                                               String actorRole,
                                               String traceId,
                                               HttpHeaders headers) {
        return new SyncActorContext(
                tenantId,
                parseLongHeader(headers, PlatformContextHeaders.PROJECT_ID),
                ignoreLegacyWorkspaceHeader(headers),
                actorId,
                actorRole,
                traceId,
                firstHeader(headers, PlatformContextHeaders.DATA_SCOPE_LEVEL),
                firstHeader(headers, PlatformContextHeaders.DATA_SCOPE_EXPRESSION),
                parseAuthorizedProjectIds(firstHeader(headers, PlatformContextHeaders.AUTHORIZED_PROJECT_IDS)),
                Boolean.valueOf(firstHeader(headers, PlatformContextHeaders.APPROVAL_REQUIRED)));
    }

    /**
     * 忽略历史工作空间 Header。
     *
     * <p>这段看起来像“什么都没做”，但它是本轮项目级收敛的关键保护点：</p>
     * <p>1. 早期网关、开发期身份令牌和 OIDC claim 中都曾把 workspace 当作通用身份上下文写入 Header；</p>
     * <p>2. 当前 FlashSync 数据同步已经明确收敛为“租户 -> 项目 -> 数据源/任务”，用户页面不再选择工作空间；</p>
     * <p>3. 因此即使 Header 里出现 {@code workspace-a}、{@code default} 或历史数字 ID，data-sync 用户侧链路也不能再解析、
     * 校验或参与查询过滤。</p>
     *
     * <p>这里仍然显式读取一次 Header，是为了让维护者知道该字段不是遗漏，而是有意做 legacy no-op。Agent 沙箱、
     * Python Runtime 或内部 worker 如果未来仍需要 workspace key，应使用各自的专用合同字段，不应让它重新污染数据同步业务范围。</p>
     */
    private static Long ignoreLegacyWorkspaceHeader(HttpHeaders headers) {
        firstHeader(headers, PlatformContextHeaders.WORKSPACE_ID);
        return null;
    }

    /**
     * 读取普通字符串 Header。
     *
     * <p>Controller 方法已经通过 {@code @RequestHeader} 单独接收了部分核心字段，但项目、工作空间等字段更适合统一从
     * HttpHeaders 中读取，避免每个路由签名继续膨胀。这里对 {@code headers == null} 做保护，便于单元测试直接构造上下文时复用。</p>
     */
    private static String firstHeader(HttpHeaders headers, String headerName) {
        return headers == null ? null : headers.getFirst(headerName);
    }

    /**
     * 解析 Long 类型上下文 Header。
     *
     * <p>项目 ID 属于后端可信上下文；如果上游传入了非数字，说明网关或调用方协议错误，应尽早失败，而不是悄悄按空值处理。
     * 悄悄吞掉错误会让任务落到默认项目，后续排查会非常困难。工作空间不再走这个方法，避免历史字符串 workspace key
     * 继续触发用户侧数据同步接口失败。</p>
     */
    private static Long parseLongHeader(HttpHeaders headers, String headerName) {
        String value = firstHeader(headers, headerName);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(headerName + " 必须是 Long 类型数字，当前值=" + value);
        }
    }

    /**
     * 解析 gateway 透传的项目授权集合。
     *
     * <p>具体编解码规则放在 platform-common 的 {@code PlatformAuthorizedProjectHeaderSupport} 中。这样 datasource-management、
     * data-quality 后续接入相同 Header 时，可以复用同一套安全规则，避免不同模块对坏片段、重复 ID、空集合的理解不一致。</p>
     */
    private static java.util.List<Long> parseAuthorizedProjectIds(String value) {
        return PlatformAuthorizedProjectHeaderSupport.parse(value);
    }
}

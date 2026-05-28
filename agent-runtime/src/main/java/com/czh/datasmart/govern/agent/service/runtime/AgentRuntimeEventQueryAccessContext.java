/**
 * @Author : Cui
 * @Date: 2026/05/27 20:10
 * @Description DataSmart Govern Backend - AgentRuntimeEventQueryAccessContext.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

import java.util.List;
import java.util.Locale;

/**
 * Agent 运行时事件查询访问上下文。
 *
 * <p>这个对象不是登录态本身，而是 agent-runtime 从 gateway 可信 Header 中消费到的“本次请求是谁、
 * 来自哪个租户、permission-admin 给了什么数据范围”的领域化快照。之所以不让查询 Service 直接读取
 * `HttpServletRequest` 或散落的 `@RequestHeader` 字符串，是为了保持三层边界清晰：</p>
 *
 * <p>1. Controller 负责 HTTP 参数和 Header 接入；</p>
 * <p>2. Resolver 负责把字符串 Header 转成类型安全上下文；</p>
 * <p>3. AccessSupport 负责把 SELF/PROJECT/TENANT/PLATFORM 翻译成最终查询条件。</p>
 *
 * <p>这样后续如果接入 JWT、服务账号签名、mTLS 或内部 Agent 网关，只需要替换上下文解析方式，
 * 不需要改动事件投影查询业务逻辑。</p>
 */
public record AgentRuntimeEventQueryAccessContext(
        Long tenantId,
        Long actorId,
        String actorRole,
        String traceId,
        String dataScopeLevel,
        List<Long> authorizedProjectIds
) {

    /**
     * 是否具备可用于后端数据范围收口的基础身份。
     *
     * <p>Agent 事件查询比普通配置查询更敏感：如果没有 tenantId/actorId，服务层无法判断“我的事件”
     * 或“本租户事件”到底指谁。因此缺少基础身份时，后续 AccessSupport 会采取保守策略返回空结果。</p>
     */
    public boolean hasIdentity() {
        return tenantId != null && actorId != null;
    }

    /**
     * 标准化角色编码。
     *
     * <p>角色来自 gateway 透传 Header，理论上已经由认证/开发身份过滤器写入；但服务层仍然做 trim 和大写，
     * 避免因为大小写差异导致数据范围兜底策略失效。</p>
     */
    public String normalizedRole() {
        return actorRole == null || actorRole.isBlank()
                ? "UNKNOWN"
                : actorRole.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 标准化数据范围等级。
     *
     * <p>如果 permission-admin 已经通过 gateway 返回 dataScopeLevel，则优先使用它；如果没有，
     * agent-runtime 会按角色做本地保守兜底。这里不直接兜底，是为了让“显式权限中心范围”和“本地迁移期范围”
     * 在代码上保持可区分，便于后续审计和排障。</p>
     */
    public String normalizedDataScopeLevel() {
        return dataScopeLevel == null || dataScopeLevel.isBlank()
                ? ""
                : dataScopeLevel.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 是否是 gateway 明确下发的 PROJECT 数据范围。
     *
     * <p>只有显式 PROJECT 范围才会强制使用 `authorizedProjectIds`。这和 data-sync 的处理方式一致：
     * 权限中心已经参与判定时，空项目集合必须被理解为“无项目可见”，不能退回到租户级全量可见。</p>
     */
    public boolean explicitProjectScope() {
        return "PROJECT".equals(normalizedDataScopeLevel());
    }

    /**
     * 将授权项目集合转成事件投影中使用的字符串 projectId。
     *
     * <p>当前 runtime event payload 来自 Python Runtime，tenant/project/actor 等字段都以 JSON 字符串形式进入
     * Java 投影；因此这里把 Long projectId 转为字符串，避免在内存 store 和未来 JSON/ClickHouse 查询之间
     * 反复做类型转换。</p>
     */
    public List<String> authorizedProjectIdsAsStrings() {
        if (authorizedProjectIds == null) {
            return List.of();
        }
        return authorizedProjectIds.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .map(String::valueOf)
                .toList();
    }
}

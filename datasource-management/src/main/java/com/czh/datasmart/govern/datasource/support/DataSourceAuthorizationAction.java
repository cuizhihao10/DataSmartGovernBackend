/**
 * @Author : Cui
 * @Date: 2026/07/09 01:07
 * @Description DataSmart Govern Backend - DataSourceAuthorizationAction.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.support;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;

/**
 * 数据源实例级授权动作。
 *
 * <p>这里的动作不是网关层面的“能不能访问某条 HTTP 路由”，而是某一条具体数据源连接实例上允许被授予的业务能力。
 * 例如同一个用户可以只被允许在任务创建向导中选择某个源端数据源，也可以被进一步允许查看详情或管理授权。
 * 把动作沉淀为枚举，而不是在业务代码中到处手写字符串，可以避免后续前端、同步任务、元数据发现和审计链路对授权含义理解不一致。</p>
 */
public enum DataSourceAuthorizationAction {

    /**
     * 查看数据源低敏信息。
     *
     * <p>VIEW 允许用户在列表、详情和任务配置中看到数据源名称、类型、用途、健康状态等低敏字段。
     * 它不表示可以读取外部业务库数据，也不表示可以修改连接凭据。</p>
     */
    VIEW,

    /**
     * 使用数据源参与平台任务。
     *
     * <p>USE 允许用户在数据同步、质量扫描、元数据发现等受控流程中引用该数据源。
     * 对真实数据的读取仍然必须经过 datasource-management 的连接状态、SQL 安全、任务预检查和审计控制。</p>
     */
    USE,

    /**
     * 管理数据源授权。
     *
     * <p>MANAGE 是高风险动作，表示可以维护该数据源的授权账本。当前仍建议只授予项目负责人、租户管理员或平台管理员。
     * 为了方便后续产品化扩展，MANAGE 在本枚举中保留，但是否能穿过 gateway 仍由 permission-admin 路由策略共同决定。</p>
     */
    MANAGE;

    /**
     * 解析单个动作编码。
     *
     * @param value 前端、脚本或导入文件提交的动作编码。
     * @return 标准授权动作。
     */
    public static DataSourceAuthorizationAction fromValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("数据源授权动作不能为空");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (DataSourceAuthorizationAction action : values()) {
            if (action.name().equals(normalized)) {
                return action;
            }
        }
        throw new IllegalArgumentException("不支持的数据源授权动作: " + value + "，可选值为 VIEW、USE、MANAGE");
    }

    /**
     * 将外部动作集合归一化为稳定的逗号分隔字符串。
     *
     * <p>授权表当前使用 VARCHAR 保存动作集合，是为了保持 MyBatis-Plus 默认映射足够简单。
     * 服务层统一用本方法完成去空、去重、校验和顺序稳定化，避免不同调用方写入 {@code use,view} 与 {@code VIEW,USE}
     * 这种语义相同但文本不同的授权事实。</p>
     */
    public static String normalizeToCsv(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("数据源授权动作列表不能为空，至少需要授予 VIEW 或 USE");
        }
        Set<DataSourceAuthorizationAction> actions = new LinkedHashSet<>();
        for (String value : values) {
            actions.add(fromValue(value));
        }
        StringJoiner joiner = new StringJoiner(",");
        for (DataSourceAuthorizationAction action : actions) {
            joiner.add(action.name());
        }
        return joiner.toString();
    }

    /**
     * 判断已授权动作集合是否满足某个必需动作。
     *
     * <p>授权动作存在包含关系：MANAGE 默认包含 VIEW 和 USE；USE 默认包含 VIEW，因为一个用户如果能在任务中选择数据源，
     * 页面通常也需要展示该数据源的低敏名称、类型和用途。反过来 VIEW 不包含 USE，避免“只看见”被误解为“可以执行同步”。</p>
     */
    public static boolean includes(String grantedActionsCsv, DataSourceAuthorizationAction requiredAction) {
        if (grantedActionsCsv == null || grantedActionsCsv.isBlank() || requiredAction == null) {
            return false;
        }
        Set<DataSourceAuthorizationAction> grantedActions = new LinkedHashSet<>();
        for (String value : grantedActionsCsv.split(",")) {
            grantedActions.add(fromValue(value));
        }
        if (grantedActions.contains(MANAGE)) {
            return true;
        }
        if (requiredAction == VIEW && grantedActions.contains(USE)) {
            return true;
        }
        return grantedActions.contains(requiredAction);
    }
}

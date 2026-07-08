/**
 * @Author : Cui
 * @Date: 2026/07/08 18:29
 * @Description DataSmart Govern Backend - GatewayExchangeAttributeNames.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.filter;

/**
 * Gateway 过滤器链内部 exchange attribute 名称。
 *
 * <p>Spring Cloud Gateway 的一个请求会按顺序经过多个 {@code GlobalFilter}。
 * Header 是要转发给下游服务的外部协议，而 exchange attribute 只存在于网关本次请求内存里，
 * 不会被浏览器、代理或下游业务服务直接看到。对于“外部可以提出请求意图，但必须由 gateway 校验后重建”
 * 的信息，attribute 是更合适的承载位置。</p>
 *
 * <p>本类目前主要用于项目切换场景：
 * 前端可以在请求中带上 {@code X-DataSmart-Project-Id} 表示“我当前选中了哪个项目”，
 * 但 {@link GatewayContractFilter} 会先清理所有外部 X-DataSmart-* 控制面 Header，避免直接信任客户端自报。
 * 清理之前，它会把这个“待校验的项目选择”放入 attribute；随后
 * {@link GatewayAuthorizationFilter} 根据 permission-admin 返回的授权项目集合校验通过后，
 * 才重新写入可信的 {@code X-DataSmart-Project-Id} 给下游服务。</p>
 */
final class GatewayExchangeAttributeNames {

    /**
     * 原始项目 Header 文本。
     *
     * <p>保存原始文本是为了区分“没有传项目”和“传了非法项目值”。
     * 没有传项目时可以继续走普通授权；传了非法值时应返回 400，提示前端请求合同错误。</p>
     */
    static final String REQUESTED_PROJECT_ID_RAW = "datasmart.gateway.requestedProjectIdRaw";

    /**
     * 解析后的正整数项目 ID。
     *
     * <p>只有当原始 Header 能解析为大于 0 的 Long 时才写入该 attribute。
     * 后续授权过滤器不再解析 Header，而是读取这个受控解析结果。</p>
     */
    static final String REQUESTED_PROJECT_ID = "datasmart.gateway.requestedProjectId";

    private GatewayExchangeAttributeNames() {
        throw new UnsupportedOperationException("GatewayExchangeAttributeNames 是常量类，不允许实例化");
    }
}

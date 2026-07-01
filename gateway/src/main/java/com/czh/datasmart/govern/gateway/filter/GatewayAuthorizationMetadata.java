/**
 * @Author : Cui
 * @Date: 2026/07/02 02:35
 * @Description DataSmart Govern Backend - GatewayAuthorizationMetadata.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.filter;

/**
 * 网关内部解析完成的授权语义。
 *
 * <p>该值对象只在 gateway 授权过滤链内部流转，不进入 HTTP 响应，也不替代 permission-admin 的策略事实。
 * 使用不可变 record 可以确保 resourceType/action 在构造判定请求前不会被后续步骤意外修改。
 *
 * @param resourceType 权限中心能够识别的资源类型编码
 * @param action 当前端点和 HTTP 方法对应的业务动作编码
 */
record GatewayAuthorizationMetadata(String resourceType, String action) {
}

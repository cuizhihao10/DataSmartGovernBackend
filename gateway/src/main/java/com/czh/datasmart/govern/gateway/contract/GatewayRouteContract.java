package com.czh.datasmart.govern.gateway.contract;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 网关路由契约对象。
 * <p>
 * 这个对象不是业务数据，而是“平台入口规则”的结构化表达。
 * 它的意义在于把 gateway application.yml 中隐含的路由约定，变成一个可以直接查看和学习的接口结果。
 */
@Data
@AllArgsConstructor
public class GatewayRouteContract {

    private String moduleName;

    private String externalPrefix;

    private String targetService;

    private String backendEntryPattern;

    private String description;
}

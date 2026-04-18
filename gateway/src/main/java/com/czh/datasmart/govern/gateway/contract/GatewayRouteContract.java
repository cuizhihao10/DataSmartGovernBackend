package com.czh.datasmart.govern.gateway.contract;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:20
 * @Description DataSmart Govern Backend - GatewayRouteContract.java
 * @Version:1.0.0
 *
 * 网关路由契约对象。
 * 这个对象不是业务实体，而是把网关中的路由约定结构化表达出来。
 * 原本只藏在配置文件里的入口规则，现在也可以通过接口直接查看，
 * 这对学习项目结构、联调和排查都很有帮助。
 */
@Data
@AllArgsConstructor
public class GatewayRouteContract {

    /**
     * 目标模块名称。
     */
    private String moduleName;

    /**
     * 对外暴露的网关前缀。
     */
    private String externalPrefix;

    /**
     * 路由目标服务名。
     */
    private String targetService;

    /**
     * 转发到后端后的入口模式。
     */
    private String backendEntryPattern;

    /**
     * 当前阶段的访问策略说明。
     */
    private String accessPolicy;

    /**
     * 业务说明。
     */
    private String description;
}

/**
 * @Author : Cui
 * @Date: 2026/04/25 23:00
 * @Description DataSmart Govern Backend - GatewayContractController.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.gateway.controller;

import com.czh.datasmart.govern.gateway.common.ApiResponse;
import com.czh.datasmart.govern.gateway.contract.GatewayRouteContract;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 网关契约控制器。
 *
 * <p>Gateway 不适合承载深业务逻辑，但非常适合承载“平台入口规则说明”。
 * 这个控制器把各模块的入口前缀、目标服务和当前访问策略结构化暴露出来，
 * 便于学习、联调、排查和后续生成 API 文档。
 *
 * <p>注意：这个接口是“契约说明”，不是最终权限判定入口。
 * 真正的路由权限判定会逐步迁移到 gateway + permission-admin 的组合：
 * gateway 负责解析请求和上下文，permission-admin 负责返回策略判定。
 */
@RestController
@RequestMapping("/gateway/contracts")
public class GatewayContractController {

    /**
     * 列出当前网关声明的路由契约。
     *
     * <p>当前采用硬编码列表，是为了让学习者能直接把代码和 application.yml 中的路由规则对应起来。
     * 等路由数量变多后，可以进一步演进为读取 Spring Cloud Gateway RouteDefinition 或配置中心。
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<GatewayRouteContract>>> listContracts() {
        return ResponseEntity.ok(ApiResponse.success("网关契约查询成功", List.of(
                new GatewayRouteContract(
                        "task-management",
                        "/api/task/**",
                        "lb://task-management",
                        "/tasks/**",
                        "当前阶段放行，后续收敛为 JWT + permission-admin 路由策略判定",
                        "任务模块入口，负责任务创建、状态流转、进度回写和执行日志查询"
                ),
                new GatewayRouteContract(
                        "permission-admin",
                        "/api/permission/**",
                        "lb://permission-admin",
                        "/permissions/**",
                        "当前阶段放行，后续作为 gateway 路由权限判定和后台权限管理的核心依赖",
                        "权限与管理中心入口，负责角色、菜单、路由策略、数据范围和权限审计"
                ),
                new GatewayRouteContract(
                        "datasource-management",
                        "/api/datasource/**",
                        "lb://datasource-management",
                        "/datasources/**",
                        "当前阶段放行，后续收敛为 JWT + permission-admin 数据范围判定",
                        "数据源模块入口，负责数据源登记、连接测试、元数据发现和同步控制面"
                ),
                new GatewayRouteContract(
                        "data-quality",
                        "/api/quality/**",
                        "lb://data-quality",
                        "/quality-rules/**",
                        "当前阶段放行，后续收敛为 JWT + permission-admin 角色/数据范围判定",
                        "数据质量模块入口，负责质量规则管理、规则执行和报告查询"
                ),
                new GatewayRouteContract(
                        "observability",
                        "/api/observability/**",
                        "lb://observability",
                        "/**",
                        "当前阶段放行，后续收敛为运维角色和审计角色专用访问策略",
                        "可观测性模块入口，后续承接告警、日志和指标聚合能力"
                )
        )));
    }
}

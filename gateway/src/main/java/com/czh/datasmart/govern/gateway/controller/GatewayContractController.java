package com.czh.datasmart.govern.gateway.controller;

import com.czh.datasmart.govern.gateway.common.ApiResponse;
import com.czh.datasmart.govern.gateway.contract.GatewayRouteContract;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/4/18 22:20
 * @Description DataSmart Govern Backend - GatewayContractController.java
 * @Version:1.0.0
 *
 * 网关契约控制器。
 * Gateway 不适合承载深业务逻辑，但非常适合承载“平台入口规则说明”。
 * 这个控制器的作用，就是把各模块的入口前缀、目标服务和当前访问策略结构化暴露出来，
 * 方便后续学习、排查和联调。
 */
@RestController
@RequestMapping("/gateway/contracts")
public class GatewayContractController {

    /**
     * 列出当前网关已声明的路由契约。
     * 当前采用硬编码列表的方式，是为了让学习者能直接把代码和 application.yml 中的路由规则对应起来。
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<GatewayRouteContract>>> listContracts() {
        return ResponseEntity.ok(ApiResponse.success("网关契约查询成功", List.of(
                new GatewayRouteContract(
                        "task-management",
                        "/api/task/**",
                        "lb://task-management",
                        "/tasks/**",
                        "当前阶段放行，后续收敛为 JWT + 角色权限校验",
                        "任务模块入口，负责任务创建、状态流转、进度回写和执行日志查询"
                ),
                new GatewayRouteContract(
                        "datasource-management",
                        "/api/datasource/**",
                        "lb://datasource-management",
                        "/datasources/**",
                        "当前阶段放行，后续收敛为 JWT + 角色权限校验",
                        "数据源模块入口，负责数据源登记、启停管理和连接测试"
                ),
                new GatewayRouteContract(
                        "data-quality",
                        "/api/quality/**",
                        "lb://data-quality",
                        "/quality-rules/**",
                        "当前阶段放行，后续收敛为 JWT + 角色权限校验",
                        "数据质量模块入口，负责质量规则管理、规则执行和报告查询"
                ),
                new GatewayRouteContract(
                        "observability",
                        "/api/observability/**",
                        "lb://observability",
                        "/**",
                        "当前阶段放行，后续收敛为运维角色专用访问策略",
                        "可观测性模块入口，后续承接告警、日志和指标聚合能力"
                )
        )));
    }
}

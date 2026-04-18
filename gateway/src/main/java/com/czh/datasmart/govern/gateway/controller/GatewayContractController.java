package com.czh.datasmart.govern.gateway.controller;

import com.czh.datasmart.govern.gateway.contract.GatewayRouteContract;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 网关契约控制器。
 * <p>
 * gateway 不应该承载深度业务逻辑，但非常适合承载“入口规则说明”。
 * 当前接口的作用，就是把三个业务模块已经稳定下来的路由前缀和后端入口模式结构化暴露出来，
 * 方便你在学习时快速理解：
 * 1. 外部请求应该走哪个前缀。
 * 2. 网关要转发到哪个服务。
 * 3. 服务内部大致有哪些资源入口。
 */
@RestController
@RequestMapping("/gateway/contracts")
public class GatewayContractController {

    @GetMapping
    public ResponseEntity<List<GatewayRouteContract>> listContracts() {
        return ResponseEntity.ok(List.of(
                new GatewayRouteContract(
                        "task-management",
                        "/api/task/**",
                        "lb://task-management",
                        "/tasks/**",
                        "任务模块入口，负责任务创建、状态流转、进度回写和执行日志查询"
                ),
                new GatewayRouteContract(
                        "datasource-management",
                        "/api/datasource/**",
                        "lb://datasource-management",
                        "/datasources/**",
                        "数据源模块入口，负责数据源登记、启停管理和连接测试"
                ),
                new GatewayRouteContract(
                        "data-quality",
                        "/api/quality/**",
                        "lb://data-quality",
                        "/quality-rules/**",
                        "数据质量模块入口，负责质量规则管理、规则执行和报告查询"
                ),
                new GatewayRouteContract(
                        "observability",
                        "/api/observability/**",
                        "lb://observability",
                        "/**",
                        "可观测性模块入口，后续承接告警、日志和指标聚合能力"
                )
        ));
    }
}

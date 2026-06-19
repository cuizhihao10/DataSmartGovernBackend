package com.czh.datasmart.govern.datasource.controller;

import com.czh.datasmart.govern.datasource.common.ApiResponse;
import com.czh.datasmart.govern.datasource.entity.ConnectorCapabilityProfile;
import com.czh.datasmart.govern.datasource.service.support.ConnectorCapabilityRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @Author : Cui
 * @Date: 2026/06/20 02:00
 * @Description DataSmart Govern Backend - ConnectorCapabilityController.java
 * @Version:1.0.0
 *
 * 连接器能力查询控制器。
 *
 * <p>这个控制器不是数据源实例管理接口，而是“平台连接器能力矩阵”的只读查询入口。
 * 它面向前端模板向导、产品配置页、网关聚合页和后续任务调度策略，回答某类连接器支持哪些同步模式、
 * 写入策略、检查点、采样、分区并行和生产化边界。</p>
 *
 * <p>安全边界：</p>
 * <p>1. 本接口不读取 datasource_config 表，不返回 JDBC URL、用户名、密码或真实连接配置；</p>
 * <p>2. 本接口不触发连接测试、元数据发现、SQL 执行或数据同步；</p>
 * <p>3. 返回内容都是低敏产品能力说明，可以被管理台、文档页和前端配置向导复用。</p>
 */
@RestController
@RequestMapping("/sync/connectors/capabilities")
@RequiredArgsConstructor
public class ConnectorCapabilityController {

    /**
     * 连接器能力注册表。
     * Controller 保持薄层，只声明 HTTP 路由和响应结构；实际能力规则统一沉淀在 registry 中。
     */
    private final ConnectorCapabilityRegistry connectorCapabilityRegistry;

    /**
     * 查询全部连接器能力画像。
     *
     * <p>该接口适合前端初始化模板向导或产品文档页时调用。
     * 返回的 ROADMAP_RESERVED 画像用于说明产品规划边界，不代表当前仓库已经具备真实执行器。</p>
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<ConnectorCapabilityProfile>>> listCapabilities() {
        return ResponseEntity.ok(ApiResponse.success(
                "连接器能力矩阵查询成功",
                connectorCapabilityRegistry.listProfiles()
        ));
    }

    /**
     * 查询单个连接器能力画像。
     *
     * <p>connectorType 大小写不敏感，并兼容 SQLSERVER、SQL_SERVER、MSSQL 等常见别名。
     * 如果传入未维护的类型，registry 会返回明确异常，帮助调用方尽早修正配置。</p>
     */
    @GetMapping("/{connectorType}")
    public ResponseEntity<ApiResponse<ConnectorCapabilityProfile>> getCapability(@PathVariable String connectorType) {
        return ResponseEntity.ok(ApiResponse.success(
                "连接器能力画像查询成功",
                connectorCapabilityRegistry.getProfile(connectorType)
        ));
    }
}

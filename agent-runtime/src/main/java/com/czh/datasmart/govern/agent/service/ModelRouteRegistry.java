/**
 * @Author : Cui
 * @Date: 2026/05/13 22:20
 * @Description DataSmart Govern Backend - ModelRouteRegistry.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service;

import com.czh.datasmart.govern.agent.config.AgentRuntimeProperties;
import com.czh.datasmart.govern.agent.controller.dto.ModelRouteView;
import com.czh.datasmart.govern.agent.model.ModelCapability;
import com.czh.datasmart.govern.agent.model.ModelWorkloadType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 模型路由注册表。
 *
 * <p>这个组件是 Agent Runtime 的模型选择入口。
 * 当前路由来自配置文件，后续可以替换为数据库、Nacos 动态配置或 permission-admin 管理面。
 * 将路由解析集中到这里，可以避免 Controller、Agent 编排器、RAG 服务各自读取配置并产生不一致。
 */
@Component
@RequiredArgsConstructor
public class ModelRouteRegistry {

    private final AgentRuntimeProperties properties;

    /**
     * 根据工作负载查找模型路由。
     *
     * <p>如果配置中没有该工作负载，返回 empty，由上层决定是报错、fallback，还是进入 dry-run。
     */
    public Optional<AgentRuntimeProperties.ModelRouteProperties> findRoute(ModelWorkloadType workloadType) {
        if (workloadType == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(properties.getModelRoutes().get(workloadType.name()));
    }

    /**
     * 返回当前全部模型路由视图。
     *
     * <p>管理后台可以用该接口展示“模型路由表”，避免模型选型只存在于配置文件里不可见。
     */
    public List<ModelRouteView> listRoutes() {
        return properties.getModelRoutes().entrySet().stream()
                .map(entry -> toView(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(ModelRouteView::workloadType))
                .toList();
    }

    /**
     * 将配置对象转换成接口视图。
     */
    public ModelRouteView toView(String workloadType, AgentRuntimeProperties.ModelRouteProperties route) {
        return new ModelRouteView(
                normalizeWorkloadKey(workloadType),
                route.getEnabled(),
                route.getProviderName(),
                route.getProviderType().name(),
                route.getModelName(),
                route.getEndpoint(),
                route.getTimeoutMs(),
                route.getCapabilities().stream().map(ModelCapability::name).toList()
        );
    }

    private String normalizeWorkloadKey(String workloadType) {
        return workloadType == null ? "" : workloadType.trim().toUpperCase(Locale.ROOT);
    }
}

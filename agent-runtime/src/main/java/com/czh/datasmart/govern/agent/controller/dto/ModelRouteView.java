/**
 * @Author : Cui
 * @Date: 2026/05/13 22:20
 * @Description DataSmart Govern Backend - ModelRouteView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * 模型路由视图。
 *
 * <p>该视图面向管理后台、运维和排障人员。
 * 当某次 Agent 调用异常时，首先需要确认“这个工作负载路由到了哪个 Provider、哪个模型、是否启用、声明了哪些能力”。
 */
public record ModelRouteView(String workloadType,
                             Boolean enabled,
                             String providerName,
                             String providerType,
                             String modelName,
                             String endpoint,
                             Long timeoutMs,
                             List<String> capabilities) {
}

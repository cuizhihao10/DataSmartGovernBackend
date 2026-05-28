/**
 * @Author : Cui
 * @Date: 2026/05/13 22:20
 * @Description DataSmart Govern Backend - ModelChatResponse.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * 模型聊天/推理响应。
 *
 * <p>第一版响应会明确标记 `dryRun`，避免开发期误以为已经完成真实模型推理。
 * 后续接入真实 Provider 后，可以保持响应结构不变，只把 dryRun 改为 false 并写入真实内容。
 *
 * @param dryRun 是否为开发期 dry-run 响应。
 * @param workloadType 实际解析出的工作负载。
 * @param providerName 命中的 Provider 名称。
 * @param providerType Provider 类型。
 * @param modelName 命中的模型名称。
 * @param content 响应内容。
 * @param warning 警告说明，例如“当前路由尚未接入真实模型服务”。
 */
public record ModelChatResponse(Boolean dryRun,
                                String workloadType,
                                String providerName,
                                String providerType,
                                String modelName,
                                String content,
                                String warning) {
}

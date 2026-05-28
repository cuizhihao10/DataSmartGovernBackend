/**
 * @Author : Cui
 * @Date: 2026/05/13 22:20
 * @Description DataSmart Govern Backend - ModelMessage.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 模型对话消息。
 *
 * <p>该 DTO 对齐大多数 Chat Completion 风格协议的最小公共字段：role + content。
 * 后续如果接入多模态图片、工具调用、结构化输出，可以在不破坏当前字段的前提下扩展 parts/toolCalls。
 *
 * @param role 消息角色，例如 system、user、assistant、tool。
 * @param content 文本内容。当前第一版只支持文本，避免过早绑定某个多模态协议格式。
 */
public record ModelMessage(
        @NotBlank(message = "role 不能为空")
        @Size(max = 32, message = "role 长度不能超过 32")
        String role,

        @NotBlank(message = "content 不能为空")
        @Size(max = 12000, message = "content 长度不能超过 12000")
        String content) {
}

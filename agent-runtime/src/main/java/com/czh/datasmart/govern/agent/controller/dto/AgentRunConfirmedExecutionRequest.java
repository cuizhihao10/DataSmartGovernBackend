/**
 * @Author : Cui
 * @Date: 2026/07/10 00:00
 * @Description DataSmart Govern Backend - AgentRunConfirmedExecutionRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

/**
 * 普通用户确认并执行一次 Agent Run 的请求。
 *
 * <p>confirmed 必须显式为 true，避免前端默认值、网络重试或脚本误调用触发写操作。确认只作用于当前用户本人发起、
 * 当前项目内的这一次 Run，不等价于永久授权某个工具。</p>
 */
public record AgentRunConfirmedExecutionRequest(
        @AssertTrue(message = "必须显式确认后才能执行 Agent 计划")
        Boolean confirmed,

        @Size(max = 500, message = "确认说明不能超过 500 个字符")
        String comment) {
}

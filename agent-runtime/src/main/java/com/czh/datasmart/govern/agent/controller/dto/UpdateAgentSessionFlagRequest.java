/**
 * @Author : Cui
 * @Date: 2026/08/04 00:00
 * @Description DataSmart Govern Backend - UpdateAgentSessionFlagRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import jakarta.validation.constraints.NotNull;

/** 置顶或归档开关请求。 */
public record UpdateAgentSessionFlagRequest(@NotNull(message = "enabled 不能为空") Boolean enabled) {
}

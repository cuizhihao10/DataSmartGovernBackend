/**
 * @Author : Cui
 * @Date: 2026/07/01 11:01
 * @Description DataSmartGovernBackend - PlatformServiceHealthProbeView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.observability.controller.dto;

import com.czh.datasmart.govern.observability.support.PlatformModuleKind;

/**
 * 单个服务的健康探针展示视图。
 *
 * <p>该视图面向运维台、smoke 脚本和后续告警智能体。
 * 它只展示低敏字段：模块编码、运行形态、目标路径、HTTP 状态码、耗时和健康等级。
 * 不展示响应正文、Header、认证 token、内部异常堆栈、SQL、prompt、模型输出或业务样本。</p>
 *
 * @param moduleCode 模块编码。
 * @param displayName 模块中文名。
 * @param moduleKind 模块运行形态。
 * @param targetUrl 探针目标 URL。当前是本地开发地址；生产可改为内网域名或服务发现地址。
 * @param probeType 探针类型，例如 HEALTH 或 METRICS。
 * @param status 健康状态：UP、DEGRADED、DOWN、SKIPPED。
 * @param statusCode HTTP 状态码；网络不可达时为空。
 * @param durationMs 探针耗时毫秒。
 * @param issueCode 低敏问题分类码。
 */
public record PlatformServiceHealthProbeView(
        String moduleCode,
        String displayName,
        PlatformModuleKind moduleKind,
        String targetUrl,
        String probeType,
        String status,
        Integer statusCode,
        long durationMs,
        String issueCode) {
}

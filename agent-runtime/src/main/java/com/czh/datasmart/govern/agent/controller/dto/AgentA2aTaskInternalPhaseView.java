/**
 * @Author : Cui
 * @Date: 2026/06/06 12:40
 * @Description DataSmart Govern Backend - AgentA2aTaskInternalPhaseView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.util.List;

/**
 * DataSmart 内部治理阶段视图。
 *
 * <p>真实商业化 Agent 平台不会只有 A2A 的八九个外部状态。一个 task 在 DataSmart 内部还会经历权限预检、
 * 审批等待、outbox 投递、worker pre-check、运行、结果归档、超时和死信等阶段。把这些阶段单独列出来，
 * 可以让我们既保持对外协议兼容，又不丢失企业级治理需要的内部可观测性。</p>
 *
 * @param phaseCode 内部阶段编码，使用大写下划线，方便未来落库、指标和事件过滤
 * @param mapsToA2aState 该阶段对外映射的 A2A TaskState
 * @param responsibleLayer 主要负责层，例如 gateway、agent-runtime、permission-admin、python-runtime 或 worker
 * @param retryable 该阶段失败后是否可以自动重试；审批拒绝和权限拒绝通常不可自动重试
 * @param sideEffectBoundary 是否已经接近或进入副作用边界；越接近副作用，取消和重试越需要保守
 * @param description 阶段业务含义
 * @param observabilityRequirement 需要记录的低敏观测证据，避免问题只能靠日志排查
 * @param hiddenPayloads 禁止在该阶段的通用事件或公开响应中扩散的敏感内容
 */
public record AgentA2aTaskInternalPhaseView(
        String phaseCode,
        String mapsToA2aState,
        String responsibleLayer,
        boolean retryable,
        String sideEffectBoundary,
        String description,
        List<String> observabilityRequirement,
        List<String> hiddenPayloads
) {
}

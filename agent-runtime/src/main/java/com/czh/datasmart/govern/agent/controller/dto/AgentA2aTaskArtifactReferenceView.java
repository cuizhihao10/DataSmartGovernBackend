/**
 * @Author : Cui
 * @Date: 2026/06/06 13:03
 * @Description DataSmart Govern Backend - AgentA2aTaskArtifactReferenceView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import java.time.Instant;
import java.util.List;

/**
 * A2A Task artifact 引用视图。
 *
 * <p>artifact 是任务结果的重要部分，但事件和查询预览只能返回引用和低敏 metadata。正文应由受权限控制的
 * artifact 服务、对象存储或业务表提供，并在读取时再次校验租户、项目、workspace、角色和保留期。</p>
 *
 * @param artifactRef 受控 artifact 引用，不是内部存储路径
 * @param artifactType artifact 类型，例如 report-summary、quality-rule-draft、diagnostic-log-summary
 * @param a2aPartMode A2A Part 映射方式说明
 * @param available 当前是否可读取。preview 中仅表示契约可用
 * @param metadataOnly 是否仅返回 metadata
 * @param createdAt artifact metadata 创建时间
 * @param accessPolicy 访问策略摘要
 * @param retentionPolicy 保留期和归档策略摘要
 * @param description 低敏描述
 * @param linkedEventSequences 与哪些 task history 事件关联
 */
public record AgentA2aTaskArtifactReferenceView(
        String artifactRef,
        String artifactType,
        String a2aPartMode,
        boolean available,
        boolean metadataOnly,
        Instant createdAt,
        List<String> accessPolicy,
        List<String> retentionPolicy,
        String description,
        List<Long> linkedEventSequences
) {
}

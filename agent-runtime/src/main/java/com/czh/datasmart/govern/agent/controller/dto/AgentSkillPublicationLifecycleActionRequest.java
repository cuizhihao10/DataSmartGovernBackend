/**
 * @Author : Cui
 * @Date: 2026/06/30 23:16
 * @Description DataSmart Govern Backend - AgentSkillPublicationLifecycleActionRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import jakarta.validation.constraints.Size;

/**
 * Skill 发布生命周期操作请求。
 *
 * <p>提交审核、审核通过、审核拒绝和下线都使用这个轻量请求承载操作人和低敏原因。
 * 这里故意不支持上传附件、脚本、prompt 或错误堆栈，原因是发布生命周期链路应该保存“谁在什么时候基于什么治理理由
 * 做了什么决定”，而不是保存高敏实现内容。</p>
 *
 * @param operatorId 操作人 ID；生产环境应优先由 gateway/IdP 注入，当前保留请求体字段便于本地联调
 * @param comment 操作说明，必须是低敏摘要，不能放入 prompt、SQL、URL、凭据、工具参数或样本数据
 * @param approvalTicketId 外部审批单 ID 或工单 ID，仅保存低敏引用，不保存审批正文
 * @param releaseChannel 发布渠道，例如 INTERNAL、BETA、STABLE；当前只作为审计注释，后续可升级为灰度策略
 */
public record AgentSkillPublicationLifecycleActionRequest(
        @Size(max = 120)
        String operatorId,

        @Size(max = 220)
        String comment,

        @Size(max = 120)
        String approvalTicketId,

        @Size(max = 40)
        String releaseChannel
) {
}

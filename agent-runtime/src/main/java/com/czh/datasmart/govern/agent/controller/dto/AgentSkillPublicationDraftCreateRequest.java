/**
 * @Author : Cui
 * @Date: 2026/06/30 23:15
 * @Description DataSmart Govern Backend - AgentSkillPublicationDraftCreateRequest.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建 Agent Skill 发布草稿请求。
 *
 * <p>该请求只承载 Skill 发布市场需要治理的低敏元数据，不承载真正的 prompt、工具参数值、SQL、样本数据、
 * 模型输出、脚本正文或内部服务地址。真实 Skill 的执行细节应继续由受控工具目录、权限中心、版本化模板仓库
 * 或后续专门的 Skill artifact 存储管理。</p>
 *
 * <p>字段说明：</p>
 * <p>- skillCode/version：组成发布唯一键，运行时和审计侧用它定位能力版本；</p>
 * <p>- domain/riskLevel/approvalPolicy：决定发布审核强度和运行时可见性；</p>
 * <p>- requiredTools/requiredPermissions/memoryDependencies：用于能力市场、Python Runtime 和权限预检理解依赖边界；</p>
 * <p>- auditRequired/tenantScoped/projectScoped：商业化部署必须关注的合规与隔离声明。</p>
 */
public record AgentSkillPublicationDraftCreateRequest(
        @NotBlank
        @Size(max = 160)
        String skillCode,

        @NotBlank
        @Size(max = 80)
        String version,

        @NotBlank
        @Size(max = 160)
        String displayName,

        @Size(max = 360)
        String description,

        @Size(max = 80)
        String domain,

        @Size(max = 40)
        String riskLevel,

        @Size(max = 80)
        String approvalPolicy,

        Boolean auditRequired,

        Boolean tenantScoped,

        Boolean projectScoped,

        List<@Size(max = 160) String> requiredTools,

        List<@Size(max = 160) String> requiredPermissions,

        List<@Size(max = 80) String> memoryDependencies,

        @Size(max = 120)
        String operatorId,

        @Size(max = 80)
        String tenantId,

        @Size(max = 80)
        String projectId
) {
}

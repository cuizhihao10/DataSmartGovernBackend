/**
 * @Author : Cui
 * @Date: 2026/05/10 14:40
 * @Description DataSmart Govern Backend - QualityProjectVisibility.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.quality.service.support;

import java.util.List;

/**
 * data-quality 模块内使用的项目级可见范围描述。
 *
 * <p>这个 record 不是数据库实体，而是把 gateway/permission-admin 透传的权限上下文，
 * 翻译成质量规则、质量报告、异常明细查询都能复用的安全过滤模型。</p>
 *
 * <p>为什么 data-quality 也必须拥有自己的项目可见范围模型：</p>
 * <p>1. 质量规则通常由项目团队创建，例如“营销项目客户手机号完整性规则”；</p>
 * <p>2. 质量报告和异常样本可能暴露真实业务字段、主键、样本载荷或修复建议；</p>
 * <p>3. 如果只按 tenantId 隔离，项目 A 的成员可能看到项目 B 的异常记录，这是生产级数据治理产品无法接受的；</p>
 * <p>4. 后续质量大盘、清洗任务、审计导出和 AI 根因分析都会复用这些结果表，因此边界必须从底层数据模型开始建立。</p>
 *
 * @param requestedProjectId 调用方主动指定的 projectId；为空表示列表接口希望查看当前身份可见的所有项目
 * @param requestedWorkspaceId 调用方主动指定的 workspaceId；为空表示不按工作空间进一步收敛
 * @param authorizedProjectIds gateway 根据 permission-admin 结果透传的项目授权集合
 * @param projectScopeEnforced 当前请求是否必须按 PROJECT 范围强制过滤
 */
public record QualityProjectVisibility(Long requestedProjectId,
                                       Long requestedWorkspaceId,
                                       List<Long> authorizedProjectIds,
                                       boolean projectScopeEnforced) {
}

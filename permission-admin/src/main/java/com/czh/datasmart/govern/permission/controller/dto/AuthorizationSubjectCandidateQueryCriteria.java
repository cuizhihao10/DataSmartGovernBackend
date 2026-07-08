/**
 * @Author : Cui
 * @Date: 2026/07/09 01:42
 * @Description DataSmart Govern Backend - AuthorizationSubjectCandidateQueryCriteria.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.permission.controller.dto;

/**
 * 授权主体候选分页查询条件。
 *
 * <p>该 DTO 服务于“把某个资源授权给其他用户/角色”的通用弹窗场景，例如数据源授权、同步任务授权、
 * 质量规则授权、未来 Agent 工具授权等。它和账号注册请求不同：账号注册是高风险写操作，候选查询是低敏只读检索，
 * 目的是让前端不要让用户手工输入 actorId/roleCode，从而减少误授权、错授权和运维排查成本。</p>
 *
 * @param tenantId 目标租户 ID。平台管理员可以显式查询其他租户；非平台管理员只能查询自己所在租户。
 * @param projectId 目标项目 ID。用于把候选用户限制在项目成员内，避免把不属于当前项目的人授权到项目资源。
 * @param subjectType 主体类型。当前支持 USER 和 ROLE；SERVICE_ACCOUNT 后续可在服务账号管理面成熟后补充。
 * @param keyword 搜索关键字。USER 支持 username、email、actorRole、actorId；ROLE 支持 roleCode、roleName、description。
 * @param activeOnly 是否只返回可用主体。默认 true，避免把 DISABLED 用户或禁用角色展示给授权弹窗。
 * @param projectMembersOnly 是否只返回 projectId 下的项目成员。默认在传入 projectId 查询 USER 时启用。
 * @param current 当前页码，从 1 开始；为空或小于 1 时服务层会兜底为第 1 页。
 * @param size 每页大小；服务层会限制最大值，防止候选弹窗一次性拉取过多身份数据。
 */
public record AuthorizationSubjectCandidateQueryCriteria(Long tenantId,
                                                         Long projectId,
                                                         String subjectType,
                                                         String keyword,
                                                         Boolean activeOnly,
                                                         Boolean projectMembersOnly,
                                                         Long current,
                                                         Long size) {
}

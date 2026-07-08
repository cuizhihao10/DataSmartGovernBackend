/**
 * @Author : Cui
 * @Date: 2026/05/10 14:05
 * @Description DataSmart Govern Backend - DatasourceProjectVisibility.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.datasource.service.support;

import java.util.List;

/**
 * datasource-management 模块内的项目级可见范围描述。
 *
 * <p>这个 record 不直接对应数据库表，它是 Controller 把 gateway/permission-admin 透传的权限上下文
 * 翻译成 MyBatis 查询条件时使用的中间模型。之所以不让 Controller 直接解析 Header，是因为项目级数据范围
 * 属于安全边界：如果不同接口各自 split 字符串、各自理解空集合语义，很容易出现某个接口在
 * PROJECT 范围下“空授权集合退化成全租户可见”的严重越权问题。</p>
 *
 * @param requestedProjectId 调用方主动传入的 projectId；为空表示列表接口希望查看当前身份可见的所有项目
 * @param requestedWorkspaceId 历史兼容字段。当前产品层级已经收敛为租户 -> 项目 -> 数据源/同步任务，
 *                             因此正式页面不再用 workspaceId 过滤；该值默认保持为空，仅为旧脚本或历史审计保留扩展位。
 * @param authorizedProjectIds gateway 从 permission-admin 透传的授权项目集合
 * @param projectScopeEnforced 是否必须按 authorizedProjectIds 强制过滤；只有 Header 明确声明 PROJECT 时才为 true
 */
public record DatasourceProjectVisibility(Long requestedProjectId,
                                          Long requestedWorkspaceId,
                                          List<Long> authorizedProjectIds,
                                          boolean projectScopeEnforced) {
}

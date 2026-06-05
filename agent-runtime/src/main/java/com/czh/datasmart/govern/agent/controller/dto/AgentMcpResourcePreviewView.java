/**
 * @Author : Cui
 * @Date: 2026/06/06 01:19
 * @Description DataSmart Govern Backend - AgentMcpResourcePreviewView.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.controller.dto;

/**
 * MCP Resource 映射预览。
 *
 * <p>MCP Resource 用来让 Host/Client 发现可供模型上下文使用的数据资源，例如文件、数据库 schema、
 * 运行时诊断或应用内部信息。DataSmart 的资源往往包含租户数据、项目对象、任务状态、模型路由事件等，
 * 因此当前 preview 只暴露“资源目录元数据”，不暴露资源正文。真实 resources/read 必须等到权限、
 * 脱敏、审计、分页、订阅和缓存策略稳定之后再实现。</p>
 *
 * @param uri MCP resource URI 草案。使用 datasmart:// scheme 表示这是内部受控资源，不是可直接公网访问 URL
 * @param name 机器可读资源名，用于后续 resources/list 或资源目录搜索
 * @param title 人类可读标题，便于前端、运维台或外部 Agent 目录展示
 * @param description 资源说明，只描述资源类型和用途，不包含资源正文、样例数据、SQL 或业务明细
 * @param mimeType 未来真实读取时建议返回的 MIME 类型
 * @param readSupported 当前 preview 是否支持读取正文。当前为 false，避免误解为 resources/read 已落地
 * @param subscriptionSupported 当前是否支持资源变更订阅。当前多数资源通过 runtime event/timeline 观察
 * @param requiredPermission 读取该资源未来需要的权限标识，用于提前规划 permission-admin 对接
 * @param contentDisclosurePolicy 正文暴露策略，明确 preview 阶段只展示目录，不展示 text/blob/resource body
 */
public record AgentMcpResourcePreviewView(
        String uri,
        String name,
        String title,
        String description,
        String mimeType,
        Boolean readSupported,
        Boolean subscriptionSupported,
        String requiredPermission,
        String contentDisclosurePolicy
) {
}

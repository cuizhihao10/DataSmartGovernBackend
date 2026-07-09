/**
 * @Author : Cui
 * @Date: 2026/07/09 23:10
 * @Description DataSmart Govern Backend - PlatformAuthorizedProjectRole.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.common.context;

/**
 * 平台级项目授权角色快照。
 *
 * <p>这个 record 是 gateway、permission-admin 和业务服务之间共享的低敏权限事实：
 * 它只保存 projectId 与项目内角色，不包含用户姓名、邮箱、组织关系、Token、审批单详情或任何业务数据。
 * 这样既能让下游服务做项目级读写控制，又不会把权限中心内部表结构泄露给所有业务模块。</p>
 *
 * @param projectId 项目 ID。它必须是正整数，业务服务最终会用它约束 resource.project_id。
 * @param projectRole 项目内角色。当前推荐值为 OWNER、MANAGER、READER、SERVICE。
 */
public record PlatformAuthorizedProjectRole(Long projectId, String projectRole) {

    /**
     * 构造项目授权角色快照。
     *
     * <p>这里不做强制异常校验，是为了让 Header 解析保持“安全容错”：
     * 如果上游出现空角色或坏 projectId，解析工具会选择忽略或降权，而不是让业务请求直接 500。
     * 真正的授权强度判断仍由 permission-admin 和业务服务的校验方法完成。</p>
     */
    public PlatformAuthorizedProjectRole {
        projectRole = projectRole == null ? null : projectRole.trim().toUpperCase();
    }
}

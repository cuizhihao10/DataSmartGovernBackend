/**
 * @Author : Cui
 * @Date: 2026/07/02 04:10
 * @Description DataSmart Govern Backend - WorkspaceFileOperation.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

/**
 * Workspace 文件工具允许的最小操作集合。
 *
 * <p>当前只开放 READ/WRITE，删除、移动和权限变更必须在未来以独立高风险工具和审批策略接入。
 */
enum WorkspaceFileOperation {
    READ,
    WRITE
}

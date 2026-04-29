package com.czh.datasmart.govern.datasource.support;

/**
 * @Author : Cui
 * @Date: 2026/4/24 23:18
 * @Description DataSmart Govern Backend - SyncPermissionApprovalMode.java
 * @Version:1.0.0
 *
 * 权限变更申请审批模式枚举。
 * 这一层用于区分“当前审批是审批人本人直接审批”还是“审批人基于委托关系代批”。
 *
 * 之所以显式建模，而不是只在审计描述里拼接字符串，是因为：
 * 1. 后续管理界面会需要按审批模式筛选和追踪；
 * 2. 审计、风控、复盘时会关心这次审批到底是不是委托代批；
 * 3. 未来接企业级流程中心时，这个字段可以直接映射为审批来源类型。
 */
public enum SyncPermissionApprovalMode {
    DIRECT_ROLE,
    DELEGATED_ROLE
}

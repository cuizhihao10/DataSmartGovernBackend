/**
 * @Author : Cui
 * @Date: 2026/04/25 22:30
 * @Description DataSmart Govern Backend - PlatformAuditResult.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.common.audit;

/**
 * 平台审计结果。
 *
 * 审计事件不只记录“发生了操作”，也必须记录操作最终结果。
 * 例如审批通过、同步任务取消成功、权限修改被拒绝、导出失败等，都应该使用统一结果语义。
 */
public enum PlatformAuditResult {
    SUCCESS,
    FAILURE,
    REJECTED,
    SKIPPED
}

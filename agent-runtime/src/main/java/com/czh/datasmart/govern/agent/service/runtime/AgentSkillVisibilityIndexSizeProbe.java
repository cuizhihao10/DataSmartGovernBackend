/**
 * @Author : Cui
 * @Date: 2026/07/02 03:15
 * @Description DataSmart Govern Backend - AgentSkillVisibilityIndexSizeProbe.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.runtime;

/**
 * Skill 可见性快照索引大小探测结果。
 *
 * <p>MySQL Store 的 size 探测可能因数据库不可用而失败，诊断接口应返回“不可探测”的低敏状态，
 * 而不是让整个接口 500。error 只能保存截断后的异常摘要，不得携带 JDBC URL、凭据或 SQL。
 */
record AgentSkillVisibilityIndexSizeProbe(int currentIndexSize, String status, String error) {
}

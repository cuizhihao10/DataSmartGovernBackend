/**
 * @Author : Cui
 * @Date: 2026/08/05 00:00
 * @Description DataSmart Govern Backend - SpecialistTurnFactStore.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.specialist;

import java.util.List;

/**
 * 专业 Agent turn 事实存储端口。
 *
 * <p>把领域服务与 PostgreSQL JDBC 实现隔开，是为了让 Java 控制面可以在单元测试中使用
 * mock Store，也为未来迁移到分区表、事件存储或只读审计副本保留替换点。无论底层实现如何变化，
 * 这个端口都只接收已经完成低敏校验的 {@link SpecialistTurnFact}。</p>
 */
public interface SpecialistTurnFactStore {

    /**
     * 幂等保存一条专业 Agent turn 事实。
     *
     * <p>相同幂等键和相同不可变身份表示重试，Store 可以更新可变状态；相同幂等键但身份不同
     * 必须拒绝，避免攻击者借已存在的 key 覆盖其他用户的事实。</p>
     *
     * @param fact 已完成低敏校验的事实
     * @return 数据库最终保存的事实
     */
    SpecialistTurnFact save(SpecialistTurnFact fact);

    /**
     * 按会话读取事实。
     *
     * @param scope 已由 Service 根据当前调用者构造的租户/项目/操作者范围
     * @param sessionId 会话 ID
     * @param limit 最大返回数量
     * @return 按更新时间倒序排列的低敏事实
     */
    List<SpecialistTurnFact> findBySession(SpecialistTurnFact.QueryScope scope,
                                           String sessionId,
                                           int limit);

    /** 按 Run 读取事实，SQL 同样必须携带租户、项目和必要的用户范围。 */
    List<SpecialistTurnFact> findByRun(SpecialistTurnFact.QueryScope scope,
                                       String runId,
                                       int limit);
}

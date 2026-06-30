/**
 * @Author : Cui
 * @Date: 2026/06/30 23:10
 * @Description DataSmart Govern Backend - AgentSkillPublicationLifecycleStatus.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.service.skill;

/**
 * Agent Skill 发布生命周期状态。
 *
 * <p>该枚举描述的是“Skill 能力包作为平台商品/能力发布单”的状态，而不是某次 Agent 执行状态。
 * 一个 Skill 从创建到真正被 Python Runtime、智能网关或未来 MCP/A2A 适配层消费，至少要经过草稿、审核、
 * 发布和下线几个阶段。把状态显式枚举出来，可以避免后续用 enabled=true/false 这种单个布尔值承载过多语义。</p>
 *
 * <p>状态流转约束：</p>
 * <p>1. {@link #DRAFT}：创建者仍可补充治理元数据，不能进入运行时默认规划；</p>
 * <p>2. {@link #IN_REVIEW}：已经提交审核，等待平台管理员、租户管理员或审批系统确认；</p>
 * <p>3. {@link #READY}：审核通过，具备进入发布目录和运行时能力市场的资格；</p>
 * <p>4. {@link #REJECTED}：审核拒绝。为了保留审计证据，不直接覆盖原记录，通常应新建版本重新提交；</p>
 * <p>5. {@link #DEPRECATED}：曾经可用但已下线，适合灰度撤回、能力替换、事故止血或客户环境裁剪。</p>
 */
public enum AgentSkillPublicationLifecycleStatus {

    /**
     * 草稿态。只代表创建了低敏元数据，不代表可被模型或运行时使用。
     */
    DRAFT,

    /**
     * 审核中。此时发布单被冻结为待审事实，后续只能被审核通过或拒绝。
     */
    IN_REVIEW,

    /**
     * 已发布。该状态表示治理规则完整并通过审核，但真实运行前仍需要执行权限、工具 readiness 和会话准入。
     */
    READY,

    /**
     * 已拒绝。拒绝原因必须保持低敏摘要，不能写入 prompt、SQL、工具参数、样本数据或内部地址。
     */
    REJECTED,

    /**
     * 已废弃。下线后默认不应再进入运行时能力目录，但保留审计和回滚分析价值。
     */
    DEPRECATED
}

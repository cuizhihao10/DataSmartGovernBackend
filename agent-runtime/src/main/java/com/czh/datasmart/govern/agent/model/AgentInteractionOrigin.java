/**
 * @Author : Cui
 * @Date: 2026/08/05 00:00
 * @Description DataSmart Govern Backend - AgentInteractionOrigin.java
 * @Version:1.0.0
 */
package com.czh.datasmart.govern.agent.model;

/**
 * 一次 Agent Run 相对于用户会话的交互来源。
 *
 * <p>Run 与聊天消息不是同一个概念：一次用户目标可以产生多个模型规划、工具执行、审批等待、失败恢复和
 * Durable 续跑 Run，但只有用户真正发送自然语言时才应该新增一条 {@code USER} 消息。本枚举把这条边界写入
 * 跨运行时契约，避免通过“输入文本是否与上一条相同”推测来源。文本推测既会重复展示初始目标，也会误删用户
 * 有意重复发送的内容。</p>
 *
 * <p>所有来源都会进入 Run 变量和审计链；{@link #USER_MESSAGE} 是唯一会生成用户聊天气泡的来源。表单、审批
 * 和自动续跑通过 Run、审批事实、工具审计及前端过程卡展示，因此既保留生产审计能力，又保持 Codex 风格的
 * “一条用户消息对应一段连续处理过程”。</p>
 */
public enum AgentInteractionOrigin {

    /** 用户首次提交目标，或在会话输入框中发送了真正的自然语言追问、补充与纠偏。 */
    USER_MESSAGE(true),

    /** 用户填写缺失字段或保存高级配置；它是结构化动作事实，不是新的聊天发言。 */
    FORM_SUBMISSION(false),

    /** 用户点击同意、拒绝、采用修复建议或确认预览；审批事实由专门审计链保存。 */
    APPROVAL_DECISION(false),

    /** 模型收到工具结果后继续推理或生成下一批工具计划。 */
    AGENT_CONTINUATION(false),

    /** 失败诊断、名称冲突修复、预检查重试等由系统发起的受控恢复步骤。 */
    SYSTEM_RECOVERY(false),

    /** Durable/MCP/元数据自动补全等不等待新用户输入的确定性续跑。 */
    AUTOMATIC_CONTINUATION(false);

    private final boolean createsUserMessage;

    AgentInteractionOrigin(boolean createsUserMessage) {
        this.createsUserMessage = createsUserMessage;
    }

    /**
     * 判断该来源是否代表一条真实用户自然语言消息。
     *
     * @return 仅 {@link #USER_MESSAGE} 返回 {@code true}
     */
    public boolean createsUserMessage() {
        return createsUserMessage;
    }
}

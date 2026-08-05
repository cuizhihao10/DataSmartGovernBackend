-- Agent Run 与用户聊天消息必须使用不同的生命周期语义。
--
-- 历史版本在每次 Python AgentPlan 接入时都把 user_input_preview 写成 USER 消息。表单提交、审批后续跑、
-- 模型工具循环和系统恢复通常沿用 session.objective，因此一个用户目标会在历史页重复出现多次。本迁移只修复
-- 这一种可以稳定识别的旧数据：同一 session 内，与初始 objective 完全相同的 USER 消息仅保留最早一条。
-- 不同文本的 USER 消息始终视为真实追问，不做模糊去重。
--
-- 新版本不再依赖该启发式规则：Python -> Java 契约显式传 interactionOrigin，Java 将其写入 agent_run.variables，
-- 并且只有 USER_MESSAGE 会创建 USER 消息。迁移为旧 Run 回填来源，便于前端以配置动作/自动续跑而非聊天气泡
-- 回放历史。所有 Run、AGENT 消息、工具审计、审批事实和执行结果都被完整保留。

WITH ranked_user_messages AS (
    SELECT
        message.message_id,
        message.run_id,
        message.content,
        session.objective,
        ROW_NUMBER() OVER (
            PARTITION BY message.session_id
            ORDER BY message.create_time, message.id
        ) AS user_message_sequence
    FROM agent_conversation_message message
    JOIN agent_session session ON session.session_id = message.session_id
    WHERE message.role = 'USER'
)
UPDATE agent_run run
SET variables = jsonb_set(
        COALESCE(run.variables, '{}'::jsonb),
        '{interactionOrigin}',
        '"USER_MESSAGE"'::jsonb,
        true
    )
FROM ranked_user_messages message
WHERE message.run_id = run.run_id
  AND NOT (COALESCE(run.variables, '{}'::jsonb) ? 'interactionOrigin')
  AND (
      message.content IS DISTINCT FROM message.objective
      OR message.user_message_sequence = 1
  );

WITH ranked_user_messages AS (
    SELECT
        message.message_id,
        message.run_id,
        message.content,
        session.objective,
        ROW_NUMBER() OVER (
            PARTITION BY message.session_id
            ORDER BY message.create_time, message.id
        ) AS user_message_sequence
    FROM agent_conversation_message message
    JOIN agent_session session ON session.session_id = message.session_id
    WHERE message.role = 'USER'
)
UPDATE agent_run run
SET variables = jsonb_set(
        COALESCE(run.variables, '{}'::jsonb),
        '{interactionOrigin}',
        CASE
            WHEN COALESCE(run.variables ->> 'pythonRequestId', '') LIKE 'loop-%'
                 OR COALESCE(run.variables -> 'stateTrace', '[]'::jsonb) @> '["resume_model_tool_loop"]'::jsonb
                THEN '"AUTOMATIC_CONTINUATION"'::jsonb
            ELSE '"AGENT_CONTINUATION"'::jsonb
        END,
        true
    )
FROM ranked_user_messages message
WHERE message.run_id = run.run_id
  AND NOT (COALESCE(run.variables, '{}'::jsonb) ? 'interactionOrigin')
  AND message.content IS NOT DISTINCT FROM message.objective
  AND message.user_message_sequence > 1;

-- 删除被明确归类为非用户消息来源的旧 USER 气泡。关联 Run 和全部执行证据保持不变。
DELETE FROM agent_conversation_message message
USING agent_run run
WHERE message.run_id = run.run_id
  AND message.role = 'USER'
  AND COALESCE(run.variables ->> 'interactionOrigin', '') IN (
      'FORM_SUBMISSION',
      'APPROVAL_DECISION',
      'AGENT_CONTINUATION',
      'SYSTEM_RECOVERY',
      'AUTOMATIC_CONTINUATION'
  );

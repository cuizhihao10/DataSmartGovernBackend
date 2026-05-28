-- DataSmart Govern Backend - agent-runtime 工具执行审计表
--
-- 设计目的：
-- 1. 把 Agent 工具调用从 JVM 内存热窗口升级为可恢复、可审计、可回放的系统事实。
-- 2. 支撑 Codex / Claude Code 类 Agent 必需的工具生命周期：计划、等待审批、审批通过、拒绝、执行中、成功、失败、跳过。
-- 3. 为 Python AI Runtime 的二轮推理、Gateway WebSocket replay、前端详情页、审计中心和长期记忆写入提供可信来源。
-- 4. 为后续“工具审计状态更新 + tool event outbox INSERT”同事务提交打基础，逐步补齐生产级可靠性。
--
-- 当前 Java 代码已经抽象 AgentToolExecutionAuditStore，并默认使用 memory 实现。
-- 该表先落地生产目标结构，后续 MySQL store 接入时应优先复用此表，而不是重新设计第二套审计模型。

CREATE TABLE IF NOT EXISTS agent_tool_execution_audit (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键，仅用于表内排序、分页和运维定位，不作为跨服务业务 ID',
    audit_id VARCHAR(128) NOT NULL COMMENT '工具执行审计业务主键，对应 AgentToolExecutionAuditRecord.auditId',
    session_id VARCHAR(128) NOT NULL COMMENT 'Agent 会话 ID，用于把工具调用归属到一次用户会话',
    run_id VARCHAR(128) NOT NULL COMMENT 'Agent Run ID，用于把多次工具调用归属到一次模型规划/执行过程',
    binding_id VARCHAR(160) NOT NULL COMMENT '工具绑定或 Python ToolPlan 派生 ID，用于关联工具目录、会话绑定和模型计划快照',
    tool_code VARCHAR(128) NOT NULL COMMENT '工具编码，例如 datasource.metadata.read、quality.rule.suggest、task.draft.persist',
    tool_type VARCHAR(80) NOT NULL COMMENT '工具类型，例如 DATASOURCE_METADATA、DATA_QUALITY、TASK_MANAGEMENT',
    target_service VARCHAR(128) DEFAULT NULL COMMENT '工具目标微服务，例如 datasource-management、data-quality、task-management',
    target_endpoint VARCHAR(300) DEFAULT NULL COMMENT '工具目标端点模板或实际端点，用于审计模型计划最终会触达哪个业务能力',
    target_resource_id BIGINT DEFAULT NULL COMMENT '目标资源 ID，例如 datasourceId、taskId；为空表示该工具不绑定单个资源',
    tenant_id BIGINT DEFAULT NULL COMMENT '租户 ID；即使当前多租户尚未完全实现，也要提前保留隔离字段',
    project_id BIGINT DEFAULT NULL COMMENT '项目 ID，用于项目级权限、数据范围过滤和审计检索',
    workspace_id BIGINT DEFAULT NULL COMMENT 'Agent 工作空间 ID，用于后续类 OpenClaw 工作区隔离和产物追踪',
    actor_id VARCHAR(128) DEFAULT NULL COMMENT '触发该工具计划的用户、服务账号或 Agent 身份',
    risk_level VARCHAR(64) NOT NULL COMMENT '工具风险等级：LOW/MEDIUM/HIGH/CRITICAL，影响审批和执行策略',
    execution_mode VARCHAR(80) NOT NULL COMMENT '执行模式：SYNC/DRAFT_ONLY/APPROVAL_REQUIRED 等，用于区分只读、草稿和高风险动作',
    requires_approval TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否显式要求人工审批；HIGH/CRITICAL 风险也应进入审批闸门',
    read_only TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否只读工具；非只读工具通常需要更严格的权限、幂等和审批控制',
    idempotent TINYINT(1) NOT NULL DEFAULT 0 COMMENT '工具是否幂等；影响失败重试、重复提交保护和补偿策略',
    allowed_actions JSON NOT NULL COMMENT '该工具允许的动作集合，例如 VIEW/GENERATE/CREATE；JSON 便于后续扩展多动作策略',
    plan_reason TEXT DEFAULT NULL COMMENT '模型或编排器选择该工具的原因，用于人工审批和复盘，不应写入超大上下文',
    plan_arguments JSON DEFAULT NULL COMMENT '模型计划的工具参数；生产环境应脱敏或限制敏感字段，完整大对象应进入对象存储引用',
    governance_hints JSON DEFAULT NULL COMMENT '治理提示，例如记忆写入策略、敏感字段标记、缓存策略、审批策略',
    parameter_validation JSON DEFAULT NULL COMMENT '参数校验摘要，例如缺失字段、系统补全字段、用户必须补充字段',
    state VARCHAR(64) NOT NULL COMMENT '工具执行状态：PLANNED/WAITING_APPROVAL/EXECUTING/SUCCEEDED/FAILED/SKIPPED/CANCELLED',
    trace_id VARCHAR(128) DEFAULT NULL COMMENT '跨 Python Runtime、Java 控制面、Gateway 和下游工具调用的链路追踪 ID',
    message VARCHAR(1024) DEFAULT NULL COMMENT '面向前端、审批页或诊断页展示的状态说明',
    approval_operator_id VARCHAR(128) DEFAULT NULL COMMENT '人工审批或拒绝的操作者 ID',
    approval_comment VARCHAR(1024) DEFAULT NULL COMMENT '人工审批说明或拒绝原因；生产环境展示时应按权限脱敏',
    approval_time DATETIME(3) DEFAULT NULL COMMENT '审批或拒绝发生时间',
    execution_start_time DATETIME(3) DEFAULT NULL COMMENT '工具进入 EXECUTING 的时间',
    execution_finish_time DATETIME(3) DEFAULT NULL COMMENT '工具进入终态的时间',
    output_summary VARCHAR(2048) DEFAULT NULL COMMENT '工具输出摘要；不应写入完整结果、样本数据或敏感明细',
    error_code VARCHAR(128) DEFAULT NULL COMMENT '失败错误码，用于告警聚合、失败分类和运营统计',
    create_time DATETIME(3) NOT NULL COMMENT '审计记录创建时间，通常等于工具计划进入 Java 控制面的时间',
    update_time DATETIME(3) NOT NULL COMMENT '审计记录最近一次状态或内容更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_tool_execution_audit_audit_id (audit_id),
    KEY idx_agent_tool_execution_audit_run_state (session_id, run_id, state, id),
    KEY idx_agent_tool_execution_audit_scope_state (tenant_id, project_id, state, update_time),
    KEY idx_agent_tool_execution_audit_tool_state (tool_code, state, update_time),
    KEY idx_agent_tool_execution_audit_trace (trace_id),
    KEY idx_agent_tool_execution_audit_approval (requires_approval, state, approval_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='agent-runtime 工具执行审计表';

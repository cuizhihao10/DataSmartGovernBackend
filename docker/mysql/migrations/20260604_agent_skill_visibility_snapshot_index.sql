-- ---------------------------------------------------------------------------
-- agent-runtime：Skill 可见性快照专用索引
-- ---------------------------------------------------------------------------
-- 背景：
-- 1. Python Runtime 会在 /agent/plans 阶段生成 skillVisibility，并发布
--    skill_visibility_snapshot_recorded runtime event；
-- 2. Java agent-runtime 6.15/6.16 已经可以把该事件转成强类型控制面视图；
-- 3. 6.17 又抽出了专用索引端口和内存物化实现，但内存实现无法跨服务重启、多实例和长期审计；
-- 4. 本表用于承接 MySQL 版 AgentSkillVisibilitySnapshotIndexStore，让 Skill 可见性事实可以恢复、导出、
--    灰度排查和长期聚合。
--
-- 安全边界：
-- 1. 本表只保存低敏聚合事实，例如可见/隐藏数量、Skill code 摘要、Manifest 指纹、权限事实来源和状态计数；
-- 2. 不保存 prompt、模型推理全文、SQL、工具参数、连接密钥、样本数据、完整权限明细或长期记忆正文；
-- 3. attributes_json 是兼容载体，不应被滥用为敏感明细仓库。未来如果要排查敏感明细，应走受控审计详情接口。
--
-- 性能与演进：
-- 1. 查询主路径按 tenant/project/run/session/request/replay_sequence 过滤，因此建立组合索引；
-- 2. Manifest 指纹、绑定状态、权限事实来源后续会用于 Skill Marketplace、灰度回滚和策略漂移报表，因此独立建索引；
-- 3. 如果未来单租户快照量进入千万级，应继续演进为按时间/月分区，或把运营报表迁移到 ClickHouse/OpenSearch。
-- ---------------------------------------------------------------------------

USE datasmart_govern;

CREATE TABLE IF NOT EXISTS agent_skill_visibility_snapshot_index (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '数据库自增主键，仅用于表内排序、分页和运维定位',
    identity_key VARCHAR(512) NOT NULL COMMENT 'runtime event 幂等键；由 Java consumer 生成，用唯一索引拦截 Kafka 重复消费和人工补偿重放',
    schema_version VARCHAR(80) DEFAULT NULL COMMENT 'runtime event schemaVersion，例如 agent-runtime-event.v1',
    source VARCHAR(128) DEFAULT NULL COMMENT '事件来源，例如 python-ai-runtime，用于区分未来多个 Runtime 或沙箱执行器',
    event_type VARCHAR(128) NOT NULL COMMENT '固定为 skill_visibility_snapshot_recorded；保留列是为了防止未来误把通用事件写入本表',
    stage VARCHAR(128) DEFAULT NULL COMMENT '事件阶段，例如 skill_visibility；用于运行时间线解释',
    message VARCHAR(1024) DEFAULT NULL COMMENT '低敏展示消息，不应包含 prompt、SQL、工具参数或样本数据',
    severity VARCHAR(32) DEFAULT 'info' COMMENT '事件级别：info/warn/error 等，用于治理页筛选和告警聚合',
    tenant_id VARCHAR(128) DEFAULT NULL COMMENT '租户边界快照；用字符串保留兼容性，避免早期租户 ID 类型尚未完全统一时阻塞索引',
    project_id VARCHAR(128) DEFAULT NULL COMMENT '项目边界快照；PROJECT 数据范围查询会按该列收口',
    actor_id VARCHAR(128) DEFAULT NULL COMMENT '发起用户、服务账号或 Agent 身份快照',
    request_id VARCHAR(128) DEFAULT NULL COMMENT 'HTTP/Gateway 请求 ID，用于把一次 /agent/plans 请求与快照串联',
    run_id VARCHAR(128) DEFAULT NULL COMMENT 'Agent Run ID，用于运行详情页和会话内多轮快照聚合',
    session_id VARCHAR(128) DEFAULT NULL COMMENT 'Agent 会话 ID，用于 WebSocket 断线重连、会话治理和用户侧排查',
    producer_sequence BIGINT DEFAULT NULL COMMENT 'Python Runtime 生产方局部事件序号，不作为 Java 控制面全局 replay 游标',
    replay_sequence BIGINT DEFAULT NULL COMMENT 'Java 控制面写入 projection 后分配的稳定 replay 游标，用于增量查询和断线续传',
    created_at DATETIME(3) DEFAULT NULL COMMENT '事件在 Python Runtime 侧创建时间',
    published_at DATETIME(3) DEFAULT NULL COMMENT '事件在 Python Runtime 侧发布到 Kafka/内存 publisher 的时间',
    consumed_at DATETIME(3) DEFAULT NULL COMMENT 'Java consumer 消费并写入 projection 的时间',
    snapshot_type VARCHAR(80) DEFAULT NULL COMMENT '快照类型，例如 session_skill_visibility，用于区分未来任务级、租户级或 Marketplace 级快照',
    snapshot_source VARCHAR(128) DEFAULT NULL COMMENT '快照来源，例如 /agent/plans 响应构建器或后台刷新任务',
    available TINYINT(1) NOT NULL DEFAULT 0 COMMENT '本次快照是否成功得到可用 Skill 可见性结果',
    available_skill_count INT NOT NULL DEFAULT 0 COMMENT '候选可用 Skill 数量，通常来自 READY descriptor 与策略过滤后的集合',
    visible_skill_count INT NOT NULL DEFAULT 0 COMMENT '最终对当前会话可见的 Skill 数量',
    hidden_skill_count INT NOT NULL DEFAULT 0 COMMENT '因权限、套餐、风险、Manifest 状态等原因隐藏的 Skill 数量',
    conditional_visible_skill_count INT NOT NULL DEFAULT 0 COMMENT '条件性可见 Skill 数量，例如需要审批、预算或二次确认后才可调用',
    permission_fact_source VARCHAR(80) DEFAULT NULL COMMENT '权限事实来源，例如 gateway-header、remote-permission-admin、fallback，用于策略漂移排查',
    actor_role_source VARCHAR(80) DEFAULT NULL COMMENT 'actorRole 的来源，例如 gateway-header、trusted-context 或 local-default',
    actor_role VARCHAR(80) DEFAULT NULL COMMENT '发起人在本次快照中的角色快照，例如 BASIC/OPERATOR/ADMIN',
    granted_permission_count INT NOT NULL DEFAULT 0 COMMENT '授权权限数量摘要，不保存完整权限清单',
    tenant_skill_enabled TINYINT(1) NOT NULL DEFAULT 0 COMMENT '租户是否整体启用 Skill 能力包',
    workspace_risk_level VARCHAR(64) DEFAULT NULL COMMENT 'workspace 风险等级快照，用于解释高风险空间下的能力收缩',
    tenant_plan_code VARCHAR(80) DEFAULT NULL COMMENT '租户套餐/能力包编码快照，用于后续商业化套餐统计',
    policy_version VARCHAR(128) DEFAULT NULL COMMENT '权限策略版本摘要，用于排查权限策略漂移，不保存完整策略正文',
    legacy_request_variables_detected TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否检测到旧版 requestVariables，帮助定位兼容路径',
    model_gateway_available TINYINT(1) NOT NULL DEFAULT 0 COMMENT '模型网关在本次规划中是否可用',
    tool_budget_allowed TINYINT(1) NOT NULL DEFAULT 0 COMMENT '工具预算策略是否允许本次会话调用工具',
    manifest_binding_status VARCHAR(80) DEFAULT 'UNBOUND_UNKNOWN' COMMENT 'Skill Publication Manifest 绑定状态，例如 BOUND_REMOTE_MANIFEST',
    manifest_status VARCHAR(80) DEFAULT 'UNKNOWN' COMMENT 'Manifest 服务状态，例如 READY、REMOTE_UNAVAILABLE',
    manifest_source VARCHAR(128) DEFAULT 'unknown' COMMENT 'Manifest 来源，例如 agent-runtime-remote 或 local-fallback',
    manifest_fingerprint VARCHAR(160) DEFAULT NULL COMMENT 'Manifest contentFingerprint，用于能力目录版本追踪、灰度、回滚和事故复盘',
    manifest_schema_version VARCHAR(80) DEFAULT NULL COMMENT 'Manifest schemaVersion',
    manifest_skill_count INT NOT NULL DEFAULT 0 COMMENT 'Manifest 中 Skill 总量摘要',
    manifest_ready_skill_count INT NOT NULL DEFAULT 0 COMMENT 'Manifest 中 READY Skill 数量摘要',
    manifest_non_ready_skill_count INT NOT NULL DEFAULT 0 COMMENT 'Manifest 中非 READY Skill 数量摘要',
    manifest_fallback TINYINT(1) NOT NULL DEFAULT 0 COMMENT '本次 Manifest 是否来自 fallback 路径',
    visible_skill_codes_json JSON NOT NULL COMMENT '可见 Skill code 摘要列表；只保存 code，不保存描述、参数、prompt 或策略明细',
    visible_skill_codes_truncated_count INT NOT NULL DEFAULT 0 COMMENT '可见 Skill code 因长度限制被截断的数量',
    hidden_skill_codes_json JSON NOT NULL COMMENT '隐藏 Skill code 摘要列表；仅用于治理解释和调试，不是完整权限事实',
    hidden_skill_codes_truncated_count INT NOT NULL DEFAULT 0 COMMENT '隐藏 Skill code 因长度限制被截断的数量',
    visible_risk_level_counts_json JSON NOT NULL COMMENT '可见 Skill 按风险等级的数量分布，例如 LOW/MEDIUM/HIGH',
    visible_domain_counts_json JSON NOT NULL COMMENT '可见 Skill 按业务域的数量分布，例如 datasource/data-quality/task-management',
    hidden_admission_status_counts_json JSON NOT NULL COMMENT '隐藏 Skill 按准入状态的数量分布，例如 PERMISSION_DENIED/PLAN_DISABLED',
    display_summary VARCHAR(2048) DEFAULT NULL COMMENT '低敏展示摘要，用于前端治理卡片快速解释本次 Skill 可见性状态',
    recommended_action_count INT NOT NULL DEFAULT 0 COMMENT '推荐治理动作数量摘要，不保存完整动作明细',
    attributes_json JSON NOT NULL COMMENT '原始低敏 attributes 快照，用于字段演进兼容；禁止写入敏感业务正文',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '索引记录创建时间，通常等于 Java consumer 首次物化时间',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '索引记录更新时间；幂等重复写入不会修改业务事实',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_skill_visibility_identity_key (identity_key),
    KEY idx_agent_skill_visibility_run_replay (tenant_id, project_id, run_id, replay_sequence),
    KEY idx_agent_skill_visibility_session_replay (tenant_id, project_id, session_id, replay_sequence),
    KEY idx_agent_skill_visibility_request (request_id),
    KEY idx_agent_skill_visibility_actor_time (tenant_id, project_id, actor_id, create_time),
    KEY idx_agent_skill_visibility_manifest (manifest_fingerprint, manifest_binding_status, create_time),
    KEY idx_agent_skill_visibility_policy_source (permission_fact_source, policy_version, create_time),
    KEY idx_agent_skill_visibility_severity_time (severity, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='agent-runtime Skill 可见性快照专用索引表';

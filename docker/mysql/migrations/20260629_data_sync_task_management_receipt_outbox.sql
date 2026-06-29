-- @Author : Cui
-- @Date: 2026/06/29 19:34
-- @Description DataSmart Govern Backend - data-sync task-management receipt outbox
-- @Version:1.0.0

-- ---------------------------------------------------------------------------
-- data-sync task-management receipt outbox
-- ---------------------------------------------------------------------------
-- 背景：
-- 1. data-sync 已经可以在 execution complete/fail 后向 task-management 投递 execution receipt；
-- 2. 但单次 HTTP 投递无法抵抗 task-management 重启、网络抖动、网关策略切换或 data-sync 进程崩溃；
-- 3. 本表把低敏 receipt 请求和投递状态持久化，形成 PENDING -> DELIVERING -> DELIVERED/RETRY_WAIT/DEAD_LETTER 的最终一致链路。
--
-- 安全边界：
-- 1. payload_json 只保存 TaskManagementExecutionReceiptRequest 的低敏字段；
-- 2. 禁止保存 SQL、字段映射正文、过滤条件、连接串、凭据、checkpoint 原始值、失败行样本、prompt、模型输出、内部 URL 或远端响应正文；
-- 3. last_error_summary 只保存标准化错误码、attempt 和下一状态，不保存异常 message 或响应体。

CREATE TABLE IF NOT EXISTS data_sync_task_management_receipt_outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'data-sync 投递 task-management receipt 的 outbox 主键，仅用于表内排序、分页和并发状态推进',
    receipt_id VARCHAR(180) NOT NULL COMMENT '稳定幂等 receiptId；同一 execution 的同一事件只能创建一条 outbox，重复投递必须复用',
    tenant_id BIGINT NOT NULL DEFAULT 0 COMMENT '租户 ID，冗余自同步任务，用于租户隔离、租户级补偿统计和后续保留期清理',
    project_id BIGINT COMMENT '项目 ID，冗余自同步任务，用于项目级运营视图和故障复盘',
    workspace_id BIGINT COMMENT '工作空间 ID，冗余自同步任务，用于空间级筛选和多团队协作排障',
    sync_task_id BIGINT NOT NULL COMMENT 'data-sync 同步任务 ID',
    sync_execution_id BIGINT NOT NULL COMMENT 'data-sync 执行记录 ID',
    event_type VARCHAR(32) NOT NULL COMMENT 'receipt 事件类型：COMPLETE、FAILED，后续可扩展 PROGRESS、CHECKPOINT',
    source_service VARCHAR(128) NOT NULL DEFAULT 'data-sync' COMMENT '来源服务名；当前为 data-sync，用于 task-management 识别投影来源',
    outbox_state VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '投递状态：PENDING、DELIVERING、RETRY_WAIT、DELIVERED、DEAD_LETTER',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '当前已尝试投递次数；进入 DELIVERING 时递增',
    max_attempt_count INT NOT NULL DEFAULT 6 COMMENT '最大投递次数；创建时从配置快照写入，达到后进入 DEAD_LETTER',
    next_retry_at DATETIME COMMENT '下一次允许重试时间；为空表示可立即投递或不再重试',
    last_attempt_at DATETIME COMMENT '最近一次开始投递时间；用于识别卡在 DELIVERING 的崩溃残留',
    delivered_at DATETIME COMMENT 'task-management 已确认接收的时间',
    dead_letter_at DATETIME COMMENT '进入 DEAD_LETTER 的时间',
    last_error_code VARCHAR(128) COMMENT '最近一次失败错误码，只允许枚举或标准码，不保存异常正文',
    last_error_summary VARCHAR(512) COMMENT '最近一次失败摘要，只保存低敏短说明，不保存 URL、请求体、响应体、SQL 或样本',
    actor_id BIGINT COMMENT '投递时使用的 actorId；后台补偿可使用平台服务账号',
    actor_role VARCHAR(64) COMMENT '投递时使用的角色，通常为 SERVICE_ACCOUNT',
    trace_id VARCHAR(128) COMMENT '链路追踪 ID，只保存低敏 trace 标识',
    payload_json JSON NOT NULL COMMENT '低敏 receipt 请求 JSON；禁止包含 SQL、连接串、凭据、checkpoint 原始值、样本数据、prompt、模型输出或内部 URL',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    UNIQUE KEY uk_data_sync_task_receipt_outbox_receipt (receipt_id),
    INDEX idx_data_sync_task_receipt_outbox_state_retry (outbox_state, next_retry_at, id),
    INDEX idx_data_sync_task_receipt_outbox_delivering (outbox_state, last_attempt_at, id),
    INDEX idx_data_sync_task_receipt_outbox_execution (sync_execution_id, event_type),
    INDEX idx_data_sync_task_receipt_outbox_task (sync_task_id, event_type, create_time),
    INDEX idx_data_sync_task_receipt_outbox_scope_state (tenant_id, project_id, workspace_id, outbox_state, update_time),
    INDEX idx_data_sync_task_receipt_outbox_dead_letter (outbox_state, dead_letter_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='data-sync 到 task-management execution receipt 的可靠投递 outbox 表';

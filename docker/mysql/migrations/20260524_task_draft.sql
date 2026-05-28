-- ---------------------------------------------------------------------------
-- task-management：任务草稿表
-- ---------------------------------------------------------------------------
-- 背景：
-- Agent Runtime 已经可以生成 task.create.draft 草稿，但如果直接调用 /tasks，
-- task-management 会创建 PENDING 任务并进入执行器可认领队列。
-- 因此这里新增 task_draft，作为“草稿/审批/真实任务转换”的缓冲层。
-- ---------------------------------------------------------------------------

USE datasmart_govern;

CREATE TABLE IF NOT EXISTS task_draft (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '任务草稿主键；草稿不进入执行器认领队列',
    name VARCHAR(255) NOT NULL COMMENT '草稿名称，转换真实任务时通常作为 task.name',
    description TEXT COMMENT '草稿说明，描述目标、风险、审批依据和执行建议',
    type VARCHAR(50) NOT NULL COMMENT '目标任务类型，例如 DATA_QUALITY_SCAN、DATA_SYNC、MANUAL_REVIEW',
    tenant_id BIGINT COMMENT '草稿所属租户 ID，用于多租户隔离和审批范围收口',
    owner_id BIGINT COMMENT '草稿负责人 ID，转换真实任务时默认写入 task.owner_id',
    project_id BIGINT COMMENT '草稿所属项目 ID，用于 PROJECT 数据范围、项目审批和项目级队列治理',
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT '草稿状态：DRAFT、PENDING_APPROVAL、APPROVED、CONVERTING、REJECTED、CONVERTED；CONVERTING 用于并发转换门闩',
    params TEXT COMMENT '草稿参数 JSON；转换真实任务前仍需按任务类型做 schema 校验',
    priority VARCHAR(10) DEFAULT 'MEDIUM' COMMENT '建议任务优先级，转换时写入 task.priority',
    max_retry_count INT NOT NULL DEFAULT 3 COMMENT '建议最大重试次数，转换时写入 task.max_retry_count',
    max_defer_count INT NOT NULL DEFAULT 20 COMMENT '建议最大连续延迟次数，转换时写入 task.max_defer_count',
    source_type VARCHAR(32) DEFAULT 'MANUAL' COMMENT '草稿来源类型，例如 AGENT、TEMPLATE、MANUAL、API',
    source_ref VARCHAR(255) COMMENT '来源引用，例如 Agent auditId、任务模板 ID 或外部工单号',
    created_by BIGINT COMMENT '创建人 ID',
    submitted_by BIGINT COMMENT '提交审批人 ID',
    approved_by BIGINT COMMENT '审批人 ID',
    approval_comment TEXT COMMENT '提交、审批或拒绝说明',
    converted_task_id BIGINT COMMENT '转换得到的真实任务 ID；为空表示尚未转换',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '最后更新时间',
    submit_time DATETIME COMMENT '提交审批时间',
    approval_time DATETIME COMMENT '审批时间',
    convert_time DATETIME COMMENT '转换真实任务时间',
    INDEX idx_task_draft_status_time (status, create_time),
    INDEX idx_task_draft_tenant_status_time (tenant_id, status, create_time),
    INDEX idx_task_draft_project_status_time (project_id, status, create_time),
    INDEX idx_task_draft_owner_status_time (owner_id, status, create_time),
    INDEX idx_task_draft_source (source_type, source_ref),
    INDEX idx_task_draft_converted_task (converted_task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务草稿表；用于 Agent/模板/人工配置生成待审批任务';

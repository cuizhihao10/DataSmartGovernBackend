-- data-sync：租户 / 应用 / 项目三层隔离下的 workspace 兼容清理。
--
-- 业务背景：
-- 1. 当前 FlashSync 用户侧产品模型已经收敛为“租户 -> 应用 -> 项目 -> 数据源/同步任务”。
--    租户表示客户公司，应用表示 FlashSync 这个产品能力，项目表示客户在 FlashSync 下的业务隔离单元。
-- 2. workspace 不再是普通用户页面需要选择、筛选或理解的层级。继续让同步任务、模板、任务分组按 workspace
--    参与聚合，会造成前端出现多个“默认分组”、任务列表与分组计数不一致、跨项目/跨用户筛选残留等问题。
-- 3. 本迁移不物理删除 workspace_id 列。原因是历史执行、审计、Agent 内部工作区和旧迁移脚本仍可能读取该字段；
--    商业化系统升级时，优先做“语义下线 + 数据清理 + 注释说明”，等所有运行证据和兼容脚本收敛后再评估删列。
-- 4. 本迁移只清理用户侧主对象：模板、任务、任务分组。执行、日志、审计、checkpoint、错误样本等历史事实
--    保留 workspace 快照，仅通过注释明确它们不再作为普通页面的数据范围。

SET search_path TO data_sync, public;

-- 任务分组曾经按 workspace 建树，因此同一项目下可能有多条 DEFAULT。
-- 如果直接把 workspace_id 置空，V9 中的唯一索引 uk_data_sync_task_group_scope_code 会冲突。
-- 这里先保留每个 tenant/project/group_code 下最优的一条项目级分组，其他同名 workspace 分组改成归档历史分组；
-- 历史任务仍保存 group_code=DEFAULT，清理 workspace 后会自然归属保留下来的项目级 DEFAULT 分组。
WITH ranked_groups AS (
    SELECT
        id,
        ROW_NUMBER() OVER (
            PARTITION BY tenant_id, COALESCE(project_id, -1), group_code
            ORDER BY
                CASE WHEN workspace_id IS NULL THEN 0 ELSE 1 END,
                CASE WHEN default_group THEN 0 ELSE 1 END,
                display_order ASC,
                id ASC
        ) AS keep_rank
    FROM data_sync_task_group
    WHERE workspace_id IS NOT NULL
       OR EXISTS (
           SELECT 1
           FROM data_sync_task_group duplicated
           WHERE duplicated.id <> data_sync_task_group.id
             AND duplicated.tenant_id = data_sync_task_group.tenant_id
             AND COALESCE(duplicated.project_id, -1) = COALESCE(data_sync_task_group.project_id, -1)
             AND duplicated.group_code = data_sync_task_group.group_code
       )
)
UPDATE data_sync_task_group target
SET
    group_code = CASE
        WHEN ranked_groups.keep_rank = 1 THEN target.group_code
        ELSE LEFT(target.group_code, 30) || '_LEGACY_' || target.id::TEXT
    END,
    group_name = CASE
        WHEN ranked_groups.keep_rank = 1 THEN target.group_name
        ELSE LEFT(target.group_name || '（历史工作空间分组）', 128)
    END,
    description = CASE
        WHEN ranked_groups.keep_rank = 1 THEN target.description
        ELSE LEFT(COALESCE(target.description, '') || '；已在项目级隔离收敛迁移中归档，原 workspaceId=' || COALESCE(target.workspace_id::TEXT, 'NULL'), 500)
    END,
    archived = CASE WHEN ranked_groups.keep_rank = 1 THEN target.archived ELSE TRUE END,
    default_group = CASE WHEN ranked_groups.keep_rank = 1 THEN target.default_group ELSE FALSE END,
    workspace_id = NULL,
    update_time = CURRENT_TIMESTAMP
FROM ranked_groups
WHERE target.id = ranked_groups.id;

UPDATE data_sync_template
SET workspace_id = NULL,
    update_time = CURRENT_TIMESTAMP
WHERE workspace_id IS NOT NULL;

UPDATE data_sync_task
SET workspace_id = NULL,
    update_time = CURRENT_TIMESTAMP
WHERE workspace_id IS NOT NULL;

COMMENT ON COLUMN data_sync_template.workspace_id IS
    '历史兼容字段：FlashSync 用户侧同步模板不再按 workspace 归属，正式隔离边界为 tenant_id + project_id；该列暂保留给旧脚本和存量迁移读取。';

COMMENT ON COLUMN data_sync_task.workspace_id IS
    '历史兼容字段：同步任务用户侧生命周期、分组、列表、编辑、运行和权限校验均不再使用 workspace_id；正式业务归属为 tenant_id + project_id。';

COMMENT ON COLUMN data_sync_task_group.workspace_id IS
    '历史兼容字段：任务分组树已收敛到项目级，同一项目下只保留一个 DEFAULT 默认分组；该列不再参与普通用户菜单、筛选或分组计数。';

COMMENT ON COLUMN data_sync_execution.workspace_id IS
    '历史执行快照字段：仅用于追溯旧版本执行或内部 worker/Agent 空间，不再作为普通用户任务列表、分组或权限隔离条件。';

COMMENT ON COLUMN data_sync_execution_log.workspace_id IS
    '历史运行日志快照字段：用于排查旧执行链路，不再代表用户可切换的业务层级。';

COMMENT ON COLUMN data_sync_object_execution.workspace_id IS
    '历史对象级执行快照字段：用于解释旧版本对象/分片执行来源，不再作为普通用户对象账本的业务过滤层级。';

COMMENT ON COLUMN data_sync_task_management_receipt_outbox.workspace_id IS
    '历史投递上下文快照字段：receipt 投递和任务管理主链路以 tenant_id + project_id + task_id 为准，不再按 workspace 路由。';

COMMENT ON COLUMN data_sync_checkpoint.workspace_id IS
    '历史 checkpoint 快照字段：用于恢复旧执行上下文，不再作为新建任务或普通查询的数据范围。';

COMMENT ON COLUMN data_sync_execution_recovery_plan.workspace_id IS
    '历史恢复计划快照字段：replay/backfill 用户入口以项目内任务和 execution 为准，不再要求用户选择 workspace。';

COMMENT ON COLUMN data_sync_error_sample.workspace_id IS
    '历史错误样本快照字段：用于兼容旧执行证据，不再作为脏数据样本查询的普通业务隔离层级。';

COMMENT ON COLUMN data_sync_incident_record.workspace_id IS
    '历史事故快照字段：事故归属、权限和列表收敛到项目；workspace 仅用于解释旧版本事故来源。';

COMMENT ON COLUMN data_sync_audit_record.workspace_id IS
    '历史审计快照字段：审计主隔离边界为租户和项目；workspace 仅保留旧链路上下文，不再作为普通用户过滤条件。';

COMMENT ON INDEX idx_data_sync_task_workspace_state IS
    '历史兼容索引：workspace_id 已退出普通任务范围，后续确认无旧查询依赖后可在单独迁移中删除。';

COMMENT ON INDEX idx_data_sync_task_workspace_group IS
    '历史兼容索引：任务分组已收敛为项目级，workspace 维度仅为旧数据卷兼容保留。';

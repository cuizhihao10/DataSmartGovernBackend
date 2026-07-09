-- 质量规则是项目内持续增长的用户业务数据，列表默认按 id DESC 返回最新规则。
-- 该索引匹配 tenant/project/status 过滤后的最新优先查询，避免服务端分页退化为扫描后排序。
CREATE INDEX IF NOT EXISTS idx_quality_rule_project_id_desc
    ON quality_rule (tenant_id, project_id, id DESC)
    WHERE status <> 'DELETED';

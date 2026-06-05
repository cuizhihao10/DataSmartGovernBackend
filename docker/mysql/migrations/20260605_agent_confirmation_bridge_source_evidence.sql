-- DataSmart Govern Backend - Agent confirmation bridge 来源证据增量字段
--
-- 设计背景：
-- 1. 5.18 已经把 handoff DAG bridge preview 写入 runtime event timeline；
-- 2. selected-node confirmation 现在需要记录“这次确认是否来自某次 handoff tool-control 预检”；
-- 3. 该字段只保存低敏来源摘要：sourceType、bridgeAction、selectionFingerprint、handoffNodeIds、
--    mappedToolNodeIds、mappedToolAuditIds、previewTraceId、previewEventType；
-- 4. 严禁把 prompt、SQL、工具参数、targetEndpoint、Kafka topic、样例数据或完整 request template 写入该列。
--
-- 查询设计：
-- - 当前不为该 JSON 列建立索引，因为它主要服务审计详情串联，不是列表页高频筛选条件；
-- - 若后续前端需要按 bridge 来源批量检索，应单独设计低基数字段或 dedicated index，而不是直接索引 JSON 明细。

USE datasmart_govern;

ALTER TABLE agent_run_tool_dag_confirmation
    ADD COLUMN bridge_source_evidence JSON DEFAULT NULL
        COMMENT 'handoff DAG bridge preview 来源证据摘要；只保存 sourceType、bridgeAction、fingerprint、节点 ID 和 traceId，不保存 prompt、SQL、工具参数或完整模板'
        AFTER delegation_evidence;

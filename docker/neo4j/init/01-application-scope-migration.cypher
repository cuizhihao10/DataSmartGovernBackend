// 一次性历史数据迁移脚本，不由应用启动自动执行。
//
// 新的 GraphRAG 合同以 tenant/application/project/sensitivity 为授权边界，
// workspace 是旧版本遗留属性。执行前请先完成备份并确认所有事实包已经带有
// application；迁移完成后可用 db.schema.visualization() 检查是否仍有旧属性。

MATCH (entity:GraphEntity)
REMOVE entity.workspace;

MATCH ()-[relationship:GRAPH_RELATION]->()
REMOVE relationship.workspace;

// DataSmart Govern GraphRAG 的实体标准 ID、别名查找和关系边最小约束。
// 该文件可在 Neo4j 管理流程中执行；Python Provider 也支持通过
// DATASMART_GRAPH_RAG_INITIALIZE_SCHEMA=true 在受控环境中幂等执行同样的约束。

CREATE CONSTRAINT datasmart_graph_entity_standard_id IF NOT EXISTS
FOR (entity:GraphEntity)
REQUIRE entity.standard_id IS UNIQUE;

CREATE INDEX datasmart_graph_entity_lookup_aliases IF NOT EXISTS
FOR (entity:GraphEntity)
ON (entity.lookup_aliases);

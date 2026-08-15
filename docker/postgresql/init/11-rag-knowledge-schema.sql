-- DataSmart Govern Backend - 持久化、受治理的 RAG 知识分块。
--
-- Python Runtime 启动时不会擅自创建或修改客户 schema。新的 Compose 数据卷由 PostgreSQL 初始化
-- 顺序执行本文件；已有数据卷需要运维人员显式执行这份幂等迁移。写入此表的文档正文必须先满足
-- 敏感度、保留周期、租户/项目/工作区范围和摄取授权规则。
-- vector 扩展及类型固定在 public，避免连接仅配置 search_path=ai_memory 时无法解析类型。

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;
CREATE SCHEMA IF NOT EXISTS ai_memory AUTHORIZATION datasmart;

CREATE TABLE IF NOT EXISTS ai_memory.rag_knowledge_chunk (
    chunk_id VARCHAR(256) PRIMARY KEY,
    document_id VARCHAR(256) NOT NULL,
    chunk_index INTEGER NOT NULL,
    title TEXT NOT NULL,
    chunk_text TEXT NOT NULL,
    source_uri TEXT NOT NULL,
    tenant_id VARCHAR(128) NOT NULL DEFAULT '*',
    project_id VARCHAR(128) NOT NULL DEFAULT '*',
    workspace_key VARCHAR(255) NOT NULL DEFAULT '*',
    source_type VARCHAR(32) NOT NULL DEFAULT 'document',
    tags_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    sensitivity_level VARCHAR(32) NOT NULL DEFAULT 'internal',
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    embedding_model VARCHAR(128),
    embedding_dimension INTEGER,
    embedding public.vector,
    content_fingerprint VARCHAR(128),
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    content_search_vector TSVECTOR GENERATED ALWAYS AS (
        to_tsvector('simple', COALESCE(title, '') || ' ' || COALESCE(chunk_text, ''))
    ) STORED,
    CONSTRAINT ck_rag_knowledge_chunk_index CHECK (chunk_index >= 0),
    CONSTRAINT ck_rag_knowledge_chunk_embedding_dimension CHECK (
        embedding_dimension IS NULL OR embedding_dimension > 0
    )
);

COMMENT ON TABLE ai_memory.rag_knowledge_chunk IS
    '供 Python Runtime RAG 证据门禁使用的持久化、治理范围内知识分块。';
COMMENT ON COLUMN ai_memory.rag_knowledge_chunk.chunk_text IS
    '已授权知识正文；禁止保存凭据、原始提示词、样例数据行或未受限的工具输出。';
COMMENT ON COLUMN ai_memory.rag_knowledge_chunk.workspace_key IS
    '检索硬范围；星号表示经过显式授权的共享范围文档。';

CREATE INDEX IF NOT EXISTS idx_rag_knowledge_chunk_scope
    ON ai_memory.rag_knowledge_chunk (
        tenant_id,
        project_id,
        workspace_key,
        enabled,
        updated_at DESC
    );
CREATE INDEX IF NOT EXISTS idx_rag_knowledge_chunk_document
    ON ai_memory.rag_knowledge_chunk (document_id, chunk_index);
-- document_id 只在治理范围内唯一；该索引用于受限替换/删除，避免跨租户扫描。
CREATE INDEX IF NOT EXISTS idx_rag_knowledge_chunk_scoped_document
    ON ai_memory.rag_knowledge_chunk (
        tenant_id,
        project_id,
        workspace_key,
        document_id,
        chunk_index
    );
CREATE INDEX IF NOT EXISTS idx_rag_knowledge_chunk_search
    ON ai_memory.rag_knowledge_chunk USING GIN (content_search_vector);
CREATE INDEX IF NOT EXISTS idx_rag_knowledge_chunk_embedding_model
    ON ai_memory.rag_knowledge_chunk (embedding_model, embedding_dimension, enabled);

-- 当前企业基线模型 BAAI/bge-m3 固定输出 1024 维向量。基础列保持无定维，以便未来并存其他模型；
-- 该部分表达式 HNSW 索引只覆盖 bge-m3，查询适配器会使用完全相同的定维表达式命中索引。
CREATE INDEX IF NOT EXISTS idx_rag_knowledge_chunk_bge_m3_hnsw
    ON ai_memory.rag_knowledge_chunk USING hnsw (
        (embedding::public.vector(1024)) public.vector_cosine_ops
    )
    WHERE enabled = TRUE
      AND embedding_model = 'BAAI/bge-m3'
      AND embedding_dimension = 1024;

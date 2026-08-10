-- DataSmart Govern Backend - durable governance RAG knowledge chunks.
--
-- Python Runtime deliberately does not create or alter this schema at startup.
-- New Compose volumes receive it through the PostgreSQL init sequence; existing
-- volumes must apply this idempotent migration as an explicit operator action.
-- Document bodies stored here must already satisfy the platform's sensitivity,
-- retention, tenant/project/workspace scope, and ingestion authorization rules.

CREATE EXTENSION IF NOT EXISTS vector;
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
    embedding VECTOR,
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
    'Durable, governance-scoped chunks used by the Python Runtime RAG evidence gate.';
COMMENT ON COLUMN ai_memory.rag_knowledge_chunk.chunk_text IS
    'Authorized knowledge content; never store credentials, raw prompts, sample rows, or unrestricted tool output.';
COMMENT ON COLUMN ai_memory.rag_knowledge_chunk.workspace_key IS
    'Hard retrieval scope. A literal asterisk denotes an explicitly shared workspace document.';

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
CREATE INDEX IF NOT EXISTS idx_rag_knowledge_chunk_search
    ON ai_memory.rag_knowledge_chunk USING GIN (content_search_vector);
CREATE INDEX IF NOT EXISTS idx_rag_knowledge_chunk_embedding_model
    ON ai_memory.rag_knowledge_chunk (embedding_model, embedding_dimension, enabled);

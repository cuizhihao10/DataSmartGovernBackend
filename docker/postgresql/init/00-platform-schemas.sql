-- DataSmart PostgreSQL 目标数据库初始化。
--
-- 本脚本只创建平台级 extension 与服务 schema，不在这里复制数百张 MySQL 业务表。
-- 每个微服务的表结构必须在对应迁移批次中独立转换、验证并登记，避免“一次性自动翻译 SQL”
-- 把 AUTO_INCREMENT、TINYINT、ON UPDATE、索引前缀、字符集和 JSON 语义差异悄悄带入生产。
--
-- schema 隔离原则：
-- 1. 一个 PostgreSQL 集群用于降低本地和中小规模部署成本；
-- 2. 每个微服务使用独立 schema 与独立 search_path，避免跨模块直接连表；
-- 3. 商业部署可进一步为每个 schema 分配独立数据库角色、连接池、备份和资源配额；
-- 4. ai_memory 同时承载结构化记忆元数据、全文索引和 pgvector 向量，逐步替代 Chroma。

CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA IF NOT EXISTS permission_admin AUTHORIZATION datasmart;
CREATE SCHEMA IF NOT EXISTS task_management AUTHORIZATION datasmart;
CREATE SCHEMA IF NOT EXISTS datasource_management AUTHORIZATION datasmart;
CREATE SCHEMA IF NOT EXISTS data_sync AUTHORIZATION datasmart;
CREATE SCHEMA IF NOT EXISTS data_quality AUTHORIZATION datasmart;
CREATE SCHEMA IF NOT EXISTS agent_runtime AUTHORIZATION datasmart;
CREATE SCHEMA IF NOT EXISTS observability AUTHORIZATION datasmart;
CREATE SCHEMA IF NOT EXISTS ai_memory AUTHORIZATION datasmart;

COMMENT ON SCHEMA permission_admin IS '权限、角色、菜单、审批与数据范围事实';
COMMENT ON SCHEMA task_management IS '任务生命周期、调度、重试、回放与 worker 事实';
COMMENT ON SCHEMA datasource_management IS '数据源、连接、元数据与连接审计事实';
COMMENT ON SCHEMA data_sync IS '数据同步模板、执行、恢复与回执事实';
COMMENT ON SCHEMA data_quality IS '质量规则、执行、问题、整改与报告事实';
COMMENT ON SCHEMA agent_runtime IS 'Agent 控制面、outbox、receipt、checkpoint 与审计事实';
COMMENT ON SCHEMA observability IS '告警、SLO、事件归档与运维配置事实';
COMMENT ON SCHEMA ai_memory IS '用户画像、长期记忆、全文索引、向量与 LangGraph durable state';

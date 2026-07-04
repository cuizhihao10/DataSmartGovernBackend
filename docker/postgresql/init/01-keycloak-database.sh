#!/bin/sh
#
# DataSmart Govern - Keycloak PostgreSQL 数据库初始化脚本。
#
# 这个脚本有两个运行场景：
# 1. PostgreSQL 首次创建数据目录时，官方 entrypoint 会自动执行 /docker-entrypoint-initdb.d 下的脚本；
# 2. 已经存在 postgresql_data 卷的本地环境中，Compose 的 keycloak-db-bootstrap 一次性服务会再次执行本脚本。
#
# 为什么要做成幂等脚本：
# - Keycloak 的 realm、用户、client、角色和密钥轮换历史都属于认证中心事实数据；
# - 这些数据不能继续放在 Keycloak dev 模式的容器文件目录里，否则容器生命周期和身份数据生命周期容易混在一起；
# - PostgreSQL 作为当前项目的目标系统记录库，更适合进入备份、恢复、审计和后续高可用托管方案；
# - 脚本每次执行都只会“补齐或更新角色密码”，不会删除现有 realm，也不会覆盖已有用户。
#
# 安全边界：
# - 这里的默认 keycloak/keycloak 仅用于本地学习和 E2E；
# - 生产环境必须通过 Secret Manager、Kubernetes Secret、Docker secret 或企业配置中心注入真实密码；
# - 脚本不会打印密码，不会导出用户数据，也不会读取 Keycloak realm secret。

set -eu

KEYCLOAK_DB_NAME="${DATASMART_KEYCLOAK_DB_NAME:-keycloak}"
KEYCLOAK_DB_USERNAME="${DATASMART_KEYCLOAK_DB_USERNAME:-keycloak}"
KEYCLOAK_DB_PASSWORD="${DATASMART_KEYCLOAK_DB_PASSWORD:-keycloak}"
POSTGRES_SUPERUSER="${POSTGRES_USER:-datasmart}"
POSTGRES_MAINTENANCE_DB="${POSTGRES_MAINTENANCE_DB:-postgres}"
POSTGRES_PORT_VALUE="${POSTGRES_PORT:-5432}"

# 官方 PostgreSQL entrypoint 首次初始化时通常通过本地 socket 连接，不需要 host/password；
# Compose bootstrap 容器则通过 Docker 网络访问 postgresql 服务，需要显式传入 host/port/password。
PSQL_HOST_ARGS=""
if [ -n "${POSTGRES_HOST:-}" ]; then
  PSQL_HOST_ARGS="--host=${POSTGRES_HOST} --port=${POSTGRES_PORT_VALUE}"
fi

if [ -n "${POSTGRES_PASSWORD:-}" ]; then
  export PGPASSWORD="${POSTGRES_PASSWORD}"
fi

echo "[datasmart-keycloak-db] ensure PostgreSQL role and database for Keycloak"

psql ${PSQL_HOST_ARGS} \
  --username "${POSTGRES_SUPERUSER}" \
  --dbname "${POSTGRES_MAINTENANCE_DB}" \
  --set ON_ERROR_STOP=1 \
  --set keycloak_db_name="${KEYCLOAK_DB_NAME}" \
  --set keycloak_db_username="${KEYCLOAK_DB_USERNAME}" \
  --set keycloak_db_password="${KEYCLOAK_DB_PASSWORD}" <<'EOSQL'
-- 先创建专用登录角色。Keycloak 不应复用 datasmart 超级/业务账号，
-- 否则认证中心 schema 权限会和平台业务 schema 权限混在一起。
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'keycloak_db_username', :'keycloak_db_password')
WHERE NOT EXISTS (
    SELECT 1
    FROM pg_roles
    WHERE rolname = :'keycloak_db_username'
)
\gexec

-- 已存在角色时只轮换密码，不修改现有数据库对象、realm 或用户数据。
-- 这样本地 .env 修改密码后重新执行 bootstrap 即可让 Keycloak 使用新凭据连接。
SELECT format('ALTER ROLE %I WITH PASSWORD %L', :'keycloak_db_username', :'keycloak_db_password')
\gexec

-- Keycloak 使用独立 database，而不是复用 datasmart_govern 下的业务 schema。
-- 这样备份恢复、权限隔离、容量评估和未来迁移到托管 PostgreSQL 时边界更清晰。
SELECT format('CREATE DATABASE %I OWNER %I ENCODING %L', :'keycloak_db_name', :'keycloak_db_username', 'UTF8')
WHERE NOT EXISTS (
    SELECT 1
    FROM pg_database
    WHERE datname = :'keycloak_db_name'
)
\gexec

SELECT format('ALTER DATABASE %I OWNER TO %I', :'keycloak_db_name', :'keycloak_db_username')
\gexec
EOSQL

psql ${PSQL_HOST_ARGS} \
  --username "${POSTGRES_SUPERUSER}" \
  --dbname "${KEYCLOAK_DB_NAME}" \
  --set ON_ERROR_STOP=1 \
  --set keycloak_db_username="${KEYCLOAK_DB_USERNAME}" <<'EOSQL'
-- PostgreSQL 15+ 的 public schema 默认权限更收敛。Keycloak 需要在自己的 database
-- 中创建和迁移表结构，因此显式授予 public schema 的常规对象创建权限。
GRANT USAGE, CREATE ON SCHEMA public TO :"keycloak_db_username";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"keycloak_db_username";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO :"keycloak_db_username";
EOSQL

echo "[datasmart-keycloak-db] Keycloak PostgreSQL database contract is ready"

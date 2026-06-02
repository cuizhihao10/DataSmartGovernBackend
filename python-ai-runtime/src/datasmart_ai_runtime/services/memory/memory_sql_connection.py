"""长期记忆 SQL Store 的共享连接工具。

候选记忆 store 与正式记忆 store 都需要连接 SQLite/MySQL。如果每个 runtime builder 都复制一份
DSN 解析、密码脱敏和驱动导入逻辑，后续一旦支持 PostgreSQL、连接池、TLS 参数或 Secret Manager，
就会出现多个文件同时改、容易漏改的耦合问题。

本文件只负责“把启动配置转换成 DB-API 连接参数/连接对象”，不理解候选表或正式记忆表的业务字段。
这样业务 store 仍然专注于幂等写入、范围检索和审计语义，基础设施细节则集中在一个小工具模块里。
"""

from __future__ import annotations

import sqlite3
from typing import Any
from urllib.parse import unquote_plus, urlsplit, urlunsplit


def build_sqlite_connection(sqlite_path: str, connect_timeout_seconds: int) -> sqlite3.Connection:
    """创建 SQLite DB-API 连接。

    SQLite 主要服务于本地学习、轻量联调和单元测试。它不是 DataSmart 的生产默认数据库，但对验证
    “跨 Runtime 重启仍能读取同一份长期记忆事实”非常有价值。

    这里不自动建表，原因是建表属于迁移职责：
    - 测试可以在 fixture 中显式创建 schema；
    - 生产 MySQL 应由 `docker/mysql/migrations` 或迁移工具执行；
    - Runtime 如果在启动时悄悄建表，容易掩盖版本漂移和字段缺失问题。
    """

    connection = sqlite3.connect(sqlite_path, timeout=connect_timeout_seconds)
    connection.row_factory = sqlite3.Row
    return connection


def build_mysql_connection(mysql_dsn: str, connect_timeout_seconds: int) -> Any:
    """创建 MySQL DB-API 连接。

    支持两类 DSN 写法：
    - URL：`mysql://user:password@localhost:3306/datasmart?charset=utf8mb4`；
    - 分号键值：`host=localhost;port=3306;user=root;password=xxx;database=datasmart`。

    当前优先尝试 PyMySQL，再尝试 mysqlclient(MySQLdb)。这样本地开发可以选择更容易安装的 PyMySQL，
    生产部署如果已有 mysqlclient 也可以复用。这里没有引入连接池，因为连接池大小、健康检查、重连策略
    应由更高层 runtime assembly 或未来 worker 框架决定，而不是由单个 store 自行创建。
    """

    if not mysql_dsn:
        raise ValueError("MySQL DSN 未配置，无法启用 mysql store。")
    kwargs = parse_mysql_dsn(mysql_dsn, connect_timeout_seconds)
    try:
        import pymysql  # type: ignore

        return pymysql.connect(**kwargs, cursorclass=pymysql.cursors.DictCursor)
    except ImportError:
        try:
            import MySQLdb  # type: ignore
        except ImportError as exc:
            raise RuntimeError("启用 mysql store 需要安装 PyMySQL 或 mysqlclient。") from exc
        return MySQLdb.connect(**kwargs)


def parse_mysql_dsn(dsn: str, connect_timeout_seconds: int) -> dict[str, Any]:
    """解析 MySQL DSN 为 DB-API 连接参数。

    解析结果只包含连接需要的低层参数，不包含业务含义。调用方不应把返回值直接暴露到诊断接口，
    因为其中包含 password。需要诊断时应使用 `mask_mysql_dsn()`。
    """

    if "://" in dsn:
        parts = urlsplit(dsn)
        return {
            "host": parts.hostname or "localhost",
            "port": parts.port or 3306,
            "user": unquote_plus(parts.username or "root"),
            "password": unquote_plus(parts.password or ""),
            "database": parts.path.lstrip("/") or "datasmart",
            "charset": _query_value(parts.query, "charset") or "utf8mb4",
            "connect_timeout": connect_timeout_seconds,
        }
    values: dict[str, str] = {}
    for item in dsn.split(";"):
        if not item.strip() or "=" not in item:
            continue
        key, value = item.split("=", 1)
        values[key.strip().lower()] = value.strip()
    return {
        "host": values.get("host", "localhost"),
        "port": int(values.get("port", "3306")),
        "user": values.get("user") or values.get("username") or "root",
        "password": values.get("password") or values.get("pwd", ""),
        "database": values.get("database") or values.get("db") or "datasmart",
        "charset": values.get("charset", "utf8mb4"),
        "connect_timeout": connect_timeout_seconds,
    }


def mask_mysql_dsn(dsn: str) -> str:
    """脱敏 MySQL DSN，供诊断接口安全展示。

    诊断接口需要回答“当前是不是连到了 MySQL、连的是哪个 host/database”，但不能泄露数据库密码。
    如果把原始 DSN 返回给前端、日志或运维截图，数据库凭证就会变成新的安全风险。
    """

    if not dsn:
        return ""
    if "://" in dsn:
        parts = urlsplit(dsn)
        host = parts.hostname or "localhost"
        port = f":{parts.port}" if parts.port else ""
        database = parts.path or ""
        return urlunsplit((parts.scheme, f"***:***@{host}{port}", database, parts.query, parts.fragment))
    masked: list[str] = []
    for item in dsn.split(";"):
        if "=" not in item:
            masked.append(item)
            continue
        key, value = item.split("=", 1)
        masked.append(f"{key}=***" if key.strip().lower() in {"password", "pwd"} else f"{key}={value}")
    return ";".join(masked)


def _query_value(query: str, name: str) -> str | None:
    """从 URL query 中读取一个简单参数。

    当前只需要 `charset`。如果未来支持 TLS、readTimeout、writeTimeout 或 serverTimezone，可以在这里
    扩展为更完整的 query parser，但不要把数据库密码或 token 记录到日志。
    """

    for item in query.split("&"):
        if not item or "=" not in item:
            continue
        key, value = item.split("=", 1)
        if key == name:
            return value
    return None

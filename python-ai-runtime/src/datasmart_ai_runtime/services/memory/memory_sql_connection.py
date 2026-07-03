"""长期记忆 SQL Store 的共享连接工具。

候选记忆 store 与正式记忆 store 都需要连接 SQLite/MySQL/PostgreSQL。如果每个 runtime builder 都复制一份
DSN 解析、密码脱敏和驱动导入逻辑，后续一旦支持 PostgreSQL、连接池、TLS 参数或 Secret Manager，
就会出现多个文件同时改、容易漏改的耦合问题。

本文件只负责“把启动配置转换成 DB-API 连接参数/连接对象”，不理解候选表或正式记忆表的业务字段。
这样业务 store 仍然专注于幂等写入、范围检索和审计语义，基础设施细节则集中在一个小工具模块里。
"""

from __future__ import annotations

import re
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


def build_postgresql_connection(postgresql_dsn: str, connect_timeout_seconds: int) -> Any:
    """创建 PostgreSQL / pgvector DB-API 连接。

    PostgreSQL 是 DataSmart AI Memory 的目标事实源，因此这里把连接能力放进共享工具层，而不是继续让
    候选、正式记忆、receipt、lease、audit outbox 组件分别理解 psycopg 的导入方式和 DSN 细节。

    支持两类 DSN 写法：
    - URL：`postgresql://datasmart:password@postgresql:5432/datasmart_govern?options=-csearch_path%3Dai_memory`；
    - 分号键值：`host=postgresql;port=5432;user=datasmart;password=xxx;database=datasmart_govern;options=-c search_path=ai_memory`。

    这里默认启用 psycopg3 的 `dict_row`，让 SQL store 可以按列名读取查询结果，避免 PostgreSQL 驱动
    默认 tuple 顺序和 MySQL/SQLite 行对象差异扩大到业务层。建表和迁移仍由 PostgreSQL init 脚本或
    专用迁移工具负责，Runtime 启动时不悄悄修改生产库。
    """

    if not postgresql_dsn:
        raise ValueError("PostgreSQL DSN 未配置，无法启用 postgresql store。")
    try:
        import psycopg  # type: ignore
        from psycopg.rows import dict_row  # type: ignore
    except ImportError as exc:
        raise RuntimeError("启用 postgresql store 需要安装 psycopg，例如 python-ai-runtime[postgresql]。") from exc

    if "://" in postgresql_dsn:
        return psycopg.connect(postgresql_dsn, connect_timeout=connect_timeout_seconds, row_factory=dict_row)
    kwargs = parse_postgresql_dsn(postgresql_dsn, connect_timeout_seconds)
    return psycopg.connect(**kwargs, row_factory=dict_row)


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


def parse_postgresql_dsn(dsn: str, connect_timeout_seconds: int) -> dict[str, Any]:
    """解析分号风格 PostgreSQL DSN。

    psycopg 原生支持 URL 和 libpq 连接串，但项目中已有 MySQL 的 `host=...;password=...` 风格配置。
    为了让本地 Compose、企业 Secret Manager 和运维脚本保持同一种书写体验，这里也兼容分号键值写法。

    返回值只包含连接所需的低层参数，不包含任何业务含义。诊断接口必须使用 `mask_postgresql_dsn()`，
    不能把该字典原样输出，因为其中包含 password。
    """

    values: dict[str, str] = {}
    for item in dsn.split(";"):
        if not item.strip() or "=" not in item:
            continue
        key, value = item.split("=", 1)
        values[key.strip().lower()] = value.strip()
    kwargs: dict[str, Any] = {
        "host": values.get("host", "localhost"),
        "port": int(values.get("port", "5432")),
        "user": values.get("user") or values.get("username") or "datasmart",
        "password": values.get("password") or values.get("pwd", ""),
        "dbname": values.get("database") or values.get("dbname") or values.get("db") or "datasmart_govern",
        "connect_timeout": connect_timeout_seconds,
    }
    if values.get("options"):
        kwargs["options"] = values["options"]
    if values.get("sslmode"):
        kwargs["sslmode"] = values["sslmode"]
    if values.get("application_name"):
        kwargs["application_name"] = values["application_name"]
    return kwargs


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
    # 先处理 libpq 常见的空格分隔形式，例如
    # `host=postgresql user=datasmart password=secret dbname=datasmart_govern`。
    # 即使当前 MySQL parser 不消费这种格式，诊断层也不能因为输入格式不规范而泄露密码。
    if ";" not in dsn:
        return _mask_key_value_password(dsn)
    masked: list[str] = []
    for item in dsn.split(";"):
        if "=" not in item:
            masked.append(item)
            continue
        key, value = item.split("=", 1)
        masked.append(f"{key}=***" if key.strip().lower() in {"password", "pwd"} else f"{key}={value}")
    return ";".join(masked)


def mask_postgresql_dsn(dsn: str) -> str:
    """脱敏 PostgreSQL DSN，供诊断接口安全展示。

    ai_memory 连接串通常会暴露 PostgreSQL host、database、search_path、sslmode 等排障信息。
    这些信息对运维有价值，但 password 绝不能进入日志、HTTP 诊断响应或截图，因此这里和 MySQL
    脱敏逻辑保持一致，只保留低敏连接目标。
    """

    if not dsn:
        return ""
    if "://" in dsn:
        parts = urlsplit(dsn)
        host = parts.hostname or "localhost"
        port = f":{parts.port}" if parts.port else ""
        database = parts.path or ""
        return urlunsplit((parts.scheme, f"***:***@{host}{port}", database, parts.query, parts.fragment))
    # psycopg/libpq 原生连接串通常使用空格分隔，而且密码可能被单引号或双引号包裹。
    # 脱敏函数必须先于连接解析具备“容错安全性”：即使 DSN 最终无法连接，也不能在 fallback
    # diagnostics 中把原始 password 回显给管理端。
    if ";" not in dsn:
        return _mask_key_value_password(dsn)
    masked: list[str] = []
    for item in dsn.split(";"):
        if "=" not in item:
            masked.append(item)
            continue
        key, value = item.split("=", 1)
        masked.append(f"{key}=***" if key.strip().lower() in {"password", "pwd"} else f"{key}={value}")
    return ";".join(masked)


def _mask_key_value_password(dsn: str) -> str:
    """遮蔽空格/混合分隔键值串中的 password。

    匹配值支持普通 token、单引号和双引号。函数只替换 password/pwd 的值，不尝试重新解析或规范化
    整个 DSN，因此 host、database、sslmode 等低敏排障信息仍会保留。
    """

    pattern = re.compile(
        r"(?i)(\b(?:password|pwd)\s*=\s*)(?:\"[^\"]*\"|'[^']*'|[^;\s]+)"
    )
    return pattern.sub(r"\1***", dsn)


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

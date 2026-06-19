"""Agent API 启动期环境变量解析工具。

这个模块把 `orchestrator_factory.py` 中反复出现的环境变量解析逻辑拆出来，目的是避免启动装配文件继续
向 500 行上限膨胀。它只处理“如何把字符串环境变量解释成低风险配置值”，不负责创建任何远程客户端。

设计原则：
- 布尔值不能直接使用 `bool("false")`，否则生产环境写入 `false` 反而会启用远程链路；
- 超时、limit、自动执行上限等数值只接受正整数，非法或非正数回退默认值；
- 逗号分隔列表主要用于项目授权范围 Header，只允许返回清洗后的字符串 tuple；
- 这里不读取 prompt、工具参数、SQL、token 内容，也不记录环境变量原文。
"""

from __future__ import annotations

import os


def truthy_env(name: str) -> bool:
    """解析布尔环境变量。

    该函数服务所有 Agent API bootstrap 开关，例如远程 Java 控制面、runtime event replay、工具反馈、
    恢复事实 provider 等。集中解析可以避免不同文件各自理解 `false/off/0`，导致某个远程链路在本地或
    CI 环境被意外打开。
    """

    value = os.getenv(name)
    if value is None:
        return False
    return str(value).strip().lower() in {"1", "true", "yes", "on", "enabled"}


def positive_int_env(name: str, default: int) -> int:
    """读取正整数环境变量。

    用途包括 HTTP 超时、replay limit、工具预算等。如果运维误配为 0、负数或空值，本函数会回退默认值，
    避免把关键路径配置成“立即超时”或“无限制”这类难排查状态。
    """

    value = os.getenv(name)
    if value is None or not value.strip():
        return default
    parsed = int(value)
    return parsed if parsed > 0 else default


def optional_positive_int_env(name: str) -> int | None:
    """读取可选正整数环境变量。

    与 `positive_int_env` 的区别是：未配置时返回 `None`。这适合“Python 可以不传，让 Java 控制面按服务端
    配置决定”的场景，例如同步自动执行最大数量。如果 Python 侧硬写默认值，反而可能覆盖 Java 按租户、
    套餐或灰度策略配置的上限。
    """

    value = os.getenv(name)
    if value is None or not value.strip():
        return None
    parsed = int(value)
    return parsed if parsed > 0 else None


def csv_env(name: str) -> tuple[str, ...]:
    """读取逗号分隔环境变量。

    当前主要用于 Java 控制面调用中的 `X-DataSmart-Authorized-Project-Ids` Header。返回值只包含去空白
    后的项目 ID 字符串，不携带 prompt、工具参数、SQL、payload 或任何业务正文。
    """

    value = os.getenv(name)
    if not value:
        return ()
    return tuple(item.strip() for item in value.split(",") if item.strip())

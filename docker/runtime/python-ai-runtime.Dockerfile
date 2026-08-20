# syntax=docker.m.daocloud.io/docker/dockerfile:1.7

# Python AI Runtime 多阶段镜像。
#
# builder 阶段负责创建虚拟环境并安装 API、LangGraph、RAG、GraphRAG、Kafka、Redis、PostgreSQL/pgvector 等可选能力；
# runtime 阶段只复制虚拟环境和运行包，不携带编译缓存、pip 缓存或仓库测试文件。
# 默认使用 DaoCloud 国内镜像站，企业部署可以通过 PYTHON_IMAGE build arg 切换私有基础镜像。

ARG PYTHON_IMAGE=docker.m.daocloud.io/library/python:3.11-slim-bookworm

FROM ${PYTHON_IMAGE} AS builder

ARG PYTHON_RUNTIME_EXTRAS=api,rag,graph,kafka,redis,postgresql,mcp
# Python 包下载与 Docker 基础镜像是两条链路：基础镜像走 DaoCloud，pip 默认走可覆盖的国内 PyPI 镜像。
# 企业环境可以在 Compose/build pipeline 中把该参数替换为内网制品库，不需要修改 Dockerfile。
ARG PIP_INDEX_URL=https://mirrors.aliyun.com/pypi/simple

ENV VIRTUAL_ENV=/opt/venv \
    PATH=/opt/venv/bin:${PATH} \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PYTHON_RUNTIME_EXTRAS=${PYTHON_RUNTIME_EXTRAS} \
    PIP_INDEX_URL=${PIP_INDEX_URL}

WORKDIR /build/python-ai-runtime

RUN python -m venv "${VIRTUAL_ENV}"

COPY python-ai-runtime/pyproject.toml ./pyproject.toml

# 依赖层只消费 pyproject.toml 和 extras，不复制业务源码。旧写法先 COPY src 再执行 `pip install .`，导致任何一行
# Python 代码变化都会让 Chroma、LangGraph、MCP 等数百个依赖重新安装 7-10 分钟。这里用 Python 3.11 自带的
# tomllib 结构化读取依赖声明，再一次性交给 pip；它不会用 grep/sed 猜 TOML，也不会遗漏 extra 内的版本约束。
# BuildKit cache 会跨源码重建复用该完整虚拟环境层，只有 pyproject 或构建 extras 真正变化时才重新解析依赖。
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install --index-url "${PIP_INDEX_URL}" --retries 10 --timeout 120 --upgrade pip setuptools wheel \
    && python - <<'PY'
import os
import subprocess
import tomllib

with open("pyproject.toml", "rb") as source:
    project = tomllib.load(source)["project"]

requirements = list(project.get("dependencies", ()))
optional = project.get("optional-dependencies", {})
for extra in (item.strip() for item in os.environ["PYTHON_RUNTIME_EXTRAS"].split(",")):
    if not extra:
        continue
    if extra not in optional:
        raise SystemExit(f"Unknown PYTHON_RUNTIME_EXTRAS entry: {extra}")
    requirements.extend(optional[extra])

if requirements:
    subprocess.check_call([
        "pip", "install", "--retries", "10", "--timeout", "120", *requirements,
    ])

# 构建期就验证“声明了可选能力，镜像确实包含对应 Driver”。过去依赖层被 Docker 缓存复用时，
# Compose 虽然传入了 graph extra，运行容器仍可能缺少 neo4j，直到真实摄取才暴露问题；
# 这里让镜像构建直接失败，避免把缺依赖伪装成运行时 Provider 故障。
if "graph" in {item.strip() for item in os.environ["PYTHON_RUNTIME_EXTRAS"].split(",") if item.strip()}:
    try:
        import importlib.util
        if importlib.util.find_spec("neo4j") is None:
            raise SystemExit("PYTHON_RUNTIME_EXTRAS contains graph but neo4j is not installed")
    except ModuleNotFoundError as exc:
        raise SystemExit("PYTHON_RUNTIME_EXTRAS contains graph but neo4j is not installed") from exc
PY

# 业务包在轻量层中安装。`--no-deps` 是安全的，因为上一层已经从同一份 pyproject 结构化安装了全部选中依赖；
# 该命令只构建约 1-2 MB 的 DataSmart wheel，因此日常源码修改不再使第三方依赖层失效。
COPY python-ai-runtime/src ./src
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install --no-deps .

FROM ${PYTHON_IMAGE} AS runtime

ENV VIRTUAL_ENV=/opt/venv \
    PATH=/opt/venv/bin:${PATH} \
    TZ=Asia/Shanghai \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1

RUN groupadd --system datasmart \
    && useradd --system --gid datasmart --home-dir /opt/datasmart --shell /usr/sbin/nologin datasmart

WORKDIR /opt/datasmart

COPY --from=builder --chown=datasmart:datasmart /opt/venv /opt/venv

EXPOSE 8090

USER datasmart:datasmart

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=5 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/agent/capabilities/closure-readiness', timeout=3)" || exit 1

CMD ["python", "-m", "uvicorn", "datasmart_ai_runtime.api:create_app", "--factory", "--host", "0.0.0.0", "--port", "8090"]

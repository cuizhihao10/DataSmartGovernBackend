# syntax=docker.m.daocloud.io/docker/dockerfile:1.7

# Python AI Runtime 多阶段镜像。
#
# builder 阶段负责创建虚拟环境并安装 API、LangGraph、RAG、Kafka、Redis、PostgreSQL/pgvector 等可选能力；
# runtime 阶段只复制虚拟环境和运行包，不携带编译缓存、pip 缓存或仓库测试文件。
# 默认使用 DaoCloud 国内镜像站，企业部署可以通过 PYTHON_IMAGE build arg 切换私有基础镜像。

ARG PYTHON_IMAGE=docker.m.daocloud.io/library/python:3.11-slim-bookworm

FROM ${PYTHON_IMAGE} AS builder

ARG PYTHON_RUNTIME_EXTRAS=api,rag,kafka,redis,postgresql

ENV VIRTUAL_ENV=/opt/venv \
    PATH=/opt/venv/bin:${PATH} \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /build/python-ai-runtime

RUN python -m venv "${VIRTUAL_ENV}"

COPY python-ai-runtime/pyproject.toml ./pyproject.toml
COPY python-ai-runtime/src ./src

# extras 由构建参数控制；默认镜像包含当前商用闭环需要的 LangGraph、Chroma、Kafka、Redis 与 PostgreSQL 客户端。
RUN pip install --upgrade pip setuptools wheel \
    && pip install ".[${PYTHON_RUNTIME_EXTRAS}]"

FROM ${PYTHON_IMAGE} AS runtime

ENV VIRTUAL_ENV=/opt/venv \
    PATH=/opt/venv/bin:${PATH} \
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

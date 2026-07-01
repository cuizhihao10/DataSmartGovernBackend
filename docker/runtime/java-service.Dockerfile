# syntax=docker.m.daocloud.io/docker/dockerfile:1.7

# DataSmart Govern Java 微服务共享多阶段镜像。
#
# 设计目标：
# 1. 一份 Dockerfile 服务 gateway、permission-admin、task-management、datasource-management、
#    data-sync、data-quality、agent-runtime、observability，避免八份镜像脚本长期漂移；
# 2. builder 阶段使用完整 Maven + JDK 21，runtime 阶段只保留 JRE 21 和最终可执行 jar；
# 3. 默认基础镜像通过 DaoCloud 国内镜像站拉取，部署方仍可用 build arg 切换到企业私有仓库；
# 4. 运行进程使用非 root 用户，健康检查只访问本容器 Actuator，不读取业务数据；
# 5. Maven 缓存使用 BuildKit cache mount，不会进入最终镜像层。

ARG MAVEN_IMAGE=docker.m.daocloud.io/library/maven:3.9.9-eclipse-temurin-21
ARG JAVA_RUNTIME_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:21-jre-jammy

FROM ${MAVEN_IMAGE} AS builder

ARG MODULE
WORKDIR /workspace

# 先复制 POM，使项目结构和模块白名单在复制源码前完成校验。
# Maven 依赖由后续 package 步骤的 BuildKit cache mount 复用，不单独执行 dependency:go-offline：
# go-offline 会解析大量测试与报告插件，首次构建成本远高于真正的生产打包，反而降低交付效率。
COPY pom.xml ./pom.xml
COPY platform-common/pom.xml ./platform-common/pom.xml
COPY gateway/pom.xml ./gateway/pom.xml
COPY permission-admin/pom.xml ./permission-admin/pom.xml
COPY task-management/pom.xml ./task-management/pom.xml
COPY datasource-management/pom.xml ./datasource-management/pom.xml
COPY data-sync/pom.xml ./data-sync/pom.xml
COPY data-quality/pom.xml ./data-quality/pom.xml
COPY agent-runtime/pom.xml ./agent-runtime/pom.xml
COPY observability/pom.xml ./observability/pom.xml

# MODULE 是构建边界，不允许调用方传任意目录或 shell 片段。
RUN case "${MODULE}" in \
      gateway|permission-admin|task-management|datasource-management|data-sync|data-quality|agent-runtime|observability) ;; \
      *) echo "Unsupported DataSmart Java module: ${MODULE}" >&2; exit 64 ;; \
    esac

# platform-common 是所有 Java 服务共享的契约库；业务模块源码只复制当前构建目标。
COPY platform-common/src ./platform-common/src
COPY ${MODULE}/src ./${MODULE}/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl "${MODULE}" -am package -DskipTests \
    && JAR_FILE="$(find "${MODULE}/target" -maxdepth 1 -type f -name '*.jar' ! -name '*.original' ! -name '*-sources.jar' | head -n 1)" \
    && test -n "${JAR_FILE}" \
    && cp "${JAR_FILE}" /workspace/app.jar

FROM ${JAVA_RUNTIME_IMAGE} AS runtime

ARG MODULE
ARG SERVER_PORT=8080

LABEL org.opencontainers.image.title="DataSmart Govern ${MODULE}" \
      org.opencontainers.image.vendor="DataSmart Govern" \
      org.opencontainers.image.description="DataSmart Govern JDK 21 Spring Boot microservice"

# curl 仅用于容器内 Actuator 健康检查；安装后清理 apt 索引，控制运行镜像体积。
RUN apt-get update \
    && apt-get install --yes --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system datasmart \
    && useradd --system --gid datasmart --home-dir /opt/datasmart --shell /usr/sbin/nologin datasmart

WORKDIR /opt/datasmart

COPY --from=builder --chown=datasmart:datasmart /workspace/app.jar ./app.jar

ENV SERVER_PORT=${SERVER_PORT} \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"

EXPOSE ${SERVER_PORT}

USER datasmart:datasmart

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
    CMD curl --fail --silent --show-error "http://127.0.0.1:${SERVER_PORT}/actuator/health" > /dev/null || exit 1

# 使用 exec 交接 PID 1，确保 Docker stop 的 SIGTERM 能直接传给 Spring Boot 做优雅停机。
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar /opt/datasmart/app.jar"]

<#
DataSmart Govern 容器化交付检查。

该脚本把本批手工验证沉淀为可重复质量门禁，覆盖三层事实：
1. Maven 是否能为 8 个 Spring Boot 服务生成包含 BOOT-INF 的可执行 jar；
2. 基础 Compose 与应用 overlay 是否能合并为合法且无循环依赖的部署模型；
3. Java/Python 镜像是否具备非 root 用户、健康检查和可执行入口。

默认行为不会启动全平台、不会创建业务任务、不会执行工具，也不会读取业务数据。
`-BuildRepresentativeImages` 只构建 gateway 与 Python Runtime 两个代表镜像，用于证明共享 Dockerfile 可用。
完整 8 个 Java 镜像由 Compose 使用同一 Dockerfile 和不同 MODULE 参数构建，因此无须每次门禁都重复下载依赖。

DaoCloud 是 Dockerfile 与 Compose 的默认镜像源。若 DaoCloud 临时超时，可通过参数传入 1ms.run、
企业 Harbor 或其他可信镜像；这只影响本次构建，不会修改仓库默认配置。
#>
[CmdletBinding()]
param(
    [switch]$SkipMaven,
    [switch]$SkipDockerInspection,
    [switch]$BuildRepresentativeImages,

    [string]$MavenImage = "docker.m.daocloud.io/library/maven:3.9.9-eclipse-temurin-21",
    [string]$JavaRuntimeImage = "docker.m.daocloud.io/library/eclipse-temurin:21-jre-jammy",
    [string]$PythonImage = "docker.m.daocloud.io/library/python:3.11-slim-bookworm"
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$javaModules = @(
    "gateway",
    "permission-admin",
    "task-management",
    "datasource-management",
    "data-sync",
    "data-quality",
    "agent-runtime",
    "observability"
)

function Write-Step {
    param([string]$Message)
    Write-Host "[STEP] $Message"
}

function Write-Pass {
    param([string]$Message)
    Write-Host "[PASS] $Message"
}

function Write-Warn {
    param([string]$Message)
    Write-Host "[WARN] $Message"
}

function Invoke-CheckedCommand {
    param(
        [string]$Command,
        [string[]]$Arguments
    )

    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "command failed: $Command $($Arguments -join ' ')"
    }
}

function Get-ApplicationJar {
    param([string]$Module)

    $targetDirectory = Join-Path $repositoryRoot "$Module\target"
    return Get-ChildItem -LiteralPath $targetDirectory -Filter "*.jar" |
        Where-Object {
            $_.Name -notlike "*.original" -and
            $_.Name -notlike "*-sources.jar" -and
            $_.Name -notlike "*-javadoc.jar"
        } |
        Select-Object -First 1
}

Push-Location $repositoryRoot
try {
    if (-not $SkipMaven) {
        Write-Step "build all Java modules with JDK 21 Maven Toolchain"
        Invoke-CheckedCommand -Command "mvn" -Arguments @(
            "-DskipTests",
            "package",
            "-Dmaven.repo.local=$repositoryRoot\.m2"
        )
    }

    Write-Step "verify Spring Boot executable jars"
    foreach ($module in $javaModules) {
        $jar = Get-ApplicationJar -Module $module
        if ($null -eq $jar) {
            throw "missing application jar: $module"
        }

        $entries = @(& jar tf $jar.FullName)
        if ($LASTEXITCODE -ne 0 -or $entries -notcontains "BOOT-INF/") {
            throw "jar is not a Spring Boot executable archive: $($jar.FullName)"
        }
        Write-Pass "$module executable jar"
    }

    Write-Step "validate merged infrastructure and application Compose model"
    Invoke-CheckedCommand -Command "docker" -Arguments @(
        "compose",
        "-f", "docker-compose.yml",
        "-f", "docker-compose.application.yml",
        "config",
        "--quiet"
    )
    Write-Pass "Compose config"

    if ($BuildRepresentativeImages) {
        Write-Step "build representative Java image"
        Invoke-CheckedCommand -Command "docker" -Arguments @(
            "build",
            "-f", "docker/runtime/java-service.Dockerfile",
            "--build-arg", "MAVEN_IMAGE=$MavenImage",
            "--build-arg", "JAVA_RUNTIME_IMAGE=$JavaRuntimeImage",
            "--build-arg", "MODULE=gateway",
            "--build-arg", "SERVER_PORT=8080",
            "-t", "datasmart/gateway:local",
            "."
        )

        Write-Step "build Python AI Runtime image"
        Invoke-CheckedCommand -Command "docker" -Arguments @(
            "build",
            "-f", "docker/runtime/python-ai-runtime.Dockerfile",
            "--build-arg", "PYTHON_IMAGE=$PythonImage",
            "--build-arg", "PYTHON_RUNTIME_EXTRAS=api,rag,kafka,redis",
            "-t", "datasmart/python-ai-runtime:local",
            "."
        )
    }

    if (-not $SkipDockerInspection) {
        Write-Step "inspect available application image contracts"
        $imageContracts = @($javaModules | ForEach-Object {
            @{ Image = "datasmart/${_}:local"; User = "datasmart:datasmart" }
        })
        $imageContracts += @{
            Image = "datasmart/python-ai-runtime:local"
            User = "datasmart:datasmart"
        }

        foreach ($contract in $imageContracts) {
            & docker image inspect $contract.Image *> $null
            if ($LASTEXITCODE -ne 0) {
                Write-Warn "image not built yet, skip inspection: $($contract.Image)"
                continue
            }

            $configuredUser = (& docker image inspect $contract.Image --format "{{.Config.User}}").Trim()
            if ($configuredUser -ne $contract.User) {
                throw "image must run as non-root user $($contract.User): $($contract.Image)"
            }

            $healthCheck = (& docker image inspect $contract.Image --format "{{json .Config.Healthcheck.Test}}").Trim()
            if ([string]::IsNullOrWhiteSpace($healthCheck) -or $healthCheck -eq "null") {
                throw "image healthcheck is missing: $($contract.Image)"
            }
            Write-Pass "$($contract.Image) non-root and healthcheck"
        }
    }

    Write-Pass "containerized delivery contract is ready"
}
finally {
    Pop-Location
}

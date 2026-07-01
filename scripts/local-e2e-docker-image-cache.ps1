<#
DataSmart Govern local E2E Docker image cache helper.

设计背景：
本地真实 E2E 依赖 MySQL、Redis、Kafka、Nacos、Keycloak、Prometheus、Grafana 等多个基础镜像。
国内网络环境直连 Docker Hub 或 Quay 时经常出现 EOF、TLS timeout、connection reset 等问题。
当前 docker-compose.yml 已按项目约定默认使用 DaoCloud，并允许通过 DATASMART_*_IMAGE 覆盖私有仓库。
本脚本仍保留多国内镜像站回退，用于 DaoCloud 临时 TLS timeout 时预拉同一镜像。

脚本策略：
1. 拉取时优先尝试国内镜像前缀，例如 docker.m.daocloud.io/library/mysql:8.0。
2. 拉取成功后仍会额外 tag 成标准镜像名，例如 mysql:8.0，兼容显式覆盖和旧的本地命令。
3. 镜像站来源 tag 不会删除，因此 Compose 默认的 DaoCloud image reference 可以直接命中本地缓存。
4. 默认不回退 Docker Hub 或 Quay 官方源；如果确实需要验证官方源，必须显式传入 -AllowOfficialFallback。

安全边界：
脚本只执行 docker image inspect、docker pull、docker tag。
脚本不启动容器，不连接 MySQL，不访问业务服务，不读取业务数据。
脚本不打印密码、token、SQL、连接串、业务样本、模型输出或 HTTP 响应正文。

PowerShell 编码说明：
仓库里很多脚本需要兼容 Windows PowerShell 5.1。该版本对无 BOM UTF-8 中文行注释比较敏感，
容易在某些终端里显示乱码。为避免中文行注释影响脚本语法，本文件把学习说明放在块注释中，
运行代码区尽量只保留 ASCII 输出和清晰命名。

使用方式：
.\scripts\local-e2e-docker-image-cache.ps1
.\scripts\local-e2e-docker-image-cache.ps1 -Scope All
.\scripts\local-e2e-docker-image-cache.ps1 -ImageName nacos,kafka,keycloak
.\scripts\local-e2e-docker-image-cache.ps1 -ImageName nacos -PullTimeoutSeconds 240
.\scripts\local-e2e-docker-image-cache.ps1 -DirectFirst
.\scripts\local-e2e-docker-image-cache.ps1 -AllowOfficialFallback
.\scripts\local-e2e-docker-image-cache.ps1 -Refresh
#>
[CmdletBinding()]
param(
    [ValidateSet("Core", "All")]
    [string]$Scope = "Core",

    [string[]]$DockerHubMirrors = @(
        "docker.m.daocloud.io",
        "docker.1ms.run",
        "proxy.vvvv.ee",
        "dockerproxy.net",
        "dockerproxy.link",
        "docker.jiaxin.site",
        "hub.rat.dev"
    ),

    [string[]]$QuayMirrors = @(
        "docker.m.daocloud.io/quay.io"
    ),

    [string[]]$ImageName = @(),

    [ValidateRange(30, 1800)]
    [int]$PullTimeoutSeconds = 420,

    [ValidateRange(1, 5)]
    [int]$RetryPerReference = 2,

    [switch]$DirectFirst,

    [switch]$AllowOfficialFallback,

    [switch]$Refresh
)

$ErrorActionPreference = "Continue"

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

function Write-Fail {
    param([string]$Message)
    Write-Host "[FAIL] $Message"
}

<#
把用户传入的 registry mirror 规范化为 Docker image reference 能识别的 host。
Docker CLI 的 image reference 不能写成 https://host/namespace/image:tag，
必须写成 host/namespace/image:tag，因此这里统一去掉协议头和末尾斜杠。
#>
function Normalize-RegistryHost {
    param([string]$RegistryHost)
    return ($RegistryHost -replace "^https?://", "").TrimEnd("/")
}

<#
检查 Docker CLI 与 daemon 是否同时可用。
真实 E2E 环境需要 daemon 可拉取镜像和启动容器，仅 PATH 中存在 docker.exe 还不够。
#>
function Test-DockerAvailable {
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -eq $docker) {
        Write-Fail "Docker CLI not found in PATH."
        return $false
    }

    & docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        Write-Fail "Docker daemon is not reachable."
        return $false
    }

    return $true
}

<#
判断 Compose 需要的标准镜像 tag 是否已经存在。
默认跳过本地已有镜像，是为了避免每次闭环诊断都重复拉大镜像。
#>
function Test-DockerImageExists {
    param([string]$ImageReference)

    & docker image inspect $ImageReference *> $null
    return ($LASTEXITCODE -eq 0)
}

<#
创建镜像清单项。
Target 表示 docker-compose.yml 最终使用的标准镜像名。
Source 表示拼接国内镜像站时使用的上游仓库路径；Docker Hub 官方镜像需要 library/ 前缀。
Registry 用于区分 Docker Hub 与 Quay，因为 Keycloak 官方镜像来源是 quay.io。
#>
function New-ImageSpec {
    param(
        [string]$Name,
        [string]$Target,
        [string]$Source,
        [ValidateSet("dockerhub", "quay")]
        [string]$Registry,
        [ValidateSet("Core", "All")]
        [string]$RequiredScope
    )

    [PSCustomObject]@{
        Name = $Name
        Target = $Target
        Source = $Source
        Registry = $Registry
        RequiredScope = $RequiredScope
    }
}

<#
返回项目 Compose 中使用的镜像清单。
Core 是真实 E2E 最小闭环依赖，覆盖认证中心、配置注册、消息队列、缓存、数据库和基础可观测面。
All 会额外拉取知识图谱、对象存储、向量库和告警管理器，用于 AI/RAG/治理增强链路。
#>
function Get-ImageCatalog {
    $catalog = @(
        (New-ImageSpec -Name "mysql" -Target "mysql:8.0" -Source "library/mysql:8.0" -Registry "dockerhub" -RequiredScope "Core"),
        (New-ImageSpec -Name "redis" -Target "redis:7.2-alpine" -Source "library/redis:7.2-alpine" -Registry "dockerhub" -RequiredScope "Core"),
        (New-ImageSpec -Name "zookeeper" -Target "confluentinc/cp-zookeeper:7.6.0" -Source "confluentinc/cp-zookeeper:7.6.0" -Registry "dockerhub" -RequiredScope "Core"),
        (New-ImageSpec -Name "kafka" -Target "confluentinc/cp-kafka:7.6.0" -Source "confluentinc/cp-kafka:7.6.0" -Registry "dockerhub" -RequiredScope "Core"),
        (New-ImageSpec -Name "nacos" -Target "nacos/nacos-server:v2.3.0" -Source "nacos/nacos-server:v2.3.0" -Registry "dockerhub" -RequiredScope "Core"),
        (New-ImageSpec -Name "keycloak" -Target "quay.io/keycloak/keycloak:26.6.4" -Source "keycloak/keycloak:26.6.4" -Registry "quay" -RequiredScope "Core"),
        (New-ImageSpec -Name "prometheus" -Target "prom/prometheus:latest" -Source "prom/prometheus:latest" -Registry "dockerhub" -RequiredScope "Core"),
        (New-ImageSpec -Name "grafana" -Target "grafana/grafana:latest" -Source "grafana/grafana:latest" -Registry "dockerhub" -RequiredScope "Core"),
        (New-ImageSpec -Name "alertmanager" -Target "prom/alertmanager:latest" -Source "prom/alertmanager:latest" -Registry "dockerhub" -RequiredScope "All"),
        (New-ImageSpec -Name "neo4j" -Target "neo4j:5.20" -Source "library/neo4j:5.20" -Registry "dockerhub" -RequiredScope "All"),
        (New-ImageSpec -Name "minio" -Target "minio/minio:latest" -Source "minio/minio:latest" -Registry "dockerhub" -RequiredScope "All"),
        (New-ImageSpec -Name "chroma" -Target "chromadb/chroma:latest" -Source "chromadb/chroma:latest" -Registry "dockerhub" -RequiredScope "All")
    )

    if ($ImageName.Count -gt 0) {
        $requestedNames = @($ImageName | ForEach-Object { $_.ToLowerInvariant() })
        $knownNames = @($catalog | ForEach-Object { $_.Name.ToLowerInvariant() })
        $unknownNames = @($requestedNames | Where-Object { $knownNames -notcontains $_ })

        if ($unknownNames.Count -gt 0) {
            Write-Fail "unknown image name: $($unknownNames -join ', ')"
            Write-Step "known image names: $($knownNames -join ', ')"
            exit 1
        }

        return $catalog | Where-Object { $requestedNames -contains $_.Name.ToLowerInvariant() }
    }

    if ($Scope -eq "All") {
        return $catalog
    }

    return $catalog | Where-Object { $_.RequiredScope -eq "Core" }
}

<#
为单个镜像生成候选拉取地址。
默认顺序是 DaoCloud 优先、其他国内镜像源补充，不再自动兜底官方源。
如果需要排查某个镜像是否只有官方源可用，应显式传入 -AllowOfficialFallback 或 -DirectFirst，
避免真实 E2E 闭环被官方源超时拖住。
#>
function Get-CandidateReferences {
    param([object]$ImageSpec)

    $directReference = $ImageSpec.Target
    $mirrorReferences = @()

    if ($ImageSpec.Registry -eq "dockerhub") {
        foreach ($mirror in $DockerHubMirrors) {
            $hostName = Normalize-RegistryHost -RegistryHost $mirror
            if (-not [string]::IsNullOrWhiteSpace($hostName)) {
                $mirrorReferences += "$hostName/$($ImageSpec.Source)"
            }
        }
    }
    elseif ($ImageSpec.Registry -eq "quay") {
        foreach ($mirror in $QuayMirrors) {
            $hostName = Normalize-RegistryHost -RegistryHost $mirror
            if (-not [string]::IsNullOrWhiteSpace($hostName)) {
                $mirrorReferences += "$hostName/$($ImageSpec.Source)"
            }
        }
    }

    if ($DirectFirst) {
        return @($directReference) + $mirrorReferences
    }

    if (-not $AllowOfficialFallback) {
        return $mirrorReferences
    }

    return $mirrorReferences + @($directReference)
}

<#
对某个候选镜像执行可重试拉取。
国内镜像站有时会在最后一层出现 EOF，短暂重试可以避免把偶发网络中断误判为配置错误。
#>
function Invoke-PullWithRetry {
    param(
        [string]$ImageReference,
        [int]$RetryCount
    )

    for ($attempt = 1; $attempt -le $RetryCount; $attempt++) {
        Write-Step "pull $ImageReference attempt $attempt/$RetryCount"
        $dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
        $process = Start-Process -FilePath $dockerCommand.Source -ArgumentList @("pull", $ImageReference) -NoNewWindow -PassThru
        $completed = $process.WaitForExit($PullTimeoutSeconds * 1000)

        if (-not $completed) {
            Write-Warn "pull timeout after $PullTimeoutSeconds seconds: $ImageReference"
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
            Start-Sleep -Seconds 2
            continue
        }

        if ($process.ExitCode -eq 0) {
            return $true
        }

        Start-Sleep -Seconds ([Math]::Min(5, 1 + $attempt))
    }

    return $false
}

<#
确保 Compose 标准镜像名可用。
如果镜像是从国内镜像站拉下来的，必须重新打成 Target tag，否则 docker compose 仍会去拉原始镜像名。
#>
function Ensure-ComposeImageCached {
    param([object]$ImageSpec)

    if (-not $Refresh -and (Test-DockerImageExists -ImageReference $ImageSpec.Target)) {
        Write-Pass "$($ImageSpec.Target) already exists locally."
        return $true
    }

    $candidates = Get-CandidateReferences -ImageSpec $ImageSpec
    foreach ($candidate in $candidates) {
        $pulled = Invoke-PullWithRetry -ImageReference $candidate -RetryCount $RetryPerReference
        if (-not $pulled) {
            Write-Warn "pull failed: $candidate"
            continue
        }

        if ($candidate -ne $ImageSpec.Target) {
            & docker tag $candidate $ImageSpec.Target
            if ($LASTEXITCODE -ne 0) {
                Write-Warn "tag failed: $candidate -> $($ImageSpec.Target)"
                continue
            }
        }

        if (Test-DockerImageExists -ImageReference $ImageSpec.Target) {
            Write-Pass "$($ImageSpec.Target) is ready."
            return $true
        }
    }

    Write-Fail "$($ImageSpec.Target) is not ready after all candidates."
    return $false
}

if (-not (Test-DockerAvailable)) {
    exit 1
}

$imageCatalog = @(Get-ImageCatalog)
$failedImages = New-Object System.Collections.Generic.List[string]

Write-Step "prepare docker image cache, scope=$Scope, images=$($imageCatalog.Count)"

foreach ($image in $imageCatalog) {
    Write-Step "prepare $($image.Name) -> $($image.Target)"
    $ok = Ensure-ComposeImageCached -ImageSpec $image
    if (-not $ok) {
        $failedImages.Add($image.Target) | Out-Null
    }
}

if ($failedImages.Count -gt 0) {
    Write-Fail "image cache preparation failed: $($failedImages -join ', ')"
    exit 1
}

Write-Pass "all requested images are ready for docker compose."
exit 0

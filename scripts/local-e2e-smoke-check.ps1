<#
    DataSmart Govern 本地端到端闭环 Smoke Check。

    设计意图：
    1. 该脚本只做只读诊断，不创建任务、不触发 worker loop、不调用 datasource-management run-once，
       因此不会读取源端数据，也不会写入目标端数据。
    2. 默认模式适合开发者快速确认“本地依赖文件、Docker 容器、关键 HTTP 端点是否就绪”；
       如果只是想检查脚本语法或仓库文件完整性，可传入 -SkipDocker -SkipHttp。
    3. 默认不会因为失败直接返回非 0 退出码，便于在服务尚未启动时作为诊断工具使用；
       如果需要 CI/严格验收语义，可传入 -Strict，此时任一失败检查会让脚本退出 1。
    4. 如需验证本地 Keycloak 样例服务账号是否能被 gateway 解析为 SERVICE_ACCOUNT，
       可传入 -CheckServiceAccountToken。脚本只调用 Keycloak token endpoint 与 gateway /auth/session，
       不会把 token 打印到终端，也不会触发任何业务写入或 worker 执行动作。

    安全边界：
    - 不打印 access token、refresh token、client secret、数据库密码、SQL、样本数据或内部请求正文。
    - 不执行 POST /sync-workers/run-once，避免无意触发真实数据搬运。
    - 不执行数据库迁移，只提示迁移文件是否存在；是否应用迁移由 runbook 中的人工步骤控制。
#>
param(
    [switch]$Strict,
    [switch]$SkipDocker,
    [switch]$SkipHttp,
    [switch]$CheckServiceAccountToken,
    [int]$TimeoutSeconds = 3,
    [string]$GatewayBaseUrl = "http://localhost:8080",
    [string]$TaskManagementBaseUrl = "http://localhost:8081",
    [string]$DatasourceManagementBaseUrl = "http://localhost:8082",
    [string]$PermissionAdminBaseUrl = "http://localhost:8085",
    [string]$DataSyncBaseUrl = "http://localhost:8086",
    [string]$AgentRuntimeBaseUrl = "http://localhost:8091",
    [string]$PythonAiRuntimeBaseUrl = "http://localhost:8090",
    [string]$KeycloakBaseUrl = "http://localhost:18080",
    [string]$ServiceAccountUsername = "sync-service",
    [string]$ServiceAccountPassword = "DataSmart@123",
    [string]$ServiceAccountClientId = "datasmart-gateway",
    [string]$PrometheusBaseUrl = "http://localhost:9090",
    [string]$GrafanaBaseUrl = "http://localhost:3000"
)

$ErrorActionPreference = "Stop"
$script:Checks = New-Object System.Collections.Generic.List[object]
$script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

function Add-Check {
    param(
        [string]$Name,
        [ValidateSet("PASS", "WARN", "FAIL")]
        [string]$Status,
        [string]$Detail
    )

    $script:Checks.Add([pscustomobject]@{
        Name = $Name
        Status = $Status
        Detail = $Detail
    }) | Out-Null

    $color = switch ($Status) {
        "PASS" { "Green" }
        "WARN" { "Yellow" }
        default { "Red" }
    }
    Write-Host ("[{0}] {1} - {2}" -f $Status, $Name, $Detail) -ForegroundColor $color
}

function Test-CommandExists {
    param([string]$CommandName)
    return $null -ne (Get-Command $CommandName -ErrorAction SilentlyContinue)
}

function Test-RequiredFile {
    param(
        [string]$RelativePath,
        [string]$Purpose
    )

    $path = Join-Path $script:RepoRoot $RelativePath
    if (Test-Path -LiteralPath $path) {
        Add-Check -Name "文件存在: $RelativePath" -Status "PASS" -Detail $Purpose
    } else {
        Add-Check -Name "文件存在: $RelativePath" -Status "FAIL" -Detail "缺少该文件会影响本地闭环联调：$Purpose"
    }
}

function Test-JsonFile {
    param(
        [string]$RelativePath,
        [string]$Purpose
    )

    $path = Join-Path $script:RepoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path)) {
        Add-Check -Name "JSON 解析: $RelativePath" -Status "FAIL" -Detail "文件不存在：$Purpose"
        return
    }
    try {
        Get-Content -Encoding UTF8 -LiteralPath $path -Raw | ConvertFrom-Json | Out-Null
        Add-Check -Name "JSON 解析: $RelativePath" -Status "PASS" -Detail $Purpose
    } catch {
        Add-Check -Name "JSON 解析: $RelativePath" -Status "FAIL" -Detail "JSON 解析失败，Keycloak realm 可能无法导入"
    }
}

function Test-FileContains {
    param(
        [string]$RelativePath,
        [string]$ExpectedText,
        [string]$Purpose
    )

    $path = Join-Path $script:RepoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path)) {
        Add-Check -Name "静态契约: $RelativePath" -Status "FAIL" -Detail "文件不存在，无法验证：$Purpose"
        return
    }

    try {
        $content = Get-Content -Encoding UTF8 -LiteralPath $path -Raw
        if ($content.Contains($ExpectedText)) {
            Add-Check -Name "静态契约: $RelativePath" -Status "PASS" -Detail $Purpose
        } else {
            Add-Check -Name "静态契约: $RelativePath" -Status "FAIL" -Detail "未找到期望片段，可能导致闭环联调漂移：$Purpose"
        }
    } catch {
        Add-Check -Name "静态契约: $RelativePath" -Status "FAIL" -Detail "无法读取文件，无法验证：$Purpose"
    }
}

function Test-DockerContainers {
    if ($SkipDocker) {
        Add-Check -Name "Docker 容器检查" -Status "WARN" -Detail "已通过 -SkipDocker 跳过"
        return
    }
    if (-not (Test-CommandExists -CommandName "docker")) {
        Add-Check -Name "Docker CLI" -Status "WARN" -Detail "当前机器未发现 docker 命令；可先只执行静态与 Maven 验证"
        return
    }

    try {
        $dockerVersion = (& docker --version) -join " "
        Add-Check -Name "Docker CLI" -Status "PASS" -Detail $dockerVersion
    } catch {
        Add-Check -Name "Docker CLI" -Status "FAIL" -Detail "docker 命令存在但无法执行"
        return
    }

    try {
        $runningContainers = & docker ps --format "{{.Names}}"
        $expectedContainers = @(
            "datasmart-mysql",
            "datasmart-redis",
            "datasmart-zookeeper",
            "datasmart-kafka",
            "datasmart-nacos",
            "datasmart-keycloak",
            "datasmart-prometheus",
            "datasmart-grafana"
        )
        foreach ($containerName in $expectedContainers) {
            if ($runningContainers -contains $containerName) {
                Add-Check -Name "Docker 容器: $containerName" -Status "PASS" -Detail "容器正在运行"
            } else {
                Add-Check -Name "Docker 容器: $containerName" -Status "FAIL" -Detail "容器未运行；可执行 docker compose up -d $($containerName -replace '^datasmart-', '') 或 docker compose up -d"
            }
        }
    } catch {
        Add-Check -Name "Docker 容器列表" -Status "FAIL" -Detail "无法读取 docker ps，可能 Docker daemon 未启动"
    }
}

function Invoke-ServiceAccountTokenProbe {
    if ($SkipHttp) {
        Add-Check -Name "服务账号 OIDC 身份探针" -Status "WARN" -Detail "已通过 -SkipHttp 跳过"
        return
    }
    if (-not $CheckServiceAccountToken) {
        Add-Check -Name "服务账号 OIDC 身份探针" -Status "WARN" -Detail "默认不获取 token；如需验证 sync-service -> gateway /auth/session，请追加 -CheckServiceAccountToken"
        return
    }

    $accessToken = $null
    try {
        $tokenResponse = Invoke-RestMethod -Method Post `
            -Uri "$KeycloakBaseUrl/realms/datasmart/protocol/openid-connect/token" `
            -ContentType "application/x-www-form-urlencoded" `
            -TimeoutSec $TimeoutSeconds `
            -Body @{
                grant_type = "password"
                client_id = $ServiceAccountClientId
                username = $ServiceAccountUsername
                password = $ServiceAccountPassword
            }
        $accessToken = $tokenResponse.access_token
        if ([string]::IsNullOrWhiteSpace($accessToken)) {
            Add-Check -Name "服务账号 OIDC 身份探针" -Status "FAIL" -Detail "Keycloak 返回成功但没有 access_token"
            return
        }

        $sessionResponse = Invoke-RestMethod -Method Get `
            -Uri "$GatewayBaseUrl/auth/session" `
            -TimeoutSec $TimeoutSeconds `
            -Headers @{
                Authorization = "Bearer $accessToken"
                "X-DataSmart-Trace-Id" = "local-smoke-service-account"
            }
        $principal = $sessionResponse.data
        if ($null -eq $principal) {
            Add-Check -Name "服务账号 OIDC 身份探针" -Status "FAIL" -Detail "gateway /auth/session 未返回 data 字段"
            return
        }

        $identityMatches =
            [string]$principal.tenantId -eq "10" -and
            [string]$principal.actorId -eq "9101" -and
            [string]$principal.actorRole -eq "SERVICE_ACCOUNT" -and
            [string]$principal.actorType -eq "SERVICE_ACCOUNT" -and
            [string]$principal.workspaceId -eq "system-sync"

        if ($identityMatches) {
            Add-Check -Name "服务账号 OIDC 身份探针" -Status "PASS" -Detail "sync-service 已被 gateway 解析为 SERVICE_ACCOUNT，tenantId=10, actorId=9101, workspaceId=system-sync"
        } else {
            Add-Check -Name "服务账号 OIDC 身份探针" -Status "FAIL" -Detail "gateway 返回的服务账号低敏身份字段不符合本地 realm 约定"
        }
    } catch {
        $statusCode = $null
        if ($_.Exception.Response -ne $null) {
            try {
                $statusCode = [int]$_.Exception.Response.StatusCode
            } catch {
                $statusCode = $null
            }
        }
        if ($statusCode -ne $null) {
            Add-Check -Name "服务账号 OIDC 身份探针" -Status "FAIL" -Detail "HTTP $statusCode；请确认 Keycloak、gateway 和 OIDC issuer 配置已启动并一致"
        } else {
            Add-Check -Name "服务账号 OIDC 身份探针" -Status "FAIL" -Detail "无法完成 token 获取或 /auth/session 调用；未输出 token、密码或响应正文"
        }
    } finally {
        $accessToken = $null
    }
}

function Invoke-HttpProbe {
    param(
        [string]$Name,
        [string]$Url,
        [int[]]$ExpectedStatusCodes = @(200)
    )

    if ($SkipHttp) {
        Add-Check -Name $Name -Status "WARN" -Detail "已通过 -SkipHttp 跳过：$Url"
        return
    }

    try {
        $response = Invoke-WebRequest -UseBasicParsing -Method GET -Uri $Url -TimeoutSec $TimeoutSeconds
        $statusCode = [int]$response.StatusCode
        if ($ExpectedStatusCodes -contains $statusCode) {
            Add-Check -Name $Name -Status "PASS" -Detail "HTTP $statusCode $Url"
        } else {
            Add-Check -Name $Name -Status "FAIL" -Detail "HTTP $statusCode，期望 $($ExpectedStatusCodes -join ',')：$Url"
        }
    } catch {
        $statusCode = $null
        if ($_.Exception.Response -ne $null) {
            try {
                $statusCode = [int]$_.Exception.Response.StatusCode
            } catch {
                $statusCode = $null
            }
        }
        if ($statusCode -ne $null -and ($ExpectedStatusCodes -contains $statusCode)) {
            Add-Check -Name $Name -Status "PASS" -Detail "HTTP $statusCode $Url"
        } elseif ($statusCode -ne $null) {
            Add-Check -Name $Name -Status "FAIL" -Detail "HTTP $statusCode，期望 $($ExpectedStatusCodes -join ',')：$Url"
        } else {
            Add-Check -Name $Name -Status "FAIL" -Detail "无法访问：$Url"
        }
    }
}

Set-Location $script:RepoRoot
Write-Host "DataSmart Govern 本地端到端闭环 Smoke Check" -ForegroundColor Cyan
Write-Host "仓库根目录：$script:RepoRoot" -ForegroundColor Cyan

Test-RequiredFile -RelativePath "pom.xml" -Purpose "Maven reactor 根配置，固定 JDK 21 与多模块构建"
Test-RequiredFile -RelativePath "docs/development-jdk21.md" -Purpose "JDK 21 / Maven Toolchains 使用说明"
Test-RequiredFile -RelativePath "docker-compose.yml" -Purpose "本地中间件、Keycloak、Prometheus、Grafana 启动编排"
Test-JsonFile -RelativePath "docker/keycloak/import/datasmart-realm.json" -Purpose "本地 OIDC realm、client、claim mapper 和样例用户"
Test-RequiredFile -RelativePath "docker/mysql/migrations/20260620_task_data_sync_worker_command_outbox.sql" -Purpose "task-management DataSync command outbox 表迁移"
Test-RequiredFile -RelativePath "docker/mysql/migrations/20260622_task_data_sync_worker_execution_receipt.sql" -Purpose "task-management DataSync execution receipt 表迁移"
Test-RequiredFile -RelativePath "docker/mysql/migrations/20260629_data_sync_template_execution_contract.sql" -Purpose "data-sync 模板执行契约字段迁移"
Test-RequiredFile -RelativePath "docker/mysql/migrations/20260629_data_sync_task_management_receipt_outbox.sql" -Purpose "data-sync task-management receipt outbox/retry/dead-letter 表迁移"
Test-FileContains -RelativePath "gateway/src/main/resources/application.yml" -ExpectedText "/api/internal/agent-runtime/**" -Purpose "gateway 暴露 agent-runtime 内部服务账号入口，支撑服务间调用闭环"
Test-FileContains -RelativePath "gateway/src/main/resources/application.yml" -ExpectedText "python-ai-runtime-runtime-diagnostics" -Purpose "gateway 必须把 Python Runtime 低敏诊断入口放在通用 /api/agent/** -> agent-runtime 路由之前"
Test-FileContains -RelativePath "gateway/src/main/resources/application.yml" -ExpectedText "/api/agent/skills/publication/refresh" -Purpose "统一网关必须能路由 Skill Manifest 受控刷新入口，避免 Python 诊断能力只停留在直连端口"
Test-FileContains -RelativePath "gateway/src/main/java/com/czh/datasmart/govern/gateway/config/GatewayAuthorizationProperties.java" -ExpectedText "setAllowedActorTypes(List.of(`"SERVICE_ACCOUNT`"))" -Purpose "默认内部端点同时要求服务账号角色和服务账号主体类型"
Test-FileContains -RelativePath "gateway/src/main/java/com/czh/datasmart/govern/gateway/authorization/GatewayInternalServiceEndpointGuard.java" -ExpectedText "actorTypeAllowed" -Purpose "内部端点守卫必须校验 actorType，避免人类用户 role-only 误入机器协议"
Test-FileContains -RelativePath "docker/keycloak/import/datasmart-realm.json" -ExpectedText '"username": "sync-service"' -Purpose "本地 realm 必须保留服务账号样例，用于验证 OIDC -> gateway 身份映射"

Test-DockerContainers

Invoke-HttpProbe -Name "Keycloak realm metadata" -Url "$KeycloakBaseUrl/realms/datasmart/.well-known/openid-configuration"
Invoke-HttpProbe -Name "Gateway health" -Url "$GatewayBaseUrl/actuator/health"
Invoke-HttpProbe -Name "Gateway auth capabilities" -Url "$GatewayBaseUrl/auth/capabilities"
Invoke-HttpProbe -Name "Task Management health" -Url "$TaskManagementBaseUrl/actuator/health"
Invoke-HttpProbe -Name "Task Management DataSync receipt query" -Url "$TaskManagementBaseUrl/internal/data-sync-worker-execution-receipts?limit=1"
Invoke-HttpProbe -Name "Datasource Management health" -Url "$DatasourceManagementBaseUrl/actuator/health"
Invoke-HttpProbe -Name "Data Sync health" -Url "$DataSyncBaseUrl/actuator/health"
Invoke-HttpProbe -Name "Data Sync connector capabilities" -Url "$DataSyncBaseUrl/sync-connectors/capabilities"
Invoke-HttpProbe -Name "Permission Admin health" -Url "$PermissionAdminBaseUrl/actuator/health"
Invoke-HttpProbe -Name "Agent Runtime health" -Url "$AgentRuntimeBaseUrl/actuator/health"
Invoke-HttpProbe -Name "Python AI Runtime closure readiness" -Url "$PythonAiRuntimeBaseUrl/agent/capabilities/closure-readiness"
Invoke-HttpProbe -Name "Python AI Runtime Skill Manifest diagnostics" -Url "$PythonAiRuntimeBaseUrl/agent/skills/publication/diagnostics"
Invoke-HttpProbe -Name "Python AI Runtime inference optimization diagnostics" -Url "$PythonAiRuntimeBaseUrl/agent/models/inference-optimization/diagnostics"
Invoke-HttpProbe -Name "Prometheus ready" -Url "$PrometheusBaseUrl/-/ready"
Invoke-HttpProbe -Name "Grafana health" -Url "$GrafanaBaseUrl/api/health"
Invoke-ServiceAccountTokenProbe

$passed = ($script:Checks | Where-Object { $_.Status -eq "PASS" }).Count
$warned = ($script:Checks | Where-Object { $_.Status -eq "WARN" }).Count
$failed = ($script:Checks | Where-Object { $_.Status -eq "FAIL" }).Count

Write-Host ""
Write-Host ("汇总：PASS={0}, WARN={1}, FAIL={2}" -f $passed, $warned, $failed) -ForegroundColor Cyan

if ($failed -gt 0) {
    Write-Host "存在失败项。若服务尚未启动，请先按 docs/local-e2e-closure-runbook.md 启动依赖和微服务后重试。" -ForegroundColor Yellow
}

if ($Strict -and $failed -gt 0) {
    exit 1
}
exit 0

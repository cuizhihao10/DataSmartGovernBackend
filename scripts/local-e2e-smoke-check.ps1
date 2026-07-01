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
    5. 如需进一步验证“认证后的统一 gateway 入口 -> Python AI Runtime 低敏诊断/指标接口”链路，
       可传入 -CheckAgentGatewayDiagnostics。该探针会复用本地样例服务账号获取 Bearer token，
       只调用 GET 诊断端点，不读取响应正文、不打印 token、不触发工具执行或数据同步。

    安全边界：
    - 不打印 access token、refresh token、client secret、数据库密码、SQL、样本数据或内部请求正文。
    - 不执行 POST /sync-workers/run-once，避免无意触发真实数据搬运。
    - 不执行数据库迁移，只提示迁移文件是否存在；是否应用迁移由 runbook 中的人工步骤控制。
    - Gateway Agent 诊断探针只验证 OIDC、permission-admin 路由授权和 gateway 转发是否贯通，
      不把 Python Runtime 的诊断响应正文输出到终端，避免未来响应字段扩展时误泄露敏感内容。
    - Python Runtime `/agent/metrics` 和 gateway `/api/agent/metrics` 只验证 HTTP 状态码与静态契约，
      不打印 Prometheus 文本正文，避免未来新增指标时把高基数字段或业务排障内容带入终端日志。
#>
param(
    [switch]$Strict,
    [switch]$SkipDocker,
    [switch]$SkipHttp,
    [switch]$CheckServiceAccountToken,
    [switch]$CheckAgentGatewayDiagnostics,
    [int]$TimeoutSeconds = 3,
    [string]$GatewayBaseUrl = "http://localhost:8080",
    [string]$TaskManagementBaseUrl = "http://localhost:8081",
    [string]$DatasourceManagementBaseUrl = "http://localhost:8082",
    [string]$DataQualityBaseUrl = "http://localhost:8083",
    [string]$ObservabilityBaseUrl = "http://localhost:8084",
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

function Get-ServiceAccountAccessToken {
    <#
        获取本地 Keycloak 样例服务账号 access token。

        设计说明：
        - 当前 smoke 脚本只在开发环境使用仓库内置 realm 样例账号，目的是验证 OIDC/JWT
          到 gateway 平台身份映射的真实链路，而不是在生产环境复用 password grant。
        - 该函数只把 token 作为内存中的临时变量返回给调用方，调用方必须在 finally 中清空引用；
          任何日志、异常说明和检查结果都不得打印 token、refresh token、完整 JWT claim 或密码。
        - 生产环境服务间调用应替换为 OIDC client credentials、企业 IdP 托管服务账号、
          mTLS/service mesh 身份或云 IAM，并把 client secret 放入 Secret Manager。
    #>
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
        throw "Keycloak 返回成功但没有 access_token"
    }
    return $accessToken
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
        $accessToken = Get-ServiceAccountAccessToken
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

function Invoke-AgentGatewayDiagnosticsProbe {
    <#
        验证认证后的 gateway 是否能到达 Python AI Runtime 低敏诊断/指标入口。

        为什么要单独做这个探针：
        - 直连 `http://localhost:8090/agent/...` 只能证明 Python Runtime 自身启动了，
          不能证明生产入口路径中必须经过的 OIDC、permission-admin、gateway route rewrite
          和下游转发全部贯通。
        - 这些诊断和指标接口属于“闭环可观测入口”，不是业务执行入口；因此脚本只允许 GET，
          只检查 HTTP 状态码，不解析也不打印响应正文。
        - 如果返回 401/403，优先排查 Keycloak claim、audience、gateway OIDC 配置和
          permission-admin 路由策略；如果返回 502/503/timeout，优先排查 gateway 路由顺序、
          Python Runtime 8090 启动状态和服务发现地址。
    #>
    if ($SkipHttp) {
        Add-Check -Name "Gateway Agent 诊断路由探针" -Status "WARN" -Detail "已通过 -SkipHttp 跳过"
        return
    }
    if (-not $CheckAgentGatewayDiagnostics) {
        Add-Check -Name "Gateway Agent 诊断路由探针" -Status "WARN" -Detail "默认不获取 token 验证 /api/agent 诊断转发；如需验证，请追加 -CheckAgentGatewayDiagnostics"
        return
    }

    $accessToken = $null
    try {
        $accessToken = Get-ServiceAccountAccessToken
    } catch {
        Add-Check -Name "Gateway Agent 诊断路由认证准备" -Status "FAIL" -Detail "无法从 Keycloak 获取本地样例服务账号 token；未输出 token、密码或响应正文"
        return
    }

    try {
        $diagnosticEndpoints = @(
            @{
                Name = "Gateway Agent closure readiness"
                Path = "/api/agent/capabilities/closure-readiness"
                Meaning = "统一 gateway 能以认证身份访问 Agent Host 能力闭口诊断"
            },
            @{
                Name = "Gateway Agent Skill Manifest diagnostics"
                Path = "/api/agent/skills/publication/diagnostics"
                Meaning = "统一 gateway 能以认证身份访问 Skill Manifest 缓存与刷新诊断"
            },
            @{
                Name = "Gateway Agent inference optimization diagnostics"
                Path = "/api/agent/models/inference-optimization/diagnostics"
                Meaning = "统一 gateway 能以认证身份访问模型推理优化控制面诊断"
            },
            @{
                Name = "Gateway Agent Prometheus metrics"
                Path = "/api/agent/metrics"
                Meaning = "统一 gateway 能以认证身份访问 Python Runtime 低基数 Prometheus 指标入口"
            }
        )

        foreach ($endpoint in $diagnosticEndpoints) {
            $url = "$GatewayBaseUrl$($endpoint.Path)"
            try {
                $response = Invoke-WebRequest -UseBasicParsing -Method GET `
                    -Uri $url `
                    -TimeoutSec $TimeoutSeconds `
                    -Headers @{
                        Authorization = "Bearer $accessToken"
                        "X-DataSmart-Trace-Id" = "local-smoke-agent-diagnostics"
                    }
                $statusCode = [int]$response.StatusCode
                if ($statusCode -eq 200) {
                    Add-Check -Name $endpoint.Name -Status "PASS" -Detail "HTTP 200；$($endpoint.Meaning)"
                } else {
                    Add-Check -Name $endpoint.Name -Status "FAIL" -Detail "HTTP $statusCode；期望 HTTP 200，未输出诊断响应正文"
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
                if ($statusCode -eq 401 -or $statusCode -eq 403) {
                    Add-Check -Name $endpoint.Name -Status "FAIL" -Detail "HTTP $statusCode；认证或授权未通过，请检查 Keycloak claim、audience、gateway OIDC 和 permission-admin 路由策略"
                } elseif ($statusCode -eq 502 -or $statusCode -eq 503) {
                    Add-Check -Name $endpoint.Name -Status "FAIL" -Detail "HTTP $statusCode；gateway 无法到达 Python Runtime，请检查路由顺序、8090 端口和服务地址"
                } elseif ($statusCode -ne $null) {
                    Add-Check -Name $endpoint.Name -Status "FAIL" -Detail "HTTP $statusCode；期望 HTTP 200，未输出诊断响应正文"
                } else {
                    Add-Check -Name $endpoint.Name -Status "FAIL" -Detail "无法访问 $url；请确认 gateway、permission-admin、Keycloak 和 Python Runtime 均已启动"
                }
            }
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
Test-RequiredFile -RelativePath "scripts/local-mysql-migration-governance.ps1" -Purpose "本地 MySQL 增量迁移治理入口，支持迁移清单检查、执行计划、Apply 和 Baseline 补登记"
Test-JsonFile -RelativePath "docker/keycloak/import/datasmart-realm.json" -Purpose "本地 OIDC realm、client、claim mapper 和样例用户"
Test-RequiredFile -RelativePath "docker/mysql/migrations/20260620_task_data_sync_worker_command_outbox.sql" -Purpose "task-management DataSync command outbox 表迁移"
Test-RequiredFile -RelativePath "docker/mysql/migrations/20260622_task_data_sync_worker_execution_receipt.sql" -Purpose "task-management DataSync execution receipt 表迁移"
Test-RequiredFile -RelativePath "docker/mysql/migrations/20260629_data_sync_template_execution_contract.sql" -Purpose "data-sync 模板执行契约字段迁移"
Test-RequiredFile -RelativePath "docker/mysql/migrations/20260629_data_sync_task_management_receipt_outbox.sql" -Purpose "data-sync task-management receipt outbox/retry/dead-letter 表迁移"
Test-FileContains -RelativePath "gateway/src/main/resources/application.yml" -ExpectedText "/api/internal/agent-runtime/**" -Purpose "gateway 暴露 agent-runtime 内部服务账号入口，支撑服务间调用闭环"
Test-FileContains -RelativePath "gateway/src/main/resources/application.yml" -ExpectedText "python-ai-runtime-runtime-diagnostics" -Purpose "gateway 必须把 Python Runtime 低敏诊断入口放在通用 /api/agent/** -> agent-runtime 路由之前"
Test-FileContains -RelativePath "gateway/src/main/resources/application.yml" -ExpectedText "/api/agent/skills/publication/refresh" -Purpose "统一网关必须能路由 Skill Manifest 受控刷新入口，避免 Python 诊断能力只停留在直连端口"
Test-FileContains -RelativePath "gateway/src/main/resources/application.yml" -ExpectedText "/api/agent/metrics" -Purpose "统一网关必须能路由 Python Runtime Prometheus 低基数指标入口，避免 AI 运行时指标只停留在直连端口"
Test-FileContains -RelativePath "gateway/src/main/resources/application.yml" -ExpectedText "RewritePath=/api/observability/" -Purpose "gateway 必须把 /api/observability/** 改写到 observability 服务内部领域路径，避免可观测性服务只存在直连入口"
Test-FileContains -RelativePath "gateway/src/main/java/com/czh/datasmart/govern/gateway/config/GatewayAuthorizationProperties.java" -ExpectedText "/api/agent/metrics" -Purpose "gateway 默认授权元数据必须识别 Python Runtime 指标入口，避免未加载 YAML 或配置中心覆盖时退化为通配规则"
Test-RequiredFile -RelativePath "python-ai-runtime/src/datasmart_ai_runtime/services/memory/langgraph_memory_retrieval_metrics.py" -Purpose "LangGraph 长期记忆检索观察图的 Prometheus 低基数指标实现"
Test-FileContains -RelativePath "python-ai-runtime/src/datasmart_ai_runtime/services/memory/langgraph_memory_retrieval_metrics.py" -ExpectedText "datasmart_ai_langgraph_memory_retrieval_workflows_total" -Purpose "agentMemoryRetrievalWorkflow 必须暴露 workflow 级指标，便于发现记忆检索观察图是否执行、fallback 或失败"
Test-FileContains -RelativePath "python-ai-runtime/src/datasmart_ai_runtime/api/metrics.py" -ExpectedText "langgraph_memory_retrieval_metrics.render_prometheus" -Purpose "/agent/metrics 必须合并长期记忆检索指标，而不是只停留在独立类实现"
Test-FileContains -RelativePath "python-ai-runtime/src/datasmart_ai_runtime/api/agent/plan_response.py" -ExpectedText "langgraph_memory_retrieval_metrics.record_summary(agent_memory_retrieval_workflow_summary)" -Purpose "/agent/plans 必须在生成 agentMemoryRetrievalWorkflow 后记录同一份低敏 summary 指标"
Test-FileContains -RelativePath "python-ai-runtime/src/datasmart_ai_runtime/services/multi_agent/product_agent_catalog.py" -ExpectedText "RUNTIME_AGENT_DELIVERY_TIERS" -Purpose "多 Agent 必须固化必做、控制范围和轻量化交付分层，避免继续无边界扩张角色"
Test-FileContains -RelativePath "python-ai-runtime/src/datasmart_ai_runtime/services/langgraph_multi_agent_collaboration.py" -ExpectedText "runtimeAgentDeliveryTiers" -Purpose "多智能体协作图必须输出运行时交付分层，方便前端、gateway 和后续 E2E 判断哪些 Agent 已进入闭环范围"
Test-FileContains -RelativePath "python-ai-runtime/src/datasmart_ai_runtime/services/agent_capability/agent_capability_matrix.py" -ExpectedText "datasmart_ai_langgraph_memory_retrieval_*" -Purpose "能力矩阵必须记录 LangGraph 记忆检索指标证据，避免闭口诊断落后于真实实现"
Test-FileContains -RelativePath "data-quality/src/main/java/com/czh/datasmart/govern/quality/controller/QualityReportExportController.java" -ExpectedText "low-sensitive-csv" -Purpose "data-quality 必须提供低敏质量报告导出入口，避免质量报告能力只停留在在线查询"
Test-FileContains -RelativePath "observability/src/main/java/com/czh/datasmart/govern/observability/controller/ObservabilityPlatformController.java" -ExpectedText "alert-coverage" -Purpose "observability 必须提供平台基础告警覆盖视图，避免可观测性只停留在 health 探活"
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
Invoke-HttpProbe -Name "Data Quality health" -Url "$DataQualityBaseUrl/actuator/health"
Invoke-HttpProbe -Name "Data Quality executor diagnostics" -Url "$DataQualityBaseUrl/quality-rules/executor/diagnostics"
Invoke-HttpProbe -Name "Data Sync health" -Url "$DataSyncBaseUrl/actuator/health"
Invoke-HttpProbe -Name "Data Sync connector capabilities" -Url "$DataSyncBaseUrl/sync-connectors/capabilities"
Invoke-HttpProbe -Name "Permission Admin health" -Url "$PermissionAdminBaseUrl/actuator/health"
Invoke-HttpProbe -Name "Agent Runtime health" -Url "$AgentRuntimeBaseUrl/actuator/health"
Invoke-HttpProbe -Name "Observability health" -Url "$ObservabilityBaseUrl/actuator/health"
Invoke-HttpProbe -Name "Observability platform closure readiness" -Url "$ObservabilityBaseUrl/observability/platform/closure-readiness"
Invoke-HttpProbe -Name "Observability service health snapshots" -Url "$ObservabilityBaseUrl/observability/platform/service-health-snapshots"
Invoke-HttpProbe -Name "Observability alert coverage" -Url "$ObservabilityBaseUrl/observability/platform/alert-coverage"
Invoke-HttpProbe -Name "Python AI Runtime closure readiness" -Url "$PythonAiRuntimeBaseUrl/agent/capabilities/closure-readiness"
Invoke-HttpProbe -Name "Python AI Runtime Skill Manifest diagnostics" -Url "$PythonAiRuntimeBaseUrl/agent/skills/publication/diagnostics"
Invoke-HttpProbe -Name "Python AI Runtime inference optimization diagnostics" -Url "$PythonAiRuntimeBaseUrl/agent/models/inference-optimization/diagnostics"
Invoke-HttpProbe -Name "Python AI Runtime Prometheus metrics" -Url "$PythonAiRuntimeBaseUrl/agent/metrics"
Invoke-HttpProbe -Name "Prometheus ready" -Url "$PrometheusBaseUrl/-/ready"
Invoke-HttpProbe -Name "Grafana health" -Url "$GrafanaBaseUrl/api/health"
Invoke-ServiceAccountTokenProbe
Invoke-AgentGatewayDiagnosticsProbe

$passed = @($script:Checks | Where-Object { $_.Status -eq "PASS" }).Count
$warned = @($script:Checks | Where-Object { $_.Status -eq "WARN" }).Count
$failed = @($script:Checks | Where-Object { $_.Status -eq "FAIL" }).Count

Write-Host ""
Write-Host ("汇总：PASS={0}, WARN={1}, FAIL={2}" -f $passed, $warned, $failed) -ForegroundColor Cyan

if ($failed -gt 0) {
    Write-Host "存在失败项。若服务尚未启动，请先按 docs/local-e2e-closure-runbook.md 启动依赖和微服务后重试。" -ForegroundColor Yellow
}

if ($Strict -and $failed -gt 0) {
    exit 1
}
exit 0

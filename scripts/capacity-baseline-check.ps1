<#
DataSmart Govern 容量基线就绪检查脚本。

这个脚本的职责不是直接在开发机上发起压测，而是验证仓库是否已经具备“可以安全开展容量验收”的交付材料。
原因有三点：
1. 真实容量压测需要客户环境的资源配额、镜像版本、Secret、测试账号、测试数据和告警路由；开发机上随手压测得到的数字很容易误导后续资源规划。
2. 当前项目处于收敛闭环阶段，高风险 worker、dispatcher、真实工具提交和写入型执行器默认关闭；容量基线的第一步应该先覆盖只读/诊断链路，再由客户环境逐步扩大到写入链路。
3. 性能验收不仅是 QPS 数字，还包括 P95/P99 延迟、错误率、Kafka backlog、数据库慢查询、缓存命中率、向量检索耗时、Agent plan 耗时和资源水位。

默认模式只做静态检查：
- 检查容量基线 Runbook 是否存在，并覆盖 gateway、Java 服务、Python Runtime、Kafka 和关键存储；
- 检查生产加固文档和最终交付清单是否引用本脚本；
- 检查 Prometheus/Grafana/Alertmanager 这类观测配置是否存在，避免压测后无指标可看；
- 检查生成的计划文件是否写入 Git 忽略的 target/ 目录。

可选能力：
- 使用 -CheckLocalTools 检查 docker、k6、hey、wrk、ab 等工具是否安装，但不直接运行这些工具；
- 使用 -WriteBaselinePlan 输出不含 Secret 的容量基线计划 JSON，供 CI、客户验收或后续真实压测脚本引用；
- 使用 -StrictTooling 可把缺少压测工具等 warning 提升为失败，适合发布前 CI 或专用压测 runner。
#>
[CmdletBinding()]
param(
    [switch]$StrictTooling,
    [switch]$CheckLocalTools,
    [switch]$WriteBaselinePlan,
    [string]$OutputDirectory = "target/capacity-baseline"
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$passCount = 0
$warnCount = 0
$failCount = 0

function Write-CheckResult {
    param(
        [ValidateSet("PASS", "WARN", "FAIL")]
        [string]$Level,
        [string]$Name,
        [string]$Detail
    )

    if ($Level -eq "PASS") {
        $script:passCount++
        Write-Host "[PASS] $Name - $Detail"
        return
    }

    if ($Level -eq "WARN") {
        $script:warnCount++
        Write-Host "[WARN] $Name - $Detail"
        return
    }

    $script:failCount++
    Write-Host "[FAIL] $Name - $Detail"
}

function Get-RepositoryContent {
    param([string]$RelativePath)

    $path = Join-Path $repositoryRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path)) {
        return $null
    }

    return Get-Content -Raw -Encoding UTF8 -LiteralPath $path
}

function Test-RequiredFile {
    param(
        [string]$RelativePath,
        [string]$Purpose
    )

    $path = Join-Path $repositoryRoot $RelativePath
    if (Test-Path -LiteralPath $path) {
        Write-CheckResult -Level "PASS" -Name $RelativePath -Detail $Purpose
    }
    else {
        Write-CheckResult -Level "FAIL" -Name $RelativePath -Detail "missing required capacity-baseline artifact: $Purpose"
    }
}

function Test-RequiredText {
    param(
        [string]$RelativePath,
        [string]$ExpectedText,
        [string]$Purpose
    )

    $content = Get-RepositoryContent -RelativePath $RelativePath
    if ($null -eq $content) {
        Write-CheckResult -Level "FAIL" -Name $RelativePath -Detail "missing file: $Purpose"
        return
    }

    if ($content.Contains($ExpectedText)) {
        Write-CheckResult -Level "PASS" -Name $RelativePath -Detail $Purpose
    }
    else {
        Write-CheckResult -Level "FAIL" -Name $RelativePath -Detail "missing '$ExpectedText': $Purpose"
    }
}

function Test-AnyText {
    param(
        [string]$Name,
        [string]$RelativePath,
        [string[]]$ExpectedTexts,
        [string]$Purpose
    )

    $content = Get-RepositoryContent -RelativePath $RelativePath
    if ($null -eq $content) {
        Write-CheckResult -Level "FAIL" -Name $RelativePath -Detail "missing file: $Purpose"
        return
    }

    foreach ($text in $ExpectedTexts) {
        if ($content.Contains($text)) {
            Write-CheckResult -Level "PASS" -Name $Name -Detail $Purpose
            return
        }
    }

    Write-CheckResult -Level "FAIL" -Name $Name -Detail "missing any expected contract text: $($ExpectedTexts -join ', ')"
}

function Test-CommandAvailable {
    param(
        [string]$CommandName,
        [string]$Purpose
    )

    if ($null -ne (Get-Command $CommandName -ErrorAction SilentlyContinue)) {
        Write-CheckResult -Level "PASS" -Name $CommandName -Detail $Purpose
    }
    else {
        Write-CheckResult -Level "WARN" -Name $CommandName -Detail "tool is not installed on this machine; install it only in CI or dedicated load-test runners when real benchmarking starts"
    }
}

$baselineScopes = @(
    [pscustomobject]@{
        Name = "gateway"
        ExpectedText = "Gateway"
        Purpose = "capacity baseline must cover authentication, route proxying, and agent-control-plane ingress latency"
        Metrics = @("p95_latency_ms", "p99_latency_ms", "http_error_rate", "route_rps")
    },
    [pscustomobject]@{
        Name = "java-services"
        # 机器断言使用 ASCII 稳定词，避免 Windows PowerShell 5 以系统代码页读取无 BOM 脚本时误解码中文。
        ExpectedText = "data-quality"
        Purpose = "capacity baseline must cover task, datasource, data-sync, data-quality, permission, observability, and agent-runtime APIs"
        Metrics = @("p95_latency_ms", "p99_latency_ms", "jvm_memory_used", "jvm_threads", "http_server_requests")
    },
    [pscustomobject]@{
        Name = "python-runtime"
        ExpectedText = "Python Runtime"
        Purpose = "capacity baseline must cover agent plan, LangGraph gate, memory retrieval, model-provider diagnostics, and metrics export"
        Metrics = @("agent_plan_p95_ms", "langgraph_gate_p95_ms", "memory_retrieval_p95_ms", "model_provider_error_rate")
    },
    [pscustomobject]@{
        Name = "kafka"
        ExpectedText = "Kafka"
        Purpose = "capacity baseline must cover worker commands, receipts, runtime events, outbox backlog, and consumer lag"
        Metrics = @("consumer_lag", "topic_backlog", "publish_error_rate", "dead_letter_count")
    },
    [pscustomobject]@{
        Name = "storage"
        ExpectedText = "MySQL"
        Purpose = "capacity baseline must cover MySQL, Redis, MinIO, Chroma, and Neo4j bottleneck indicators"
        Metrics = @("mysql_slow_query_count", "redis_hit_rate", "minio_latency_ms", "chroma_query_p95_ms", "neo4j_query_p95_ms")
    },
    [pscustomobject]@{
        Name = "agent-plan"
        ExpectedText = "Agent plan"
        Purpose = "capacity baseline must cover multi-agent plan generation, event envelope size, and read-only smoke control-plane latency"
        Metrics = @("plan_generation_p95_ms", "runtime_event_count", "envelope_bytes", "agent_error_rate")
    }
)

$observabilityContracts = @(
    [pscustomobject]@{
        Path = "docker/prometheus/prometheus.application.yml"
        Purpose = "Prometheus application scrape config is needed before capacity results can be correlated with service metrics"
    },
    [pscustomobject]@{
        Path = "docker/prometheus/rules/agent-runtime-alerts.yml"
        Purpose = "Agent Runtime alert rules provide a starting point for agent and outbox pressure observation"
    },
    [pscustomobject]@{
        Path = "docker/prometheus/rules/python-ai-runtime-alerts.yml"
        Purpose = "Python Runtime alert rules provide a starting point for AI runtime latency and error observation"
    },
    [pscustomobject]@{
        Path = "docker/grafana/dashboards/data-sync-overview.json"
        Purpose = "Grafana dashboard config proves that baseline evidence can be visualized instead of remaining raw logs"
    },
    [pscustomobject]@{
        Path = "docker/alertmanager/alertmanager.yml"
        Purpose = "Alertmanager config is needed when baseline thresholds evolve into operational alerts"
    }
)

Push-Location $repositoryRoot
try {
    Write-Host "[STEP] verify capacity-baseline documentation contract"
    Test-RequiredFile -RelativePath "docs/capacity-baseline-runbook.md" -Purpose "capacity baseline runbook for production performance acceptance"
    Test-RequiredText -RelativePath "docs/capacity-baseline-runbook.md" -ExpectedText "P95" -Purpose "runbook must define latency percentile acceptance language"
    Test-RequiredText -RelativePath "docs/capacity-baseline-runbook.md" -ExpectedText "P99" -Purpose "runbook must define tail-latency acceptance language"
    Test-RequiredText -RelativePath "docs/capacity-baseline-runbook.md" -ExpectedText "SLO" -Purpose "runbook must connect capacity numbers to service-level objectives"
    Test-RequiredText -RelativePath "docs/capacity-baseline-runbook.md" -ExpectedText "Stage 1" -Purpose "first baseline stage must start from read-only and diagnostic paths"

    foreach ($scope in $baselineScopes) {
        Test-RequiredText -RelativePath "docs/capacity-baseline-runbook.md" -ExpectedText $scope.ExpectedText -Purpose $scope.Purpose
    }

    Write-Host "[STEP] verify observability evidence paths"
    foreach ($contract in $observabilityContracts) {
        Test-RequiredFile -RelativePath $contract.Path -Purpose $contract.Purpose
    }
    Test-AnyText -Name "prometheus-scrape-actuator" -RelativePath "docker/prometheus/prometheus.application.yml" -ExpectedTexts @("/actuator/prometheus", "actuator/prometheus") -Purpose "Prometheus must scrape Java Actuator or Python metrics before baseline reports are credible"

    Write-Host "[STEP] verify delivery documentation wiring"
    Test-RequiredText -RelativePath "docs/production-hardening-runbook.md" -ExpectedText "capacity-baseline-check.ps1" -Purpose "production hardening runbook must expose the capacity-baseline readiness gate"
    Test-RequiredText -RelativePath "docs/final-convergence-delivery-checklist.md" -ExpectedText "capacity-baseline-check.ps1" -Purpose "final delivery checklist must include the capacity-baseline readiness gate"
    Test-RequiredText -RelativePath ".gitignore" -ExpectedText "target/" -Purpose "generated baseline plans and reports must stay outside committed source files"

    Write-Host "[STEP] inspect optional load-test tooling"
    if ($CheckLocalTools) {
        Test-CommandAvailable -CommandName "docker" -Purpose "Docker is useful for running isolated load-test tools"
        Test-CommandAvailable -CommandName "k6" -Purpose "k6 is recommended for HTTP scenario baselines and report export"
        Test-CommandAvailable -CommandName "hey" -Purpose "hey can provide lightweight HTTP smoke pressure checks"
        Test-CommandAvailable -CommandName "wrk" -Purpose "wrk can provide high-throughput HTTP benchmarks on Linux runners"
        Test-CommandAvailable -CommandName "ab" -Purpose "ApacheBench can be used as a fallback HTTP benchmark tool"
    }
    else {
        Write-CheckResult -Level "WARN" -Name "load-test-tooling" -Detail "local tool checks skipped; use -CheckLocalTools in CI or dedicated capacity runners"
    }

    if ($WriteBaselinePlan) {
        Write-Host "[STEP] write capacity baseline plan"
        $resolvedOutputDirectory = Join-Path $repositoryRoot $OutputDirectory
        New-Item -ItemType Directory -Force -Path $resolvedOutputDirectory | Out-Null

        $plan = [pscustomobject]@{
            schema = "datasmart.capacity-baseline.plan.v1"
            generatedAt = (Get-Date).ToUniversalTime().ToString("o")
            scope = $baselineScopes | Select-Object Name, Purpose, Metrics
            observabilityEvidence = $observabilityContracts | Select-Object Path, Purpose
            recommendedStages = @(
                "stage-1-readonly-control-plane",
                "stage-2-diagnostic-apis",
                "stage-3-controlled-async-backlog",
                "stage-4-customer-approved-write-paths"
            )
            safetyBoundaries = @(
                "No secrets, tokens, credentials, prompts, model outputs, table rows, exported files, or customer data are included.",
                "Default baseline readiness does not run load tests or mutate business data.",
                "Enable real write-path benchmarks only after approval, audit, rollback, and isolated test data are ready."
            )
            runbooks = @(
                "docs/capacity-baseline-runbook.md",
                "docs/production-hardening-runbook.md",
                "docs/final-convergence-delivery-checklist.md"
            )
        }

        $outputFile = Join-Path $resolvedOutputDirectory "datasmart-capacity-baseline-plan.json"
        $plan | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath $outputFile
        Write-CheckResult -Level "PASS" -Name $OutputDirectory -Detail "wrote capacity baseline plan to $outputFile"
    }

    Write-Host "[SUMMARY] PASS=$passCount, WARN=$warnCount, FAIL=$failCount"

    if ($failCount -gt 0) {
        exit 1
    }

    if ($StrictTooling -and $warnCount -gt 0) {
        Write-Host "[FAIL] strict capacity-baseline gate is enabled, warnings are treated as release blockers"
        exit 1
    }

    Write-Host "[PASS] capacity-baseline readiness check completed"
}
finally {
    Pop-Location
}

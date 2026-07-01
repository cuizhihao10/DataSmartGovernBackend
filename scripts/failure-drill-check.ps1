<#
DataSmart Govern 故障演练就绪检查脚本。

本脚本用于验证仓库是否具备开展生产级故障演练所需的文档、场景、观测和恢复边界。
默认模式严格保持非破坏性：不停止容器、不断开网络、不删除 volume、不修改数据库、不读取 Secret、
不触发 worker 或工具执行。真实故障注入必须由人工审批，并在隔离预生产环境中按 Runbook 执行。

可选参数：
- -CheckLocalTools：只检查 Docker、kubectl、Helm 等演练工具是否存在，不调用它们注入故障；
- -WriteDrillPlan：输出不含 Secret 和业务数据的 JSON 场景计划到 Git 忽略的 target/ 目录；
- -StrictTooling：将工具链 warning 提升为失败，供专用演练 runner 或发布 CI 使用。
#>
[CmdletBinding()]
param(
    [switch]$StrictTooling,
    [switch]$CheckLocalTools,
    [switch]$WriteDrillPlan,
    [string]$OutputDirectory = "target/failure-drills"
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

    switch ($Level) {
        "PASS" { $script:passCount++ }
        "WARN" { $script:warnCount++ }
        "FAIL" { $script:failCount++ }
    }
    Write-Host "[$Level] $Name - $Detail"
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
    param([string]$RelativePath, [string]$Purpose)

    if (Test-Path -LiteralPath (Join-Path $repositoryRoot $RelativePath)) {
        Write-CheckResult "PASS" $RelativePath $Purpose
    }
    else {
        Write-CheckResult "FAIL" $RelativePath "missing required failure-drill artifact: $Purpose"
    }
}

function Test-RequiredText {
    param([string]$RelativePath, [string]$ExpectedText, [string]$Purpose)

    $content = Get-RepositoryContent $RelativePath
    if ($null -eq $content) {
        Write-CheckResult "FAIL" $RelativePath "missing file: $Purpose"
        return
    }
    if ($content.Contains($ExpectedText)) {
        Write-CheckResult "PASS" $RelativePath $Purpose
    }
    else {
        Write-CheckResult "FAIL" $RelativePath "missing '$ExpectedText': $Purpose"
    }
}

function Test-CommandAvailable {
    param([string]$CommandName, [string]$Purpose)

    if ($null -ne (Get-Command $CommandName -ErrorAction SilentlyContinue)) {
        Write-CheckResult "PASS" $CommandName $Purpose
    }
    else {
        Write-CheckResult "WARN" $CommandName "tool is unavailable; install it only on CI or approved drill runners"
    }
}

# Machine-readable scenarios intentionally exclude endpoints, credentials, tenant data, and injection commands.
$drillScenarios = @(
    [pscustomobject]@{ Name = "identity-provider"; Component = "Keycloak"; FailureMode = "identity provider unavailable"; ExpectedBehavior = "new authentication fails closed without bypass"; RecoveryEvidence = "discovery, JWKS, user token, and service account token pass" },
    [pscustomobject]@{ Name = "gateway-instance"; Component = "Gateway"; FailureMode = "one ingress instance unavailable"; ExpectedBehavior = "traffic moves to healthy instances"; RecoveryEvidence = "readiness and read-only route smoke pass" },
    [pscustomobject]@{ Name = "java-service"; Component = "Java services"; FailureMode = "one core service instance unavailable"; ExpectedBehavior = "explicit failure and durable async state"; RecoveryEvidence = "health, idempotency, and backlog recovery pass" },
    [pscustomobject]@{ Name = "python-runtime"; Component = "Python Runtime"; FailureMode = "runtime or model provider timeout"; ExpectedBehavior = "agent requests degrade without bypassing gates"; RecoveryEvidence = "diagnostics, metrics, and dry-run plan pass" },
    [pscustomobject]@{ Name = "kafka-broker"; Component = "Kafka"; FailureMode = "broker unavailable"; ExpectedBehavior = "outbox remains durable and messages are not silently lost"; RecoveryEvidence = "topics, consumers, lag recovery, and idempotency pass" },
    [pscustomobject]@{ Name = "mysql-primary"; Component = "MySQL"; FailureMode = "primary unavailable"; ExpectedBehavior = "transactions fail explicitly without partial commit"; RecoveryEvidence = "migration, core tables, transactions, and smoke pass" },
    [pscustomobject]@{ Name = "redis-cache"; Component = "Redis"; FailureMode = "cache unavailable"; ExpectedBehavior = "facts remain durable and security does not fail open"; RecoveryEvidence = "session, TTL, namespace, and cache rebuild pass" },
    [pscustomobject]@{ Name = "minio-object-store"; Component = "MinIO"; FailureMode = "object store unavailable"; ExpectedBehavior = "artifact operations fail or queue explicitly"; RecoveryEvidence = "bucket, checksum, and pending operations pass" },
    [pscustomobject]@{ Name = "chroma-vector-store"; Component = "Chroma"; FailureMode = "vector retrieval timeout"; ExpectedBehavior = "memory retrieval degrades within namespace"; RecoveryEvidence = "collection, namespace filter, and retrieval pass" },
    [pscustomobject]@{ Name = "neo4j-graph-store"; Component = "Neo4j"; FailureMode = "graph query unavailable"; ExpectedBehavior = "graph enrichment degrades while MySQL facts remain authoritative"; RecoveryEvidence = "constraints, indexes, and graph queries pass" },
    [pscustomobject]@{ Name = "nacos-control-plane"; Component = "Nacos"; FailureMode = "registry or config unavailable"; ExpectedBehavior = "running services retain validated configuration"; RecoveryEvidence = "config version and service registration pass" }
)

$composeContracts = @(
    [pscustomobject]@{ File = "docker-compose.yml"; Text = "  mysql:"; Name = "mysql" },
    [pscustomobject]@{ File = "docker-compose.yml"; Text = "  redis:"; Name = "redis" },
    [pscustomobject]@{ File = "docker-compose.yml"; Text = "  kafka:"; Name = "kafka" },
    [pscustomobject]@{ File = "docker-compose.yml"; Text = "  neo4j:"; Name = "neo4j" },
    [pscustomobject]@{ File = "docker-compose.yml"; Text = "  minio:"; Name = "minio" },
    [pscustomobject]@{ File = "docker-compose.yml"; Text = "  nacos:"; Name = "nacos" },
    [pscustomobject]@{ File = "docker-compose.yml"; Text = "  keycloak:"; Name = "keycloak" },
    [pscustomobject]@{ File = "docker-compose.yml"; Text = "  chroma:"; Name = "chroma" },
    [pscustomobject]@{ File = "docker-compose.application.yml"; Text = "  gateway:"; Name = "gateway" },
    [pscustomobject]@{ File = "docker-compose.application.yml"; Text = "  agent-runtime:"; Name = "agent-runtime" },
    [pscustomobject]@{ File = "docker-compose.application.yml"; Text = "  python-ai-runtime:"; Name = "python-ai-runtime" }
)

Push-Location $repositoryRoot
try {
    Write-Host "[STEP] verify failure-drill governance contract"
    Test-RequiredFile "docs/failure-drill-runbook.md" "production failure-drill governance and scenario runbook"
    foreach ($term in @("RTO", "RPO", "blast radius", "stop condition", "Keycloak", "Gateway", "Python Runtime", "Kafka", "MySQL", "Redis", "MinIO", "Chroma", "Neo4j", "Nacos")) {
        Test-RequiredText "docs/failure-drill-runbook.md" $term "runbook must define '$term' governance or component coverage"
    }

    Write-Host "[STEP] verify deployable component coverage"
    foreach ($contract in $composeContracts) {
        Test-RequiredText $contract.File $contract.Text "Compose must expose $($contract.Name) before its drill scenario is actionable"
    }

    Write-Host "[STEP] verify observability and recovery prerequisites"
    foreach ($path in @(
        "scripts/backup-restore-check.ps1",
        "scripts/capacity-baseline-check.ps1",
        "docker/prometheus/prometheus.application.yml",
        "docker/prometheus/rules/agent-runtime-alerts.yml",
        "docker/prometheus/rules/python-ai-runtime-alerts.yml",
        "docker/alertmanager/alertmanager.yml"
    )) {
        Test-RequiredFile $path "failure drills require recovery, baseline, metrics, and alert evidence"
    }
    Test-RequiredText "docs/production-hardening-runbook.md" "failure-drill-check.ps1" "production hardening runbook must expose the failure-drill gate"
    Test-RequiredText "docs/final-convergence-delivery-checklist.md" "failure-drill-check.ps1" "final delivery checklist must expose the failure-drill gate"
    Test-RequiredText ".gitignore" "target/" "generated drill plans must stay outside committed source"

    Write-Host "[STEP] inspect optional drill tooling"
    if ($CheckLocalTools) {
        Test-CommandAvailable "docker" "Docker supports isolated Compose drill environments"
        Test-CommandAvailable "kubectl" "kubectl supports approved Kubernetes drill operations"
        Test-CommandAvailable "helm" "Helm supports release state review and controlled recovery"
    }
    else {
        Write-CheckResult "WARN" "drill-tooling" "local tool checks skipped; use -CheckLocalTools on an approved drill runner"
    }

    if ($WriteDrillPlan) {
        Write-Host "[STEP] write non-sensitive failure-drill plan"
        $outputPath = Join-Path $repositoryRoot $OutputDirectory
        New-Item -ItemType Directory -Force -Path $outputPath | Out-Null
        $plan = [pscustomobject]@{
            schema = "datasmart.failure-drill.plan.v1"
            generatedAt = (Get-Date).ToUniversalTime().ToString("o")
            scenarios = $drillScenarios
            requiredGovernance = @("human approval", "isolated environment", "blast radius", "stop condition", "recovery owner", "evidence archive")
            safetyBoundaries = @(
                "This plan contains no credentials, tokens, tenant identifiers, endpoints, customer data, prompts, or model outputs.",
                "The readiness script never stops services, changes networks, deletes volumes, or mutates business data.",
                "Real fault injection requires a separately approved environment-specific procedure."
            )
            runbooks = @("docs/failure-drill-runbook.md", "docs/backup-restore-runbook.md", "docs/capacity-baseline-runbook.md")
        }
        $outputFile = Join-Path $outputPath "datasmart-failure-drill-plan.json"
        $plan | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath $outputFile
        Write-CheckResult "PASS" $OutputDirectory "wrote non-sensitive failure-drill plan to $outputFile"
    }

    Write-Host "[SUMMARY] PASS=$passCount, WARN=$warnCount, FAIL=$failCount"
    if ($failCount -gt 0) {
        exit 1
    }
    if ($StrictTooling -and $warnCount -gt 0) {
        Write-Host "[FAIL] strict failure-drill gate is enabled, warnings are treated as release blockers"
        exit 1
    }
    Write-Host "[PASS] failure-drill readiness check completed"
}
finally {
    Pop-Location
}

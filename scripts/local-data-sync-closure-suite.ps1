<#
    DataSmart Govern 数据同步闭环验收总入口。

    设计意图：
    1. 当前数据同步能力已经形成多层自动化证据，但入口分散在多个 Maven 测试和真实数据库脚本中。
       本脚本把这些证据按“控制面 -> HTTP 合同 -> 执行面 -> 可选真实数据库”的顺序串起来，
       让后续本地验收、CI 守门和项目进度交接有一个稳定入口。
    2. 默认只运行快速、可重复、低副作用的测试：
       - data-sync 控制面 run-once 闭环；
       - OBJECT_LIST 失败对象选择性重试；
       - data-sync -> datasource-management HTTP 契约；
       - datasource-management H2/JDBC 执行面。
       这些测试不启动 Docker、不连接真实业务库、不触发真实 worker loop。
    3. 真实 MySQL -> PostgreSQL JDBC 写入验收通过 -IncludeRealJdbc 显式开启，并复用
       local-data-sync-real-e2e.ps1。这样可以避免“只是想快速回归，却意外创建/覆盖 E2E 表”的风险。

    推荐用法：
    - 查看计划，不执行 Maven：
      .\scripts\local-data-sync-closure-suite.ps1 -PlanOnly
    - 快速闭环守门：
      .\scripts\local-data-sync-closure-suite.ps1
    - 加上 data-sync/datasource-management 模块全量测试：
      .\scripts\local-data-sync-closure-suite.ps1 -IncludeModuleTestSuites
    - 加上真实 MySQL/PostgreSQL 写入验收：
      .\scripts\local-data-sync-closure-suite.ps1 -IncludeRealJdbc
    - 严格模式，任一阶段失败即退出非 0：
      .\scripts\local-data-sync-closure-suite.ps1 -Strict
#>
param(
    [switch]$PlanOnly,
    [switch]$IncludeModuleTestSuites,
    [switch]$IncludeRealJdbc,
    [switch]$SkipCompile,
    [switch]$SkipDependencyStartForRealJdbc,
    [switch]$Strict
)

$ErrorActionPreference = "Stop"
$script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$script:Checks = New-Object System.Collections.Generic.List[object]
$script:FailureCount = 0

function Add-ClosureCheck {
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
    if ($Status -eq "FAIL") {
        $script:FailureCount++
    }

    $color = switch ($Status) {
        "PASS" { "Green" }
        "WARN" { "Yellow" }
        default { "Red" }
    }
    Write-Host ("[{0}] {1} - {2}" -f $Status, $Name, $Detail) -ForegroundColor $color
}

function Get-MavenCommand {
    $mavenWrapper = Join-Path $script:RepoRoot "mvnw.cmd"
    if (Test-Path -LiteralPath $mavenWrapper) {
        return $mavenWrapper
    }
    return "mvn"
}

function Invoke-ClosureMavenStep {
    param(
        [string]$Name,
        [string[]]$Arguments,
        [string]$PassDetail,
        [string]$FailDetail
    )

    Push-Location $script:RepoRoot
    try {
        $maven = Get-MavenCommand
        Write-Host ""
        Write-Host ">>> $Name" -ForegroundColor Cyan
        & $maven @Arguments
        if ($LASTEXITCODE -eq 0) {
            Add-ClosureCheck -Name $Name -Status "PASS" -Detail $PassDetail
            return $true
        }
        Add-ClosureCheck -Name $Name -Status "FAIL" -Detail $FailDetail
        if ($Strict) {
            exit 1
        }
        return $false
    } finally {
        Pop-Location
    }
}

function Invoke-RealJdbcStep {
    $scriptPath = Join-Path $script:RepoRoot "scripts\local-data-sync-real-e2e.ps1"
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        Add-ClosureCheck -Name "Real JDBC E2E" -Status "FAIL" -Detail "local-data-sync-real-e2e.ps1 is missing"
        if ($Strict) {
            exit 1
        }
        return $false
    }

    $arguments = @("-Strict")
    if ($SkipDependencyStartForRealJdbc) {
        $arguments += "-SkipDependencyStart"
    }

    Write-Host ""
    Write-Host ">>> Real JDBC E2E" -ForegroundColor Cyan
    & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath @arguments
    if ($LASTEXITCODE -eq 0) {
        Add-ClosureCheck -Name "Real JDBC E2E" -Status "PASS" -Detail "MySQL -> PostgreSQL datasource-management real JDBC E2E passed"
        return $true
    }
    Add-ClosureCheck -Name "Real JDBC E2E" -Status "FAIL" -Detail "Real JDBC E2E failed; inspect the previous low-sensitive output"
    if ($Strict) {
        exit 1
    }
    return $false
}

function Write-ClosurePlan {
    Write-Host "DataSmart Govern data-sync closure suite" -ForegroundColor Cyan
    Write-Host "Repo root: $script:RepoRoot"
    Write-Host ""
    Write-Host "Planned quick gates:" -ForegroundColor Cyan
    Write-Host "1. data-sync control-plane run-once and object retry E2E tests"
    Write-Host "2. data-sync -> datasource-management HTTP contract E2E test"
    Write-Host "3. datasource-management H2/JDBC connector runtime E2E test"
    if (-not $SkipCompile) {
        Write-Host "4. data-sync + datasource-management compile gate"
    }
    if ($IncludeModuleTestSuites) {
        Write-Host "5. full data-sync and datasource-management module test suites"
    }
    if ($IncludeRealJdbc) {
        Write-Host "6. explicit real MySQL -> PostgreSQL JDBC E2E via local-data-sync-real-e2e.ps1"
    }
    Write-Host ""
    Write-Host "Safety boundaries:" -ForegroundColor Yellow
    Write-Host "- Default gates do not start Docker and do not write external databases."
    Write-Host "- Real JDBC E2E is opt-in and only overwrites dedicated E2E tables."
    Write-Host "- The suite does not print database passwords, JDBC URLs, SQL bodies, row samples, tokens, or raw response bodies."
}

Write-ClosurePlan
if ($PlanOnly) {
    Add-ClosureCheck -Name "Plan only" -Status "PASS" -Detail "No Maven, Docker, or database action executed"
    exit 0
}

$allPassed = $true

<#
    第一层：data-sync 控制面守门。
    这一组测试不访问真实网络和数据库，重点证明模板/执行计划/对象账本/失败对象重试/run-once 多批次派发
    在控制面内部可以形成稳定状态机。它回答的是“data-sync 是否会正确决定要派发什么、如何回写状态”。
#>
$allPassed = (Invoke-ClosureMavenStep `
    -Name "data-sync control-plane closure tests" `
    -Arguments @(
        "-pl", "data-sync",
        "-am",
        "-Dtest=SyncOfflineRunnerRunOnceControlPlaneE2ETest,SyncObjectListSelectiveRetryControlPlaneE2ETest",
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "test",
        "-DskipTests=false"
    ) `
    -PassDetail "run-once control-plane and OBJECT_LIST selective retry E2E passed" `
    -FailDetail "data-sync control-plane closure tests failed") -and $allPassed

<#
    第二层：跨微服务 HTTP 合同守门。
    这里使用真实 HttpDatasourceRunOnceClient + RestClient，但下游由本地轻量 HttpServer 模拟。
    它回答的是“data-sync 通过 HTTP JSON 合同调用 datasource-management 时，Header、请求体、响应 envelope
    和 fail-closed 策略是否仍然正确”，弥补 fake client 测试无法覆盖的序列化与状态码行为。
#>
$allPassed = (Invoke-ClosureMavenStep `
    -Name "data-sync HTTP contract E2E" `
    -Arguments @(
        "-pl", "data-sync",
        "-am",
        "-Dtest=SyncBatchRunOnceHttpContractE2ETest",
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "test",
        "-DskipTests=false"
    ) `
    -PassDetail "data-sync -> datasource-management run-once HTTP contract E2E passed" `
    -FailDetail "data-sync HTTP contract E2E failed") -and $allPassed

<#
    第三层：datasource-management 执行面守门。
    H2/JDBC E2E 不依赖 Docker，重点证明 Java Reader/Writer、字段映射、过滤条件、批次推进和写入事务
    在真实 JDBC 执行路径上可以跑通。它回答的是“执行面是否真的会读写表”，而不是只验证控制面 DTO。
#>
$allPassed = (Invoke-ClosureMavenStep `
    -Name "datasource-management JDBC runtime E2E" `
    -Arguments @(
        "-pl", "datasource-management",
        "-am",
        "-Dtest=SyncBatchConnectorRuntimeJdbcE2ETest",
        "-Dsurefire.failIfNoSpecifiedTests=false",
        "test",
        "-DskipTests=false"
    ) `
    -PassDetail "datasource-management H2/JDBC connector runtime E2E passed" `
    -FailDetail "datasource-management JDBC runtime E2E failed") -and $allPassed

if (-not $SkipCompile) {
    $allPassed = (Invoke-ClosureMavenStep `
        -Name "data-sync and datasource-management compile" `
        -Arguments @(
            "-pl", "data-sync,datasource-management",
            "-am",
            "-DskipTests",
            "compile"
        ) `
        -PassDetail "data-sync and datasource-management compile gate passed" `
        -FailDetail "compile gate failed") -and $allPassed
} else {
    Add-ClosureCheck -Name "compile gate" -Status "WARN" -Detail "Skipped by parameter"
}

if ($IncludeModuleTestSuites) {
    $allPassed = (Invoke-ClosureMavenStep `
        -Name "data-sync full test suite" `
        -Arguments @(
            "-pl", "data-sync",
            "-am",
            "test",
            "-DskipTests=false"
        ) `
        -PassDetail "data-sync full test suite passed" `
        -FailDetail "data-sync full test suite failed") -and $allPassed

    $allPassed = (Invoke-ClosureMavenStep `
        -Name "datasource-management full test suite" `
        -Arguments @(
            "-pl", "datasource-management",
            "-am",
            "test",
            "-DskipTests=false"
        ) `
        -PassDetail "datasource-management full test suite passed" `
        -FailDetail "datasource-management full test suite failed") -and $allPassed
}

if ($IncludeRealJdbc) {
    $allPassed = (Invoke-RealJdbcStep) -and $allPassed
} else {
    Add-ClosureCheck -Name "Real JDBC E2E" -Status "WARN" -Detail "Skipped by default; pass -IncludeRealJdbc to run the explicit write E2E"
}

Write-Host ""
Write-Host "Closure suite summary" -ForegroundColor Cyan
$script:Checks | Format-Table -AutoSize

if ($script:FailureCount -gt 0 -or -not $allPassed) {
    Write-Host "Data-sync closure suite finished with $script:FailureCount failure(s)." -ForegroundColor Red
    exit 1
}

Write-Host "Data-sync closure suite finished without hard failures." -ForegroundColor Green
exit 0

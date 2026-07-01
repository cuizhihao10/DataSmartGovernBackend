<#
DataSmart Govern 最终全平台闭环审计脚本。

脚本把“源码存在”“测试通过”“生产环境已经验收”明确分开：
1. 默认模式检查仓库中的业务服务、Agent 能力域、LangGraph、多智能体、身份权限、部署和运维制品；
2. -RunPythonTests 与 -RunMavenTests 用于复跑全量测试，而不是只统计旧 target 报告；
3. -WriteEvidence 只把低敏统计写入 target/，不记录 Secret、endpoint、prompt、SQL、工具参数或模型输出。

脚本不会启动服务、连接数据库、触发 worker、提交工具、执行同步、执行质量整改或进行故障注入。
#>
[CmdletBinding()]
param(
    [switch]$RunPythonTests,
    [switch]$RunMavenTests,
    [switch]$WriteEvidence,
    [switch]$Strict,
    [string]$OutputDirectory = "target/final-platform-closure"
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$passCount = 0
$warnCount = 0
$failCount = 0
$validationResults = [ordered]@{}
$oversizedProductionFiles = @()
$oversizedTestFiles = @()

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
        Write-CheckResult "FAIL" $RelativePath "missing required closure evidence: $Purpose"
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

function Get-SurefireSummary {
    $reports = Get-ChildItem -Path $repositoryRoot -Recurse -Filter "TEST-*.xml" |
        Where-Object { $_.FullName -match "\\target\\surefire-reports\\" }
    $summary = [ordered]@{ reports = $reports.Count; tests = 0; failures = 0; errors = 0; skipped = 0 }
    foreach ($report in $reports) {
        [xml]$xml = Get-Content -Raw -LiteralPath $report.FullName
        $summary.tests += [int]$xml.testsuite.tests
        $summary.failures += [int]$xml.testsuite.failures
        $summary.errors += [int]$xml.testsuite.errors
        $summary.skipped += [int]$xml.testsuite.skipped
    }
    return [pscustomobject]$summary
}

Push-Location $repositoryRoot
try {
    Write-Host "[STEP] verify Java module and business-service evidence"
    $javaModules = @(
        "platform-common", "gateway", "permission-admin", "task-management",
        "datasource-management", "data-sync", "data-quality", "agent-runtime", "observability"
    )
    foreach ($module in $javaModules) {
        Test-RequiredText "pom.xml" "<module>$module</module>" "root Maven reactor must include $module"
        Test-RequiredFile "$module/pom.xml" "$module must remain an independently buildable Maven module"
    }
    foreach ($applicationFile in @(
        "gateway/src/main/java/com/czh/datasmart/govern/gateway/GatewayApplication.java",
        "permission-admin/src/main/java/com/czh/datasmart/govern/permission/PermissionAdminApplication.java",
        "task-management/src/main/java/com/czh/datasmart/govern/task/TaskManagementApplication.java",
        "datasource-management/src/main/java/com/czh/datasmart/govern/datasource/DataSourceManagementApplication.java",
        "data-sync/src/main/java/com/czh/datasmart/govern/datasync/DataSyncApplication.java",
        "data-quality/src/main/java/com/czh/datasmart/govern/quality/DataQualityApplication.java",
        "agent-runtime/src/main/java/com/czh/datasmart/govern/agent/AgentRuntimeApplication.java",
        "observability/src/main/java/com/czh/datasmart/govern/observability/ObservabilityApplication.java"
    )) {
        Test-RequiredFile $applicationFile "commercial service module must expose a Spring Boot application entry"
    }

    Write-Host "[STEP] verify identity, permission, and ingress evidence"
    Test-RequiredFile "docker/keycloak/import/datasmart-realm.json" "Keycloak realm import is the local OIDC identity baseline"
    Test-RequiredFile "gateway/src/main/java/com/czh/datasmart/govern/gateway/config/GatewaySecurityConfig.java" "gateway must validate authenticated ingress"
    Test-RequiredFile "gateway/src/main/java/com/czh/datasmart/govern/gateway/controller/GatewayAuthenticationCenterController.java" "gateway must expose low-sensitive authentication diagnostics"
    Test-RequiredFile "permission-admin/src/main/java/com/czh/datasmart/govern/permission/controller/AgentToolActionApprovalFactController.java" "HITL approval facts belong to permission control plane"

    Write-Host "[STEP] verify complete Agent capability-domain evidence"
    $capabilityMatrix = "python-ai-runtime/src/datasmart_ai_runtime/services/agent_capability/agent_capability_matrix.py"
    foreach ($domain in @("tools", "skills", "memory", "query-engine", "context", "permission", "sub-agent", "sessions", "command", "hook", "tech-stack", "llm")) {
        Test-RequiredText $capabilityMatrix "domain_id=`"$domain`"" "Agent capability matrix must explicitly track $domain"
    }
    foreach ($file in @(
        "python-ai-runtime/src/datasmart_ai_runtime/services/tools/workspace_file_tool.py",
        "python-ai-runtime/src/datasmart_ai_runtime/services/tools/web_search_tool.py",
        "python-ai-runtime/src/datasmart_ai_runtime/services/skills/skill_visibility_cache.py",
        "python-ai-runtime/src/datasmart_ai_runtime/services/memory/memory_sqlite_fts_adapter.py",
        "python-ai-runtime/src/datasmart_ai_runtime/services/memory/memory_chroma_adapter.py",
        "python-ai-runtime/src/datasmart_ai_runtime/services/model_gateway/model_query_engine.py",
        "python-ai-runtime/src/datasmart_ai_runtime/services/context_micro_compactor.py",
        "python-ai-runtime/src/datasmart_ai_runtime/services/runtime_events/runtime_event_websocket.py",
        "python-ai-runtime/src/datasmart_ai_runtime/services/tools/controlled_command_worker_runner.py",
        "python-ai-runtime/src/datasmart_ai_runtime/services/model_gateway/model_gateway.py"
    )) {
        Test-RequiredFile $file "Agent platform domain must have concrete runtime implementation evidence"
    }

    Write-Host "[STEP] verify LangGraph and multi-agent evidence"
    Test-RequiredText "python-ai-runtime/pyproject.toml" "langgraph>=" "Python Runtime API dependency set must include LangGraph"
    foreach ($file in @(
        "python-ai-runtime/src/datasmart_ai_runtime/services/langgraph_planning_workflow.py",
        "python-ai-runtime/src/datasmart_ai_runtime/services/langgraph_multi_agent_collaboration.py",
        "python-ai-runtime/src/datasmart_ai_runtime/services/multi_agent/langgraph_execution_plan.py",
        "python-ai-runtime/src/datasmart_ai_runtime/services/tools/langgraph_execution_gate.py",
        "python-ai-runtime/src/datasmart_ai_runtime/services/memory/langgraph_memory_retrieval_workflow.py",
        "python-ai-runtime/src/datasmart_ai_runtime/services/tools/langgraph_execution_gate_metrics.py",
        "python-ai-runtime/src/datasmart_ai_runtime/services/memory/langgraph_memory_retrieval_metrics.py",
        "python-ai-runtime/tests/test_langgraph_multi_agent_execution_plan.py",
        "python-ai-runtime/tests/test_langgraph_execution_gate_workflow.py",
        "python-ai-runtime/tests/test_langgraph_memory_retrieval_workflow.py"
    )) {
        Test-RequiredFile $file "LangGraph must have workflow, metric, and test evidence instead of dependency-only adoption"
    }
    $agentCatalog = "python-ai-runtime/src/datasmart_ai_runtime/services/multi_agent/product_agent_catalog.py"
    foreach ($role in @(
        "MASTER_ORCHESTRATOR", "DATASOURCE_AGENT", "DATA_QUALITY_AGENT", "PERMISSION_AGENT",
        "TASK_AGENT", "MEMORY_AGENT", "OPS_AGENT", "DATA_SYNC_AGENT",
        "ETL_DEVELOPMENT_AGENT", "DATA_ASSET_AGENT", "COMPLIANCE_MASKING_AGENT", "REFLECTION_OPTIMIZATION_AGENT"
    )) {
        Test-RequiredText $agentCatalog $role "multi-agent catalog must explicitly classify $role"
    }
    foreach ($tier in @("must_do", "controlled_scope", "lightweight")) {
        Test-RequiredText $agentCatalog $tier "multi-agent catalog must preserve delivery tier '$tier'"
    }

    Write-Host "[STEP] verify deployment, observability, and production gates"
    foreach ($file in @(
        "docker-compose.yml", "docker-compose.application.yml",
        "helm/datasmart-govern/Chart.yaml",
        "docker/prometheus/prometheus.application.yml",
        "docker/alertmanager/alertmanager.yml",
        "docs/backup-restore-runbook.md",
        "docs/capacity-baseline-runbook.md",
        "docs/failure-drill-runbook.md",
        "docs/final-platform-closure-audit.md"
    )) {
        Test-RequiredFile $file "final closure requires deployable and operable evidence"
    }
    $productionOutput = & powershell -NoProfile -ExecutionPolicy Bypass -File ".\scripts\production-readiness-check.ps1" 2>&1
    $productionExitCode = $LASTEXITCODE
    $productionSummary = ($productionOutput | Select-String "\[SUMMARY\]" | Select-Object -Last 1).Line
    $validationResults.productionReadiness = [ordered]@{ exitCode = $productionExitCode; summary = $productionSummary }
    if ($productionExitCode -eq 0) {
        Write-CheckResult "PASS" "production-readiness" $productionSummary
    }
    else {
        Write-CheckResult "FAIL" "production-readiness" "production static gate failed"
    }

    Write-Host "[STEP] verify source-file size boundary"
    $sourceFiles = Get-ChildItem -Recurse -File -Include "*.java", "*.py" |
        Where-Object { $_.FullName -notmatch "\\target\\|\\.m2\\|\\.pytest_cache\\" }
    foreach ($file in $sourceFiles) {
        $lineCount = (Get-Content -LiteralPath $file.FullName).Count
        if ($lineCount -le 500) {
            continue
        }
        $relativePath = $file.FullName.Substring($repositoryRoot.Length + 1)
        $record = [pscustomobject]@{ path = $relativePath; lines = $lineCount }
        if ($relativePath -match "\\src\\test\\|python-ai-runtime\\tests\\") {
            $oversizedTestFiles += $record
        }
        else {
            $oversizedProductionFiles += $record
        }
    }
    if ($oversizedProductionFiles.Count -eq 0) {
        Write-CheckResult "PASS" "production-source-size" "all production Java/Python files are within 500 lines"
    }
    else {
        Write-CheckResult "WARN" "production-source-size" "$($oversizedProductionFiles.Count) production files exceed 500 lines and remain a bounded P1 split task"
    }
    if ($oversizedTestFiles.Count -eq 0) {
        Write-CheckResult "PASS" "test-source-size" "all Java/Python test files are within 500 lines"
    }
    else {
        Write-CheckResult "WARN" "test-source-size" "$($oversizedTestFiles.Count) test files exceed 500 lines and should be split by scenario"
    }

    Write-Host "[STEP] optionally execute full test suites"
    if ($RunPythonTests) {
        # Windows PowerShell 5 会在 ErrorActionPreference=Stop 时把原生进程 stderr
        # 包装成 NativeCommandError。测试工具可能把普通 warning 写到 stderr，因此这里临时
        # 允许原生进程完成，再统一使用退出码和测试报告判断成败，不能按输出文本猜测结果。
        $ErrorActionPreference = "Continue"
        try {
            $pythonOutput = & python -m pytest "python-ai-runtime\tests" -q 2>&1
            $pythonExitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = "Stop"
        }
        $pythonSummary = ($pythonOutput | Select-String "passed" | Select-Object -Last 1).Line
        $validationResults.pythonTests = [ordered]@{ executed = $true; exitCode = $pythonExitCode; summary = $pythonSummary }
        if ($pythonExitCode -eq 0) {
            Write-CheckResult "PASS" "python-tests" $pythonSummary
        }
        else {
            Write-CheckResult "FAIL" "python-tests" "Python Runtime test suite failed"
        }
    }
    else {
        $validationResults.pythonTests = [ordered]@{ executed = $false }
        Write-CheckResult "WARN" "python-tests" "not executed; use -RunPythonTests for fresh evidence"
    }

    if ($RunMavenTests) {
        $ErrorActionPreference = "Continue"
        try {
            $mavenOutput = & ".\mvnw.cmd" -q test 2>&1
            $mavenExitCode = $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = "Stop"
        }
        $surefire = Get-SurefireSummary
        $validationResults.mavenTests = [ordered]@{
            executed = $true; exitCode = $mavenExitCode; tests = $surefire.tests
            failures = $surefire.failures; errors = $surefire.errors; skipped = $surefire.skipped
        }
        if ($mavenExitCode -eq 0 -and $surefire.failures -eq 0 -and $surefire.errors -eq 0) {
            Write-CheckResult "PASS" "maven-tests" "tests=$($surefire.tests), failures=0, errors=0, skipped=$($surefire.skipped)"
        }
        else {
            Write-CheckResult "FAIL" "maven-tests" "Maven reactor or Surefire reports contain failures"
        }
    }
    else {
        $validationResults.mavenTests = [ordered]@{ executed = $false }
        Write-CheckResult "WARN" "maven-tests" "not executed; use -RunMavenTests for fresh JDK 21 reactor evidence"
    }

    if ($WriteEvidence) {
        $outputPath = Join-Path $repositoryRoot $OutputDirectory
        New-Item -ItemType Directory -Force -Path $outputPath | Out-Null
        $commit = (& git rev-parse HEAD).Trim()
        # Include the evidence-write PASS before serialization so JSON and terminal summaries match.
        $evidencePassCount = $passCount + 1
        $evidence = [ordered]@{
            schema = "datasmart.final-platform-closure.v1"
            generatedAt = (Get-Date).ToUniversalTime().ToString("o")
            gitCommit = $commit
            result = [ordered]@{ pass = $evidencePassCount; warn = $warnCount; fail = $failCount }
            validations = $validationResults
            oversizedProductionFiles = $oversizedProductionFiles
            oversizedTestFiles = $oversizedTestFiles
            boundary = "Engineering release-candidate evidence only; customer production verification remains environment-specific."
        }
        $outputFile = Join-Path $outputPath "datasmart-final-platform-closure-evidence.json"
        $evidence | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 -LiteralPath $outputFile
        Write-CheckResult "PASS" $OutputDirectory "wrote non-sensitive closure evidence to $outputFile"
    }

    Write-Host "[SUMMARY] PASS=$passCount, WARN=$warnCount, FAIL=$failCount"
    if ($failCount -gt 0) {
        exit 1
    }
    if ($Strict -and $warnCount -gt 0) {
        Write-Host "[FAIL] strict final audit treats warnings as release blockers"
        exit 1
    }
    Write-Host "[PASS] final platform closure audit completed"
}
finally {
    Pop-Location
}

<#
    DataSmart Govern 最终交付闭环总闸门。

    脚本定位：
    - 这是“收敛阶段”的总入口，用来把仓库中已经存在的生产就绪、容器化交付、备份恢复、容量基线、
      故障演练、最终闭环审计和可选真实 E2E smoke 串成一条可重复执行的交付门禁。
    - 它不是新的业务功能，也不是新的 Agent 能力扩展；它的价值是把项目从“很多分散脚本都能跑”
      收敛到“一个命令能说明当前版本是否具备交付候选状态”。

    安全边界：
    - 默认只运行静态/只读门禁，不创建任务、不触发 data-sync worker、不执行 Agent 工具、不读取源端业务数据、
      不写入目标端业务数据、不打印 token/secret/prompt/模型输出。
    - `-RunLiveSmoke` 会调用已有的 `local-e2e-smoke-check.ps1`，但该 smoke 本身也只访问健康检查、OIDC 会话解析、
      Java 控制面和 Python Runtime 低敏诊断接口，不会触发真实写入链路。
    - `-RunContainerizedDelivery` 默认以 `-SkipMaven -SkipDockerInspection` 执行，表示复用当前已经构建出的 jar
      和 Compose 合同做快速验收；如果要重新构建，可追加 `-BuildContainerImages` 或直接单独运行原脚本。

    为什么需要“总闸门”：
    - 项目已经进入闭环收敛，不应再靠记忆分散命令来判断状态。
    - 商业化交付需要能回答“这次版本到底跑了哪些门禁、哪些只是 warning、哪些还属于客户环境验收项”。
    - 因此本脚本会解析每个子脚本的 `[SUMMARY] PASS=..., WARN=..., FAIL=...`，并可选写入低敏 JSON 证据。
#>
[CmdletBinding()]
param(
    [switch]$Strict,
    [switch]$RunContainerizedDelivery,
    [switch]$BuildContainerImages,
    [switch]$RunLiveSmoke,
    [switch]$RunFullTests,
    [switch]$WriteEvidence,
    [string]$OutputDirectory = "target/final-delivery-closure"
)

# 参数说明：
# - Strict：把任意子门禁 warning 视为最终失败，适合发布前或客户验收前使用。
# - RunContainerizedDelivery：执行快速容器化合同校验，默认复用已有 jar，不重新构建全量模块。
# - BuildContainerImages：构建 gateway 与 Python Runtime 两个代表镜像，增强容器交付证据。
# - RunLiveSmoke：调用真实本地只读 E2E smoke，要求本地 Compose 服务已经启动。
# - RunFullTests：让最终审计脚本复跑 Python 与 Maven 全量测试。
# - WriteEvidence：写入低敏 JSON 证据到 target/，不会记录 token、secret、prompt、模型输出或业务数据。
# - OutputDirectory：最终总闸门证据输出目录。

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$gateResults = New-Object System.Collections.Generic.List[object]
$failedGateCount = 0
$warnGateCount = 0

function Write-GateLine {
    param(
        [ValidateSet("INFO", "PASS", "WARN", "FAIL", "SKIP")]
        [string]$Level,
        [string]$Message
    )

    $color = switch ($Level) {
        "PASS" { "Green" }
        "WARN" { "Yellow" }
        "FAIL" { "Red" }
        "SKIP" { "DarkYellow" }
        default { "Cyan" }
    }
    Write-Host "[$Level] $Message" -ForegroundColor $color
}

function Convert-GateSummary {
    <#
        从子脚本输出中提取统一 summary。

        设计说明：
        - 现有多数门禁脚本已经输出 `[SUMMARY] PASS=..., WARN=..., FAIL=...`；
        - 这里不重新解析每一行 PASS/WARN/FAIL，避免不同脚本输出细节变化导致总闸门脆弱；
        - 如果某个脚本没有 summary，则以进程退出码作为最小事实，summary 字段保留为空。
    #>
    param(
        [string]$GateName,
        [object[]]$Output,
        [int]$ExitCode
    )

    $summaryLine = ($Output | Select-String "\[SUMMARY\]" | Select-Object -Last 1).Line
    $pass = $null
    $warn = $null
    $fail = $null

    if (-not [string]::IsNullOrWhiteSpace($summaryLine)) {
        $match = [regex]::Match($summaryLine, "PASS=(\d+),\s*WARN=(\d+),\s*FAIL=(\d+)")
        if ($match.Success) {
            $pass = [int]$match.Groups[1].Value
            $warn = [int]$match.Groups[2].Value
            $fail = [int]$match.Groups[3].Value
        }
    }

    return [pscustomobject]@{
        gate = $GateName
        exitCode = $ExitCode
        pass = $pass
        warn = $warn
        fail = $fail
        summary = $summaryLine
    }
}

function Invoke-ClosureGate {
    <#
        执行一个子门禁，并把结果归一化到总闸门。

        输入输出语义：
        - ScriptPath：仓库内脚本路径；
        - Arguments：传给子脚本的参数数组；
        - Required：true 表示该门禁失败会让总闸门失败；当前收敛阶段的核心门禁均为 required；
        - 输出不会打印子脚本完整正文，只打印 summary，避免终端被大量细节淹没，也避免未来子脚本扩展字段时误泄露。
    #>
    param(
        [string]$Name,
        [string]$ScriptPath,
        [string[]]$Arguments = @(),
        [bool]$Required = $true
    )

    $resolvedScript = Join-Path $repositoryRoot $ScriptPath
    if (-not (Test-Path -LiteralPath $resolvedScript)) {
        $script:failedGateCount++
        $result = [pscustomobject]@{
            gate = $Name
            exitCode = 127
            pass = $null
            warn = $null
            fail = 1
            summary = "missing script: $ScriptPath"
        }
        $script:gateResults.Add($result) | Out-Null
        Write-GateLine -Level "FAIL" -Message "$Name - missing script: $ScriptPath"
        return
    }

    Write-GateLine -Level "INFO" -Message "$Name"
    $ErrorActionPreference = "Continue"
    try {
        $output = & powershell -NoProfile -ExecutionPolicy Bypass -File $resolvedScript @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = "Stop"
    }

    $result = Convert-GateSummary -GateName $Name -Output $output -ExitCode $exitCode
    $script:gateResults.Add($result) | Out-Null

    $gateWarn = if ($null -ne $result.warn) { $result.warn } else { 0 }
    $gateFail = if ($null -ne $result.fail) { $result.fail } else { 0 }

    if ($exitCode -ne 0 -or $gateFail -gt 0) {
        if ($Required) {
            $script:failedGateCount++
        }
        Write-GateLine -Level "FAIL" -Message "$Name - exitCode=$exitCode; $($result.summary)"
        return
    }

    if ($gateWarn -gt 0) {
        $script:warnGateCount++
        Write-GateLine -Level "WARN" -Message "$Name - $($result.summary)"
        return
    }

    Write-GateLine -Level "PASS" -Message "$Name - $($result.summary)"
}

function Add-SkippedGate {
    param(
        [string]$Name,
        [string]$Reason
    )

    $script:gateResults.Add([pscustomobject]@{
        gate = $Name
        exitCode = $null
        pass = $null
        warn = $null
        fail = $null
        summary = "skipped: $Reason"
    }) | Out-Null
    Write-GateLine -Level "SKIP" -Message "$Name - $Reason"
}

Push-Location $repositoryRoot
try {
    Write-GateLine -Level "INFO" -Message "DataSmart Govern final delivery closure gate started"

    Invoke-ClosureGate -Name "production-readiness" -ScriptPath "scripts/production-readiness-check.ps1"

    Invoke-ClosureGate -Name "helm-delivery" -ScriptPath "scripts/helm-delivery-check.ps1"

    Invoke-ClosureGate -Name "sbom-readiness" -ScriptPath "scripts/sbom-check.ps1"

    Invoke-ClosureGate -Name "image-signature-readiness" -ScriptPath "scripts/verify-image-signatures.ps1"

    Invoke-ClosureGate -Name "backup-restore-readiness" -ScriptPath "scripts/backup-restore-check.ps1"

    Invoke-ClosureGate -Name "capacity-baseline-readiness" -ScriptPath "scripts/capacity-baseline-check.ps1"

    Invoke-ClosureGate -Name "failure-drill-readiness" -ScriptPath "scripts/failure-drill-check.ps1"

    if ($RunContainerizedDelivery) {
        $containerArgs = @("-SkipMaven", "-SkipDockerInspection")
        if ($BuildContainerImages) {
            # 构建代表镜像时仍然跳过全量镜像检查，避免要求所有本地镜像都已经存在。
            $containerArgs += "-BuildRepresentativeImages"
        }
        Invoke-ClosureGate -Name "containerized-delivery" -ScriptPath "scripts/containerized-delivery-check.ps1" -Arguments $containerArgs
    }
    else {
        Add-SkippedGate -Name "containerized-delivery" -Reason "use -RunContainerizedDelivery after jar artifacts are ready"
    }

    $finalAuditArgs = @()
    if ($RunFullTests) {
        $finalAuditArgs += "-RunPythonTests"
        $finalAuditArgs += "-RunMavenTests"
    }
    if ($WriteEvidence) {
        $finalAuditArgs += "-WriteEvidence"
    }
    Invoke-ClosureGate -Name "final-platform-closure-audit" -ScriptPath "scripts/final-platform-closure-audit.ps1" -Arguments $finalAuditArgs

    if ($RunLiveSmoke) {
        Invoke-ClosureGate `
            -Name "local-readonly-e2e-smoke" `
            -ScriptPath "scripts/local-e2e-smoke-check.ps1" `
            -Arguments @("-Strict", "-CheckServiceAccountToken", "-CheckAgentGatewayDiagnostics")
    }
    else {
        Add-SkippedGate -Name "local-readonly-e2e-smoke" -Reason "use -RunLiveSmoke when local Compose services are running"
    }

    if ($WriteEvidence) {
        $resolvedOutputDirectory = Join-Path $repositoryRoot $OutputDirectory
        New-Item -ItemType Directory -Force -Path $resolvedOutputDirectory | Out-Null
        $evidence = [ordered]@{
            schema = "datasmart.final-delivery-closure.v1"
            generatedAt = (Get-Date).ToUniversalTime().ToString("o")
            gitCommit = (& git rev-parse HEAD).Trim()
            strict = [bool]$Strict
            runContainerizedDelivery = [bool]$RunContainerizedDelivery
            runLiveSmoke = [bool]$RunLiveSmoke
            runFullTests = [bool]$RunFullTests
            result = [ordered]@{
                failedGates = $failedGateCount
                gatesWithWarnings = $warnGateCount
            }
            gates = $gateResults
            boundary = "Low-sensitive delivery evidence only. No secrets, tokens, prompts, model outputs, SQL rows, object contents, or customer data are recorded."
        }
        $outputFile = Join-Path $resolvedOutputDirectory "datasmart-final-delivery-closure-evidence.json"
        $evidence | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 -LiteralPath $outputFile
        Write-GateLine -Level "PASS" -Message "evidence written to $outputFile"
    }

    Write-GateLine -Level "INFO" -Message "final delivery closure summary: failedGates=$failedGateCount, gatesWithWarnings=$warnGateCount"

    if ($failedGateCount -gt 0) {
        exit 1
    }
    if ($Strict -and $warnGateCount -gt 0) {
        Write-GateLine -Level "FAIL" -Message "strict mode treats warning gates as release blockers"
        exit 1
    }

    Write-GateLine -Level "PASS" -Message "final delivery closure gate completed"
}
finally {
    Pop-Location
}

<#
    DataSmart Govern 本地依赖恢复演练脚本。

    脚本定位：
    - 这个脚本专门解决本地长期运行后最常见的依赖漂移：Zookeeper 退出、Kafka 无法连接 Zookeeper、
      Python AI Runtime 因 Kafka bootstrap 失败而处于 unhealthy。
    - 它不是灾备恢复脚本，不会删除 volume、不重置数据库、不重建 Keycloak realm、不清空 Kafka topic、
      不执行 worker loop，也不会触发任何业务任务。

    默认行为：
    - 默认只检查 Docker、Compose 模型和几个关键容器状态；
    - 只有显式传入 `-RecoverKafkaChain` 才会执行 `docker compose up -d zookeeper kafka`；
    - 只有显式传入 `-RestartPythonRuntime` 才会在 Kafka 链路恢复后尝试重启 Python Runtime。

    为什么不自动删除容器或 volume：
    - 容器是运行实例，volume 才是本地状态事实；删除 volume 会丢失数据库、Keycloak、Kafka、MinIO 等状态；
    - 本项目已经把 Keycloak 身份事实迁移到 PostgreSQL-backed database，更不能用“删卷重来”伪装修复；
    - 商业化运维应优先做有界恢复：拉起依赖、等待健康、重连 runtime、再跑只读 smoke。
#>
[CmdletBinding()]
param(
    # 开启后按依赖顺序拉起 Zookeeper 与 Kafka。
    [switch]$RecoverKafkaChain,

    # 开启后在 Kafka 链路恢复后重启 Python Runtime，优先走 compose service，失败再回退到容器名。
    [switch]$RestartPythonRuntime,

    # 严格模式下，只要关键容器不存在、未运行或恢复失败就返回非 0。
    [switch]$Strict,

    # 等待 Kafka/Python Runtime 恢复的最长秒数。
    [int]$WaitSeconds = 90,

    # Compose service 名称，应用 overlay 启动时通常存在；仅基础 Compose 时可能不存在。
    [string]$PythonComposeService = "python-ai-runtime",

    # 兜底容器名，处理 `docker compose restart python-ai-runtime` 因 overlay 未加载而报 no such service 的情况。
    [string]$PythonContainerName = "datasmart-python-ai-runtime"
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$passCount = 0
$warnCount = 0
$failCount = 0

function Write-DrillResult {
    param(
        [ValidateSet("PASS", "WARN", "FAIL", "INFO")]
        [string]$Level,
        [string]$Name,
        [string]$Detail
    )

    switch ($Level) {
        "PASS" { $script:passCount++ }
        "WARN" { $script:warnCount++ }
        "FAIL" { $script:failCount++ }
    }

    $color = switch ($Level) {
        "PASS" { "Green" }
        "WARN" { "Yellow" }
        "FAIL" { "Red" }
        default { "Cyan" }
    }
    Write-Host "[$Level] $Name - $Detail" -ForegroundColor $color
}

function Invoke-NativeCommand {
    <#
        执行 Docker 命令并返回完整结果。

        说明：
        - 本脚本不把敏感环境变量传给 Docker；
        - stdout/stderr 只用于判断恢复状态，输出摘要不包含 token、secret 或业务数据；
        - 不在这里直接 throw，是为了让恢复分支可以优雅降级并给出更清楚的诊断。
    #>
    param(
        [string]$Command,
        [string[]]$Arguments
    )

    $ErrorActionPreference = "Continue"
    try {
        $output = & $Command @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = "Stop"
    }

    return [pscustomobject]@{
        exitCode = $exitCode
        output = $output
    }
}

function Get-ContainerState {
    param([string]$ContainerName)

    $inspect = Invoke-NativeCommand `
        -Command "docker" `
        -Arguments @(
            "inspect",
            $ContainerName,
            "--format",
            "{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}"
        )

    if ($inspect.exitCode -ne 0) {
        return [pscustomobject]@{
            exists = $false
            status = "missing"
            health = "missing"
        }
    }

    $parts = (($inspect.output | Select-Object -First 1) -split "\|")
    return [pscustomobject]@{
        exists = $true
        status = $parts[0]
        health = if ($parts.Count -gt 1) { $parts[1] } else { "none" }
    }
}

function Test-ContainerReady {
    param(
        [string]$ContainerName,
        [bool]$RequireHealthy = $false
    )

    $state = Get-ContainerState -ContainerName $ContainerName
    if (-not $state.exists) {
        Write-DrillResult -Level "WARN" -Name $ContainerName -Detail "container is not present"
        return $false
    }
    if ($state.status -ne "running") {
        Write-DrillResult -Level "WARN" -Name $ContainerName -Detail "status=$($state.status), health=$($state.health)"
        return $false
    }
    if ($RequireHealthy -and $state.health -ne "healthy") {
        Write-DrillResult -Level "WARN" -Name $ContainerName -Detail "running but health=$($state.health)"
        return $false
    }

    Write-DrillResult -Level "PASS" -Name $ContainerName -Detail "status=$($state.status), health=$($state.health)"
    return $true
}

function Wait-ContainerReady {
    param(
        [string]$ContainerName,
        [bool]$RequireHealthy,
        [int]$TimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $state = Get-ContainerState -ContainerName $ContainerName
        if ($state.exists -and $state.status -eq "running") {
            if (-not $RequireHealthy -or $state.health -eq "healthy") {
                Write-DrillResult -Level "PASS" -Name $ContainerName -Detail "recovered with status=$($state.status), health=$($state.health)"
                return $true
            }
        }
        Start-Sleep -Seconds 3
    }

    $final = Get-ContainerState -ContainerName $ContainerName
    Write-DrillResult -Level "FAIL" -Name $ContainerName -Detail "not ready after ${TimeoutSeconds}s; status=$($final.status), health=$($final.health)"
    return $false
}

function Restart-PythonRuntimeSafely {
    <#
        重启 Python Runtime 的兼容策略。

        设计意图：
        - 本地可能只用了 `docker-compose.yml`，没有加载 `docker-compose.application.yml`；
        - 这种情况下 `docker compose restart python-ai-runtime` 会提示 no such service，
          但历史容器 `datasmart-python-ai-runtime` 可能仍然存在；
        - 因此先尝试 Compose service，失败后再用容器名兜底，避免把“服务名不在当前 compose 文件中”
          误判为 Python Runtime 无法恢复。
    #>
    $composeRestart = Invoke-NativeCommand -Command "docker" -Arguments @("compose", "restart", $PythonComposeService)
    if ($composeRestart.exitCode -eq 0) {
        Write-DrillResult -Level "PASS" -Name "python-runtime-restart" -Detail "restarted compose service $PythonComposeService"
        return
    }

    $containerState = Get-ContainerState -ContainerName $PythonContainerName
    if (-not $containerState.exists) {
        Write-DrillResult -Level "WARN" -Name "python-runtime-restart" -Detail "compose service unavailable and fallback container is missing"
        return
    }

    $containerRestart = Invoke-NativeCommand -Command "docker" -Arguments @("restart", $PythonContainerName)
    if ($containerRestart.exitCode -eq 0) {
        Write-DrillResult -Level "PASS" -Name "python-runtime-restart" -Detail "restarted fallback container $PythonContainerName"
    }
    else {
        Write-DrillResult -Level "FAIL" -Name "python-runtime-restart" -Detail "failed to restart compose service and fallback container"
    }
}

Push-Location $repositoryRoot
try {
    Write-DrillResult -Level "INFO" -Name "drill-boundary" -Detail "no volume deletion, no database reset, no worker loop, no business mutation"

    if ($null -eq (Get-Command "docker" -ErrorAction SilentlyContinue)) {
        Write-DrillResult -Level "FAIL" -Name "docker-cli" -Detail "Docker CLI is not available"
    }
    else {
        Write-DrillResult -Level "PASS" -Name "docker-cli" -Detail "Docker CLI is available"
    }

    $dockerVersion = Invoke-NativeCommand -Command "docker" -Arguments @("version", "--format", "{{.Server.Version}}")
    if ($dockerVersion.exitCode -eq 0) {
        Write-DrillResult -Level "PASS" -Name "docker-daemon" -Detail "Docker daemon is reachable"
    }
    else {
        Write-DrillResult -Level "FAIL" -Name "docker-daemon" -Detail "Docker daemon is not reachable"
    }

    $composeConfig = Invoke-NativeCommand -Command "docker" -Arguments @("compose", "config", "--quiet")
    if ($composeConfig.exitCode -eq 0) {
        Write-DrillResult -Level "PASS" -Name "compose-config" -Detail "base Compose model is valid"
    }
    else {
        Write-DrillResult -Level "FAIL" -Name "compose-config" -Detail "base Compose model is invalid"
    }

    Test-ContainerReady -ContainerName "datasmart-zookeeper" | Out-Null
    Test-ContainerReady -ContainerName "datasmart-kafka" | Out-Null
    Test-ContainerReady -ContainerName $PythonContainerName -RequireHealthy $true | Out-Null

    if ($RecoverKafkaChain) {
        Write-DrillResult -Level "INFO" -Name "recover-kafka-chain" -Detail "running docker compose up -d zookeeper kafka"
        $recover = Invoke-NativeCommand -Command "docker" -Arguments @("compose", "up", "-d", "zookeeper", "kafka")
        if ($recover.exitCode -eq 0) {
            Write-DrillResult -Level "PASS" -Name "recover-kafka-chain" -Detail "compose accepted zookeeper/kafka recovery request"
        }
        else {
            Write-DrillResult -Level "FAIL" -Name "recover-kafka-chain" -Detail "compose failed to recover zookeeper/kafka"
        }

        Wait-ContainerReady -ContainerName "datasmart-zookeeper" -RequireHealthy $false -TimeoutSeconds $WaitSeconds | Out-Null
        Wait-ContainerReady -ContainerName "datasmart-kafka" -RequireHealthy $false -TimeoutSeconds $WaitSeconds | Out-Null
    }
    else {
        Write-DrillResult -Level "WARN" -Name "recover-kafka-chain" -Detail "not executed; use -RecoverKafkaChain to repair local Zookeeper/Kafka dependency drift"
    }

    if ($RestartPythonRuntime) {
        Restart-PythonRuntimeSafely
        Wait-ContainerReady -ContainerName $PythonContainerName -RequireHealthy $true -TimeoutSeconds $WaitSeconds | Out-Null
    }
    else {
        Write-DrillResult -Level "WARN" -Name "python-runtime-restart" -Detail "not executed; use -RestartPythonRuntime after Kafka recovery if Python Runtime is unhealthy"
    }

    Write-Host "[SUMMARY] PASS=$passCount, WARN=$warnCount, FAIL=$failCount"

    if ($failCount -gt 0) {
        exit 1
    }
    if ($Strict -and $warnCount -gt 0) {
        Write-DrillResult -Level "FAIL" -Name "strict-mode" -Detail "warnings are treated as drill blockers"
        exit 1
    }
}
finally {
    Pop-Location
}

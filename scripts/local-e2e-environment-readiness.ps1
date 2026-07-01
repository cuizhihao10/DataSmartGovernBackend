<#
    DataSmart Govern 本地真实 E2E 环境就绪诊断脚本。

    设计意图：
    1. `local-e2e-smoke-check.ps1` 适合在服务已经启动后验证关键 HTTP 探针；而真实联调经常更早卡在
       Docker、MySQL 凭据、端口占用、Python API 依赖这些“启动前置条件”上。
    2. 本脚本只回答“当前机器是否具备继续启动全链路的条件”，不会启动容器、不会启动 Java 微服务、
       不会执行 SQL、不会创建任务，也不会触发 Python Runtime 工具执行。
    3. 输出必须保持低敏：只打印 PASS/WARN/FAIL、端口是否打开、环境变量是否设置、MySQL 失败分类码，
       不打印数据库密码、JWT、SQL 正文、诊断响应正文或任何业务数据。

    推荐用法：
    - 启动任何服务前先跑一次：
      .\scripts\local-e2e-environment-readiness.ps1
    - 已设置本地开发库密码后验证 MySQL 凭据：
      $env:DATASMART_MYSQL_USER = "root"
      $env:DATASMART_MYSQL_PASSWORD = "<本地开发库密码>"
      .\scripts\local-e2e-environment-readiness.ps1 -ProbeMySqlCredential
    - CI 或严格验收希望失败即退出：
      .\scripts\local-e2e-environment-readiness.ps1 -Strict

    安全边界：
    - MySQL 探针只执行 `SELECT 1`，不会读取业务表、不会创建库表、不会应用 migration。
    - 只有显式传入 `-ProbeMySqlCredential` 且通过参数或环境变量提供密码时，才会做真实凭据探测。
    - Python Runtime 探针只检查 3 个低敏诊断 GET 接口的状态码，不输出响应正文。
#>
param(
    [switch]$Strict,
    [switch]$ProbeMySqlCredential,
    [int]$TimeoutMilliseconds = 800,
    [string]$MySqlHost = "127.0.0.1",
    [int]$MySqlPort = 3306,
    [string]$DatabaseName = "datasmart_govern",
    [string]$MySqlUser = "",
    [string]$MySqlPassword = "",
    [string]$PythonAiRuntimeBaseUrl = "http://127.0.0.1:8090"
)

$ErrorActionPreference = "Stop"
$script:Checks = New-Object System.Collections.Generic.List[object]
$script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

if (
    -not $PSBoundParameters.ContainsKey("MySqlPort") -and
    -not [string]::IsNullOrWhiteSpace($env:DATASMART_LOCAL_MYSQL_PORT)
) {
    $MySqlPort = [int]$env:DATASMART_LOCAL_MYSQL_PORT
}

if ([string]::IsNullOrWhiteSpace($MySqlUser)) {
    $MySqlUser = if ([string]::IsNullOrWhiteSpace($env:DATASMART_MYSQL_USER)) { "root" } else { $env:DATASMART_MYSQL_USER }
}
if ([string]::IsNullOrWhiteSpace($MySqlPassword)) {
    $MySqlPassword = if ([string]::IsNullOrWhiteSpace($env:DATASMART_MYSQL_PASSWORD)) { "" } else { $env:DATASMART_MYSQL_PASSWORD }
}

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

function Test-TcpPort {
    param(
        [string]$HostName,
        [int]$Port
    )

    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($HostName, $Port, $null, $null)
        $connected = $async.AsyncWaitHandle.WaitOne($TimeoutMilliseconds, $false)
        if (-not $connected) {
            return $false
        }
        try {
            $client.EndConnect($async)
            return $true
        } catch {
            return $false
        }
    } finally {
        $client.Close()
    }
}

function Get-EnvironmentSecretState {
    param([string]$Name)

    $item = Get-Item "Env:$Name" -ErrorAction SilentlyContinue
    if ($null -eq $item) {
        return "NOT_SET"
    }
    if ([string]::IsNullOrWhiteSpace($item.Value)) {
        return "EMPTY"
    }
    return "SET"
}

function Find-MySqlCli {
    $candidates = @(
        "mysql.exe",
        "D:\ENV\MySQL\MySQL Server 8.0\bin\mysql.exe",
        "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
    )

    foreach ($candidate in $candidates) {
        $command = Get-Command $candidate -ErrorAction SilentlyContinue
        if ($command) {
            return $command.Source
        }
    }
    return $null
}

function Get-MySqlFailureCode {
    param([string]$ErrorText)

    if ([string]::IsNullOrWhiteSpace($ErrorText)) {
        return "MYSQL_UNKNOWN_ERROR"
    }
    if ($ErrorText -match "ERROR 1045|Access denied") {
        return "ACCESS_DENIED"
    }
    if ($ErrorText -match "ERROR 1049|Unknown database") {
        return "UNKNOWN_DATABASE"
    }
    if ($ErrorText -match "Unknown MySQL server host") {
        return "HOST_UNRESOLVED"
    }
    if ($ErrorText -match "Can't connect|Can`t connect|Connection refused|No connection could be made") {
        return "CONNECTION_FAILED"
    }
    return "MYSQL_COMMAND_FAILED"
}

function Invoke-MySqlCredentialProbe {
    param([string]$MySqlCliPath)

    <#
        只做最低成本的 `SELECT 1` 探针。

        为什么使用 `MYSQL_PWD` 临时环境变量：
        - 不把密码拼进命令行参数，避免被进程列表、终端历史或日志采集器记录；
        - 探针结束后恢复原始 MYSQL_PWD 状态，避免影响用户后续 shell。
    #>
    $oldMySqlPwd = $env:MYSQL_PWD
    $hadOldMySqlPwd = $null -ne (Get-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue)
    try {
        $env:MYSQL_PWD = $MySqlPassword
        $output = & $MySqlCliPath `
            --host=$MySqlHost `
            --port=$MySqlPort `
            --user=$MySqlUser `
            --database=$DatabaseName `
            --batch `
            --raw `
            --skip-column-names `
            --connect-timeout=3 `
            -e "SELECT 1;" 2>&1
        if ($LASTEXITCODE -eq 0) {
            Add-Check -Name "MySQL credential probe" -Status "PASS" -Detail "Connected to ${MySqlHost}:$MySqlPort/$DatabaseName; password and query result are not printed"
            return
        }
        $issueCode = Get-MySqlFailureCode -ErrorText ($output | Out-String)
        Add-Check -Name "MySQL credential probe" -Status "FAIL" -Detail "Connection failed issueCode=$issueCode; check DATASMART_MYSQL_USER/DATASMART_MYSQL_PASSWORD or local dev database status"
    } finally {
        if ($hadOldMySqlPwd) {
            $env:MYSQL_PWD = $oldMySqlPwd
        } else {
            Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
        }
    }
}

function Test-HttpStatusOnly {
    param(
        [string]$Name,
        [string]$Url
    )

    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
        Add-Check -Name $Name -Status "PASS" -Detail "HTTP $($response.StatusCode) $Url"
    } catch {
        $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { "NO_RESPONSE" }
        Add-Check -Name $Name -Status "WARN" -Detail "Probe failed, status=$status; this is expected before the service is started"
    }
}

Write-Host "DataSmart Govern local real E2E environment readiness" -ForegroundColor Cyan
Write-Host "Repo root: $script:RepoRoot"

$docker = Get-Command docker -ErrorAction SilentlyContinue
if ($docker) {
    try {
        docker info --format "{{.ServerVersion}}" *> $null
        Add-Check -Name "Docker daemon" -Status "PASS" -Detail "Docker CLI and daemon are available; docker compose can start local infrastructure"
    } catch {
        Add-Check -Name "Docker daemon" -Status "FAIL" -Detail "Docker CLI exists but daemon is unavailable; start Docker Desktop or check user permissions"
    }
} else {
    Add-Check -Name "Docker CLI" -Status "FAIL" -Detail "docker is not found in PATH; standard docker compose local dependencies cannot be started"
}

$mysqlCli = Find-MySqlCli
if ($mysqlCli) {
    Add-Check -Name "MySQL CLI" -Status "PASS" -Detail "mysql.exe is available for LocalCli migration plan and credential probe"
} else {
    Add-Check -Name "MySQL CLI" -Status "WARN" -Detail "mysql.exe is not found; Docker mode can still use the container mysql client when Docker is available"
}

foreach ($name in @("DATASMART_MYSQL_USER", "DATASMART_MYSQL_PASSWORD", "MYSQL_PWD", "MYSQL_PASSWORD")) {
    $state = Get-EnvironmentSecretState -Name $name
    $status = if ($state -eq "SET") { "PASS" } else { "WARN" }
    Add-Check -Name "Environment variable: $name" -Status $status -Detail "state=$state; only presence is reported, value is not printed"
}

$ports = @(
    @{ Name = "MySQL"; Port = $MySqlPort; Required = $true },
    @{ Name = "Redis"; Port = 6379; Required = $true },
    @{ Name = "Nacos"; Port = 8848; Required = $true },
    @{ Name = "Kafka"; Port = 9092; Required = $true },
    @{ Name = "Keycloak"; Port = 18080; Required = $true },
    @{ Name = "Gateway"; Port = 8080; Required = $true },
    @{ Name = "Task Management"; Port = 8081; Required = $true },
    @{ Name = "Datasource Management"; Port = 8082; Required = $true },
    @{ Name = "Permission Admin"; Port = 8085; Required = $true },
    @{ Name = "Data Sync"; Port = 8086; Required = $true },
    @{ Name = "Python AI Runtime"; Port = 8090; Required = $true },
    @{ Name = "Agent Runtime"; Port = 8091; Required = $true },
    @{ Name = "Prometheus"; Port = 9090; Required = $false },
    @{ Name = "Grafana"; Port = 3000; Required = $false }
)

foreach ($entry in $ports) {
    $open = Test-TcpPort -HostName "127.0.0.1" -Port $entry.Port
    if ($open) {
        Add-Check -Name "Port: $($entry.Name)" -Status "PASS" -Detail "127.0.0.1:$($entry.Port) is open"
    } else {
        $status = if ($entry.Required) { "FAIL" } else { "WARN" }
        Add-Check -Name "Port: $($entry.Name)" -Status $status -Detail "127.0.0.1:$($entry.Port) is closed; start the service before full E2E"
    }
}

if ($ProbeMySqlCredential) {
    if (-not $mysqlCli) {
        Add-Check -Name "MySQL credential probe" -Status "FAIL" -Detail "Probe requested but mysql.exe is not found"
    } elseif ([string]::IsNullOrWhiteSpace($MySqlPassword)) {
        Add-Check -Name "MySQL credential probe" -Status "FAIL" -Detail "Probe requested but MySqlPassword or DATASMART_MYSQL_PASSWORD is not provided; password guessing is disabled"
    } else {
        Invoke-MySqlCredentialProbe -MySqlCliPath $mysqlCli
    }
} else {
    Add-Check -Name "MySQL credential probe" -Status "WARN" -Detail "Skipped by default; set DATASMART_MYSQL_PASSWORD and pass -ProbeMySqlCredential to verify the real connection"
}

foreach ($pythonDependency in @("fastapi", "uvicorn")) {
    $pythonProbe = "import importlib.util, sys; sys.exit(0 if importlib.util.find_spec('$pythonDependency') else 1)"
    & python -c $pythonProbe 2>$null
    if ($LASTEXITCODE -eq 0) {
        Add-Check -Name "Python API dependency: $pythonDependency" -Status "PASS" -Detail "Importable"
    } else {
        Add-Check -Name "Python API dependency: $pythonDependency" -Status "WARN" -Detail "Not importable; install python-ai-runtime[api] before starting port 8090"
    }
}

Test-HttpStatusOnly -Name "Python Runtime closure readiness" -Url "$PythonAiRuntimeBaseUrl/agent/capabilities/closure-readiness"
Test-HttpStatusOnly -Name "Python Runtime Skill diagnostics" -Url "$PythonAiRuntimeBaseUrl/agent/skills/publication/diagnostics"
Test-HttpStatusOnly -Name "Python Runtime inference diagnostics" -Url "$PythonAiRuntimeBaseUrl/agent/models/inference-optimization/diagnostics"

$pass = @($script:Checks | Where-Object { $_.Status -eq "PASS" }).Count
$warn = @($script:Checks | Where-Object { $_.Status -eq "WARN" }).Count
$fail = @($script:Checks | Where-Object { $_.Status -eq "FAIL" }).Count

Write-Host ""
Write-Host ("Summary: PASS={0}, WARN={1}, FAIL={2}" -f $pass, $warn, $fail) -ForegroundColor Cyan

if ($fail -gt 0) {
    Write-Host "Full real E2E is not ready yet. Fix FAIL items first; closed ports usually mean infrastructure or microservices are not running." -ForegroundColor Yellow
}
if ($Strict -and $fail -gt 0) {
    exit 1
}

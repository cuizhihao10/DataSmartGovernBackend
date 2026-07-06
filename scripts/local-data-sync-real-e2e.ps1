<#
    DataSmart Govern 数据同步真实数据库 E2E 脚本。

    设计意图：
    1. 本脚本用于显式验收 datasource-management 的真实 JDBC 数据面能力：从 MySQL 源端读取，
       按字段映射与 where 过滤写入 PostgreSQL 目标端。
    2. 它不是只读 smoke check。脚本会触发一个 opt-in JUnit 测试，测试会创建/覆盖专用 E2E 表：
       - MySQL: datasmart_govern.datasmart_e2e_source_customers
       - PostgreSQL: datasmart_e2e.customers_clean
       因此只有在本地开发库、临时库或可丢弃测试库中才应该运行。
    3. 脚本默认使用 docker-compose.local-e2e.yml，把 MySQL 暴露到 13306，避免 Windows 本机 MySQL80
       或其他本机服务占用 3306 时互相干扰。
    4. 输出保持低敏：不打印数据库密码、JDBC URL 完整正文、SQL 正文、表数据、JWT、token 或连接错误原文。

    推荐用法：
    - 只检查脚本计划，不启动容器、不运行 Maven：
      .\scripts\local-data-sync-real-e2e.ps1 -PlanOnly
    - 启动 MySQL/PostgreSQL 容器并运行真实 E2E：
      .\scripts\local-data-sync-real-e2e.ps1
    - 如果依赖已经启动，只运行 Maven E2E：
      .\scripts\local-data-sync-real-e2e.ps1 -SkipDependencyStart
    - CI 或严格验收希望失败即退出：
      .\scripts\local-data-sync-real-e2e.ps1 -Strict
#>
param(
    [switch]$PlanOnly,
    [switch]$SkipDependencyStart,
    [switch]$SkipMaven,
    [switch]$Strict,
    [string]$MySqlHost = "127.0.0.1",
    [int]$MySqlPort = 13306,
    [string]$MySqlDatabase = "datasmart_govern",
    [string]$MySqlUser = "",
    [string]$MySqlPassword = "",
    [string]$PostgresHost = "127.0.0.1",
    [int]$PostgresPort = 5432,
    [string]$PostgresDatabase = "datasmart_govern",
    [string]$PostgresUser = "",
    [string]$PostgresPassword = "",
    [string]$MySqlContainerName = "datasmart-mysql",
    [string]$PostgresContainerName = "datasmart-postgresql",
    [int]$StartupTimeoutSeconds = 120,
    [int]$ProbeIntervalSeconds = 3
)

$ErrorActionPreference = "Stop"
$script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$script:Checks = New-Object System.Collections.Generic.List[object]
$script:FailureCount = 0

if (-not $PSBoundParameters.ContainsKey("MySqlPort") -and -not [string]::IsNullOrWhiteSpace($env:DATASMART_LOCAL_MYSQL_PORT)) {
    $MySqlPort = [int]$env:DATASMART_LOCAL_MYSQL_PORT
}
if (-not $PSBoundParameters.ContainsKey("PostgresPort") -and -not [string]::IsNullOrWhiteSpace($env:DATASMART_LOCAL_POSTGRES_PORT)) {
    $PostgresPort = [int]$env:DATASMART_LOCAL_POSTGRES_PORT
}
if ([string]::IsNullOrWhiteSpace($MySqlUser)) {
    $MySqlUser = if ([string]::IsNullOrWhiteSpace($env:DATASMART_E2E_MYSQL_USER)) {
        if ([string]::IsNullOrWhiteSpace($env:DATASMART_MYSQL_USER)) { "datasmart" } else { $env:DATASMART_MYSQL_USER }
    } else {
        $env:DATASMART_E2E_MYSQL_USER
    }
}
if ([string]::IsNullOrWhiteSpace($MySqlPassword)) {
    $MySqlPassword = if ([string]::IsNullOrWhiteSpace($env:DATASMART_E2E_MYSQL_PASSWORD)) {
        if ([string]::IsNullOrWhiteSpace($env:DATASMART_MYSQL_PASSWORD)) { "password" } else { $env:DATASMART_MYSQL_PASSWORD }
    } else {
        $env:DATASMART_E2E_MYSQL_PASSWORD
    }
}
if ([string]::IsNullOrWhiteSpace($PostgresUser)) {
    $PostgresUser = if ([string]::IsNullOrWhiteSpace($env:DATASMART_E2E_POSTGRES_USER)) {
        if ([string]::IsNullOrWhiteSpace($env:DATASMART_POSTGRES_USER)) { "datasmart" } else { $env:DATASMART_POSTGRES_USER }
    } else {
        $env:DATASMART_E2E_POSTGRES_USER
    }
}
if ([string]::IsNullOrWhiteSpace($PostgresPassword)) {
    $PostgresPassword = if ([string]::IsNullOrWhiteSpace($env:DATASMART_E2E_POSTGRES_PASSWORD)) {
        if ([string]::IsNullOrWhiteSpace($env:DATASMART_POSTGRES_PASSWORD)) { "password" } else { $env:DATASMART_POSTGRES_PASSWORD }
    } else {
        $env:DATASMART_E2E_POSTGRES_PASSWORD
    }
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

function Test-TcpPort {
    param(
        [string]$HostName,
        [int]$Port
    )

    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($HostName, $Port, $null, $null)
        $connected = $async.AsyncWaitHandle.WaitOne(1000, $false)
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

function Wait-TcpPort {
    param(
        [string]$Name,
        [string]$HostName,
        [int]$Port
    )

    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-TcpPort -HostName $HostName -Port $Port) {
            Add-Check -Name "$Name port" -Status "PASS" -Detail "${HostName}:$Port is reachable"
            return $true
        }
        Start-Sleep -Seconds $ProbeIntervalSeconds
    }
    Add-Check -Name "$Name port" -Status "FAIL" -Detail "${HostName}:$Port is not reachable before timeout"
    return $false
}

function Test-DockerContainerExists {
    param([string]$ContainerName)

    $containerId = docker ps --filter "name=^/${ContainerName}$" --format "{{.ID}}" 2>$null
    return -not [string]::IsNullOrWhiteSpace($containerId)
}

function Wait-MySqlCredentialReady {
    <#
        等待 MySQL 从“端口打开”进入“可使用指定账号访问指定数据库”的状态。

        为什么不能只等 TCP 端口：
        - MySQL 容器启动时会先打开端口，再继续执行初始化脚本、创建 database、创建用户和授权；
        - 如果 Maven E2E 抢在这个窗口执行，JDBC 会看到 Unknown database、Access denied 或 SQLSyntaxErrorException；
        - 对真实 E2E 来说，端口可达只是网络层 ready，账号 + database + SELECT 1 才是数据面 ready。

        安全边界：
        - 探针只执行 SELECT 1，不读取业务表、不创建/修改对象；
        - 使用 MYSQL_PWD 注入到容器内进程，避免把密码拼进 mysql 命令行参数；
        - 输出只报告 PASS/FAIL，不打印密码、SQL 结果正文或底层错误原文。
    #>
    if (-not (Test-DockerContainerExists -ContainerName $MySqlContainerName)) {
        Add-Check -Name "MySQL credential readiness" -Status "WARN" -Detail "Container $MySqlContainerName is not running; Maven E2E will validate external MySQL directly"
        return $true
    }

    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $output = docker exec `
            -e "MYSQL_PWD=$MySqlPassword" `
            $MySqlContainerName `
            mysql `
            --host=127.0.0.1 `
            --port=3306 `
            --user=$MySqlUser `
            --database=$MySqlDatabase `
            --batch `
            --raw `
            --skip-column-names `
            -e "SELECT 1;" 2>$null
        if ($LASTEXITCODE -eq 0 -and ($output | Select-Object -First 1) -eq "1") {
            Add-Check -Name "MySQL credential readiness" -Status "PASS" -Detail "Database $MySqlDatabase accepts the configured E2E user"
            return $true
        }
        Start-Sleep -Seconds $ProbeIntervalSeconds
    }
    Add-Check -Name "MySQL credential readiness" -Status "FAIL" -Detail "Database $MySqlDatabase did not become query-ready before timeout"
    return $false
}

function Wait-PostgresCredentialReady {
    <#
        等待 PostgreSQL 目标库进入可查询状态。

        PostgreSQL 容器 healthcheck 变为 healthy 通常已经足够，但这里仍然用 psql SELECT 1 再确认一次，
        目的是让脚本对“容器已启动但目标 database/user 尚不可用”的问题给出更明确的失败分类。
    #>
    if (-not (Test-DockerContainerExists -ContainerName $PostgresContainerName)) {
        Add-Check -Name "PostgreSQL credential readiness" -Status "WARN" -Detail "Container $PostgresContainerName is not running; Maven E2E will validate external PostgreSQL directly"
        return $true
    }

    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $output = docker exec `
            -e "PGPASSWORD=$PostgresPassword" `
            $PostgresContainerName `
            psql `
            -U $PostgresUser `
            -d $PostgresDatabase `
            -Atqc "SELECT 1;" 2>$null
        if ($LASTEXITCODE -eq 0 -and ($output | Select-Object -First 1) -eq "1") {
            Add-Check -Name "PostgreSQL credential readiness" -Status "PASS" -Detail "Database $PostgresDatabase accepts the configured E2E user"
            return $true
        }
        Start-Sleep -Seconds $ProbeIntervalSeconds
    }
    Add-Check -Name "PostgreSQL credential readiness" -Status "FAIL" -Detail "Database $PostgresDatabase did not become query-ready before timeout"
    return $false
}

function Get-MaskedJdbcEndpoint {
    param(
        [string]$Kind,
        [string]$HostName,
        [int]$Port,
        [string]$Database
    )

    return "${Kind}://${HostName}:$Port/$Database"
}

function Set-ScopedEnv {
    param(
        [string]$Name,
        [string]$Value,
        [hashtable]$Backup
    )

    $existing = Get-Item "Env:$Name" -ErrorAction SilentlyContinue
    $Backup[$Name] = [pscustomobject]@{
        Exists = $null -ne $existing
        Value = if ($null -eq $existing) { $null } else { $existing.Value }
    }
    Set-Item "Env:$Name" -Value $Value
}

function Restore-ScopedEnv {
    param([hashtable]$Backup)

    foreach ($entry in $Backup.GetEnumerator()) {
        if ($entry.Value.Exists) {
            Set-Item "Env:$($entry.Key)" -Value $entry.Value.Value
        } else {
            Remove-Item "Env:$($entry.Key)" -ErrorAction SilentlyContinue
        }
    }
}

function Invoke-DependencyStart {
    $docker = Get-Command docker -ErrorAction SilentlyContinue
    if (-not $docker) {
        Add-Check -Name "Docker CLI" -Status "FAIL" -Detail "docker is not found in PATH; cannot start MySQL/PostgreSQL containers"
        return $false
    }

    try {
        docker info --format "{{.ServerVersion}}" *> $null
        Add-Check -Name "Docker daemon" -Status "PASS" -Detail "Docker CLI and daemon are available"
    } catch {
        Add-Check -Name "Docker daemon" -Status "FAIL" -Detail "Docker CLI exists but daemon is unavailable; start Docker Desktop first"
        return $false
    }

    Push-Location $script:RepoRoot
    try {
        $env:DATASMART_LOCAL_MYSQL_PORT = [string]$MySqlPort
        $env:DATASMART_LOCAL_POSTGRES_PORT = [string]$PostgresPort
        $env:DATASMART_MYSQL_PASSWORD = $MySqlPassword
        $env:DATASMART_POSTGRES_PASSWORD = $PostgresPassword

        <#
            使用 docker-compose.local-e2e.yml 是为了把 MySQL 映射到 13306。
            PostgreSQL 暂时仍使用 5432，后续如果本机已有 PostgreSQL，也可以通过 -PostgresPort 覆盖。
        #>
        docker compose -f docker-compose.yml -f docker-compose.local-e2e.yml up -d postgresql mysql
        if ($LASTEXITCODE -ne 0) {
            Add-Check -Name "Docker compose up" -Status "FAIL" -Detail "Failed to start postgresql/mysql containers"
            return $false
        }
        Add-Check -Name "Docker compose up" -Status "PASS" -Detail "postgresql/mysql containers requested"
        return $true
    } finally {
        Pop-Location
    }
}

function Invoke-MavenRealE2E {
    $envBackup = @{}
    <#
        注意 PowerShell 字符串插值边界：
        - `${MySqlDatabase}?useUnicode=...` 必须用花括号包住变量名；
        - 如果写成 `$MySqlDatabase?useUnicode=...`，PowerShell 会尝试把 `?useUnicode`
          合并进变量解析，最终生成错误的 JDBC URL，表现为 Java 侧 SQLSyntaxErrorException。
    #>
    $mysqlUrl = "jdbc:mysql://${MySqlHost}:${MySqlPort}/${MySqlDatabase}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true"
    $postgresUrl = "jdbc:postgresql://${PostgresHost}:${PostgresPort}/${PostgresDatabase}"

    try {
        Set-ScopedEnv -Name "DATASMART_E2E_REAL_JDBC" -Value "true" -Backup $envBackup
        Set-ScopedEnv -Name "DATASMART_LOCAL_MYSQL_PORT" -Value ([string]$MySqlPort) -Backup $envBackup
        Set-ScopedEnv -Name "DATASMART_LOCAL_POSTGRES_PORT" -Value ([string]$PostgresPort) -Backup $envBackup
        Set-ScopedEnv -Name "DATASMART_E2E_MYSQL_URL" -Value $mysqlUrl -Backup $envBackup
        Set-ScopedEnv -Name "DATASMART_E2E_MYSQL_USER" -Value $MySqlUser -Backup $envBackup
        Set-ScopedEnv -Name "DATASMART_E2E_MYSQL_PASSWORD" -Value $MySqlPassword -Backup $envBackup
        Set-ScopedEnv -Name "DATASMART_E2E_POSTGRES_URL" -Value $postgresUrl -Backup $envBackup
        Set-ScopedEnv -Name "DATASMART_E2E_POSTGRES_USER" -Value $PostgresUser -Backup $envBackup
        Set-ScopedEnv -Name "DATASMART_E2E_POSTGRES_PASSWORD" -Value $PostgresPassword -Backup $envBackup

        Push-Location $script:RepoRoot
        try {
            $maven = if (Test-Path (Join-Path $script:RepoRoot "mvnw.cmd")) {
                Join-Path $script:RepoRoot "mvnw.cmd"
            } else {
                "mvn"
            }
            & $maven `
                -pl datasource-management `
                -am `
                "-Dtest=SyncBatchConnectorRuntimeExternalJdbcE2ETest" `
                "-Dsurefire.failIfNoSpecifiedTests=false" `
                test `
                "-DskipTests=false"
            if ($LASTEXITCODE -eq 0) {
                Add-Check -Name "Maven real JDBC E2E" -Status "PASS" -Detail "datasource-management external JDBC E2E passed"
                return $true
            }
            Add-Check -Name "Maven real JDBC E2E" -Status "FAIL" -Detail "datasource-management external JDBC E2E failed; inspect surefire reports for low-level diagnostics"
            return $false
        } finally {
            Pop-Location
        }
    } finally {
        Restore-ScopedEnv -Backup $envBackup
    }
}

Write-Host "DataSmart Govern data-sync real database E2E" -ForegroundColor Cyan
Write-Host "Repo root: $script:RepoRoot"
Write-Host ("MySQL endpoint: {0}" -f (Get-MaskedJdbcEndpoint -Kind "mysql" -HostName $MySqlHost -Port $MySqlPort -Database $MySqlDatabase))
Write-Host ("PostgreSQL endpoint: {0}" -f (Get-MaskedJdbcEndpoint -Kind "postgresql" -HostName $PostgresHost -Port $PostgresPort -Database $PostgresDatabase))
Write-Host "Password values are never printed. The test overwrites only dedicated E2E tables." -ForegroundColor Yellow

if ($PlanOnly) {
    Add-Check -Name "Plan only" -Status "PASS" -Detail "No containers started and Maven E2E not executed"
    exit 0
}

$allPassed = $true
if (-not $SkipDependencyStart) {
    $allPassed = (Invoke-DependencyStart) -and $allPassed
} else {
    Add-Check -Name "Dependency start" -Status "WARN" -Detail "Skipped by parameter; existing MySQL/PostgreSQL services are expected"
}

$allPassed = (Wait-TcpPort -Name "MySQL" -HostName $MySqlHost -Port $MySqlPort) -and $allPassed
$allPassed = (Wait-TcpPort -Name "PostgreSQL" -HostName $PostgresHost -Port $PostgresPort) -and $allPassed
$allPassed = (Wait-MySqlCredentialReady) -and $allPassed
$allPassed = (Wait-PostgresCredentialReady) -and $allPassed

if (-not $SkipMaven) {
    $allPassed = (Invoke-MavenRealE2E) -and $allPassed
} else {
    Add-Check -Name "Maven real JDBC E2E" -Status "WARN" -Detail "Skipped by parameter; only dependencies and ports were checked"
}

$failedCount = $script:FailureCount
if ($failedCount -gt 0) {
    Write-Host "Real database E2E finished with $failedCount failure(s)." -ForegroundColor Red
    exit 1
}

Write-Host "Real database E2E finished without hard failures." -ForegroundColor Green
exit 0

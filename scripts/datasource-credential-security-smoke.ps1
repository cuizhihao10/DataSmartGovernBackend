<#
    DataSmart Govern 数据源凭据安全只读 Smoke。

    脚本定位：
    - 这个脚本用于验证 datasource-management 的“外部数据源连接密码不再明文存储”是否在真实运行态生效。
    - 它不是迁移脚本，不会修改数据库，不会创建数据源，不会触发数据同步 worker，也不会读取源端业务表数据。
    - 它可以在本地开发机、交付前检查、CI 只读阶段运行，帮助我们持续防止后续代码改动把 password 又暴露回接口或明文表字段。

    安全边界：
    - SQL 只查询 Flyway 版本、password 状态计数、字段类型和密文长度，不输出 password 明文、密文正文、JDBC URL 或用户名。
    - HTTP 检查只验证响应是否包含 password 字段或 ENC[v1] 前缀，不打印完整响应正文。
    - 可选连接测试只输出 testStatus、metadataDiscoverable、discoveredTableCount 等低敏状态，不打印连接串、账号或密码。

    为什么单独做这个脚本：
    - 数据源密码和用户登录密码安全模型不同，外部数据源密码必须可逆解密给 JDBC 使用，因此要持续验证“可逆加密 + API 脱敏”两条线。
    - 只靠一次人工 SQL 抽查容易遗忘；脚本化后可以挂到最终交付总闸门、发布前 smoke 或客户环境巡检中。
#>
[CmdletBinding()]
param(
    [switch]$Strict,
    [switch]$SkipDocker,
    [switch]$SkipDatabase,
    [switch]$SkipHttp,
    [switch]$CheckConnectionTest,
    [int]$DatasourceId = 23,
    [int]$TimeoutSeconds = 5,
    [string]$PostgresContainer = "datasmart-postgresql",
    [string]$PostgresUser = "datasmart",
    [string]$PostgresDatabase = "datasmart_govern",
    [string]$DatasourceManagementBaseUrl = "http://localhost:8082"
)

$ErrorActionPreference = "Stop"
$script:passCount = 0
$script:warnCount = 0
$script:failCount = 0

function Write-SmokeResult {
    param(
        [ValidateSet("PASS", "WARN", "FAIL")]
        [string]$Level,
        [string]$Name,
        [string]$Detail
    )

    if ($Level -eq "PASS") {
        $script:passCount++
    } elseif ($Level -eq "WARN") {
        $script:warnCount++
    } else {
        $script:failCount++
    }

    $color = switch ($Level) {
        "PASS" { "Green" }
        "WARN" { "Yellow" }
        default { "Red" }
    }
    Write-Host ("[{0}] {1} - {2}" -f $Level, $Name, $Detail) -ForegroundColor $color
}

function Test-CommandExists {
    param([string]$CommandName)
    return $null -ne (Get-Command $CommandName -ErrorAction SilentlyContinue)
}

function Invoke-PostgresScalar {
    <#
        执行只读 SQL 并返回 psql 的单行文本。

        设计说明：
        - 这里使用 docker exec 进入 PostgreSQL 容器，是为了复用本地 Compose 网络和认证上下文；
        - SQL 必须由脚本内固定拼接，调用方不能把用户输入直接拼进查询；
        - 输出必须保持低敏，任何需要排查 password 正文的场景都应通过受控 DBA 工具处理，而不是 smoke 脚本。
    #>
    param([string]$Sql)

    $output = & docker exec $PostgresContainer psql `
        -U $PostgresUser `
        -d $PostgresDatabase `
        -t `
        -A `
        -c $Sql 2>&1

    if ($LASTEXITCODE -ne 0) {
        throw "psql 只读查询失败：$($output -join ' ')"
    }
    return (($output -join "`n").Trim())
}

function Test-DockerRuntime {
    if ($SkipDocker) {
        Write-SmokeResult -Level "WARN" -Name "Docker CLI" -Detail "已通过 -SkipDocker 跳过 Docker 检查"
        return
    }
    if (-not (Test-CommandExists -CommandName "docker")) {
        Write-SmokeResult -Level "FAIL" -Name "Docker CLI" -Detail "当前环境未发现 docker 命令，无法检查运行态数据库"
        return
    }

    try {
        $version = (& docker --version) -join " "
        Write-SmokeResult -Level "PASS" -Name "Docker CLI" -Detail $version
    } catch {
        Write-SmokeResult -Level "FAIL" -Name "Docker CLI" -Detail "docker 命令存在但无法执行"
        return
    }

    try {
        $containerLine = (& docker ps --filter "name=$PostgresContainer" --format "{{.Names}}|{{.Status}}") -join "`n"
        if ($containerLine -match [regex]::Escape($PostgresContainer)) {
            Write-SmokeResult -Level "PASS" -Name "PostgreSQL 容器" -Detail "$PostgresContainer 正在运行"
        } else {
            Write-SmokeResult -Level "FAIL" -Name "PostgreSQL 容器" -Detail "$PostgresContainer 未运行，无法验证 datasource_config.password"
        }
    } catch {
        Write-SmokeResult -Level "FAIL" -Name "PostgreSQL 容器" -Detail "无法读取 docker ps"
    }
}

function Test-DatabaseCredentialState {
    if ($SkipDatabase) {
        Write-SmokeResult -Level "WARN" -Name "数据库凭据状态" -Detail "已通过 -SkipDatabase 跳过"
        return
    }

    try {
        $flywayV9 = Invoke-PostgresScalar -Sql "SELECT COUNT(*) FROM datasource_management.flyway_schema_history WHERE version='9' AND success=true;"
        if ([int]$flywayV9 -eq 1) {
            Write-SmokeResult -Level "PASS" -Name "Flyway V9" -Detail "datasource password field encryption 已成功应用"
        } else {
            Write-SmokeResult -Level "FAIL" -Name "Flyway V9" -Detail "未找到成功的 V9 迁移记录"
        }
    } catch {
        Write-SmokeResult -Level "FAIL" -Name "Flyway V9" -Detail $_.Exception.Message
        return
    }

    try {
        $columnType = Invoke-PostgresScalar -Sql "SELECT data_type FROM information_schema.columns WHERE table_schema='datasource_management' AND table_name='datasource_config' AND column_name='password';"
        if ($columnType -eq "text") {
            Write-SmokeResult -Level "PASS" -Name "password 字段类型" -Detail "datasource_config.password 已调整为 TEXT，可容纳版本化密文"
        } else {
            Write-SmokeResult -Level "FAIL" -Name "password 字段类型" -Detail "当前类型为 $columnType，可能仍停留在历史长度限制"
        }
    } catch {
        Write-SmokeResult -Level "FAIL" -Name "password 字段类型" -Detail $_.Exception.Message
    }

    try {
        $stateLine = Invoke-PostgresScalar -Sql "SELECT count(*) || '|' || count(*) FILTER (WHERE password LIKE 'ENC[v1]:%') || '|' || count(*) FILTER (WHERE password IS NULL) || '|' || count(*) FILTER (WHERE password IS NOT NULL AND password NOT LIKE 'ENC[v1]:%') FROM datasource_management.datasource_config;"
        $parts = $stateLine.Split("|")
        $total = [int]$parts[0]
        $encrypted = [int]$parts[1]
        $nullPassword = [int]$parts[2]
        $legacyPlaintext = [int]$parts[3]

        if ($total -eq 0) {
            Write-SmokeResult -Level "WARN" -Name "数据源凭据密文状态" -Detail "当前没有数据源记录，无法验证历史迁移效果"
            return
        }
        if ($legacyPlaintext -eq 0 -and $nullPassword -eq 0 -and $encrypted -eq $total) {
            Write-SmokeResult -Level "PASS" -Name "数据源凭据密文状态" -Detail "total=$total, encrypted=$encrypted, legacyPlaintext=0, nullPassword=0"
        } else {
            Write-SmokeResult -Level "FAIL" -Name "数据源凭据密文状态" -Detail "total=$total, encrypted=$encrypted, legacyPlaintext=$legacyPlaintext, nullPassword=$nullPassword"
        }
    } catch {
        Write-SmokeResult -Level "FAIL" -Name "数据源凭据密文状态" -Detail $_.Exception.Message
    }
}

function Test-HttpSanitization {
    if ($SkipHttp) {
        Write-SmokeResult -Level "WARN" -Name "HTTP 接口脱敏" -Detail "已通过 -SkipHttp 跳过"
        return
    }

    try {
        $url = "$DatasourceManagementBaseUrl/datasources?current=1&size=3"
        $response = Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec $TimeoutSeconds
        if ($response.StatusCode -eq 200) {
            Write-SmokeResult -Level "PASS" -Name "数据源列表 HTTP" -Detail "GET /datasources 返回 200"
        } else {
            Write-SmokeResult -Level "FAIL" -Name "数据源列表 HTTP" -Detail "GET /datasources 返回 HTTP $($response.StatusCode)"
            return
        }

        $content = [string]$response.Content
        if ($content.Contains('"password"')) {
            Write-SmokeResult -Level "FAIL" -Name "接口 password 字段脱敏" -Detail "响应中仍出现 password 字段，可能把凭据暴露给前端"
        } else {
            Write-SmokeResult -Level "PASS" -Name "接口 password 字段脱敏" -Detail "响应中未出现 password 字段"
        }
        if ($content.Contains("ENC[v1]")) {
            Write-SmokeResult -Level "FAIL" -Name "接口密文正文脱敏" -Detail "响应中出现 ENC[v1] 前缀，说明密文正文被返回"
        } else {
            Write-SmokeResult -Level "PASS" -Name "接口密文正文脱敏" -Detail "响应中未出现 ENC[v1] 密文前缀"
        }
    } catch {
        Write-SmokeResult -Level "FAIL" -Name "HTTP 接口脱敏" -Detail $_.Exception.Message
    }
}

function Test-OptionalConnectionDecrypt {
    if (-not $CheckConnectionTest) {
        Write-SmokeResult -Level "WARN" -Name "密文解密连接测试" -Detail "默认不触发连接测试；如需验证 JDBC 解密链路，请追加 -CheckConnectionTest"
        return
    }
    if ($SkipHttp) {
        Write-SmokeResult -Level "WARN" -Name "密文解密连接测试" -Detail "已通过 -SkipHttp 跳过 HTTP，无法执行连接测试"
        return
    }

    try {
        $url = "$DatasourceManagementBaseUrl/datasources/$DatasourceId/test"
        $response = Invoke-RestMethod -Method Post -Uri $url -ContentType "application/json" -Body "{}" -TimeoutSec $TimeoutSeconds
        $data = $response.data
        if ($null -ne $data -and $data.testStatus -eq "SUCCESS") {
            Write-SmokeResult -Level "PASS" -Name "密文解密连接测试" -Detail "datasourceId=$DatasourceId, testStatus=SUCCESS, metadataDiscoverable=$($data.metadataDiscoverable), discoveredTableCount=$($data.discoveredTableCount)"
        } else {
            Write-SmokeResult -Level "FAIL" -Name "密文解密连接测试" -Detail "datasourceId=$DatasourceId 未返回 SUCCESS，当前状态=$($data.testStatus)"
        }
    } catch {
        Write-SmokeResult -Level "FAIL" -Name "密文解密连接测试" -Detail $_.Exception.Message
    }
}

Test-DockerRuntime
Test-DatabaseCredentialState
Test-HttpSanitization
Test-OptionalConnectionDecrypt

Write-Host "[SUMMARY] PASS=$script:passCount, WARN=$script:warnCount, FAIL=$script:failCount"

if ($script:failCount -gt 0) {
    exit 1
}
if ($Strict -and $script:warnCount -gt 0) {
    exit 1
}

<#
    DataSmart Govern data-sync 平台级真实 API E2E。

    设计意图：
    1. 该脚本用于补齐“从平台 API 发起真实数据同步任务”的验收入口，而不是只验证模块内 JUnit、
       fake client 或 datasource-management 单独 JDBC runner。
    2. 默认假设 Java 微服务运行在 Windows 宿主机上，因此登记到 datasource-management 的 JDBC URL
       指向 127.0.0.1:13306 / 127.0.0.1:5432；如果后续切换为容器化 Java 服务，可追加
       -UseContainerJdbcUrls，让 datasource-management 在容器网络中通过 mysql/postgresql 服务名访问数据库。
    3. 默认优先支持 gateway + Keycloak token 路径；如果本地 gateway、Keycloak、Nacos 或 permission-admin
       尚未全部启动，可显式追加 -UseDirectServiceUrls，通过 data-sync/datasource-management 直连 API 验证
       “多服务 HTTP 合同 + 真实 JDBC 数据面”。
    4. 本脚本会创建/覆盖专用 E2E 表：
       - MySQL: datasmart_govern.datasmart_e2e_platform_orders
       - PostgreSQL: datasmart_e2e.orders_platform_clean
       不会访问或打印真实业务表数据。

    本脚本覆盖的闭环：
    1. 创建源端/目标端数据源；
    2. 测试数据源连接；
    3. 创建 FULL + SINGLE_OBJECT + AUTO_SPLIT_PK 同步模板；
    4. 执行 precheck，确认当前 runner 可以启动；
    5. 创建任务并 run，触发 data-sync worker loop；
    6. worker 调用 datasource-management run-once，完成 MySQL -> PostgreSQL 真实写入；
    7. 故意让 id=11..15 因目标 CHECK 约束超过脏数据比例阈值，使对应分片失败；
    8. 修复失败分片源数据后，只重试失败分片，不重跑已成功分片；
    9. 故意让 id=7 因目标 NOT NULL 约束落为少量脏数据样本；
    10. 修复 id=7 后，通过 PRIMARY_KEY_EQ dirty replay 只重放该坏行；
    11. 最终断言 PostgreSQL 目标表包含 1..20 全量数据。
    12. 可选追加 -IncludeOfflineModeClosureE2E，继续验证 SCHEDULED_BATCH、CUSTOM_SQL_QUERY、SCHEMA_FULL
        三类离线模式的真实 API 创建、预检、审批确认、worker 执行和 PostgreSQL 行数断言。

    安全边界：
    - 不打印 access token、refresh token、数据库密码、完整 JDBC URL、SQL 正文、样本行、错误堆栈或响应正文。
    - SQL 仅作用于脚本声明的专用 E2E 表；如果参数中的表名/schema 不是安全标识符，脚本会 fail-closed。
    - 脚本默认不启动 Java 微服务；请先用 scripts/local-e2e-start-runtime.ps1 或手工方式启动服务。
    - gateway 路径只使用本地 Keycloak 样例账号，生产环境应改为正式 OIDC client credentials、企业 IdP、
      mTLS/service mesh 和 Secret Manager。

    推荐用法：
    - 只查看计划，不启动容器、不写数据库、不调用 API：
      .\scripts\local-data-sync-platform-e2e.ps1 -PlanOnly
    - 本地 Java 微服务已启动，直连微服务执行完整平台级 E2E：
      .\scripts\local-data-sync-platform-e2e.ps1 -UseDirectServiceUrls
    - 本地 gateway/Keycloak/permission-admin/Nacos/Java 服务全部启动后，通过 gateway 执行：
      .\scripts\local-data-sync-platform-e2e.ps1
    - 如果 Java 服务跑在 Docker 网络里：
      .\scripts\local-data-sync-platform-e2e.ps1 -UseContainerJdbcUrls
#>
param(
    [switch]$PlanOnly,
    [switch]$UseDirectServiceUrls,
    [switch]$UseContainerJdbcUrls,
    [switch]$SkipDependencyStart,
    [switch]$SkipDatabasePrepare,
    [switch]$SkipTaskReceiptProbe,
    [switch]$IncludeOfflineModeClosureE2E,
    [switch]$Strict,

    [string]$GatewayBaseUrl = "http://localhost:8080",
    [string]$DatasourceManagementBaseUrl = "http://localhost:8082",
    [string]$DataSyncBaseUrl = "http://localhost:8086",
    [string]$TaskManagementBaseUrl = "http://localhost:8081",
    [string]$KeycloakBaseUrl = "http://localhost:18080",

    [string]$UserAccountUsername = "project-owner",
    [string]$UserAccountPassword = "DataSmart@123",
    [string]$ServiceAccountUsername = "sync-service",
    [string]$ServiceAccountPassword = "DataSmart@123",
    [string]$OidcClientId = "datasmart-gateway",
    [string]$UserAccessToken = "",
    [string]$ServiceAccessToken = "",

    [long]$TenantId = 10,
    [long]$ProjectId = 101,
    [long]$WorkspaceId = 301,
    [long]$ActorId = 1001,
    [string]$ActorRole = "PROJECT_OWNER",

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
    [string]$SourceJdbcUrl = "",
    [string]$TargetJdbcUrl = "",
    [string]$MySqlContainerName = "datasmart-mysql",
    [string]$PostgresContainerName = "datasmart-postgresql",

    [string]$SourceTable = "datasmart_e2e_platform_orders",
    [string]$TargetSchema = "datasmart_e2e",
    [string]$TargetTable = "orders_platform_clean",

    [int]$TimeoutSeconds = 10,
    [int]$StartupTimeoutSeconds = 120,
    [int]$ProbeIntervalSeconds = 3
)

$ErrorActionPreference = "Stop"
$script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$script:Checks = New-Object System.Collections.Generic.List[object]
$script:FailureCount = 0
$script:RunId = (Get-Date).ToString("yyyyMMddHHmmss")
$script:ScheduledBatchSourceTable = "datasmart_e2e_scheduled_orders"
$script:ScheduledBatchTargetTable = "orders_scheduled_batch"
$script:CustomSqlSourceTable = "datasmart_e2e_sql_orders"
$script:CustomSqlTargetTable = "orders_custom_sql_clean"
$script:SchemaFullSourceOrdersTable = "datasmart_e2e_schema_orders"
$script:SchemaFullSourceCustomersTable = "datasmart_e2e_schema_customers"
$script:SchemaFullTargetOrdersTable = "datasmart_e2e_schema_orders"
$script:SchemaFullTargetCustomersTable = "datasmart_e2e_schema_customers"

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

function Fail-Step {
    param(
        [string]$Name,
        [string]$Detail
    )

    Add-Check -Name $Name -Status "FAIL" -Detail $Detail
    throw $Detail
}

function Test-CommandExists {
    param([string]$CommandName)
    return $null -ne (Get-Command $CommandName -ErrorAction SilentlyContinue)
}

function Invoke-NativeCommandSafely {
    param(
        [string]$Name,
        [scriptblock]$Command
    )

    <#
        Windows PowerShell 5.1 对 native command 的 stderr 处理比较“敏感”：
        Docker Compose 在命令成功时也可能把 warning 写到 stderr，例如提示存在 orphan containers。
        如果全局 $ErrorActionPreference=Stop，这类非致命 warning 可能被 PowerShell 包装成
        NativeCommandError，从而让脚本在 Docker 已经成功执行后提前中断。

        因此这里专门为 docker/docker compose/mysql/psql 这类外部命令建立一个安全执行器：
        - 临时把 ErrorActionPreference 调整为 Continue，避免 stderr warning 直接变成终止异常；
        - 捕获 stdout/stderr，但调用方默认不打印，继续遵守低敏输出策略；
        - 只使用 $LASTEXITCODE 判断 native 命令是否真正失败。
    #>
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $Command 2>&1
        return [pscustomobject]@{
            Name = $Name
            ExitCode = $LASTEXITCODE
            Output = $output
        }
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}

function Assert-SafeIdentifier {
    param(
        [string]$Name,
        [string]$Value
    )

    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch '^[A-Za-z_][A-Za-z0-9_]{0,63}$') {
        Fail-Step -Name "安全标识符: $Name" -Detail "参数不是安全数据库标识符，脚本已拒绝继续"
    }
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
            Add-Check -Name "$Name 端口" -Status "PASS" -Detail "${HostName}:$Port 已可达"
            return $true
        }
        Start-Sleep -Seconds $ProbeIntervalSeconds
    }
    Fail-Step -Name "$Name 端口" -Detail "${HostName}:$Port 在超时时间内不可达"
}

function Invoke-DependencyStart {
    if ($SkipDependencyStart) {
        Add-Check -Name "基础依赖启动" -Status "WARN" -Detail "已通过 -SkipDependencyStart 跳过，要求外部环境已启动 MySQL/PostgreSQL"
        return
    }
    if (-not (Test-CommandExists -CommandName "docker")) {
        Fail-Step -Name "Docker CLI" -Detail "未发现 docker 命令，无法准备真实 MySQL/PostgreSQL E2E 数据库"
    }

    try {
        docker info --format "{{.ServerVersion}}" *> $null
        Add-Check -Name "Docker daemon" -Status "PASS" -Detail "Docker CLI 与 daemon 可用"
    } catch {
        Fail-Step -Name "Docker daemon" -Detail "Docker CLI 存在但 daemon 不可用，请先启动 Docker Desktop"
    }

    Push-Location $script:RepoRoot
    try {
        $env:DATASMART_LOCAL_MYSQL_PORT = [string]$MySqlPort
        $env:DATASMART_LOCAL_POSTGRES_PORT = [string]$PostgresPort
        $env:DATASMART_MYSQL_PASSWORD = $MySqlPassword
        $env:DATASMART_POSTGRES_PASSWORD = $PostgresPassword

        $services = @("postgresql", "mysql")
        if (-not $UseDirectServiceUrls) {
            $services += @("keycloak-db-bootstrap", "keycloak")
        }
        $composeResult = Invoke-NativeCommandSafely -Name "Docker compose up" -Command {
            docker compose -f docker-compose.yml -f docker-compose.local-e2e.yml up -d @services
        }
        if ($composeResult.ExitCode -ne 0) {
            Fail-Step -Name "Docker compose up" -Detail "基础依赖容器启动失败，请检查镜像拉取、端口占用和 Docker Desktop 状态"
        }
        Add-Check -Name "Docker compose up" -Status "PASS" -Detail "已请求启动 E2E 所需基础依赖容器"
    } finally {
        Pop-Location
    }
}

function Test-DockerContainerExists {
    param([string]$ContainerName)

    $psResult = Invoke-NativeCommandSafely -Name "Docker ps: $ContainerName" -Command {
        docker ps --filter "name=^/${ContainerName}$" --format "{{.ID}}"
    }
    if ($psResult.ExitCode -ne 0) {
        return $false
    }
    $containerId = ($psResult.Output | Select-Object -First 1) -as [string]
    return -not [string]::IsNullOrWhiteSpace($containerId)
}

function Invoke-MySqlNonQuery {
    param([string]$Sql)

    if (-not (Test-DockerContainerExists -ContainerName $MySqlContainerName)) {
        Fail-Step -Name "MySQL 容器" -Detail "未发现运行中的 $MySqlContainerName，无法准备源端 E2E 表"
    }
    $mysqlResult = Invoke-NativeCommandSafely -Name "MySQL E2E SQL" -Command {
        $Sql | docker exec -i `
            -e "MYSQL_PWD=$MySqlPassword" `
            $MySqlContainerName `
            mysql `
            --host=127.0.0.1 `
            --port=3306 `
            --user=$MySqlUser `
            --database=$MySqlDatabase `
            --batch `
            --raw `
            --skip-column-names
    }
    if ($mysqlResult.ExitCode -ne 0) {
        Fail-Step -Name "MySQL E2E SQL" -Detail "源端 E2E 表准备或修复失败；为低敏输出，脚本不打印 SQL 正文和底层错误正文"
    }
}

function Invoke-PostgresNonQuery {
    param([string]$Sql)

    if (-not (Test-DockerContainerExists -ContainerName $PostgresContainerName)) {
        Fail-Step -Name "PostgreSQL 容器" -Detail "未发现运行中的 $PostgresContainerName，无法准备目标端 E2E 表"
    }
    $postgresResult = Invoke-NativeCommandSafely -Name "PostgreSQL E2E SQL" -Command {
        $Sql | docker exec -i `
            -e "PGPASSWORD=$PostgresPassword" `
            $PostgresContainerName `
            psql `
            -U $PostgresUser `
            -d $PostgresDatabase `
            -v ON_ERROR_STOP=1 `
            -q
    }
    if ($postgresResult.ExitCode -ne 0) {
        Fail-Step -Name "PostgreSQL E2E SQL" -Detail "目标端 E2E 表准备或修复失败；为低敏输出，脚本不打印 SQL 正文和底层错误正文"
    }
}

function Invoke-PostgresScalar {
    param([string]$Sql)

    $postgresResult = Invoke-NativeCommandSafely -Name "PostgreSQL scalar SQL" -Command {
        $Sql | docker exec -i `
            -e "PGPASSWORD=$PostgresPassword" `
            $PostgresContainerName `
            psql `
            -U $PostgresUser `
            -d $PostgresDatabase `
            -Atq
    }
    if ($postgresResult.ExitCode -ne 0) {
        Fail-Step -Name "PostgreSQL 结果断言" -Detail "目标端断言查询失败；脚本不打印 SQL 正文和底层错误正文"
    }
    return (($postgresResult.Output | Select-Object -First 1) -as [string]).Trim()
}

function Initialize-E2EDatabase {
    if ($SkipDatabasePrepare) {
        Add-Check -Name "E2E 表准备" -Status "WARN" -Detail "已通过 -SkipDatabasePrepare 跳过，要求外部已准备源表和目标表"
        return
    }

    Assert-SafeIdentifier -Name "SourceTable" -Value $SourceTable
    Assert-SafeIdentifier -Name "TargetSchema" -Value $TargetSchema
    Assert-SafeIdentifier -Name "TargetTable" -Value $TargetTable

    <#
        源端数据设计：
        - id=7 的 customer_name=NULL，用于制造“少量坏行”并触发 dirty sample；
        - id=11..15 的 amount 为负数，目标端 CHECK(amount >= 0) 会让整个分片超过 dirty ratio，
          用于验证失败分片选择性重试，而不是整单回滚或整单重跑；
        - region 全部为 EAST，让 filterConfig 真实参与读取，但不会因为过滤减少测试行数。
    #>
    $mysqlSql = @"
DROP TABLE IF EXISTS $SourceTable;
CREATE TABLE $SourceTable (
    id BIGINT PRIMARY KEY,
    customer_name VARCHAR(128) NULL,
    amount DECIMAL(18, 2) NOT NULL,
    region VARCHAR(32) NOT NULL
);
INSERT INTO $SourceTable (id, customer_name, amount, region) VALUES
(1, 'Customer-1', 101.00, 'EAST'),
(2, 'Customer-2', 102.00, 'EAST'),
(3, 'Customer-3', 103.00, 'EAST'),
(4, 'Customer-4', 104.00, 'EAST'),
(5, 'Customer-5', 105.00, 'EAST'),
(6, 'Customer-6', 106.00, 'EAST'),
(7, NULL, 107.00, 'EAST'),
(8, 'Customer-8', 108.00, 'EAST'),
(9, 'Customer-9', 109.00, 'EAST'),
(10, 'Customer-10', 110.00, 'EAST'),
(11, 'Customer-11', -111.00, 'EAST'),
(12, 'Customer-12', -112.00, 'EAST'),
(13, 'Customer-13', -113.00, 'EAST'),
(14, 'Customer-14', -114.00, 'EAST'),
(15, 'Customer-15', -115.00, 'EAST'),
(16, 'Customer-16', 116.00, 'EAST'),
(17, 'Customer-17', 117.00, 'EAST'),
(18, 'Customer-18', 118.00, 'EAST'),
(19, 'Customer-19', 119.00, 'EAST'),
(20, 'Customer-20', 120.00, 'EAST');
"@

    <#
        目标端约束设计：
        - name NOT NULL：用于把 id=7 转换为结构化 dirty sample；
        - amount >= 0：用于把 id=11..15 所在分片打成失败分片；
        - PRIMARY KEY(id)：配合 UPSERT 和 PRIMARY_KEY_EQ replay，保证重试/重放幂等。
    #>
    $postgresSql = @"
CREATE SCHEMA IF NOT EXISTS $TargetSchema;
DROP TABLE IF EXISTS $TargetSchema.$TargetTable;
CREATE TABLE $TargetSchema.$TargetTable (
    id BIGINT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    amount NUMERIC(18, 2) NOT NULL,
    region VARCHAR(32) NOT NULL,
    CONSTRAINT ck_${TargetTable}_amount_non_negative CHECK (amount >= 0)
);
"@

    Invoke-MySqlNonQuery -Sql $mysqlSql
    Invoke-PostgresNonQuery -Sql $postgresSql
    Add-Check -Name "E2E 表准备" -Status "PASS" -Detail "已创建专用 MySQL 源表和 PostgreSQL 目标表"
}

function Repair-FailedShardSourceRows {
    $repairSql = "UPDATE $SourceTable SET amount = ABS(amount) WHERE id BETWEEN 11 AND 15;"
    Invoke-MySqlNonQuery -Sql $repairSql
    Add-Check -Name "失败分片源数据修复" -Status "PASS" -Detail "已修复 id=11..15 的源端金额，后续只重试失败分片"
}

function Repair-DirtySourceRow {
    $repairSql = "UPDATE $SourceTable SET customer_name = 'Repaired-Customer-7' WHERE id = 7;"
    Invoke-MySqlNonQuery -Sql $repairSql
    Add-Check -Name "脏数据源行修复" -Status "PASS" -Detail "已修复 id=7 的源端姓名，后续通过 PRIMARY_KEY_EQ replay 精确重放"
}

function Initialize-OfflineModeE2EDatabase {
    if (-not $IncludeOfflineModeClosureE2E) {
        return
    }
    if ($SkipDatabasePrepare) {
        Add-Check -Name "离线模式 E2E 表准备" -Status "WARN" -Detail "已通过 -SkipDatabasePrepare 跳过，要求外部已准备 SCHEDULED/CUSTOM_SQL/SCHEMA_FULL 专用表"
        return
    }

    $offlineIdentifiers = @(
        $script:ScheduledBatchSourceTable,
        $script:ScheduledBatchTargetTable,
        $script:CustomSqlSourceTable,
        $script:CustomSqlTargetTable,
        $script:SchemaFullSourceOrdersTable,
        $script:SchemaFullSourceCustomersTable,
        $script:SchemaFullTargetOrdersTable,
        $script:SchemaFullTargetCustomersTable
    )
    foreach ($identifier in $offlineIdentifiers) {
        Assert-SafeIdentifier -Name "OfflineModeTable" -Value $identifier
    }

    <#
        离线模式闭环表设计原则：
        - SCHEDULED_BATCH 使用单表、单次有界窗口，证明“定时批量”可以由任务调度层触发，执行面复用 run-once；
        - CUSTOM_SQL_QUERY 使用只读 SELECT 结果集，目标字段按 SQL alias 映射，不把 SQL 正文打印到终端；
        - SCHEMA_FULL 使用两张同构小表，让 datasource-management metadata discovery 枚举表/字段后转 OBJECT_LIST fan-out。

        这里仍然只创建专用 E2E 表，不碰业务表；目标端先清表重建，保证每次验收结果可重复。
    #>
    $mysqlSql = @"
DROP TABLE IF EXISTS $($script:ScheduledBatchSourceTable);
CREATE TABLE $($script:ScheduledBatchSourceTable) (
    id BIGINT PRIMARY KEY,
    customer_name VARCHAR(128) NOT NULL,
    amount DECIMAL(18, 2) NOT NULL,
    region VARCHAR(32) NOT NULL
);
INSERT INTO $($script:ScheduledBatchSourceTable) (id, customer_name, amount, region) VALUES
(101, 'Scheduled-Customer-101', 201.00, 'NORTH'),
(102, 'Scheduled-Customer-102', 202.00, 'NORTH'),
(103, 'Scheduled-Customer-103', 203.00, 'NORTH');

DROP TABLE IF EXISTS $($script:CustomSqlSourceTable);
CREATE TABLE $($script:CustomSqlSourceTable) (
    id BIGINT PRIMARY KEY,
    customer_name VARCHAR(128) NOT NULL,
    amount DECIMAL(18, 2) NOT NULL,
    region VARCHAR(32) NOT NULL
);
INSERT INTO $($script:CustomSqlSourceTable) (id, customer_name, amount, region) VALUES
(201, 'Sql-Customer-201', 301.00, 'EAST'),
(202, 'Sql-Customer-202', 302.00, 'WEST'),
(203, 'Sql-Customer-203', 303.00, 'EAST');

DROP TABLE IF EXISTS $($script:SchemaFullSourceOrdersTable);
CREATE TABLE $($script:SchemaFullSourceOrdersTable) (
    id BIGINT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    amount DECIMAL(18, 2) NOT NULL,
    region VARCHAR(32) NOT NULL
);
INSERT INTO $($script:SchemaFullSourceOrdersTable) (id, name, amount, region) VALUES
(301, 'Schema-Order-301', 401.00, 'SOUTH'),
(302, 'Schema-Order-302', 402.00, 'SOUTH');

DROP TABLE IF EXISTS $($script:SchemaFullSourceCustomersTable);
CREATE TABLE $($script:SchemaFullSourceCustomersTable) (
    id BIGINT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    amount DECIMAL(18, 2) NOT NULL,
    region VARCHAR(32) NOT NULL
);
INSERT INTO $($script:SchemaFullSourceCustomersTable) (id, name, amount, region) VALUES
(401, 'Schema-Customer-401', 501.00, 'SOUTH'),
(402, 'Schema-Customer-402', 502.00, 'SOUTH');
"@

    $postgresSql = @"
CREATE SCHEMA IF NOT EXISTS $TargetSchema;
DROP TABLE IF EXISTS $TargetSchema.$($script:ScheduledBatchTargetTable);
CREATE TABLE $TargetSchema.$($script:ScheduledBatchTargetTable) (
    id BIGINT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    amount NUMERIC(18, 2) NOT NULL,
    region VARCHAR(32) NOT NULL
);

DROP TABLE IF EXISTS $TargetSchema.$($script:CustomSqlTargetTable);
CREATE TABLE $TargetSchema.$($script:CustomSqlTargetTable) (
    id BIGINT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    amount NUMERIC(18, 2) NOT NULL,
    region VARCHAR(32) NOT NULL
);

DROP TABLE IF EXISTS $TargetSchema.$($script:SchemaFullTargetOrdersTable);
CREATE TABLE $TargetSchema.$($script:SchemaFullTargetOrdersTable) (
    id BIGINT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    amount NUMERIC(18, 2) NOT NULL,
    region VARCHAR(32) NOT NULL
);

DROP TABLE IF EXISTS $TargetSchema.$($script:SchemaFullTargetCustomersTable);
CREATE TABLE $TargetSchema.$($script:SchemaFullTargetCustomersTable) (
    id BIGINT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    amount NUMERIC(18, 2) NOT NULL,
    region VARCHAR(32) NOT NULL
);
"@

    Invoke-MySqlNonQuery -Sql $mysqlSql
    Invoke-PostgresNonQuery -Sql $postgresSql
    Add-Check -Name "离线模式 E2E 表准备" -Status "PASS" -Detail "已创建 SCHEDULED_BATCH、CUSTOM_SQL_QUERY、SCHEMA_FULL 专用源表和目标表"
}

function Resolve-SourceJdbcUrl {
    if (-not [string]::IsNullOrWhiteSpace($SourceJdbcUrl)) {
        return $SourceJdbcUrl
    }
    if ($UseContainerJdbcUrls) {
        return "jdbc:mysql://mysql:3306/${MySqlDatabase}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true"
    }
    return "jdbc:mysql://${MySqlHost}:${MySqlPort}/${MySqlDatabase}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&rewriteBatchedStatements=true"
}

function Resolve-TargetJdbcUrl {
    if (-not [string]::IsNullOrWhiteSpace($TargetJdbcUrl)) {
        return $TargetJdbcUrl
    }
    if ($UseContainerJdbcUrls) {
        return "jdbc:postgresql://postgresql:5432/${PostgresDatabase}"
    }
    return "jdbc:postgresql://${PostgresHost}:${PostgresPort}/${PostgresDatabase}"
}

function Get-MaskedJdbcEndpoint {
    param(
        [string]$Kind,
        [string]$JdbcUrl
    )

    if ($JdbcUrl -match "jdbc:[a-z]+://([^/?]+)") {
        return "${Kind}://$($Matches[1])/<database>"
    }
    return "${Kind}://<masked>"
}

function Get-AccessToken {
    param(
        [string]$Username,
        [string]$Password,
        [string]$Purpose
    )

    try {
        $tokenResponse = Invoke-RestMethod -Method Post `
            -Uri "$KeycloakBaseUrl/realms/datasmart/protocol/openid-connect/token" `
            -ContentType "application/x-www-form-urlencoded" `
            -TimeoutSec $TimeoutSeconds `
            -Body @{
                grant_type = "password"
                client_id = $OidcClientId
                username = $Username
                password = $Password
            }
        $accessToken = $tokenResponse.access_token
        if ([string]::IsNullOrWhiteSpace($accessToken)) {
            Fail-Step -Name "Keycloak token: $Purpose" -Detail "Keycloak 返回成功但没有 access_token"
        }
        Add-Check -Name "Keycloak token: $Purpose" -Status "PASS" -Detail "已获取本地样例账号 token，未打印 token 正文"
        return $accessToken
    } catch {
        Fail-Step -Name "Keycloak token: $Purpose" -Detail "无法获取本地 Keycloak token，请确认 keycloak 已启动且 realm 已导入"
    }
}

function New-TraceId {
    param([string]$Stage)
    return "local-data-sync-platform-e2e-$script:RunId-$Stage"
}

function New-ApiHeaders {
    param(
        [string]$TraceId,
        [string]$Token,
        [string]$Role = $ActorRole,
        [string]$ActorType = "USER",
        [long]$CurrentActorId = $ActorId
    )

    $headers = @{
        "Accept" = "application/json"
        "X-DataSmart-Trace-Id" = $TraceId
    }

    if (-not [string]::IsNullOrWhiteSpace($Token)) {
        $headers["Authorization"] = "Bearer $Token"
    }

    if ($UseDirectServiceUrls) {
        <#
            直连模式没有 gateway 帮我们注入平台上下文，因此脚本显式补齐低敏身份 Header。
            这些 Header 只用于本地 E2E；生产环境不应允许浏览器或外部客户端自报身份。
        #>
        $headers["X-DataSmart-Tenant-Id"] = [string]$TenantId
        $headers["X-DataSmart-Project-Id"] = [string]$ProjectId
        $headers["X-DataSmart-Workspace-Id"] = [string]$WorkspaceId
        $headers["X-DataSmart-Actor-Id"] = [string]$CurrentActorId
        $headers["X-DataSmart-Actor-Role"] = $Role
        $headers["X-DataSmart-Actor-Type"] = $ActorType
        $headers["X-DataSmart-Source-Service"] = "local-data-sync-platform-e2e"
        $headers["X-DataSmart-Data-Scope-Level"] = "PROJECT"
        $headers["X-DataSmart-Authorized-Project-Ids"] = [string]$ProjectId
    }

    return $headers
}

function Invoke-Api {
    param(
        [string]$Name,
        [ValidateSet("GET", "POST", "PUT", "DELETE")]
        [string]$Method,
        [string]$Url,
        [hashtable]$Headers,
        [object]$Body = $null
    )

    try {
        $parameters = @{
            Method = $Method
            Uri = $Url
            Headers = $Headers
            TimeoutSec = $TimeoutSeconds
        }
        if ($null -ne $Body) {
            $parameters["ContentType"] = "application/json; charset=utf-8"
            $parameters["Body"] = ($Body | ConvertTo-Json -Depth 30 -Compress)
        }
        $response = Invoke-RestMethod @parameters
        Add-Check -Name $Name -Status "PASS" -Detail "HTTP 调用成功，响应正文未打印"
        return $response
    } catch {
        $statusCode = $null
        if ($_.Exception.Response -ne $null) {
            try {
                $statusCode = [int]$_.Exception.Response.StatusCode
            } catch {
                $statusCode = $null
            }
        }
        $statusText = if ($null -eq $statusCode) { "NO_RESPONSE" } else { [string]$statusCode }
        Fail-Step -Name $Name -Detail "HTTP 调用失败，status=$statusText；脚本不打印响应正文、token 或底层堆栈"
    }
}

function Get-EnvelopeData {
    param(
        [object]$Response,
        [string]$Name
    )

    if ($null -eq $Response -or -not ($Response.PSObject.Properties.Name -contains "code")) {
        Fail-Step -Name $Name -Detail "响应不是统一 code/message/data envelope"
    }
    if ([int]$Response.code -ne 0) {
        Fail-Step -Name $Name -Detail "业务 code 非 0，message 未展开打印，请查看服务日志中的 traceId"
    }
    return $Response.data
}

function Get-PageRecords {
    param([object]$PageData)

    if ($null -eq $PageData -or -not ($PageData.PSObject.Properties.Name -contains "records")) {
        return @()
    }
    return @($PageData.records)
}

function Assert-DatasourceConnectionTestSuccess {
    param(
        [object]$ConnectionTestData,
        [string]$Name
    )

    <#
        datasource-management 的连接测试接口采用“HTTP 调用成功 + data.testStatus 表达连接结果”的产品语义：
        - HTTP 200/code=0：表示平台接口完成了一次测试动作，服务没有崩溃；
        - data.testStatus=SUCCESS：才表示 datasource-management 所在运行环境真的连上了用户配置的 JDBC。

        这个区分对容器化 E2E 很关键：当 Java 服务运行在 Docker 网络里时，JDBC 写成 127.0.0.1
        实际会指向 Java 容器自身，而不是 Windows 宿主机或数据库容器。此时接口仍可能返回 code=0，
        但 data.testStatus 会是 FAILED。脚本必须在这里 fail-fast，并提示使用 -UseContainerJdbcUrls，
        否则后续 AUTO_SPLIT_PK range-probe 才暴露问题，排障成本会高很多。
    #>
    if ($null -eq $ConnectionTestData -or -not ($ConnectionTestData.PSObject.Properties.Name -contains "testStatus")) {
        Fail-Step -Name $Name -Detail "连接测试响应缺少 testStatus，无法确认执行器所在环境是否能访问该数据源"
    }
    if ("SUCCESS" -ne ([string]$ConnectionTestData.testStatus).ToUpperInvariant()) {
        $hint = if ($UseContainerJdbcUrls) {
            "当前已使用 -UseContainerJdbcUrls，请检查数据库容器、凭据、网络别名和 datasource-management 日志"
        } else {
            "如果 Java 服务运行在 Docker 容器内，请追加 -UseContainerJdbcUrls，让 JDBC 使用 mysql/postgresql 服务名"
        }
        Fail-Step -Name $Name -Detail "连接测试结果不是 SUCCESS；$hint"
    }
    Add-Check -Name $Name -Status "PASS" -Detail "连接测试返回 SUCCESS"
}

function Invoke-HealthProbe {
    param(
        [string]$Name,
        [string]$Url
    )

    try {
        $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec $TimeoutSeconds
        if ([int]$response.StatusCode -eq 200) {
            Add-Check -Name $Name -Status "PASS" -Detail "health 端点可达"
            return
        }
        Fail-Step -Name $Name -Detail "health 端点返回非 200 状态"
    } catch {
        Fail-Step -Name $Name -Detail "health 端点不可达，请先启动对应 Java 微服务"
    }
}

function Invoke-OptionalReceiptProbe {
    param([hashtable]$Headers)

    if ($SkipTaskReceiptProbe) {
        Add-Check -Name "task-management receipt 探针" -Status "WARN" -Detail "已通过 -SkipTaskReceiptProbe 跳过"
        return
    }
    try {
        $response = Invoke-WebRequest `
            -Uri "$TaskManagementBaseUrl/internal/data-sync-worker-execution-receipts?limit=5" `
            -Headers $Headers `
            -UseBasicParsing `
            -TimeoutSec $TimeoutSeconds
        if ([int]$response.StatusCode -eq 200) {
            Add-Check -Name "task-management receipt 探针" -Status "PASS" -Detail "receipt 查询端点可达，响应正文未打印"
            return
        }
        Add-Check -Name "task-management receipt 探针" -Status "WARN" -Detail "receipt 查询端点返回非 200，但不阻断 data-sync 主链路"
    } catch {
        Add-Check -Name "task-management receipt 探针" -Status "WARN" -Detail "receipt 查询端点暂不可达，不阻断 data-sync 主链路；可检查 task-management 服务"
    }
}

function Assert-TargetCount {
    param(
        [int]$Expected,
        [string]$Stage
    )

    $countSql = "SELECT COUNT(*) FROM $TargetSchema.$TargetTable;"
    $actual = [int](Invoke-PostgresScalar -Sql $countSql)
    if ($actual -ne $Expected) {
        Fail-Step -Name "目标表行数断言: $Stage" -Detail "期望 $Expected 条，实际 $actual 条"
    }
    Add-Check -Name "目标表行数断言: $Stage" -Status "PASS" -Detail "目标表达到预期行数 $Expected"
}

function Assert-TargetTableCount {
    param(
        [string]$TableName,
        [int]$Expected,
        [string]$Stage
    )

    Assert-SafeIdentifier -Name "TargetTable:$Stage" -Value $TableName
    $countSql = "SELECT COUNT(*) FROM $TargetSchema.$TableName;"
    $actual = [int](Invoke-PostgresScalar -Sql $countSql)
    if ($actual -ne $Expected) {
        Fail-Step -Name "目标表行数断言: $Stage" -Detail "期望 $Expected 条，实际 $actual 条"
    }
    Add-Check -Name "目标表行数断言: $Stage" -Status "PASS" -Detail "目标表达到预期行数 $Expected"
}

function Assert-ReplayedRow {
    $nameSql = "SELECT name FROM $TargetSchema.$TargetTable WHERE id = 7;"
    $actualName = Invoke-PostgresScalar -Sql $nameSql
    if ($actualName -ne "Repaired-Customer-7") {
        Fail-Step -Name "脏数据 replay 断言" -Detail "id=7 未按修复后的源端值写入目标端"
    }
    Add-Check -Name "脏数据 replay 断言" -Status "PASS" -Detail "id=7 已通过 PRIMARY_KEY_EQ replay 写入修复后的值"
}

function Write-ExecutionPlan {
    $sourceJdbc = Resolve-SourceJdbcUrl
    $targetJdbc = Resolve-TargetJdbcUrl

    Write-Host "DataSmart Govern data-sync platform API E2E" -ForegroundColor Cyan
    Write-Host "Repo root: $script:RepoRoot"
    Write-Host ("API mode: {0}" -f ($(if ($UseDirectServiceUrls) { "direct service URLs" } else { "gateway + Keycloak token" })))
    Write-Host ("Source endpoint: {0}" -f (Get-MaskedJdbcEndpoint -Kind "mysql" -JdbcUrl $sourceJdbc))
    Write-Host ("Target endpoint: {0}" -f (Get-MaskedJdbcEndpoint -Kind "postgresql" -JdbcUrl $targetJdbc))
    Write-Host "Password, token, full JDBC URL, SQL bodies, row samples, and raw responses are never printed." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Planned stages:" -ForegroundColor Cyan
    Write-Host "1. Start or reuse MySQL/PostgreSQL dependency containers unless skipped."
    Write-Host "2. Prepare dedicated E2E source/target tables with one dirty row and one failed shard scenario."
    Write-Host "3. Create datasource records through API and test both connections."
    Write-Host "4. Create FULL/SINGLE_OBJECT/AUTO_SPLIT_PK sync template and run precheck."
    Write-Host "5. Create task, run worker loop, retry only failed shard, then replay repaired dirty row."
    Write-Host "6. Assert PostgreSQL target table reaches 20 complete rows."
    if ($IncludeOfflineModeClosureE2E) {
        Write-Host "7. Additionally verify SCHEDULED_BATCH, CUSTOM_SQL_QUERY, and SCHEMA_FULL platform/API closure."
    }
}

function Resolve-ApiRoots {
    if ($UseDirectServiceUrls) {
        return [pscustomobject]@{
            Datasource = $DatasourceManagementBaseUrl
            Sync = $DataSyncBaseUrl
        }
    }
    return [pscustomobject]@{
        Datasource = "$GatewayBaseUrl/api/datasource"
        Sync = "$GatewayBaseUrl/api/sync"
    }
}

function Invoke-WorkerLoop {
    param(
        [string]$Stage,
        [hashtable]$Headers,
        [string]$SyncApiRoot
    )

    $body = @{
        executorId = "local-platform-api-e2e"
        tenantId = $TenantId
        maxExecutions = 1
        leaseSeconds = 600
    }
    $response = Invoke-Api `
        -Name "worker loop: $Stage" `
        -Method "POST" `
        -Url "$SyncApiRoot/internal/sync-workers/run-once" `
        -Headers $Headers `
        -Body $body
    return Get-EnvelopeData -Response $response -Name "worker loop envelope: $Stage"
}

function Assert-OfflinePrecheckRunnable {
    param(
        [object]$Precheck,
        [string]$Stage,
        [switch]$ApprovalExpected
    )

    if ($ApprovalExpected) {
        if ([string]$Precheck.precheckStatus -ne "REQUIRES_APPROVAL" -or -not [bool]$Precheck.approvalRequired) {
            Fail-Step -Name "离线模式预检查: $Stage" -Detail "预期该模式需要审批，但 precheck 未返回 REQUIRES_APPROVAL"
        }
        Add-Check -Name "离线模式预检查: $Stage" -Status "PASS" -Detail "precheck 已识别为可执行但需要审批确认"
        return
    }
    if (-not [bool]$Precheck.canStartExecution) {
        Fail-Step -Name "离线模式预检查: $Stage" -Detail "预期该模式可直接启动，但 precheck 未放行"
    }
    Add-Check -Name "离线模式预检查: $Stage" -Status "PASS" -Detail "precheck 已允许当前 runner 启动"
}

function Invoke-OfflineModeTemplateTaskRun {
    param(
        [string]$Stage,
        [object]$ApiRoots,
        [hashtable]$TemplateHeaders,
        [hashtable]$TaskHeaders,
        [hashtable]$RunHeaders,
        [hashtable]$WorkerHeaders,
        [hashtable]$TemplateBody,
        [hashtable]$TaskBody,
        [switch]$ApprovalExpected
    )

    $templateResponse = Invoke-Api `
        -Name "创建离线模式模板: $Stage" `
        -Method "POST" `
        -Url "$($ApiRoots.Sync)/sync-templates" `
        -Headers $TemplateHeaders `
        -Body $TemplateBody
    $template = Get-EnvelopeData -Response $templateResponse -Name "离线模式模板 envelope: $Stage"
    $templateId = [long]$template.id
    if ($templateId -le 0) {
        Fail-Step -Name "离线模式模板 ID: $Stage" -Detail "创建模板后未拿到有效 ID"
    }

    $precheckResponse = Invoke-Api `
        -Name "离线模式模板预检查: $Stage" `
        -Method "POST" `
        -Url "$($ApiRoots.Sync)/sync-templates/$templateId/precheck" `
        -Headers $TemplateHeaders
    $precheck = Get-EnvelopeData -Response $precheckResponse -Name "离线模式预检查 envelope: $Stage"
    Assert-OfflinePrecheckRunnable -Precheck $precheck -Stage $Stage -ApprovalExpected:$ApprovalExpected

    $TaskBody.templateId = $templateId
    $taskResponse = Invoke-Api `
        -Name "创建离线模式任务: $Stage" `
        -Method "POST" `
        -Url "$($ApiRoots.Sync)/sync-tasks" `
        -Headers $TaskHeaders `
        -Body $TaskBody
    $task = Get-EnvelopeData -Response $taskResponse -Name "离线模式任务 envelope: $Stage"
    $taskId = [long]$task.id
    if ($taskId -le 0) {
        Fail-Step -Name "离线模式任务 ID: $Stage" -Detail "创建任务后未拿到有效 ID"
    }

    Invoke-Api `
        -Name "提交离线模式任务运行: $Stage" `
        -Method "POST" `
        -Url "$($ApiRoots.Sync)/sync-tasks/$taskId/run" `
        -Headers $RunHeaders | Out-Null

    Invoke-WorkerLoop -Stage "offline-mode-$Stage" -Headers $WorkerHeaders -SyncApiRoot $ApiRoots.Sync | Out-Null
    return [pscustomobject]@{
        TemplateId = $templateId
        TaskId = $taskId
    }
}

function Invoke-OfflineModeClosureE2E {
    param(
        [object]$ApiRoots,
        [hashtable]$UserHeaders,
        [hashtable]$ApprovalHeaders,
        [hashtable]$WorkerHeaders,
        [long]$SourceDatasourceId,
        [long]$TargetDatasourceId
    )

    if (-not $IncludeOfflineModeClosureE2E) {
        return
    }

    <#
        这一组 E2E 专门证明“规划上已经支持的离线模式”真的能从 API 链路落到执行面：
        1. SCHEDULED_BATCH：任务层持有 scheduleConfig，本次 worker loop 只代表一个有界批处理窗口；
        2. CUSTOM_SQL_QUERY：只读 SQL 正文只进入 internal run-once 请求，不在脚本输出、审计摘要或响应正文中展开；
        3. SCHEMA_FULL：data-sync 不直连源库，而是调用 datasource-management metadata discovery，再转 OBJECT_LIST fan-out。

        这里没有额外实现 DATABASE_FULL 的真实表写入，是因为它与 SCHEMA_FULL 使用同一个 discovery fan-out 执行器。
        在本地 E2E 中用两张专用表验证 schema 级发现、对象列表生成和多对象写入，已经覆盖核心执行链路；
        真正全库迁移在客户环境应额外加入 include/exclude、容量评估、目标命名和审批演练。
    #>
    Add-Check -Name "离线模式闭环 E2E" -Status "PASS" -Detail "开始执行 SCHEDULED_BATCH、CUSTOM_SQL_QUERY、SCHEMA_FULL 可选验收"

    $scheduledFieldMappingConfig = @(
        @{ sourceField = "id"; targetField = "id" },
        @{ sourceField = "customer_name"; targetField = "name" },
        @{ sourceField = "amount"; targetField = "amount" },
        @{ sourceField = "region"; targetField = "region" }
    ) | ConvertTo-Json -Depth 10 -Compress
    $scheduledFilterConfig = @{
        logic = "AND"
        conditions = @(
            @{ field = "region"; operator = "="; value = "NORTH" }
        )
    } | ConvertTo-Json -Depth 10 -Compress
    $scheduledTaskScheduleConfig = @{
        type = "CRON"
        cron = "0 0/30 * * * ?"
        timezone = "Asia/Shanghai"
        windowPolicy = "BOUNDED_WINDOW_PER_TRIGGER"
    } | ConvertTo-Json -Depth 10 -Compress
    Invoke-OfflineModeTemplateTaskRun `
        -Stage "scheduled-batch" `
        -ApiRoots $ApiRoots `
        -TemplateHeaders $UserHeaders `
        -TaskHeaders $UserHeaders `
        -RunHeaders $UserHeaders `
        -WorkerHeaders $WorkerHeaders `
        -TemplateBody @{
            tenantId = $TenantId
            projectId = $ProjectId
            workspaceId = $WorkspaceId
            name = "E2E SCHEDULED_BATCH $script:RunId"
            description = "local platform offline mode scheduled batch E2E"
            sourceDatasourceId = $SourceDatasourceId
            targetDatasourceId = $TargetDatasourceId
            sourceSchemaName = $MySqlDatabase
            sourceObjectName = $script:ScheduledBatchSourceTable
            targetSchemaName = $TargetSchema
            targetObjectName = $script:ScheduledBatchTargetTable
            sourceConnectorType = "MYSQL"
            targetConnectorType = "POSTGRESQL"
            syncMode = "SCHEDULED_BATCH"
            syncScopeType = "SINGLE_OBJECT"
            writeStrategy = "UPSERT"
            primaryKeyField = "id"
            fieldMappingConfig = $scheduledFieldMappingConfig
            filterConfig = $scheduledFilterConfig
        } `
        -TaskBody @{
            tenantId = $TenantId
            projectId = $ProjectId
            workspaceId = $WorkspaceId
            name = "E2E scheduled batch task $script:RunId"
            description = "local scheduled batch offline mode E2E task"
            priority = "HIGH"
            runMode = "SCHEDULED"
            scheduleConfig = $scheduledTaskScheduleConfig
            ownerId = $ActorId
        } | Out-Null
    Assert-TargetTableCount -TableName $script:ScheduledBatchTargetTable -Expected 3 -Stage "SCHEDULED_BATCH"

    $customSqlConfig = @{
        sql = "select id, customer_name as name, amount, region from $($script:CustomSqlSourceTable) where region = 'EAST'"
        statementRef = "local-e2e.custom-sql.$script:RunId"
    } | ConvertTo-Json -Depth 10 -Compress
    $customSqlFieldMappingConfig = @(
        @{ sourceField = "id"; targetField = "id" },
        @{ sourceField = "name"; targetField = "name" },
        @{ sourceField = "amount"; targetField = "amount" },
        @{ sourceField = "region"; targetField = "region" }
    ) | ConvertTo-Json -Depth 10 -Compress
    Invoke-OfflineModeTemplateTaskRun `
        -Stage "custom-sql-query" `
        -ApiRoots $ApiRoots `
        -TemplateHeaders $UserHeaders `
        -TaskHeaders $ApprovalHeaders `
        -RunHeaders $ApprovalHeaders `
        -WorkerHeaders $WorkerHeaders `
        -ApprovalExpected `
        -TemplateBody @{
            tenantId = $TenantId
            projectId = $ProjectId
            workspaceId = $WorkspaceId
            name = "E2E CUSTOM_SQL_QUERY $script:RunId"
            description = "local platform offline mode custom SQL E2E"
            sourceDatasourceId = $SourceDatasourceId
            targetDatasourceId = $TargetDatasourceId
            sourceSchemaName = $MySqlDatabase
            targetSchemaName = $TargetSchema
            targetObjectName = $script:CustomSqlTargetTable
            sourceConnectorType = "MYSQL"
            targetConnectorType = "POSTGRESQL"
            syncMode = "CUSTOM_SQL_QUERY"
            syncScopeType = "CUSTOM_SQL_QUERY"
            writeStrategy = "UPSERT"
            primaryKeyField = "id"
            fieldMappingConfig = $customSqlFieldMappingConfig
            customSqlConfig = $customSqlConfig
        } `
        -TaskBody @{
            tenantId = $TenantId
            projectId = $ProjectId
            workspaceId = $WorkspaceId
            name = "E2E custom SQL task $script:RunId"
            description = "local custom SQL offline mode E2E task"
            priority = "HIGH"
            runMode = "MANUAL"
            ownerId = $ActorId
            approvalConfirmed = $true
            approvalFactId = "approval:local-e2e-$script:RunId-custom-sql"
        } | Out-Null
    Assert-TargetTableCount -TableName $script:CustomSqlTargetTable -Expected 2 -Stage "CUSTOM_SQL_QUERY"

    $schemaDiscoveryConfig = @{
        discoveryPolicy = @{
            catalog = $MySqlDatabase
            includePatterns = @(
                $script:SchemaFullSourceOrdersTable,
                $script:SchemaFullSourceCustomersTable
            )
            maxObjects = 5
            includeViews = $false
        }
    } | ConvertTo-Json -Depth 10 -Compress
    Invoke-OfflineModeTemplateTaskRun `
        -Stage "schema-full" `
        -ApiRoots $ApiRoots `
        -TemplateHeaders $UserHeaders `
        -TaskHeaders $ApprovalHeaders `
        -RunHeaders $ApprovalHeaders `
        -WorkerHeaders $WorkerHeaders `
        -ApprovalExpected `
        -TemplateBody @{
            tenantId = $TenantId
            projectId = $ProjectId
            workspaceId = $WorkspaceId
            name = "E2E SCHEMA_FULL $script:RunId"
            description = "local platform offline mode schema full E2E"
            sourceDatasourceId = $SourceDatasourceId
            targetDatasourceId = $TargetDatasourceId
            sourceSchemaName = $MySqlDatabase
            targetSchemaName = $TargetSchema
            sourceConnectorType = "MYSQL"
            targetConnectorType = "POSTGRESQL"
            syncMode = "FULL"
            syncScopeType = "SCHEMA_FULL"
            writeStrategy = "UPSERT"
            primaryKeyField = "id"
            objectMappingConfig = $schemaDiscoveryConfig
        } `
        -TaskBody @{
            tenantId = $TenantId
            projectId = $ProjectId
            workspaceId = $WorkspaceId
            name = "E2E schema full task $script:RunId"
            description = "local schema full offline mode E2E task"
            priority = "HIGH"
            runMode = "MANUAL"
            ownerId = $ActorId
            approvalConfirmed = $true
            approvalFactId = "approval:local-e2e-$script:RunId-schema-full"
        } | Out-Null
    Assert-TargetTableCount -TableName $script:SchemaFullTargetOrdersTable -Expected 2 -Stage "SCHEMA_FULL orders"
    Assert-TargetTableCount -TableName $script:SchemaFullTargetCustomersTable -Expected 2 -Stage "SCHEMA_FULL customers"
}

function Main {
    Assert-SafeIdentifier -Name "SourceTable" -Value $SourceTable
    Assert-SafeIdentifier -Name "TargetSchema" -Value $TargetSchema
    Assert-SafeIdentifier -Name "TargetTable" -Value $TargetTable

    Write-ExecutionPlan
    if ($PlanOnly) {
        Add-Check -Name "Plan only" -Status "PASS" -Detail "未启动容器、未写数据库、未调用 API"
        return
    }

    Invoke-DependencyStart
    Wait-TcpPort -Name "MySQL" -HostName $MySqlHost -Port $MySqlPort | Out-Null
    Wait-TcpPort -Name "PostgreSQL" -HostName $PostgresHost -Port $PostgresPort | Out-Null
    if (-not $UseDirectServiceUrls) {
        Wait-TcpPort -Name "Keycloak" -HostName "127.0.0.1" -Port 18080 | Out-Null
    }
    Initialize-E2EDatabase
    Initialize-OfflineModeE2EDatabase

    $apiRoots = Resolve-ApiRoots
    if ($UseDirectServiceUrls) {
        Invoke-HealthProbe -Name "datasource-management health" -Url "$DatasourceManagementBaseUrl/actuator/health"
        Invoke-HealthProbe -Name "data-sync health" -Url "$DataSyncBaseUrl/actuator/health"
    } else {
        Invoke-HealthProbe -Name "gateway health" -Url "$GatewayBaseUrl/actuator/health"
    }

    $userToken = $UserAccessToken
    $serviceToken = $ServiceAccessToken
    if (-not $UseDirectServiceUrls) {
        if ([string]::IsNullOrWhiteSpace($userToken)) {
            $userToken = Get-AccessToken -Username $UserAccountUsername -Password $UserAccountPassword -Purpose "user-flow"
        }
        if ([string]::IsNullOrWhiteSpace($serviceToken)) {
            $serviceToken = Get-AccessToken -Username $ServiceAccountUsername -Password $ServiceAccountPassword -Purpose "worker-flow"
        }
    }

    $userHeaders = New-ApiHeaders -TraceId (New-TraceId "user") -Token $userToken
    $workerHeaders = New-ApiHeaders `
        -TraceId (New-TraceId "worker") `
        -Token $serviceToken `
        -Role "SERVICE_ACCOUNT" `
        -ActorType "SERVICE_ACCOUNT" `
        -CurrentActorId 9101

    $sourceJdbc = Resolve-SourceJdbcUrl
    $targetJdbc = Resolve-TargetJdbcUrl
    Add-Check -Name "JDBC URL 解析" -Status "PASS" -Detail "已解析源端和目标端 JDBC URL，完整值不打印"

    $sourceDatasourceResponse = Invoke-Api `
        -Name "创建 MySQL 源数据源" `
        -Method "POST" `
        -Url "$($apiRoots.Datasource)/datasources" `
        -Headers $userHeaders `
        -Body @{
            tenantId = $TenantId
            projectId = $ProjectId
            workspaceId = $WorkspaceId
            name = "E2E MySQL source $script:RunId"
            type = "MYSQL"
            jdbcUrl = $sourceJdbc
            username = $MySqlUser
            password = $MySqlPassword
            description = "local platform data-sync E2E source"
        }
    $sourceDatasource = Get-EnvelopeData -Response $sourceDatasourceResponse -Name "源数据源 envelope"

    $targetDatasourceResponse = Invoke-Api `
        -Name "创建 PostgreSQL 目标数据源" `
        -Method "POST" `
        -Url "$($apiRoots.Datasource)/datasources" `
        -Headers $userHeaders `
        -Body @{
            tenantId = $TenantId
            projectId = $ProjectId
            workspaceId = $WorkspaceId
            name = "E2E PostgreSQL target $script:RunId"
            type = "POSTGRESQL"
            jdbcUrl = $targetJdbc
            username = $PostgresUser
            password = $PostgresPassword
            description = "local platform data-sync E2E target"
        }
    $targetDatasource = Get-EnvelopeData -Response $targetDatasourceResponse -Name "目标数据源 envelope"

    $sourceDatasourceId = [long]$sourceDatasource.id
    $targetDatasourceId = [long]$targetDatasource.id
    if ($sourceDatasourceId -le 0 -or $targetDatasourceId -le 0) {
        Fail-Step -Name "数据源 ID" -Detail "创建数据源后未拿到有效 ID"
    }
    Add-Check -Name "数据源 ID" -Status "PASS" -Detail "已获得源端/目标端 datasourceId，具体 ID 仅用于本轮内部调用"

    $sourceConnectionTestResponse = Invoke-Api `
        -Name "源数据源连接测试" `
        -Method "POST" `
        -Url "$($apiRoots.Datasource)/datasources/$sourceDatasourceId/test" `
        -Headers $userHeaders
    Assert-DatasourceConnectionTestSuccess `
        -ConnectionTestData (Get-EnvelopeData -Response $sourceConnectionTestResponse -Name "源数据源连接测试 envelope") `
        -Name "源数据源连接测试结果"
    $targetConnectionTestResponse = Invoke-Api `
        -Name "目标数据源连接测试" `
        -Method "POST" `
        -Url "$($apiRoots.Datasource)/datasources/$targetDatasourceId/test" `
        -Headers $userHeaders
    Assert-DatasourceConnectionTestSuccess `
        -ConnectionTestData (Get-EnvelopeData -Response $targetConnectionTestResponse -Name "目标数据源连接测试 envelope") `
        -Name "目标数据源连接测试结果"

    $fieldMappingConfig = @(
        @{ sourceField = "id"; targetField = "id" },
        @{ sourceField = "customer_name"; targetField = "name" },
        @{ sourceField = "amount"; targetField = "amount" },
        @{ sourceField = "region"; targetField = "region" }
    ) | ConvertTo-Json -Depth 10 -Compress
    <#
        filterConfig 不能直接保存 where SQL 字符串，而要保存结构化条件合同：
        1. Java 侧 SyncFilterExecutionContractSupport 会把条件解析成安全字段名、受控操作符和参数值；
        2. datasource-management 真正执行读取时再通过 PreparedStatement 绑定参数，避免 SQL 注入和审计不可解释；
        3. 这里故意使用 { logic, conditions } 包装，而不是单元素数组管道写法。Windows PowerShell 会把
           单元素数组通过管道展开成对象，导致 JSON 从 `[{"field":...}]` 退化为 `{"field":...}`，
           从而触发 FILTER_CONFIG_SCHEMA_UNSUPPORTED。
    #>
    $filterConfig = @{
        logic = "AND"
        conditions = @(
            @{ field = "region"; operator = "="; value = "EAST" }
        )
    } | ConvertTo-Json -Depth 10 -Compress
    $partitionConfig = @{
        strategy = "AUTO_SPLIT_PK"
        splitPk = "id"
        shardCount = 4
        channel = 2
        taskGroupSize = 2
        maxShardAttempts = 1
        maxDirtyRecordCount = 10
        maxDirtyRecordRatio = 0.20
    } | ConvertTo-Json -Depth 10 -Compress

    $templateResponse = Invoke-Api `
        -Name "创建 data-sync 同步模板" `
        -Method "POST" `
        -Url "$($apiRoots.Sync)/sync-templates" `
        -Headers $userHeaders `
        -Body @{
            tenantId = $TenantId
            projectId = $ProjectId
            workspaceId = $WorkspaceId
            name = "E2E MySQL to PostgreSQL AUTO_SPLIT_PK $script:RunId"
            description = "local platform API E2E"
            sourceDatasourceId = $sourceDatasourceId
            targetDatasourceId = $targetDatasourceId
            sourceSchemaName = $MySqlDatabase
            sourceObjectName = $SourceTable
            targetSchemaName = $TargetSchema
            targetObjectName = $TargetTable
            sourceConnectorType = "MYSQL"
            targetConnectorType = "POSTGRESQL"
            syncMode = "FULL"
            syncScopeType = "SINGLE_OBJECT"
            writeStrategy = "UPSERT"
            primaryKeyField = "id"
            fieldMappingConfig = $fieldMappingConfig
            filterConfig = $filterConfig
            partitionConfig = $partitionConfig
        }
    $template = Get-EnvelopeData -Response $templateResponse -Name "同步模板 envelope"
    $templateId = [long]$template.id
    if ($templateId -le 0) {
        Fail-Step -Name "同步模板 ID" -Detail "创建模板后未拿到有效 ID"
    }

    $precheckResponse = Invoke-Api `
        -Name "模板执行前预检查" `
        -Method "POST" `
        -Url "$($apiRoots.Sync)/sync-templates/$templateId/precheck" `
        -Headers $userHeaders
    $precheck = Get-EnvelopeData -Response $precheckResponse -Name "预检查 envelope"
    if (-not [bool]$precheck.canStartExecution) {
        Fail-Step -Name "模板执行前预检查" -Detail "precheck 表示当前模板不可启动，请查看 data-sync 服务日志中的 traceId"
    }
    Add-Check -Name "模板执行前预检查结果" -Status "PASS" -Detail "当前模板可进入 worker 执行链路"

    $taskResponse = Invoke-Api `
        -Name "创建 data-sync 同步任务" `
        -Method "POST" `
        -Url "$($apiRoots.Sync)/sync-tasks" `
        -Headers $userHeaders `
        -Body @{
            tenantId = $TenantId
            projectId = $ProjectId
            workspaceId = $WorkspaceId
            templateId = $templateId
            name = "E2E platform API task $script:RunId"
            description = "local platform API E2E task"
            priority = "HIGH"
            runMode = "MANUAL"
            ownerId = $ActorId
        }
    $task = Get-EnvelopeData -Response $taskResponse -Name "同步任务 envelope"
    $taskId = [long]$task.id
    if ($taskId -le 0) {
        Fail-Step -Name "同步任务 ID" -Detail "创建任务后未拿到有效 ID"
    }

    Invoke-Api `
        -Name "提交同步任务运行" `
        -Method "POST" `
        -Url "$($apiRoots.Sync)/sync-tasks/$taskId/run" `
        -Headers $userHeaders | Out-Null

    Invoke-WorkerLoop -Stage "first-run-with-dirty-row-and-failed-shard" -Headers $workerHeaders -SyncApiRoot $apiRoots.Sync | Out-Null

    $executionsResponse = Invoke-Api `
        -Name "查询任务 execution 列表" `
        -Method "GET" `
        -Url "$($apiRoots.Sync)/sync-tasks/$taskId/executions?current=1&size=10" `
        -Headers $userHeaders
    $executionsPage = Get-EnvelopeData -Response $executionsResponse -Name "execution 列表 envelope"
    $executions = Get-PageRecords -PageData $executionsPage
    if ($executions.Count -eq 0) {
        Fail-Step -Name "查询任务 execution 列表" -Detail "任务运行后未查询到 execution"
    }
    $firstExecution = $executions | Sort-Object -Property id | Select-Object -First 1
    $firstExecutionId = [long]$firstExecution.id
    Add-Check -Name "首轮 execution" -Status "PASS" -Detail "已定位首轮 execution，用于后续对象账本和 dirty sample 查询"

    $objectsResponse = Invoke-Api `
        -Name "查询分片账本" `
        -Method "GET" `
        -Url "$($apiRoots.Sync)/sync-tasks/$taskId/executions/$firstExecutionId/objects?current=1&size=50" `
        -Headers $userHeaders
    $objectsPage = Get-EnvelopeData -Response $objectsResponse -Name "分片账本 envelope"
    $objects = Get-PageRecords -PageData $objectsPage
    $failedObjects = @($objects | Where-Object { $_.objectState -eq "FAILED" })
    if ($failedObjects.Count -eq 0) {
        Fail-Step -Name "失败分片账本" -Detail "未发现 FAILED 分片；预期 id=11..15 所在分片因 dirty ratio 超阈值失败"
    }
    $failedOrdinal = [int]($failedObjects | Select-Object -First 1).objectOrdinal
    Add-Check -Name "失败分片账本" -Status "PASS" -Detail "已发现 FAILED 分片，后续只重试该分片"

    Repair-FailedShardSourceRows

    Invoke-Api `
        -Name "选择性重试失败分片" `
        -Method "POST" `
        -Url "$($apiRoots.Sync)/sync-tasks/$taskId/executions/$firstExecutionId/objects/retry" `
        -Headers $userHeaders `
        -Body @{
            objectOrdinals = @($failedOrdinal)
            retryAttemptBudget = 3
            resetAttemptCount = $true
            reason = "local platform E2E repaired failed shard source rows"
        } | Out-Null

    Invoke-WorkerLoop -Stage "retry-failed-shard-only" -Headers $workerHeaders -SyncApiRoot $apiRoots.Sync | Out-Null
    Assert-TargetCount -Expected 19 -Stage "failed shard retry completed and dirty row still pending replay"

    $errorsResponse = Invoke-Api `
        -Name "查询 retryable 脏数据样本" `
        -Method "GET" `
        -Url "$($apiRoots.Sync)/sync-tasks/$taskId/errors?executionId=$firstExecutionId&retryable=true&current=1&size=50" `
        -Headers $userHeaders
    $errorsPage = Get-EnvelopeData -Response $errorsResponse -Name "脏数据样本 envelope"
    $errorSamples = Get-PageRecords -PageData $errorsPage
    $dirtySample = $errorSamples |
        Where-Object {
            $sourceRecordKey = $_.sourceRecordKey -as [string]
            $matchesPrimaryKeyValue = -not [string]::IsNullOrWhiteSpace($sourceRecordKey) -and
                $sourceRecordKey.IndexOf("`"value`":7", [System.StringComparison]::Ordinal) -ge 0
            $matchesPrimaryKeyValue -or $_.errorType -eq "NOT_NULL_VIOLATION"
        } |
        Select-Object -First 1
    if ($null -eq $dirtySample) {
        Fail-Step -Name "定位 id=7 脏数据样本" -Detail "未找到可 replay 的 id=7 脏数据样本"
    }
    Add-Check -Name "定位 id=7 脏数据样本" -Status "PASS" -Detail "已找到可按 PRIMARY_KEY_EQ replay 的低敏样本"

    Repair-DirtySourceRow

    $replayResponse = Invoke-Api `
        -Name "发起 dirty record replay" `
        -Method "POST" `
        -Url "$($apiRoots.Sync)/sync-tasks/$taskId/errors/replay" `
        -Headers $userHeaders `
        -Body @{
            executionId = $firstExecutionId
            errorSampleIds = @([long]$dirtySample.id)
            repairConfirmed = $true
            repairStrategy = "MANUAL_FIXED_AND_REPLAY"
            reason = "local platform E2E repaired source row and replays by PRIMARY_KEY_EQ"
        }
    $replay = Get-EnvelopeData -Response $replayResponse -Name "dirty replay envelope"
    if ([long]$replay.replayExecutionId -le 0) {
        Fail-Step -Name "dirty replay execution" -Detail "dirty replay 未创建有效 replay execution"
    }

    Invoke-WorkerLoop -Stage "dirty-record-primary-key-replay" -Headers $workerHeaders -SyncApiRoot $apiRoots.Sync | Out-Null
    Assert-TargetCount -Expected 20 -Stage "dirty replay completed"
    Assert-ReplayedRow
    Invoke-OfflineModeClosureE2E `
        -ApiRoots $apiRoots `
        -UserHeaders $userHeaders `
        -ApprovalHeaders $workerHeaders `
        -WorkerHeaders $workerHeaders `
        -SourceDatasourceId $sourceDatasourceId `
        -TargetDatasourceId $targetDatasourceId
    Invoke-OptionalReceiptProbe -Headers $workerHeaders
}

try {
    Main
} catch {
    if ($Strict) {
        exit 1
    }
    Write-Host ""
    Write-Host "Platform API E2E stopped before completion." -ForegroundColor Red
    Write-Host "Reason was intentionally kept low-sensitive; inspect service logs with the traceId values printed by your services." -ForegroundColor Yellow
    exit 1
}

Write-Host ""
Write-Host "Platform API E2E summary" -ForegroundColor Cyan
$script:Checks | Format-Table -AutoSize

if ($script:FailureCount -gt 0) {
    Write-Host "Platform API E2E finished with $script:FailureCount failure(s)." -ForegroundColor Red
    exit 1
}

Write-Host "Platform API E2E finished without hard failures." -ForegroundColor Green
exit 0

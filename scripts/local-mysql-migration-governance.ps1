<#
    DataSmart Govern 本地 MySQL 迁移治理脚本。

    设计意图：
    1. 当前仓库已经积累了较多 `docker/mysql/migrations/*.sql` 增量脚本，如果仍依赖人工记忆
       “哪些脚本跑过、哪些脚本没跑过”，本地闭环联调会很容易出现 schema 漂移。
    2. 本脚本不是替代 Flyway/Liquibase 的最终生产方案，而是一个过渡型治理工具：它用独立
       `datasmart_schema_migration_history` 表记录文件名、SHA-256 校验和、执行时间和执行模式，
       让本地开发者可以安全地查看计划、执行未应用脚本、或为已经手工执行过的数据库补登记。
    3. 默认模式不会执行 SQL，只读取迁移文件和数据库历史；只有显式传入 `-Apply` 才会把未执行
       的脚本写入本地 MySQL。这样可以避免误把 smoke/诊断动作变成数据库变更动作。

    常用命令：
    - 只检查迁移文件，不连接数据库：
      .\scripts\local-mysql-migration-governance.ps1 -StaticOnly
    - 查看本地 MySQL 里哪些迁移尚未执行：
      .\scripts\local-mysql-migration-governance.ps1
    - Docker 不在 PATH 但本机安装了 mysql.exe 时，改用本地 CLI 连接：
      .\scripts\local-mysql-migration-governance.ps1 -ConnectionMode LocalCli
    - 执行尚未登记的迁移：
      .\scripts\local-mysql-migration-governance.ps1 -Apply
    - 已经手工执行过迁移时，仅补齐历史登记：
      .\scripts\local-mysql-migration-governance.ps1 -BaselineExisting

    安全边界：
    - 脚本不会打印 MySQL 密码、连接串、业务数据、SQL 正文或查询结果正文。
    - `-Apply` 只按文件名顺序执行仓库内的 migration 文件，不会自动删除表、清空数据或重建库。
    - `-BaselineExisting` 不执行 migration SQL，只登记“当前库已经由人工确认具备这些结构”；
      它适合旧本地库补账，不适合作为跳过真实迁移的生产手段。
#>
param(
    [switch]$StaticOnly,
    [switch]$Apply,
    [switch]$BaselineExisting,
    [switch]$Strict,
    [ValidateSet("Auto", "Docker", "LocalCli")]
    [string]$ConnectionMode = "Auto",
    [string]$ContainerName = "datasmart-mysql",
    [string]$MySqlHost = "127.0.0.1",
    [int]$MySqlPort = 3306,
    [string]$DatabaseName = "datasmart_govern",
    [string]$MySqlUser = "",
    [string]$MySqlPassword = "",
    [string]$MigrationsPath = ""
)

$ErrorActionPreference = "Stop"
$script:Checks = New-Object System.Collections.Generic.List[object]
$script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$script:HistoryTableName = "datasmart_schema_migration_history"
$script:EffectiveConnectionMode = $null

if ([string]::IsNullOrWhiteSpace($MySqlUser)) {
    $MySqlUser = if ([string]::IsNullOrWhiteSpace($env:DATASMART_MYSQL_USER)) { "root" } else { $env:DATASMART_MYSQL_USER }
}
if ([string]::IsNullOrWhiteSpace($MySqlPassword)) {
    $MySqlPassword = if ([string]::IsNullOrWhiteSpace($env:DATASMART_MYSQL_PASSWORD)) { "password" } else { $env:DATASMART_MYSQL_PASSWORD }
}
if ([string]::IsNullOrWhiteSpace($MigrationsPath)) {
    $MigrationsPath = Join-Path $script:RepoRoot "docker\mysql\migrations"
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

function Test-CommandExists {
    param([string]$CommandName)
    return $null -ne (Get-Command $CommandName -ErrorAction SilentlyContinue)
}

function ConvertTo-SqlLiteral {
    param([string]$Value)

    if ($null -eq $Value) {
        return "NULL"
    }
    return "'" + ($Value -replace "'", "''") + "'"
}

function Get-MigrationRecords {
    <#
        读取并校验迁移文件清单。

        业务含义：
        - migrationId 使用不带 `.sql` 后缀的完整文件名，而不是只用日期。原因是同一天可能有多个
          模块迁移，例如 data-sync、permission-admin、agent-runtime 都可能在 20260628 增量变更。
        - SHA-256 用于发现“文件已经登记但内容被改过”的漂移风险。迁移脚本一旦被执行，原则上
          应该追加新 migration，而不是修改旧 migration，否则不同开发者数据库会出现不可解释差异。
    #>
    if (-not (Test-Path -LiteralPath $MigrationsPath)) {
        Add-Check -Name "迁移目录" -Status "FAIL" -Detail "目录不存在：$MigrationsPath"
        return @()
    }

    $files = Get-ChildItem -LiteralPath $MigrationsPath -Filter "*.sql" | Sort-Object Name
    if ($files.Count -eq 0) {
        Add-Check -Name "迁移文件清单" -Status "FAIL" -Detail "未找到任何 SQL 迁移文件"
        return @()
    }

    $records = New-Object System.Collections.Generic.List[object]
    $seenIds = @{}
    foreach ($file in $files) {
        $migrationId = [System.IO.Path]::GetFileNameWithoutExtension($file.Name)
        $checksum = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash.ToLowerInvariant()
        $length = $file.Length
        $nameMatches = $file.Name -match "^\d{8}_[a-z0-9_]+\.sql$"

        if (-not $nameMatches) {
            Add-Check -Name "迁移命名: $($file.Name)" -Status "FAIL" -Detail "命名应为 yyyyMMdd_lower_snake_case.sql，便于排序、审计和故障定位"
        } elseif ($seenIds.ContainsKey($migrationId)) {
            Add-Check -Name "迁移重复: $($file.Name)" -Status "FAIL" -Detail "migrationId 重复会导致历史表主键冲突"
        } elseif ($length -le 0) {
            Add-Check -Name "迁移内容: $($file.Name)" -Status "FAIL" -Detail "SQL 文件为空，无法作为可审计迁移"
        } else {
            Add-Check -Name "迁移文件: $($file.Name)" -Status "PASS" -Detail "checksum=$($checksum.Substring(0, 12))..., size=$length bytes"
        }

        $seenIds[$migrationId] = $true
        $records.Add([pscustomobject]@{
            MigrationId = $migrationId
            FileName = $file.Name
            FullName = $file.FullName
            Checksum = $checksum
            Length = $length
        }) | Out-Null
    }
    return $records
}

function Resolve-MySqlConnectionMode {
    <#
        选择本轮迁移治理要使用的 MySQL 连接方式。

        设计说明：
        - Docker 模式适合仓库默认 Compose 环境，直接进入 `datasmart-mysql` 容器执行 mysql client；
        - LocalCli 模式适合 Docker Desktop 未安装、Docker 未加入 PATH、或开发者已经有本地 MySQL
          实例的机器，通过本机 `mysql.exe` 连接 `127.0.0.1:3306`；
        - Auto 模式优先选择正在运行的 Docker 容器；如果 Docker 不存在或容器没运行，再退到本地
          mysql CLI。这样不会强迫所有开发者拥有同一种基础设施入口。
    #>
    if ($ConnectionMode -eq "Docker") {
        return "Docker"
    }
    if ($ConnectionMode -eq "LocalCli") {
        return "LocalCli"
    }
    if ((Test-CommandExists -CommandName "docker")) {
        try {
            $runningContainers = & docker ps --format "{{.Names}}"
            if ($runningContainers -contains $ContainerName) {
                return "Docker"
            }
        } catch {
            # Auto 模式下 Docker CLI 异常时继续尝试 LocalCli，避免因为 Docker Desktop 未启动阻塞静态以外的本地治理。
        }
    }
    if (Test-CommandExists -CommandName "mysql") {
        return "LocalCli"
    }
    return $null
}

function Invoke-MySql {
    param(
        [string]$Sql,
        [switch]$SkipColumnNames
    )

    if ([string]::IsNullOrWhiteSpace($script:EffectiveConnectionMode)) {
        throw "MySQL connection mode is not resolved"
    }

    if ($script:EffectiveConnectionMode -eq "Docker") {
        $dockerArgs = @("exec", "-i", "-e", "MYSQL_PWD=$MySqlPassword", $ContainerName, "mysql", "-u$MySqlUser", "--batch", "--raw")
        if ($SkipColumnNames) {
            $dockerArgs += "--skip-column-names"
        }
        $dockerArgs += $DatabaseName
        $output = $Sql | docker @dockerArgs 2>&1
    } else {
        $mysqlArgs = @("-h$MySqlHost", "-P$MySqlPort", "-u$MySqlUser", "--batch", "--raw")
        if ($SkipColumnNames) {
            $mysqlArgs += "--skip-column-names"
        }
        $mysqlArgs += $DatabaseName
        $previousMysqlPwd = $env:MYSQL_PWD
        try {
            $env:MYSQL_PWD = $MySqlPassword
            $output = $Sql | mysql @mysqlArgs 2>&1
        } finally {
            $env:MYSQL_PWD = $previousMysqlPwd
        }
    }
    if ($LASTEXITCODE -ne 0) {
        throw "mysql command failed: $($output -join ' ')"
    }
    return $output
}

function Test-MySqlConnectionReady {
    $script:EffectiveConnectionMode = Resolve-MySqlConnectionMode
    if ([string]::IsNullOrWhiteSpace($script:EffectiveConnectionMode)) {
        Add-Check -Name "MySQL 客户端" -Status "FAIL" -Detail "未发现可用 Docker 容器或 mysql CLI，无法读取本地 MySQL 迁移历史"
        return $false
    }

    if ($script:EffectiveConnectionMode -eq "Docker") {
        Add-Check -Name "MySQL 连接模式" -Status "PASS" -Detail "使用 Docker 容器 $ContainerName"
    } else {
        Add-Check -Name "MySQL 连接模式" -Status "PASS" -Detail "使用本机 mysql CLI 连接 ${MySqlHost}:${MySqlPort}/$DatabaseName"
    }

    try {
        Invoke-MySql -Sql "SELECT 1;" -SkipColumnNames | Out-Null
        Add-Check -Name "MySQL 连接" -Status "PASS" -Detail "已连接 $DatabaseName；未打印密码或查询正文"
        return $true
    } catch {
        Add-Check -Name "MySQL 连接" -Status "FAIL" -Detail "无法连接本地 MySQL；请检查连接模式、容器或本地服务、数据库名、DATASMART_MYSQL_USER 和 DATASMART_MYSQL_PASSWORD"
        return $false
    }
}

function Ensure-HistoryTable {
    <#
        创建本地迁移历史表。

        为什么不把这张表放进某个业务模块：
        - 它属于本地闭环联调和 schema 治理基础设施，不归 task-management、data-sync 或 agent-runtime
          任一业务域独占；
        - 后续正式引入 Flyway/Liquibase 时，可以把这张表迁移为标准工具的 history 表或保留为过渡审计表。
    #>
    $sql = 'CREATE TABLE IF NOT EXISTS ' + $script:HistoryTableName + ' (migration_id VARCHAR(200) NOT NULL, file_name VARCHAR(255) NOT NULL, checksum_sha256 CHAR(64) NOT NULL, applied_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, applied_by VARCHAR(128) NOT NULL, execution_mode VARCHAR(32) NOT NULL, execution_millis BIGINT NOT NULL DEFAULT 0, note VARCHAR(512) NULL, PRIMARY KEY (migration_id)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT=''DataSmart local migration governance history'';'
    Invoke-MySql -Sql $sql | Out-Null
    Add-Check -Name "迁移历史表" -Status "PASS" -Detail "$script:HistoryTableName 已存在或已创建"
}

function Get-AppliedMigrationMap {
    $historyTableLiteral = ConvertTo-SqlLiteral $script:HistoryTableName
    $existsSql = 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ' + $historyTableLiteral + ';'
    $tableExists = (Invoke-MySql -Sql $existsSql -SkipColumnNames | Select-Object -First 1)
    if ([string]$tableExists -ne "1") {
        Add-Check -Name "迁移历史表" -Status "WARN" -Detail "$script:HistoryTableName 尚不存在；默认视为未登记任何迁移"
        return @{}
    }

    $historyQuerySql = 'SELECT migration_id, checksum_sha256, execution_mode FROM ' + $script:HistoryTableName + ' ORDER BY migration_id;'
    $rows = Invoke-MySql -Sql $historyQuerySql -SkipColumnNames
    $map = @{}
    foreach ($row in $rows) {
        if ([string]::IsNullOrWhiteSpace($row)) {
            continue
        }
        $parts = [string]$row -split "`t"
        if ($parts.Count -ge 3) {
            $map[$parts[0]] = [pscustomobject]@{
                Checksum = $parts[1]
                ExecutionMode = $parts[2]
            }
        }
    }
    Add-Check -Name "迁移历史读取" -Status "PASS" -Detail "已读取 $($map.Count) 条迁移历史"
    return $map
}

function Show-MigrationPlan {
    param(
        [object[]]$MigrationRecords,
        [hashtable]$AppliedMap
    )

    $pending = 0
    foreach ($record in $MigrationRecords) {
        if ($AppliedMap.ContainsKey($record.MigrationId)) {
            $history = $AppliedMap[$record.MigrationId]
            if ([string]$history.Checksum -eq [string]$record.Checksum) {
                Add-Check -Name "迁移计划: $($record.FileName)" -Status "PASS" -Detail "已登记，mode=$($history.ExecutionMode)"
            } else {
                Add-Check -Name "迁移计划: $($record.FileName)" -Status "FAIL" -Detail "历史 checksum 与当前文件不一致；请追加新迁移，不要修改已执行脚本"
            }
        } else {
            $pending += 1
            Add-Check -Name "迁移计划: $($record.FileName)" -Status "WARN" -Detail "尚未登记；默认不会执行，传入 -Apply 才会运行"
        }
    }
    return $pending
}

function Register-MigrationHistory {
    param(
        [object]$Record,
        [string]$ExecutionMode,
        [long]$ExecutionMillis,
        [string]$Note
    )

    $sql = 'INSERT INTO ' + $script:HistoryTableName + ' (migration_id, file_name, checksum_sha256, applied_by, execution_mode, execution_millis, note) VALUES (' + (ConvertTo-SqlLiteral $Record.MigrationId) + ', ' + (ConvertTo-SqlLiteral $Record.FileName) + ', ' + (ConvertTo-SqlLiteral $Record.Checksum) + ', ''local-mysql-migration-governance.ps1'', ' + (ConvertTo-SqlLiteral $ExecutionMode) + ', ' + $ExecutionMillis + ', ' + (ConvertTo-SqlLiteral $Note) + ');'
    Invoke-MySql -Sql $sql | Out-Null
}

function Invoke-MigrationFile {
    param([object]$Record)

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $migrationSql = Get-Content -Encoding UTF8 -LiteralPath $Record.FullName -Raw
        Invoke-MySql -Sql $migrationSql | Out-Null
        $stopwatch.Stop()
        Register-MigrationHistory -Record $Record -ExecutionMode "APPLIED" -ExecutionMillis $stopwatch.ElapsedMilliseconds -Note "本地脚本按文件顺序执行完成"
        Add-Check -Name "执行迁移: $($Record.FileName)" -Status "PASS" -Detail "已执行并登记，elapsedMs=$($stopwatch.ElapsedMilliseconds)"
    } catch {
        $stopwatch.Stop()
        Add-Check -Name "执行迁移: $($Record.FileName)" -Status "FAIL" -Detail "执行失败；未输出 SQL 正文，请查看 MySQL 日志或单独检查该 migration"
        throw
    }
}

Set-Location $script:RepoRoot
Write-Host "DataSmart Govern 本地 MySQL 迁移治理" -ForegroundColor Cyan
Write-Host "仓库根目录：$script:RepoRoot" -ForegroundColor Cyan
Write-Host "迁移目录：$MigrationsPath" -ForegroundColor Cyan

if ($Apply -and $BaselineExisting) {
    Add-Check -Name "执行模式" -Status "FAIL" -Detail "-Apply 与 -BaselineExisting 不能同时使用，避免既执行又补登记导致语义混乱"
} else {
    $migrationRecords = @(Get-MigrationRecords)

    if (-not $StaticOnly -and $migrationRecords.Count -gt 0) {
        $ready = Test-MySqlConnectionReady
        if ($ready) {
            if ($Apply -or $BaselineExisting) {
                Ensure-HistoryTable
            }

            $appliedMap = Get-AppliedMigrationMap
            $pending = Show-MigrationPlan -MigrationRecords $migrationRecords -AppliedMap $appliedMap

            if ($BaselineExisting) {
                Ensure-HistoryTable
                foreach ($record in $migrationRecords) {
                    if (-not $appliedMap.ContainsKey($record.MigrationId)) {
                        Register-MigrationHistory -Record $record -ExecutionMode "BASELINED" -ExecutionMillis 0 -Note "人工确认已有库结构后补登记；未执行 SQL"
                        Add-Check -Name "补登记迁移: $($record.FileName)" -Status "PASS" -Detail "已登记为 BASELINED，未执行 SQL"
                    }
                }
            } elseif ($Apply) {
                Ensure-HistoryTable
                foreach ($record in $migrationRecords) {
                    if (-not $appliedMap.ContainsKey($record.MigrationId)) {
                        Invoke-MigrationFile -Record $record
                    }
                }
            } elseif ($pending -eq 0) {
                Add-Check -Name "迁移计划汇总" -Status "PASS" -Detail "所有迁移均已登记"
            } else {
                Add-Check -Name "迁移计划汇总" -Status "WARN" -Detail "存在 $pending 个未登记迁移；如确认需要执行，请传入 -Apply"
            }
        }
    } elseif ($StaticOnly) {
        Add-Check -Name "静态模式" -Status "PASS" -Detail "已跳过 Docker/MySQL，只校验迁移文件清单"
    }
}

$passed = @($script:Checks | Where-Object { $_.Status -eq "PASS" }).Count
$warned = @($script:Checks | Where-Object { $_.Status -eq "WARN" }).Count
$failed = @($script:Checks | Where-Object { $_.Status -eq "FAIL" }).Count

Write-Host ""
Write-Host ("Summary: PASS={0}, WARN={1}, FAIL={2}" -f $passed, $warned, $failed) -ForegroundColor Cyan

if ($failed -gt 0) {
    Write-Host "Migration governance failed. Fix naming, checksum drift, MySQL connectivity, or the failing SQL file before closure smoke." -ForegroundColor Yellow
}

if ($Strict -and $failed -gt 0) {
    exit 1
}
exit 0

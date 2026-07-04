<#
DataSmart Govern 备份恢复就绪检查脚本。

这个脚本的目标不是在开发机上直接备份生产数据，而是验证仓库是否已经具备“可恢复性交付”的基础契约。
原因很简单：真正的生产备份通常依赖客户环境的数据库权限、对象存储权限、Kubernetes Secret、企业备份平台、
存储快照、跨可用区复制或云厂商托管能力；如果在仓库脚本里直接读取密码并执行备份，很容易把 Secret、导出文件
或客户数据误落到 Git 工作区。因此本脚本默认只做静态检查：

1. 检查备份恢复 runbook 是否存在，并覆盖 PostgreSQL、MySQL、Redis、Kafka、MinIO、Neo4j、Chroma、Keycloak 等关键组件；
2. 检查 Compose 是否为这些有状态组件声明持久化 volume，或为 Keycloak 这类组件声明 PostgreSQL-backed 存储契约；
3. 检查本地生成的恢复清单是否写入 `target/` 这类 Git 忽略目录，而不是写入源码目录；
4. 可选检查本机是否具备 docker、mysqldump、redis-cli、mc、kafka-topics、cypher-shell 等恢复演练工具；
5. 可选输出一个不含 Secret 的恢复范围清单，供后续 CI、审计或客户交付材料引用。

脚本刻意不执行这些动作：
- 不读取 `.env.application` 中的真实密码；
- 不连接 PostgreSQL、MySQL、Redis、Kafka、MinIO、Neo4j、Chroma 或 Keycloak；
- 不导出表数据、对象文件、token、realm secret 或业务报表；
- 不删除数据卷，不执行恢复覆盖，不触发任何 Java/Python worker。

后续如果要做真实恢复演练，应在临时环境中执行：
“备份 -> 清空临时环境 -> 恢复 -> 启动服务 -> OIDC token -> health -> 只读 smoke”。
这个流程属于运维演练，不应该由默认本地静态门禁偷偷完成。
#>
[CmdletBinding()]
param(
    [switch]$StrictTooling,
    [switch]$CheckLocalTools,
    [switch]$WriteRecoveryInventory,
    [string]$OutputDirectory = "target/backup-restore"
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

    if ($Level -eq "PASS") {
        $script:passCount++
        Write-Host "[PASS] $Name - $Detail"
        return
    }

    if ($Level -eq "WARN") {
        $script:warnCount++
        Write-Host "[WARN] $Name - $Detail"
        return
    }

    $script:failCount++
    Write-Host "[FAIL] $Name - $Detail"
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
    param(
        [string]$RelativePath,
        [string]$Purpose
    )

    $path = Join-Path $repositoryRoot $RelativePath
    if (Test-Path -LiteralPath $path) {
        Write-CheckResult -Level "PASS" -Name $RelativePath -Detail $Purpose
    }
    else {
        Write-CheckResult -Level "FAIL" -Name $RelativePath -Detail "missing required artifact: $Purpose"
    }
}

function Test-RequiredText {
    param(
        [string]$RelativePath,
        [string]$ExpectedText,
        [string]$Purpose
    )

    $content = Get-RepositoryContent -RelativePath $RelativePath
    if ($null -eq $content) {
        Write-CheckResult -Level "FAIL" -Name $RelativePath -Detail "missing file: $Purpose"
        return
    }

    if ($content.Contains($ExpectedText)) {
        Write-CheckResult -Level "PASS" -Name $RelativePath -Detail $Purpose
    }
    else {
        Write-CheckResult -Level "FAIL" -Name $RelativePath -Detail "missing '$ExpectedText': $Purpose"
    }
}

function Test-CommandAvailable {
    param(
        [string]$CommandName,
        [string]$Purpose
    )

    if ($null -ne (Get-Command $CommandName -ErrorAction SilentlyContinue)) {
        Write-CheckResult -Level "PASS" -Name $CommandName -Detail $Purpose
    }
    else {
        Write-CheckResult -Level "WARN" -Name $CommandName -Detail "tool is not installed on this machine; install it in restore-drill runners if this component is in scope"
    }
}

$volumeContracts = @(
    [pscustomobject]@{
        Name = "postgresql_data"
        File = "docker-compose.yml"
        Text = "postgresql_data:/var/lib/postgresql/data"
        Purpose = "PostgreSQL is the target system-of-record, Keycloak store, Agent memory, and pgvector/LangGraph durable-state base"
    },
    [pscustomobject]@{
        Name = "mysql_data"
        File = "docker-compose.yml"
        Text = "mysql_data:/var/lib/mysql"
        Purpose = "MySQL business database must have persistent storage before backup/restore can be meaningful"
    },
    [pscustomobject]@{
        Name = "redis_data"
        File = "docker-compose.yml"
        Text = "redis_data:/data"
        Purpose = "Redis session/cache state must define whether AOF/RDB or loss-tolerant recovery is expected"
    },
    [pscustomobject]@{
        Name = "kafka_data"
        File = "docker-compose.yml"
        Text = "kafka_data:/var/lib/kafka/data"
        Purpose = "Kafka logs, offsets, and replay windows must survive container recreation"
    },
    [pscustomobject]@{
        Name = "neo4j_data"
        File = "docker-compose.yml"
        Text = "neo4j_data:/data"
        Purpose = "Neo4j graph and lineage data must be restorable or explicitly rebuildable"
    },
    [pscustomobject]@{
        Name = "minio_data"
        File = "docker-compose.yml"
        Text = "minio_data:/data"
        Purpose = "MinIO reports, artifacts, and exported files must not be tied to container lifecycle"
    },
    [pscustomobject]@{
        Name = "nacos_data"
        File = "docker-compose.yml"
        Text = "nacos_data:/home/nacos/data"
        Purpose = "Nacos registry/config state must be restorable when self-managed"
    },
    [pscustomobject]@{
        Name = "prometheus_data"
        File = "docker-compose.yml"
        Text = "prometheus_data:/prometheus"
        Purpose = "Prometheus local metrics retention should be explicit even if long-term metrics are externalized"
    },
    [pscustomobject]@{
        Name = "grafana_data"
        File = "docker-compose.yml"
        Text = "grafana_data:/var/lib/grafana"
        Purpose = "Grafana dashboards, users, and runtime state must be recoverable or managed as code"
    },
    [pscustomobject]@{
        Name = "chroma_data"
        File = "docker-compose.yml"
        Text = "chroma_data:/chroma/chroma"
        Purpose = "Chroma vector and long-term-memory indexes need either backup or rebuild contracts"
    }
)

$databaseContracts = @(
    [pscustomobject]@{
        Name = "keycloak-postgresql-mode"
        File = "docker-compose.yml"
        Text = "KC_DB: postgres"
        Purpose = "Keycloak must use PostgreSQL instead of dev file storage for realm, users, roles, clients, and service accounts"
    },
    [pscustomobject]@{
        Name = "keycloak-postgresql-url"
        File = "docker-compose.yml"
        Text = 'KC_DB_URL: jdbc:postgresql://postgresql:5432/${DATASMART_KEYCLOAK_DB_NAME:-keycloak}'
        Purpose = "Keycloak must connect to the dedicated keycloak database through Compose service DNS"
    },
    [pscustomobject]@{
        Name = "keycloak-db-bootstrap"
        File = "docker-compose.yml"
        Text = "keycloak-db-bootstrap:"
        Purpose = "Existing local PostgreSQL volumes need an idempotent bootstrap path because initdb scripts only run on first volume creation"
    },
    [pscustomobject]@{
        Name = "keycloak-db-secret-variable"
        File = ".env.application.example"
        Text = "DATASMART_KEYCLOAK_DB_PASSWORD=keycloak"
        Purpose = "Keycloak database credentials must be visible as local sample variables and replaced by Secret Manager in production"
    },
    [pscustomobject]@{
        Name = "keycloak-db-init-script"
        File = "docker/postgresql/init/01-keycloak-database.sh"
        Text = "CREATE DATABASE"
        Purpose = "Keycloak database and role creation must be versioned as database bootstrap code"
    }
)

$configContracts = @(
    [pscustomobject]@{
        Name = "keycloak-realm-import"
        Path = "docker/keycloak/import"
        Purpose = "Keycloak bootstrap realm should be versioned as config-as-code"
    },
    [pscustomobject]@{
        Name = "prometheus-config"
        Path = "docker/prometheus"
        Purpose = "Prometheus scrape config and alerting rules should be recoverable from Git"
    },
    [pscustomobject]@{
        Name = "grafana-provisioning"
        Path = "docker/grafana/provisioning"
        Purpose = "Grafana datasource/dashboard provisioning should be recoverable from Git"
    },
    [pscustomobject]@{
        Name = "alertmanager-config"
        Path = "docker/alertmanager"
        Purpose = "Alertmanager routing and templates should be recoverable from Git"
    }
)

Push-Location $repositoryRoot
try {
    Write-Host "[STEP] verify backup/restore documentation contract"
    Test-RequiredFile -RelativePath "docs/backup-restore-runbook.md" -Purpose "backup and restore runbook for production reliability delivery"
    Test-RequiredText -RelativePath "docs/backup-restore-runbook.md" -ExpectedText "RPO" -Purpose "runbook must define recovery point objective"
    Test-RequiredText -RelativePath "docs/backup-restore-runbook.md" -ExpectedText "RTO" -Purpose "runbook must define recovery time objective"
    Test-RequiredText -RelativePath "docs/backup-restore-runbook.md" -ExpectedText "PITR" -Purpose "runbook must discuss point-in-time recovery for MySQL or equivalent stores"

    foreach ($component in @("MySQL", "Redis", "Kafka", "MinIO", "Neo4j", "Chroma", "Keycloak", "Nacos")) {
        Test-RequiredText -RelativePath "docs/backup-restore-runbook.md" -ExpectedText $component -Purpose "runbook must cover $component recovery scope"
    }

    Write-Host "[STEP] verify persistent volume contracts"
    foreach ($contract in $volumeContracts) {
        Test-RequiredText -RelativePath $contract.File -ExpectedText $contract.Text -Purpose $contract.Purpose
    }

    Write-Host "[STEP] verify PostgreSQL-backed identity contracts"
    foreach ($contract in $databaseContracts) {
        Test-RequiredText -RelativePath $contract.File -ExpectedText $contract.Text -Purpose $contract.Purpose
    }

    Write-Host "[STEP] verify config-as-code recovery scope"
    foreach ($contract in $configContracts) {
        Test-RequiredFile -RelativePath $contract.Path -Purpose $contract.Purpose
    }

    Write-Host "[STEP] verify safe local artifact boundary"
    Test-RequiredText -RelativePath ".gitignore" -ExpectedText "target/" -Purpose "generated recovery inventory must stay outside committed source files"
    Test-RequiredText -RelativePath ".env.application.example" -ExpectedText "Secret Manager" -Purpose "real backup credentials must be supplied outside Git"
    Test-RequiredText -RelativePath "docs/production-hardening-runbook.md" -ExpectedText "backup-restore-check.ps1" -Purpose "production hardening runbook must expose the backup/restore readiness gate"
    Test-RequiredText -RelativePath "docs/final-convergence-delivery-checklist.md" -ExpectedText "backup-restore-check.ps1" -Purpose "final delivery checklist must include the backup/restore readiness gate"

    Write-Host "[STEP] inspect optional restore-drill tooling"
    if ($CheckLocalTools) {
        Test-CommandAvailable -CommandName "docker" -Purpose "Docker is needed for local compose restore drills"
        Test-CommandAvailable -CommandName "mysqldump" -Purpose "mysqldump is commonly used for logical MySQL backup"
        Test-CommandAvailable -CommandName "mysql" -Purpose "mysql client is commonly used for restore validation queries"
        Test-CommandAvailable -CommandName "redis-cli" -Purpose "redis-cli is useful for RDB/AOF and keyspace validation"
        Test-CommandAvailable -CommandName "mc" -Purpose "MinIO client is useful for bucket copy and restore validation"
        Test-CommandAvailable -CommandName "kafka-topics" -Purpose "Kafka CLI is useful for topic inventory and restore validation"
        Test-CommandAvailable -CommandName "cypher-shell" -Purpose "Neo4j CLI is useful for graph backup and validation"
    }
    else {
        Write-CheckResult -Level "WARN" -Name "restore-drill-tooling" -Detail "local tool checks skipped; use -CheckLocalTools in CI or restore-drill runners"
    }

    if ($WriteRecoveryInventory) {
        Write-Host "[STEP] write recovery inventory"
        $resolvedOutputDirectory = Join-Path $repositoryRoot $OutputDirectory
        New-Item -ItemType Directory -Force -Path $resolvedOutputDirectory | Out-Null

        $inventory = [pscustomobject]@{
            schema = "datasmart.backup-restore.recovery-inventory.v1"
            generatedAt = (Get-Date).ToUniversalTime().ToString("o")
            statefulVolumes = $volumeContracts | Select-Object Name, File, Text, Purpose
            statefulDatabases = $databaseContracts | Select-Object Name, File, Text, Purpose
            configAsCodePaths = $configContracts | Select-Object Name, Path, Purpose
            runbooks = @(
                "docs/backup-restore-runbook.md",
                "docs/production-hardening-runbook.md",
                "docs/final-convergence-delivery-checklist.md"
            )
            notes = @(
                "This inventory contains restore scope metadata only.",
                "No secrets, tokens, credentials, database rows, object contents, prompt data, model outputs, or business data are included.",
                "Use this file as a release review aid, not as a replacement for a real restore drill."
            )
        }

        $outputFile = Join-Path $resolvedOutputDirectory "datasmart-recovery-inventory.json"
        $inventory | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 -LiteralPath $outputFile
        Write-CheckResult -Level "PASS" -Name $OutputDirectory -Detail "wrote recovery inventory to $outputFile"
    }

    Write-Host "[SUMMARY] PASS=$passCount, WARN=$warnCount, FAIL=$failCount"

    if ($failCount -gt 0) {
        exit 1
    }

    if ($StrictTooling -and $warnCount -gt 0) {
        Write-Host "[FAIL] strict backup/restore gate is enabled, warnings are treated as release blockers"
        exit 1
    }

    Write-Host "[PASS] backup/restore readiness check completed"
}
finally {
    Pop-Location
}

<#
.SYNOPSIS
    DataSmart Govern 六类 Autopilot repair 独立 Docker 黑盒矩阵。

.DESCRIPTION
    每个矩阵单元都使用独立的 MySQL 源表、PostgreSQL 目标表、Gateway trace、Agent Run、
    data-sync task/execution 和 recovery case。矩阵通过公开 Gateway/Keycloak/API 以及真实
    Docker worker 验证以下链路：

      真实故障 execution -> 日志/诊断 -> Agent 自主 SEARCH/SKIP -> Java 授权与指纹校验
      -> 指定低风险 repair -> Kafka/outbox/consumer -> 重跑 -> PRECHECK/MONITOR -> 最终验证

    -RepairMatrixAction 只缩小首次 AUTOPILOT 授权盒，不能替模型生成动作，也不能伪造 Java receipt。
    每个单元最终必须由 local-six-agent-governed-e2e.ps1 读取公开 recoveryAction 并断言一致；
    任一单元失败都保持失败，不把单元测试或计划输出冒充 Docker 黑盒通过。

    默认不执行任何网络请求。带 -Execute 后才会调用平台脚本；脚本不会读取、打印或写入模型 API key，
    embedding/reranker/推理 provider 继续复用当前 Docker Compose 已注入的环境配置。
#>

[CmdletBinding()]
param(
    [switch]$Execute,
    [switch]$PlanOnly,
    [switch]$StopOnFailure,
    [switch]$SkipDependencyStart,
    [switch]$IncludeAgentGraphRecoveryE2E,
    [switch]$UseContainerJdbcUrls,
    [switch]$Strict,

    [string]$GatewayBaseUrl = 'http://localhost:8080',
    [string]$KeycloakBaseUrl = 'http://localhost:18080',
    [string]$UserAccountUsername = 'project-owner',
    [string]$UserAccountPassword = 'DataSmart@123',
    [long]$TenantId = 10,
    [long]$ApplicationId = 10010,
    [long]$ProjectId = 101,
    [long]$ActorId = 1001,
    [string]$TargetSchema = 'datasmart_e2e',
    [string]$MySqlDatabase = 'datasmart_govern',
    [string]$MySqlUser = '',
    [string]$MySqlPassword = '',
    [string]$PostgresUser = '',
    [string]$PostgresPassword = '',
    [string]$MySqlHost = '127.0.0.1',
    [int]$MySqlPort = 13306,
    [string]$PostgresHost = '127.0.0.1',
    [int]$PostgresPort = 5432,
    [int]$TimeoutSeconds = 240,
    [int]$StartupTimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
$script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$script:RunId = (Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmss')
$script:Failures = New-Object System.Collections.Generic.List[object]
$script:Results = New-Object System.Collections.Generic.List[object]

function Write-MatrixPlan {
    <#
    .SYNOPSIS
        输出固定的六单元故障矩阵。

    .DESCRIPTION
        矩阵描述是测试合同，不是模型提示词。每行明确真实业务故障类别、受治理动作和最终
        需要看到的控制面证据，便于审计人员区分“模型选择”与“脚本期望”。
    #>
    param([Parameter(Mandatory = $true)][object[]]$Cases)

    Write-Host ''
    Write-Host 'DataSmart Govern 六类 repair 独立 Docker 黑盒矩阵' -ForegroundColor Cyan
    Write-Host ("运行标识：{0}" -f $script:RunId)
    Write-Host ("执行模式：{0}" -f ($(if ($Execute) { 'EXECUTE' } else { 'PLAN_ONLY' })))
    $Cases | Select-Object Index, Action, FailureCode, FaultFixture, Evidence | Format-Table -AutoSize
    Write-Host '每个单元：独立源表/目标表 + 真实失败 execution + SEARCH/SKIP + Java repair receipt + Kafka/outbox consumer + 重跑 + PRECHECK/MONITOR + 最终状态。' -ForegroundColor Yellow
}

function Assert-SafeIdentifier {
    <# 校验矩阵生成的表名，防止测试参数把 SQL 片段带入专用 fixture。 #>
    param([Parameter(Mandatory = $true)][string]$Name, [Parameter(Mandatory = $true)][string]$Value)
    if ($Value -notmatch '^[A-Za-z_][A-Za-z0-9_$]{0,127}$') {
        throw "$Name 不是安全数据库标识符"
    }
}

function Invoke-MatrixUnit {
    <#
    .SYNOPSIS
        启动一个独立 repair 单元并收集低敏结果。

    .DESCRIPTION
        本方法只负责进程编排和结果门禁。实际数据源创建、Agent 调用、任务运行、Kafka 消费、
        Java repair、重排队和最终验证全部由平台级黑盒脚本执行；因此这里不会注入 recoveryAction、
        actionFingerprint、case version 或任何模型响应字段。
    #>
    param(
        [Parameter(Mandatory = $true)][object]$Case,
        [Parameter(Mandatory = $true)][int]$Index
    )

    $suffix = ('{0}_{1}_{2}' -f $Case.ShortName, $script:RunId, $Index).ToLowerInvariant()
    $sourceTable = "datasmart_e2e_repair_source_$suffix"
    $targetTable = "repair_target_$suffix"
    Assert-SafeIdentifier -Name 'SourceTable' -Value $sourceTable
    Assert-SafeIdentifier -Name 'TargetTable' -Value $targetTable

    $arguments = @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
        (Join-Path $script:RepoRoot 'scripts/local-data-sync-platform-e2e.ps1'),
        '-RepairMatrixAction', $Case.Action,
        '-RepairMatrixFailureCode', $Case.FailureCode,
        '-SourceTable', $sourceTable,
        '-TargetTable', $targetTable,
        '-TargetSchema', $TargetSchema,
        '-MySqlDatabase', $MySqlDatabase,
        '-GatewayBaseUrl', $GatewayBaseUrl,
        '-KeycloakBaseUrl', $KeycloakBaseUrl,
        '-UserAccountUsername', $UserAccountUsername,
        '-UserAccountPassword', $UserAccountPassword,
        '-TenantId', ([string]$TenantId),
        '-ApplicationId', ([string]$ApplicationId),
        '-ProjectId', ([string]$ProjectId),
        '-ActorId', ([string]$ActorId),
        '-TimeoutSeconds', ([string]$TimeoutSeconds),
        '-StartupTimeoutSeconds', ([string]$StartupTimeoutSeconds)
    )
    if ($PlanOnly -or -not $Execute) { $arguments += '-PlanOnly' }
    if ($SkipDependencyStart) { $arguments += '-SkipDependencyStart' }
    if ($IncludeAgentGraphRecoveryE2E) { $arguments += '-IncludeAgentGraphRecoveryE2E' }
    if ($UseContainerJdbcUrls) { $arguments += '-UseContainerJdbcUrls' }
    if ($Strict) { $arguments += '-Strict' }
    if (-not [string]::IsNullOrWhiteSpace($MySqlUser)) { $arguments += @('-MySqlUser', $MySqlUser) }
    if (-not [string]::IsNullOrWhiteSpace($MySqlPassword)) { $arguments += @('-MySqlPassword', $MySqlPassword) }
    if (-not [string]::IsNullOrWhiteSpace($PostgresUser)) { $arguments += @('-PostgresUser', $PostgresUser) }
    if (-not [string]::IsNullOrWhiteSpace($PostgresPassword)) { $arguments += @('-PostgresPassword', $PostgresPassword) }

    Write-Host ''
    Write-Host ("[{0}/6] {1} | failure={2} | fixture={3}" -f $Index, $Case.Action, $Case.FailureCode, $Case.FaultFixture) -ForegroundColor Magenta
    try {
        $output = @(& powershell @arguments 2>&1 | ForEach-Object { [string]$_ })
        $exitCode = $LASTEXITCODE
    } catch {
        $output = @($_.Exception.Message)
        $exitCode = 1
    }

    # 只从子脚本的低敏检查名称中抽取结果，避免把模型原文、SQL、连接串和响应正文带回矩阵日志。
    $passLines = @($output | Where-Object { $_ -match '\[PASS\]|Status\s*=\s*PASS|finished without hard failures' } | Select-Object -Last 12)
    $failLines = @($output | Where-Object { $_ -match '\[FAIL\]|stopped|未收敛|Platform API E2E stopped' } | Select-Object -Last 8)
    $actionEvidence = @($output | Where-Object { $_ -match [regex]::Escape($Case.Action) } | Select-Object -Last 8)
    $passed = if ($PlanOnly -or -not $Execute) {
        # PlanOnly 只证明六个单元的参数、标识符和脚本合同可解析；不会把“计划”误报成 Docker 运行通过。
        $exitCode -eq 0 -and $failLines.Count -eq 0
    } else {
        $exitCode -eq 0 -and $failLines.Count -eq 0 -and $actionEvidence.Count -gt 0
    }
    if ($passed) {
        Write-Host ("[PASS] {0} 已通过独立 Docker 黑盒门禁；低敏检查行={1}" -f $Case.Action, $passLines.Count) -ForegroundColor Green
    } else {
        $reason = if ($failLines.Count -gt 0) { ($failLines -join ' | ') } else { '缺少动作证据或子脚本退出码非 0' }
        Write-Host ("[FAIL] {0} 未通过：{1}" -f $Case.Action, $reason) -ForegroundColor Red
        $script:Failures.Add([pscustomobject]@{ Action = $Case.Action; ExitCode = $exitCode; Reason = $reason })
    }
    $script:Results.Add([pscustomobject]@{
            Index = $Index
            Action = $Case.Action
            FailureCode = $Case.FailureCode
            SourceTable = $sourceTable
            TargetTable = $targetTable
            Status = if ($passed) { 'PASS' } else { 'FAIL' }
            ExitCode = $exitCode
        })
    if (-not $passed -and $StopOnFailure) {
        throw "Repair 矩阵在 $($Case.Action) 单元停止"
    }
}

try {
    if ($Execute -and $PlanOnly) {
        throw '不能同时指定 -Execute 和 -PlanOnly'
    }
    $cases = @(
        [pscustomobject]@{ Index = 1; ShortName = 'rollback'; Action = 'ROLLBACK_EXECUTION_POLICY'; FailureCode = 'EXECUTION_POLICY_REGRESSION'; FaultFixture = '最近成功策略快照 + 当前失败 execution'; Evidence = '成功快照、当前策略 snapshot、repair receipt、重排 execution' },
        [pscustomobject]@{ Index = 2; ShortName = 'tune'; Action = 'TUNE_EXECUTION_POLICY'; FailureCode = 'CONNECTOR_TIMEOUT'; FaultFixture = '连接/批量负载瞬态故障'; Evidence = '有界调参、策略覆盖、repair receipt、重跑' },
        [pscustomobject]@{ Index = 3; ShortName = 'metadata'; Action = 'REFRESH_METADATA'; FailureCode = 'STALE_METADATA'; FaultFixture = '两端元数据缓存过期'; Evidence = 'forceRefresh、元数据预检、repair receipt、重跑' },
        [pscustomobject]@{ Index = 4; ShortName = 'checkpoint'; Action = 'RESUME_FROM_CHECKPOINT'; FailureCode = 'WORKER_INTERRUPTED_CHECKPOINTED'; FaultFixture = 'worker 中断 + 持久 checkpoint'; Evidence = 'checkpoint 绑定 replay execution、repair receipt、最终验证' },
        [pscustomobject]@{ Index = 5; ShortName = 'shards'; Action = 'REPLAY_FAILED_SHARDS'; FailureCode = 'FAILED_PARTITION_SHARD'; FaultFixture = '幂等写策略下 FAILED PARTITION_SHARD'; Evidence = '仅失败分片重排、对象账本、repair receipt、重跑' },
        [pscustomobject]@{ Index = 6; ShortName = 'mapping'; Action = 'REPAIR_FIELD_MAPPING'; FailureCode = 'FIELD_MAPPING_MISSING'; FaultFixture = '唯一元数据证明的字段映射缺陷'; Evidence = '元数据修复、配置条件更新、repair receipt、重跑' }
    )
    Write-MatrixPlan -Cases $cases
    foreach ($case in $cases) {
        Invoke-MatrixUnit -Case $case -Index ([int]$case.Index)
    }
    Write-Host ''
    $script:Results | Format-Table -AutoSize
    if ($script:Failures.Count -gt 0) {
        Write-Host ("六类 repair 黑盒矩阵完成但有 {0} 个失败单元。" -f $script:Failures.Count) -ForegroundColor Red
        exit 1
    }
    Write-Host '六类 repair 独立 Docker 黑盒矩阵全部通过。' -ForegroundColor Green
    exit 0
} catch {
    Write-Host ("Repair 黑盒矩阵终止：{0}" -f $_.Exception.Message) -ForegroundColor Red
    exit 1
}

<#
.SYNOPSIS
    验证 local-six-agent-governed-e2e.ps1 的进程退出码合同。

.DESCRIPTION
    本回归通过子 PowerShell 进程启动目标脚本，从而验证 CI 实际读取的同一组退出码。
    整个过程保持离线：PlanOnly 和 Specialist 聚合夹具不会访问服务；预期失败用例会在
    本地参数校验阶段停止，不读取凭据，也不访问网络。
#>

[CmdletBinding()]
param(
    [string]$PowerShellExecutable = (Get-Command powershell.exe -ErrorAction Stop).Source
)

$ErrorActionPreference = 'Stop'

function Invoke-E2EChildProcess {
    param(
        [Parameter(Mandatory = $true)][string]$ScriptPath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$CredentialSentinel
    )

    $resolvedScriptPath = (Resolve-Path -LiteralPath $ScriptPath -ErrorAction Stop).Path
    if ([string]::IsNullOrWhiteSpace($resolvedScriptPath)) {
        throw '子 E2E 脚本路径解析结果为空。'
    }

    # Windows PowerShell 5.1 的 ProcessStartInfo 没有 ArgumentList。直接按空格拼接会拆散
    # 包含空格的数据源名称和目标描述，因此先把本夹具的受控 token 转成命名参数，
    # 再以 JSON/Base64 传入子进程。
    $namedParameters = [ordered]@{}
    for ($index = 0; $index -lt $Arguments.Count; $index++) {
        $token = [string]$Arguments[$index]
        if ($token -notmatch '^-(?<name>[A-Za-z][A-Za-z0-9]*)$') {
            throw "子 E2E 参数不是命名参数：index=$index。"
        }

        $parameterName = $Matches.name
        $nextIndex = $index + 1
        if ($nextIndex -lt $Arguments.Count -and [string]$Arguments[$nextIndex] -notmatch '^-[A-Za-z][A-Za-z0-9]*$') {
            $namedParameters[$parameterName] = [string]$Arguments[$nextIndex]
            $index = $nextIndex
        } else {
            $namedParameters[$parameterName] = $true
        }
    }

    $invocationData = [ordered]@{
        scriptPath = $resolvedScriptPath
        parameters = $namedParameters
    }
    $invocationJson = $invocationData | ConvertTo-Json -Compress
    $invocationPayload = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($invocationJson))
    $childCommandLines = @(
        "`$invocationJson = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('$invocationPayload'))",
        '$invocation = ConvertFrom-Json -InputObject $invocationJson',
        '$childScriptPath = [string]$invocation.scriptPath',
        '$childParameters = @{}',
        '$invocation.parameters.psobject.Properties | ForEach-Object { $childParameters[$_.Name] = $_.Value }',
        '& $childScriptPath @childParameters',
        'if ($?) { exit 0 } else { exit 1 }'
    )
    $childCommand = $childCommandLines -join [Environment]::NewLine
    $encodedCommand = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($childCommand))

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $PowerShellExecutable
    $startInfo.Arguments = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -OutputFormat Text -EncodedCommand $encodedCommand"
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    # 该值只是测试哨兵，不是真实密码，并且绝不能出现在捕获输出中。
    $startInfo.EnvironmentVariables['DATASMART_KEYCLOAK_LOCAL_USER_PASSWORD'] = $CredentialSentinel

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) {
            throw '无法启动子 PowerShell 进程。'
        }

        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        [System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]@($stdoutTask, $stderrTask))
        # .NET Framework 在异步输出流读取完成后还需要一次无参数等待；否则连续启动多个子进程时，
        # 调用方不能可靠读取缓存的 ExitCode。
        $process.WaitForExit()
        $process.Refresh()

        return [pscustomobject]@{
            ExitCode = [int]$process.ExitCode
            Output = $stdoutTask.Result + [Environment]::NewLine + $stderrTask.Result
            ScriptPathValid = [bool](Test-Path -LiteralPath $resolvedScriptPath -PathType Leaf)
        }
    } finally {
        $process.Dispose()
    }
}

function Assert-E2EExitCase {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][object]$Result,
        [Parameter(Mandatory = $true)][string]$CredentialSentinel,
        [switch]$ExpectSuccess,
        [switch]$ExpectFailMarker
    )

    if ($Result.Output -match [regex]::Escape($CredentialSentinel)) {
        throw "$Name 把凭据测试哨兵泄漏到了子进程输出。"
    }

    if ($ExpectSuccess -and $Result.ExitCode -ne 0) {
        throw "$Name 期望退出码为 0，实际为 $($Result.ExitCode)。"
    }
    if (-not $ExpectSuccess -and $Result.ExitCode -eq 0) {
        $failMarkerPresent = $Result.Output -match '(?m)^\[FAIL\]'
        throw "$Name 已输出或记录失败却以 0 退出（failMarkerPresent=$failMarkerPresent, outputLength=$($Result.Output.Length), scriptPathValid=$($Result.ScriptPathValid)）。"
    }
    if ($ExpectFailMarker -and $Result.Output -notmatch '(?m)^\[FAIL\]') {
        throw "$Name 期望子进程输出包含 [FAIL] 标记。"
    }

    Write-Host "[PASS] $Name - exitCode=$($Result.ExitCode)"
}

try {
    $e2eScriptPath = Join-Path $PSScriptRoot 'local-six-agent-governed-e2e.ps1'
    if (-not (Test-Path -LiteralPath $e2eScriptPath -PathType Leaf)) {
        throw "缺少目标 E2E 脚本：$e2eScriptPath"
    }

    # 静态哨兵只是安全测试数据，不是凭据，并且会被刻意禁止写入输出。
    $credentialSentinel = 'local-six-agent-e2e-regression-not-a-real-secret'

    $planOnly = Invoke-E2EChildProcess -ScriptPath $e2eScriptPath -Arguments @('-PlanOnly') -CredentialSentinel $credentialSentinel
    Assert-E2EExitCase -Name '仅规划模式' -Result $planOnly -CredentialSentinel $credentialSentinel -ExpectSuccess

    $aggregation = Invoke-E2EChildProcess -ScriptPath $e2eScriptPath -Arguments @('-RunSpecialistStatusAggregationRegressionTest') -CredentialSentinel $credentialSentinel
    Assert-E2EExitCase -Name 'Specialist 状态聚合回归' -Result $aggregation -CredentialSentinel $credentialSentinel -ExpectSuccess

    $autopilotPublicContract = Invoke-E2EChildProcess -ScriptPath $e2eScriptPath -Arguments @('-RunAutopilotPublicContractRegressionTest') -CredentialSentinel $credentialSentinel
    Assert-E2EExitCase -Name 'Autopilot 公开恢复合同回归' -Result $autopilotPublicContract -CredentialSentinel $credentialSentinel -ExpectSuccess

    $requestContract = Invoke-E2EChildProcess -ScriptPath $e2eScriptPath -Arguments @(
        '-RunRequestContractRegressionTest',
        '-SourceDatasourceName', 'source datasource with spaces',
        '-TargetDatasourceName', 'target datasource with spaces',
        '-SourceSchemaName', 'source_schema',
        '-SourceObjectName', 'source_orders',
        '-TargetSchemaName', 'target_schema',
        '-TargetObjectName', 'target_orders'
    ) -CredentialSentinel $credentialSentinel
    Assert-E2EExitCase -Name '结构化对象映射请求合同' -Result $requestContract -CredentialSentinel $credentialSentinel -ExpectSuccess

    # 两个模式互斥，因此该用例应在访问 Keycloak 前失败。
    $intentionalFailure = Invoke-E2EChildProcess -ScriptPath $e2eScriptPath -Arguments @('-Execute', '-PlanOnly') -CredentialSentinel $credentialSentinel
    Assert-E2EExitCase -Name '预期的本地参数校验失败' -Result $intentionalFailure -CredentialSentinel $credentialSentinel -ExpectFailMarker

    # AUTOPILOT 延续授权只能由首次明确确认的 Success 流程建立。
    # 该离线用例证明调用方不能在仅规划请求或后续 Recovery 请求中自行添加该授权。
    $autopilotWithoutConfirmation = Invoke-E2EChildProcess -ScriptPath $e2eScriptPath -Arguments @('-EnableAutopilot') -CredentialSentinel $credentialSentinel
    Assert-E2EExitCase -Name 'AUTOPILOT 必须由首次明确确认建立' -Result $autopilotWithoutConfirmation -CredentialSentinel $credentialSentinel -ExpectFailMarker

    Write-Host '[PASS] 六 Agent 本地 E2E 退出码合同回归已完成。' -ForegroundColor Green
    exit 0
} catch {
    $safeMessage = [string]$_.Exception.Message
    if ([string]::IsNullOrWhiteSpace($safeMessage)) {
        $safeMessage = '未知断言失败'
    }
    Write-Host "[FAIL] 六 Agent 本地 E2E 退出码合同回归失败：$safeMessage" -ForegroundColor Red
    exit 1
}

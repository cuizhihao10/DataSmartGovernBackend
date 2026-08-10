<#
.SYNOPSIS
    Verifies the process exit-code contract of local-six-agent-governed-e2e.ps1.

.DESCRIPTION
    This regression launches the target script in child PowerShell processes so that it
    validates the same exit codes consumed by CI. It stays offline: PlanOnly and the
    specialist aggregation fixture do not access services, and the failure case stops
    during local argument validation before credential or network use.
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
        throw 'Child E2E script path resolved to an empty value.'
    }

    # Windows PowerShell 5.1 ProcessStartInfo has no ArgumentList. Joining with spaces
    # splits datasource names and objectives that contain spaces. Convert this fixture's
    # controlled tokens to named parameters and transport the map as JSON/Base64.
    $namedParameters = [ordered]@{}
    for ($index = 0; $index -lt $Arguments.Count; $index++) {
        $token = [string]$Arguments[$index]
        if ($token -notmatch '^-(?<name>[A-Za-z][A-Za-z0-9]*)$') {
            throw "Child E2E argument token is not a named parameter: index=$index."
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
    # This is a test-only marker, never a real password. It must not appear in captured output.
    $startInfo.EnvironmentVariables['DATASMART_KEYCLOAK_LOCAL_USER_PASSWORD'] = $CredentialSentinel

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) {
            throw 'Could not start the child PowerShell process.'
        }

        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        [System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]@($stdoutTask, $stderrTask))
        # .NET Framework requires a final parameterless wait after asynchronous stream draining before
        # callers can rely on the cached ExitCode across repeated child-process launches.
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
        throw "$Name leaked the credential test sentinel into child-process output."
    }

    if ($ExpectSuccess -and $Result.ExitCode -ne 0) {
        throw "$Name expected exit code 0 but received $($Result.ExitCode)."
    }
    if (-not $ExpectSuccess -and $Result.ExitCode -eq 0) {
        $failMarkerPresent = $Result.Output -match '(?m)^\[FAIL\]'
        throw "$Name printed or recorded a failure but exited 0 (failMarkerPresent=$failMarkerPresent, outputLength=$($Result.Output.Length), scriptPathValid=$($Result.ScriptPathValid))."
    }
    if ($ExpectFailMarker -and $Result.Output -notmatch '(?m)^\[FAIL\]') {
        throw "$Name expected a [FAIL] marker in child-process output."
    }

    Write-Host "[PASS] $Name - exitCode=$($Result.ExitCode)"
}

try {
    $e2eScriptPath = Join-Path $PSScriptRoot 'local-six-agent-governed-e2e.ps1'
    if (-not (Test-Path -LiteralPath $e2eScriptPath -PathType Leaf)) {
        throw "Target E2E script is missing: $e2eScriptPath"
    }

    # A static marker is safe test data, not a credential. It is deliberately never written to output.
    $credentialSentinel = 'local-six-agent-e2e-regression-not-a-real-secret'

    $planOnly = Invoke-E2EChildProcess -ScriptPath $e2eScriptPath -Arguments @('-PlanOnly') -CredentialSentinel $credentialSentinel
    Assert-E2EExitCase -Name 'PlanOnly' -Result $planOnly -CredentialSentinel $credentialSentinel -ExpectSuccess

    $aggregation = Invoke-E2EChildProcess -ScriptPath $e2eScriptPath -Arguments @('-RunSpecialistStatusAggregationRegressionTest') -CredentialSentinel $credentialSentinel
    Assert-E2EExitCase -Name 'Specialist status aggregation regression' -Result $aggregation -CredentialSentinel $credentialSentinel -ExpectSuccess

    $requestContract = Invoke-E2EChildProcess -ScriptPath $e2eScriptPath -Arguments @(
        '-RunRequestContractRegressionTest',
        '-SourceDatasourceName', 'source datasource with spaces',
        '-TargetDatasourceName', 'target datasource with spaces',
        '-SourceSchemaName', 'source_schema',
        '-SourceObjectName', 'source_orders',
        '-TargetSchemaName', 'target_schema',
        '-TargetObjectName', 'target_orders'
    ) -CredentialSentinel $credentialSentinel
    Assert-E2EExitCase -Name 'Structured object mapping request contract' -Result $requestContract -CredentialSentinel $credentialSentinel -ExpectSuccess

    # This fails before Keycloak access because the modes are mutually exclusive.
    $intentionalFailure = Invoke-E2EChildProcess -ScriptPath $e2eScriptPath -Arguments @('-Execute', '-PlanOnly') -CredentialSentinel $credentialSentinel
    Assert-E2EExitCase -Name 'Intentional local validation failure' -Result $intentionalFailure -CredentialSentinel $credentialSentinel -ExpectFailMarker

    Write-Host '[PASS] local-six-agent-governed-e2e exit-code contract regression completed.' -ForegroundColor Green
    exit 0
} catch {
    $safeMessage = [string]$_.Exception.Message
    if ([string]::IsNullOrWhiteSpace($safeMessage)) {
        $safeMessage = 'unknown assertion failure'
    }
    Write-Host "[FAIL] local-six-agent-governed-e2e exit-code contract regression failed: $safeMessage" -ForegroundColor Red
    exit 1
}

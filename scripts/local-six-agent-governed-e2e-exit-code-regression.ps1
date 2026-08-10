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
        [Parameter(Mandatory = $true)][string]$TargetScript,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$CredentialSentinel
    )

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $PowerShellExecutable
    $startInfo.Arguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "{0}" {1}' -f $TargetScript, ($Arguments -join ' ')
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

        return [pscustomobject]@{
            ExitCode = [int]$process.ExitCode
            Output = $stdoutTask.Result + [Environment]::NewLine + $stderrTask.Result
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
        throw "$Name printed or recorded a failure but exited 0."
    }
    if ($ExpectFailMarker -and $Result.Output -notmatch '(?m)^\[FAIL\]') {
        throw "$Name expected a [FAIL] marker in child-process output."
    }

    Write-Host "[PASS] $Name - exitCode=$($Result.ExitCode)"
}

try {
    $targetScript = Join-Path $PSScriptRoot 'local-six-agent-governed-e2e.ps1'
    if (-not (Test-Path -LiteralPath $targetScript -PathType Leaf)) {
        throw "Target E2E script is missing: $targetScript"
    }

    # A static marker is safe test data, not a credential. It is deliberately never written to output.
    $credentialSentinel = 'local-six-agent-e2e-regression-not-a-real-secret'

    $planOnly = Invoke-E2EChildProcess -TargetScript $targetScript -Arguments @('-PlanOnly') -CredentialSentinel $credentialSentinel
    Assert-E2EExitCase -Name 'PlanOnly' -Result $planOnly -CredentialSentinel $credentialSentinel -ExpectSuccess

    $aggregation = Invoke-E2EChildProcess -TargetScript $targetScript -Arguments @('-RunSpecialistStatusAggregationRegressionTest') -CredentialSentinel $credentialSentinel
    Assert-E2EExitCase -Name 'Specialist status aggregation regression' -Result $aggregation -CredentialSentinel $credentialSentinel -ExpectSuccess

    # This fails before Keycloak access because the modes are mutually exclusive.
    $intentionalFailure = Invoke-E2EChildProcess -TargetScript $targetScript -Arguments @('-Execute', '-PlanOnly') -CredentialSentinel $credentialSentinel
    Assert-E2EExitCase -Name 'Intentional local validation failure' -Result $intentionalFailure -CredentialSentinel $credentialSentinel -ExpectFailMarker

    Write-Host '[PASS] local-six-agent-governed-e2e exit-code contract regression completed.' -ForegroundColor Green
    exit 0
} catch {
    Write-Host '[FAIL] local-six-agent-governed-e2e exit-code contract regression failed.' -ForegroundColor Red
    exit 1
}

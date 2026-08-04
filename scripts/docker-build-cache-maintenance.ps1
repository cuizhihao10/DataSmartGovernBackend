<#
DataSmart Govern BuildKit cache maintenance helper.

The script is report-only by default. Pass -Prune to remove old BuildKit
cache records until the selected builder is at or below MaxUsedSpace.

Safety boundary:
- Manages BuildKit cache only through `docker buildx prune`.
- Never removes images, containers, networks, or volumes.
- Never invokes `docker system prune` or `docker volume prune`.

Examples:
  .\scripts\docker-build-cache-maintenance.ps1
  .\scripts\docker-build-cache-maintenance.ps1 -Prune
  .\scripts\docker-build-cache-maintenance.ps1 -Prune -MaxUsedSpace 15GB
  .\scripts\docker-build-cache-maintenance.ps1 -Prune -Builder desktop-linux
  .\scripts\docker-build-cache-maintenance.ps1 -Prune -WhatIf
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    # Disabled by default. Only an explicit -Prune enables buildx cache deletion.
    [switch]$Prune,

    # Target BuildKit cache ceiling; ValidatePattern rejects ambiguous size values before execution.
    [ValidatePattern("^[1-9][0-9]*(B|KB|MB|GB|TB)$")]
    [string]$MaxUsedSpace = "10GB",

    # Optional builder name. An empty value means the currently active Docker builder.
    [ValidateNotNullOrEmpty()]
    [string]$Builder = ""
)

$ErrorActionPreference = "Stop"

<#
.SYNOPSIS
Writes a recognizable in-progress step.
.DESCRIPTION
The stable prefix lets operators and CI distinguish work in progress from a completed check. It does not alter command success state.
#>
function Write-Step {
    param([string]$Message)
    Write-Host "[STEP] $Message"
}

<#
.SYNOPSIS
Writes a successfully completed check or operation.
.DESCRIPTION
This helper only formats output. Exit codes and exceptions remain the source of truth so a success label cannot hide a failed command.
#>
function Write-Pass {
    param([string]$Message)
    Write-Host "[PASS] $Message"
}

<#
.SYNOPSIS
Verifies that both Docker CLI and Docker daemon are available.
.DESCRIPTION
The command lookup runs first, followed by docker info. Either failure stops the script so an unreachable daemon is never reported as an empty cache.
#>
function Test-DockerAvailable {
    $dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
    if ($null -eq $dockerCommand) {
        throw "Docker CLI was not found in PATH."
    }

    & docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Docker daemon is not reachable. Start Docker Desktop and retry."
    }
}

<#
.SYNOPSIS
Reads the current Docker disk-usage records.
.OUTPUTS
An object array converted from the JSON lines emitted by docker system df.
.NOTES
This is read-only. Each line is parsed independently because Docker CLI emits one JSON object per resource category.
#>
function Get-DockerDiskUsage {
    $rawLines = @(& docker system df --format json 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to read Docker disk usage: $($rawLines -join [Environment]::NewLine)"
    }

    $records = @()
    foreach ($line in $rawLines) {
        $text = "$line".Trim()
        if ([string]::IsNullOrWhiteSpace($text)) {
            continue
        }

        $records += ($text | ConvertFrom-Json)
    }

    return $records
}

<#
.SYNOPSIS
Displays before/after disk usage in a stable troubleshooting format.
.PARAMETER Label
The phase name, such as Before or After.
.PARAMETER Records
The record array returned by Get-DockerDiskUsage.
#>
function Write-DockerDiskUsage {
    param(
        [string]$Label,
        [object[]]$Records
    )

    Write-Step "$Label Docker disk usage"
    foreach ($record in $Records) {
        Write-Host ("  {0}: count={1}, active={2}, size={3}, reclaimable={4}" -f `
            $record.Type,
            $record.TotalCount,
            $record.Active,
            $record.Size,
            $record.Reclaimable)
    }
}

<#
.SYNOPSIS
Reduces one BuildKit builder cache to the configured ceiling.
.DESCRIPTION
Only docker buildx prune is called. The script never invokes system or volume prune, so containers, images, networks, and volumes are outside its deletion boundary.
SupportsShouldProcess allows -WhatIf to show the exact target and action without deleting anything.
#>
function Invoke-BuildCachePrune {
    $arguments = @(
        "buildx",
        "prune",
        "--all",
        "--force",
        "--max-used-space",
        $MaxUsedSpace
    )

    if (-not [string]::IsNullOrWhiteSpace($Builder)) {
        $arguments += @("--builder", $Builder)
    }

    $builderLabel = if ([string]::IsNullOrWhiteSpace($Builder)) {
        "the active BuildKit builder"
    }
    else {
        "BuildKit builder '$Builder'"
    }

    if (-not $PSCmdlet.ShouldProcess(
        $builderLabel,
        "Prune BuildKit cache to a maximum of $MaxUsedSpace"
    )) {
        return
    }

    Write-Step "Pruning $builderLabel to max-used-space=$MaxUsedSpace"
    $pruneOutput = @(& docker @arguments 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "BuildKit cache prune failed: $($pruneOutput -join [Environment]::NewLine)"
    }

    $summary = @(
        $pruneOutput |
            ForEach-Object { "$_".Trim() } |
            Where-Object { $_ -match "^(Total|Reclaimed):" } |
            Select-Object -Last 1
    )
    if ($summary.Count -gt 0) {
        Write-Host "  $($summary[0])"
    }

    Write-Pass "BuildKit cache maintenance completed."
}

# The main flow reports current usage first. Without -Prune it exits here, keeping the default mode read-only.
Test-DockerAvailable

$before = @(Get-DockerDiskUsage)
Write-DockerDiskUsage -Label "Before" -Records $before

if (-not $Prune) {
    Write-Step "Report-only mode. Add -Prune to enforce the $MaxUsedSpace cache limit."
    exit 0
}

Invoke-BuildCachePrune
# Give Docker Desktop a short interval to refresh accounting before the after snapshot.
Start-Sleep -Milliseconds 500

$after = @(Get-DockerDiskUsage)
Write-DockerDiskUsage -Label "After" -Records $after
Write-Pass "Images, containers, networks, and volumes were not pruned."

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
    [switch]$Prune,

    [ValidatePattern("^[1-9][0-9]*(B|KB|MB|GB|TB)$")]
    [string]$MaxUsedSpace = "10GB",

    [ValidateNotNullOrEmpty()]
    [string]$Builder = ""
)

$ErrorActionPreference = "Stop"

function Write-Step {
    param([string]$Message)
    Write-Host "[STEP] $Message"
}

function Write-Pass {
    param([string]$Message)
    Write-Host "[PASS] $Message"
}

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

Test-DockerAvailable

$before = @(Get-DockerDiskUsage)
Write-DockerDiskUsage -Label "Before" -Records $before

if (-not $Prune) {
    Write-Step "Report-only mode. Add -Prune to enforce the $MaxUsedSpace cache limit."
    exit 0
}

Invoke-BuildCachePrune
Start-Sleep -Milliseconds 500

$after = @(Get-DockerDiskUsage)
Write-DockerDiskUsage -Label "After" -Records $after
Write-Pass "Images, containers, networks, and volumes were not pruned."


<#
.SYNOPSIS
    Creates or verifies the Kafka topics used by DataSmart Autopilot Recovery.

.DESCRIPTION
    Spring Kafka uses autoCreateTopics=false for @RetryableTopic in production. The broker,
    deployment pipeline, or Kafka operator must therefore create the main topic, two retry
    topics, and the DLT before agent-runtime starts. This script verifies by default and only
    changes broker metadata when -Apply is explicit. It never consumes records or changes offsets.

    The default topic names match three total attempts, 1000 ms initial delay, multiplier 2,
    and the suffixes declared on AgentAutopilotRecoveryTriggerKafkaConsumer. Keep this script
    and its regression checks aligned whenever that Java policy changes.
#>
[CmdletBinding()]
param(
    [switch]$Apply,
    [switch]$UseDocker,
    [string]$KafkaContainerName = 'datasmart-kafka',
    [string]$BootstrapServer = 'localhost:9092',
    [string]$BaseTopic = 'datasmart.agent.autopilot-recovery-trigger.v1',
    [ValidateRange(1, 128)]
    [int]$Partitions = 1,
    [ValidateRange(1, 9)]
    [int]$ReplicationFactor = 1,
    [ValidateRange(1, 9)]
    [int]$MinInSyncReplicas = 1,
    [ValidateRange(60000, 31536000000)]
    [long]$MainRetentionMs = 604800000,
    [ValidateRange(60000, 31536000000)]
    [long]$RetryRetentionMs = 259200000,
    [ValidateRange(60000, 31536000000)]
    [long]$DltRetentionMs = 2592000000
)

$ErrorActionPreference = 'Stop'

if ($MinInSyncReplicas -gt $ReplicationFactor) {
    throw 'MinInSyncReplicas cannot exceed ReplicationFactor.'
}

$topicSpecs = @(
    [pscustomobject]@{ Name = $BaseTopic; RetentionMs = $MainRetentionMs; Purpose = 'main recovery trigger' },
    [pscustomobject]@{ Name = "$BaseTopic-autopilot-recovery-retry-1000"; RetentionMs = $RetryRetentionMs; Purpose = 'first 1s technical retry' },
    [pscustomobject]@{ Name = "$BaseTopic-autopilot-recovery-retry-2000"; RetentionMs = $RetryRetentionMs; Purpose = 'second 2s technical retry' },
    [pscustomobject]@{ Name = "$BaseTopic-autopilot-recovery-dlt"; RetentionMs = $DltRetentionMs; Purpose = 'retry-exhausted DLT' }
)

function Invoke-KafkaTopics {
    <#
    .SYNOPSIS
        Runs one kafka-topics command through the host CLI or the local Kafka container.

    .DESCRIPTION
        Arguments are passed as an array to the native process instead of being evaluated as
        a command string. Topic names and broker addresses therefore cannot introduce another
        shell statement. Docker mode is intended for local Compose; production runners normally
        inject kafka-topics plus a least-privilege operations identity. A non-zero native exit
        immediately fails the release gate.

    .PARAMETER Arguments
        kafka-topics arguments without the executable name.
    #>
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    if ($UseDocker) {
        $output = & docker exec $KafkaContainerName kafka-topics @Arguments 2>&1
    } else {
        $output = & kafka-topics @Arguments 2>&1
    }
    if ($LASTEXITCODE -ne 0) {
        throw "kafka-topics failed with exitCode=$LASTEXITCODE."
    }
    return @($output)
}

function Get-ExistingTopicNames {
    <#
    .SYNOPSIS
        Reads the broker topic-name set.

    .DESCRIPTION
        Only --list is called. No record, offset, payload, or ACL body is read. The HashSet is
        case-sensitive because Kafka topic names are case-sensitive. A unary comma prevents
        PowerShell from flattening the HashSet into a string array at the function boundary.
    #>
    $lines = Invoke-KafkaTopics -Arguments @('--bootstrap-server', $BootstrapServer, '--list')
    $names = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($line in $lines) {
        $name = ([string]$line).Trim()
        if (-not [string]::IsNullOrWhiteSpace($name)) {
            $null = $names.Add($name)
        }
    }
    return ,$names
}

function New-GovernedTopic {
    <#
    .SYNOPSIS
        Creates one missing Autopilot topic with its durability and retention contract.

    .DESCRIPTION
        --if-not-exists makes deployment reruns idempotent. Partitions and replicas control
        ordering, throughput, and failure tolerance. min.insync.replicas prevents a producer
        from accepting writes below the required replica quorum. Separate retention periods
        keep short retry traffic inexpensive while preserving DLT evidence for operators.
        Existing topic partition counts are never changed by this function.

    .PARAMETER Spec
        Fixed topic name, retention, and purpose values from topicSpecs.
    #>
    param([Parameter(Mandatory = $true)][object]$Spec)

    Invoke-KafkaTopics -Arguments @(
        '--bootstrap-server', $BootstrapServer,
        '--create', '--if-not-exists',
        '--topic', [string]$Spec.Name,
        '--partitions', [string]$Partitions,
        '--replication-factor', [string]$ReplicationFactor,
        '--config', "min.insync.replicas=$MinInSyncReplicas",
        '--config', "retention.ms=$($Spec.RetentionMs)"
    ) | Out-Null
}

function Assert-GovernedTopic {
    <#
    .SYNOPSIS
        Verifies that one governed topic exists and has readable partition metadata.

    .DESCRIPTION
        Presence in --list proves the name exists; --describe additionally catches unreadable
        partition or leader metadata. Raw broker output is intentionally not printed. The release
        log receives only a bounded PASS line and operators can run an explicit describe command
        when deeper diagnosis is required.

    .PARAMETER Spec
        Fixed topic specification being checked.
    .PARAMETER Existing
        Topic-name HashSet loaded from the broker in this run.
    #>
    param(
        [Parameter(Mandatory = $true)][object]$Spec,
        [Parameter(Mandatory = $true)][System.Collections.Generic.HashSet[string]]$Existing
    )

    if (-not $Existing.Contains([string]$Spec.Name)) {
        throw "Missing Kafka topic: $($Spec.Name)."
    }
    Invoke-KafkaTopics -Arguments @(
        '--bootstrap-server', $BootstrapServer,
        '--describe', '--topic', [string]$Spec.Name
    ) | Out-Null
    Write-Host "[PASS] $($Spec.Purpose) topic exists and metadata is readable: $($Spec.Name)"
}

if ($UseDocker) {
    & docker inspect $KafkaContainerName *> $null
    if ($LASTEXITCODE -ne 0) {
        throw "Local Kafka container was not found: $KafkaContainerName."
    }
} elseif ($null -eq (Get-Command kafka-topics -ErrorAction SilentlyContinue)) {
    throw 'kafka-topics CLI was not found; local Compose users can add -UseDocker.'
}

$existing = Get-ExistingTopicNames
foreach ($spec in $topicSpecs) {
    if (-not $existing.Contains([string]$spec.Name)) {
        if (-not $Apply) {
            throw "Missing Kafka topic: $($spec.Name). Review parameters and use -Apply to create it."
        }
        New-GovernedTopic -Spec $spec
        Write-Host "[PASS] Created $($spec.Purpose) topic: $($spec.Name)"
    }
}

# Reload metadata after creation. Reusing the old set would incorrectly report a new topic as missing.
$existing = Get-ExistingTopicNames
foreach ($spec in $topicSpecs) {
    Assert-GovernedTopic -Spec $spec -Existing $existing
}

$mode = if ($Apply) { 'APPLY' } else { 'VERIFY' }
Write-Host "[PASS] Autopilot Kafka topic governance completed; mode=$mode, topics=$($topicSpecs.Count)."

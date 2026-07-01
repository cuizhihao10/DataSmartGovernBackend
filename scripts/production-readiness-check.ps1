<#
DataSmart Govern 生产就绪静态检查脚本。

这个脚本的定位和 local-e2e-smoke-check.ps1、containerized-delivery-check.ps1 不一样：
1. smoke 脚本验证“当前本机完整链路是否可访问”；
2. containerized delivery 脚本验证“应用镜像、可执行 jar、Compose overlay 是否可交付”；
3. 本脚本验证“仓库是否已经把生产上线前的硬要求结构化，并且没有明显配置漂移”。

为什么默认只把部分项记为 WARN：
- 当前项目已经完成本地闭环，并已逐步补齐生产环境值说明、SBOM 就绪、镜像签名验证、备份恢复和 Kubernetes/Helm 初始交付制品；
- 容量与故障演练的静态就绪制品已经完成；真实压测和真实故障注入仍必须在客户预生产或专用 runner 中执行，不能由本地静态脚本伪装完成；
- 当进入真正上线前门禁或 CI 发布阶段时，可以启用 -StrictProductionGates，把 WARN 也视为失败。

脚本故意不读取真实 Secret、不连接生产数据库、不执行迁移、不启动 worker，也不触发任何 Agent 工具。
它只检查仓库文件、静态配置和交付文档契约，适合在开发机、CI 静态阶段和代码审查前运行。
#>
[CmdletBinding()]
param(
    [switch]$StrictProductionGates
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

function Get-RepositoryFileContent {
    param([string]$RelativePath)

    $path = Join-Path $repositoryRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path)) {
        return $null
    }

    return Get-Content -Raw -LiteralPath $path
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
        Write-CheckResult -Level "FAIL" -Name $RelativePath -Detail "missing required production-readiness artifact: $Purpose"
    }
}

function Test-RequiredText {
    param(
        [string]$RelativePath,
        [string]$ExpectedText,
        [string]$Purpose
    )

    $content = Get-RepositoryFileContent -RelativePath $RelativePath
    if ($null -eq $content) {
        Write-CheckResult -Level "FAIL" -Name $RelativePath -Detail "missing file for contract: $Purpose"
        return
    }

    if ($content.Contains($ExpectedText)) {
        Write-CheckResult -Level "PASS" -Name $RelativePath -Detail $Purpose
    }
    else {
        Write-CheckResult -Level "FAIL" -Name $RelativePath -Detail "contract drift, missing '$ExpectedText': $Purpose"
    }
}

function Test-OptionalProductionArtifact {
    param(
        [string]$RelativePath,
        [string]$Purpose
    )

    $path = Join-Path $repositoryRoot $RelativePath
    if (Test-Path -LiteralPath $path) {
        Write-CheckResult -Level "PASS" -Name $RelativePath -Detail $Purpose
    }
    else {
        Write-CheckResult -Level "WARN" -Name $RelativePath -Detail "production blocker remains: $Purpose"
    }
}

function Test-AnyPathExists {
    param(
        [string]$Name,
        [string[]]$RelativePaths,
        [string]$Purpose
    )

    foreach ($relativePath in $RelativePaths) {
        $path = Join-Path $repositoryRoot $relativePath
        if (Test-Path -LiteralPath $path) {
            Write-CheckResult -Level "PASS" -Name $Name -Detail "$Purpose; found $relativePath"
            return
        }
    }

    Write-CheckResult -Level "WARN" -Name $Name -Detail "production blocker remains: $Purpose"
}

Push-Location $repositoryRoot
try {
    Write-Host "[STEP] local closure and delivery boundary artifacts"
    Test-RequiredFile -RelativePath "docs/final-convergence-delivery-checklist.md" -Purpose "final boundary between closed capabilities, controlled-disabled capabilities, and production TODOs"
    Test-RequiredFile -RelativePath "docs/production-hardening-runbook.md" -Purpose "production hardening route for security, supply chain, Kubernetes, backup, capacity, and failure drills"
    Test-RequiredFile -RelativePath "docs/containerized-application-deployment.md" -Purpose "containerized application delivery guide"
    Test-RequiredFile -RelativePath "docs/local-e2e-closure-runbook.md" -Purpose "local full-stack E2E closure runbook"
    Test-RequiredFile -RelativePath "scripts/containerized-delivery-check.ps1" -Purpose "repeatable containerized delivery gate"
    Test-RequiredFile -RelativePath "scripts/local-e2e-smoke-check.ps1" -Purpose "real read-only local E2E smoke gate"

    Write-Host "[STEP] secret and OIDC production contract"
    Test-RequiredText -RelativePath ".gitignore" -ExpectedText ".env.application" -Purpose "local environment file that may contain secrets must not be committed"
    Test-RequiredText -RelativePath ".env.application.example" -ExpectedText "Secret Manager" -Purpose "environment example must teach that real secrets belong outside Git"
    Test-RequiredText -RelativePath ".env.application.example" -ExpectedText "replace-with-a-long-random-secret" -Purpose "gateway HMAC secret must remain an obvious placeholder in the committed example"
    Test-RequiredText -RelativePath ".env.application.example" -ExpectedText "DATASMART_KEYCLOAK_IMAGE=quay.m.daocloud.io/keycloak/keycloak:26.6.4" -Purpose "Keycloak comes from Quay, so DaoCloud must use the Quay mirror endpoint"
    Test-RequiredText -RelativePath "gateway/src/main/resources/application.yml" -ExpectedText 'jwk-set-uri: ${DATASMART_GATEWAY_OIDC_JWK_SET_URI:}' -Purpose "gateway must expose a JWKS override while keeping issuer validation enabled"
    Test-RequiredText -RelativePath "docker-compose.application.yml" -ExpectedText "DATASMART_GATEWAY_OIDC_JWK_SET_URI: http://keycloak:18080/realms/datasmart/protocol/openid-connect/certs" -Purpose "container mode must fetch Keycloak signing keys through service DNS"
    Test-RequiredText -RelativePath "docs/production-hardening-runbook.md" -ExpectedText "Secret Manager" -Purpose "production hardening docs must explicitly cover secret externalization"
    Test-RequiredText -RelativePath "docs/production-hardening-runbook.md" -ExpectedText "TLS" -Purpose "production hardening docs must explicitly cover TLS"
    Test-RequiredText -RelativePath "docs/production-hardening-runbook.md" -ExpectedText "mTLS" -Purpose "production hardening docs must explicitly cover optional internal mTLS"

    Write-Host "[STEP] container and supply-chain hardening contract"
    Test-RequiredFile -RelativePath "docker/runtime/java-service.Dockerfile" -Purpose "shared Java service image build contract"
    Test-RequiredFile -RelativePath "docker/runtime/python-ai-runtime.Dockerfile" -Purpose "Python AI Runtime image build contract"
    Test-RequiredText -RelativePath "docker-compose.application.yml" -ExpectedText "no-new-privileges:true" -Purpose "application containers should reject privilege escalation"
    Test-RequiredText -RelativePath "docker-compose.application.yml" -ExpectedText "read_only: true" -Purpose "application containers should prefer read-only root filesystems"
    Test-RequiredText -RelativePath "docs/production-hardening-runbook.md" -ExpectedText "SBOM" -Purpose "production hardening docs must cover software bill of materials"
    Test-RequiredText -RelativePath "docs/production-hardening-runbook.md" -ExpectedText "镜像签名" -Purpose "production hardening docs must cover image signing"
    Test-AnyPathExists -Name "sbom-generation" -RelativePaths @("scripts/generate-sbom.ps1", "scripts/sbom-check.ps1", "docker/sbom") -Purpose "add a repeatable SBOM generation or verification artifact before production release"
    Test-AnyPathExists -Name "image-signing" -RelativePaths @("scripts/sign-images.ps1", "scripts/verify-image-signatures.ps1", "docker/cosign") -Purpose "add image signing and signature verification before production release"

    Write-Host "[STEP] Kubernetes and multi-environment delivery contract"
    Test-RequiredText -RelativePath "docs/production-hardening-runbook.md" -ExpectedText "Kubernetes/Helm" -Purpose "production hardening docs must state the path beyond Compose"
    Test-AnyPathExists -Name "kubernetes-helm" -RelativePaths @("helm", "charts", "k8s", "deploy/kubernetes") -Purpose "add Kubernetes or Helm delivery manifests before production release"
    Test-OptionalProductionArtifact -RelativePath "docs/production-environment-values.md" -Purpose "document environment-specific values, secret references, and production overrides"

    Write-Host "[STEP] data reliability, capacity, and failure-drill contract"
    Test-RequiredFile -RelativePath "scripts/local-mysql-migration-governance.ps1" -Purpose "current migration governance bridge for local integration"
    Test-RequiredText -RelativePath "docs/production-hardening-runbook.md" -ExpectedText "备份恢复" -Purpose "production hardening docs must cover backup and restore"
    Test-RequiredText -RelativePath "docs/production-hardening-runbook.md" -ExpectedText "容量基线" -Purpose "production hardening docs must cover capacity baseline"
    Test-RequiredText -RelativePath "docs/production-hardening-runbook.md" -ExpectedText "故障演练" -Purpose "production hardening docs must cover failure drills"
    Test-AnyPathExists -Name "backup-restore" -RelativePaths @("scripts/backup-restore-check.ps1", "scripts/mysql-backup.ps1", "docs/backup-restore-runbook.md") -Purpose "add backup/restore runbook or executable checks before production release"
    Test-AnyPathExists -Name "capacity-baseline" -RelativePaths @("scripts/capacity-baseline-check.ps1", "scripts/capacity-baseline.ps1", "scripts/load-test", "docs/capacity-baseline-runbook.md", "docs/capacity-baseline-report.md") -Purpose "add repeatable capacity baseline procedure before production release"
    Test-AnyPathExists -Name "failure-drills" -RelativePaths @("scripts/failure-drill-check.ps1", "scripts/failure-drill.ps1", "docs/failure-drill-runbook.md", "docs/disaster-recovery-runbook.md") -Purpose "add failure-drill or disaster-recovery runbook before production release"

    Write-Host "[SUMMARY] PASS=$passCount, WARN=$warnCount, FAIL=$failCount"

    if ($failCount -gt 0) {
        exit 1
    }

    if ($StrictProductionGates -and $warnCount -gt 0) {
        Write-Host "[FAIL] strict production gates are enabled, warnings are treated as release blockers"
        exit 1
    }

    Write-Host "[PASS] production readiness static gate completed"
}
finally {
    Pop-Location
}

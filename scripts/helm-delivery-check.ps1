<#
DataSmart Govern Kubernetes/Helm 交付就绪检查脚本。

这个脚本用于验证仓库是否已经具备 Compose 之外的生产部署交付边界。
它默认只做静态检查，不连接 Kubernetes 集群、不创建 namespace、不读取 Secret、不部署服务。

为什么不默认执行 `helm install`：
1. 正式部署需要客户集群、Ingress、证书、Secret、镜像仓库、存储和网络策略，开发机通常不具备这些生产上下文；
2. 贸然安装 chart 可能在错误集群中创建资源，尤其是当前项目包含 gateway、权限中心、Agent Runtime 等安全敏感入口；
3. 当前收敛阶段更需要“可审查、可模板化、可被 CI 扩展”的交付物，而不是在本地伪造生产部署成功。

如果本机或 CI runner 安装了 Helm，本脚本会自动执行 `helm lint` 和 `helm template`。
如果未安装 Helm，默认只给 WARN，不阻断当前生产化收敛；进入真实发布门禁时可使用 -StrictTooling。
#>
[CmdletBinding()]
param(
    [switch]$StrictTooling,
    [switch]$WriteRenderedManifests,
    [string]$OutputDirectory = "target/helm"
)

$ErrorActionPreference = "Stop"
$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$chartPath = Join-Path $repositoryRoot "helm/datasmart-govern"
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
        Write-CheckResult -Level "FAIL" -Name $RelativePath -Detail "missing required Helm delivery artifact: $Purpose"
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
    param([string]$CommandName)

    return $null -ne (Get-Command $CommandName -ErrorAction SilentlyContinue)
}

function Invoke-HelmCommand {
    param(
        [string[]]$Arguments,
        [string]$Name,
        [string]$Purpose
    )

    & helm @Arguments *> $null
    if ($LASTEXITCODE -eq 0) {
        Write-CheckResult -Level "PASS" -Name $Name -Detail $Purpose
    }
    else {
        Write-CheckResult -Level "FAIL" -Name $Name -Detail "helm command failed: helm $($Arguments -join ' ')"
    }
}

Push-Location $repositoryRoot
try {
    Write-Host "[STEP] verify Helm chart artifact layout"
    Test-RequiredFile -RelativePath "helm/datasmart-govern/Chart.yaml" -Purpose "Helm chart metadata"
    Test-RequiredFile -RelativePath "helm/datasmart-govern/values.yaml" -Purpose "default application-layer values"
    Test-RequiredFile -RelativePath "helm/datasmart-govern/values-production.example.yaml" -Purpose "production override example without secrets"
    Test-RequiredFile -RelativePath "helm/datasmart-govern/templates/_helpers.tpl" -Purpose "shared naming, label, and image helpers"
    Test-RequiredFile -RelativePath "helm/datasmart-govern/templates/application-services.yaml" -Purpose "Deployment and Service template for Java/Python application services"
    Test-RequiredFile -RelativePath "helm/datasmart-govern/templates/gateway-ingress.yaml" -Purpose "Gateway Ingress and TLS boundary"
    Test-RequiredFile -RelativePath "helm/datasmart-govern/templates/secret-contract.yaml" -Purpose "required Secret key contract without storing secret values"
    Test-RequiredFile -RelativePath "helm/datasmart-govern/templates/network-policy.yaml" -Purpose "network policy extension point"

    Write-Host "[STEP] verify production safety contracts"
    Test-RequiredText -RelativePath "helm/datasmart-govern/values.yaml" -ExpectedText "existingSecret: datasmart-platform-secrets" -Purpose "chart must reference external secrets instead of storing credentials"
    Test-RequiredText -RelativePath "helm/datasmart-govern/values.yaml" -ExpectedText "runAsNonRoot: true" -Purpose "pods should default to non-root execution"
    Test-RequiredText -RelativePath "helm/datasmart-govern/values.yaml" -ExpectedText "readOnlyRootFilesystem: true" -Purpose "containers should default to read-only root filesystem"
    Test-RequiredText -RelativePath "helm/datasmart-govern/values.yaml" -ExpectedText 'DATASMART_TASK_AGENT_ASYNC_WORKER_ENABLED: "false"' -Purpose "task worker must remain disabled by default"
    Test-RequiredText -RelativePath "helm/datasmart-govern/values.yaml" -ExpectedText 'DATASMART_AGENT_RUNTIME_OUTBOX_DISPATCHER_ENABLED: "false"' -Purpose "agent outbox dispatcher must remain disabled by default"
    Test-RequiredText -RelativePath "helm/datasmart-govern/values.yaml" -ExpectedText "DATASMART_AI_OPENAI_COMPATIBLE_BASE_URL" -Purpose "model provider is configured as replaceable external endpoint"
    Test-RequiredText -RelativePath "helm/datasmart-govern/templates/application-services.yaml" -ExpectedText "secretKeyRef" -Purpose "sensitive values must be injected from Kubernetes Secret"
    Test-RequiredText -RelativePath "helm/datasmart-govern/templates/application-services.yaml" -ExpectedText "readinessProbe" -Purpose "application services must expose readiness probes"
    Test-RequiredText -RelativePath "helm/datasmart-govern/templates/application-services.yaml" -ExpectedText "RollingUpdate" -Purpose "application services should support rolling upgrades"

    Write-Host "[STEP] verify documentation wiring"
    Test-RequiredFile -RelativePath "docs/kubernetes-helm-deployment.md" -Purpose "Kubernetes and Helm deployment guide"
    Test-RequiredText -RelativePath "docs/production-hardening-runbook.md" -ExpectedText "helm-delivery-check.ps1" -Purpose "production hardening runbook must expose Helm readiness gate"
    Test-RequiredText -RelativePath "docs/final-convergence-delivery-checklist.md" -ExpectedText "helm-delivery-check.ps1" -Purpose "final delivery checklist must include Helm readiness gate"
    Test-RequiredText -RelativePath ".gitignore" -ExpectedText "target/" -Purpose "rendered manifests should stay outside committed source files"

    Write-Host "[STEP] inspect optional Helm CLI"
    if (Test-CommandAvailable -CommandName "helm") {
        Invoke-HelmCommand -Name "helm lint" -Purpose "Helm chart lint passed" -Arguments @("lint", $chartPath)
        Invoke-HelmCommand -Name "helm template" -Purpose "Helm chart renders with production example values" -Arguments @(
            "template",
            "datasmart-govern",
            $chartPath,
            "--values",
            (Join-Path $chartPath "values-production.example.yaml")
        )

        if ($WriteRenderedManifests) {
            $resolvedOutputDirectory = Join-Path $repositoryRoot $OutputDirectory
            New-Item -ItemType Directory -Force -Path $resolvedOutputDirectory | Out-Null
            $outputFile = Join-Path $resolvedOutputDirectory "datasmart-govern-rendered.yaml"
            & helm template datasmart-govern $chartPath --values (Join-Path $chartPath "values-production.example.yaml") |
                Set-Content -Encoding UTF8 -LiteralPath $outputFile
            if ($LASTEXITCODE -eq 0) {
                Write-CheckResult -Level "PASS" -Name $OutputDirectory -Detail "wrote rendered manifests to $outputFile"
            }
            else {
                Write-CheckResult -Level "FAIL" -Name $OutputDirectory -Detail "failed to render manifests"
            }
        }
    }
    else {
        Write-CheckResult -Level "WARN" -Name "helm" -Detail "Helm CLI is not installed; install it in release CI to run helm lint/template"
    }

    Write-Host "[SUMMARY] PASS=$passCount, WARN=$warnCount, FAIL=$failCount"

    if ($failCount -gt 0) {
        exit 1
    }

    if ($StrictTooling -and $warnCount -gt 0) {
        Write-Host "[FAIL] strict Helm delivery gate is enabled, warnings are treated as release blockers"
        exit 1
    }

    Write-Host "[PASS] Helm delivery readiness check completed"
}
finally {
    Pop-Location
}
